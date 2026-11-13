package tv.monotv.launcher.data.inputs

import android.content.Context
import android.content.Intent
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tv.monotv.launcher.core.InputItem
import tv.monotv.launcher.core.InputKind
import tv.monotv.launcher.data.InputsRepository
import tv.monotv.launcher.data.system.resolveSafeActivity
import tv.monotv.launcher.data.system.startActivitySafely

/**
 * THE extension point for switching the TV to another source.
 *
 * The repository decides *what* can be switched to; an [InputSwitcher] knows *how*. The default, [TifInputSwitcher],
 * uses nothing but the documented Android TV Input Framework contract. Should a firmware ever need something else —
 * a vendor SDK with a public, documented API for its inputs, say — it goes into another implementation of this
 * interface and nowhere else, so the rest of the app never learns about vendors.
 *
 * Rules for every implementation: public, documented APIs only. No vendor shell commands, no undocumented
 * broadcasts or service calls, no system settings, nothing that needs a privileged permission. An input that
 * cannot be switched that way is reported as `false` — a greyed-out row is fine, a bricked TV is not.
 */
internal fun interface InputSwitcher {
    /** Shows [item] on screen. Returns false when it cannot be done; must not throw for ordinary failures. */
    fun switchTo(item: InputItem): Boolean
}

/**
 * Switches inputs the way the TV Input Framework documents it for third-party apps: by *viewing* a channel.
 * A passthrough input (HDMI, AV, ...) is one channel, addressed by its passthrough uri; the system's input viewer
 * (`ru.sberdevices.tvinputs` on Sber TVs, the Live Channels app on stock Android TV) picks the intent up and does
 * the actual tuning with the privileges it has and we do not. The tuner is opened as the channel list.
 */
internal class TifInputSwitcher(context: Context) : InputSwitcher {

    private val appContext: Context = context.applicationContext ?: context

    override fun switchTo(item: InputItem): Boolean {
        if (!item.switchable) return false
        val intent = if (item.kind == InputKind.Tuner) tunerIntent() else passthroughIntent(item.id)
        return appContext.startActivitySafely(intent)
    }

    companion object {
        /** Views the channel list — what "switch to the tuner" means for an app that cannot tune by itself. */
        fun tunerIntent(): Intent = Intent(Intent.ACTION_VIEW, TvContract.Channels.CONTENT_URI)

        /** Views the single channel a passthrough input consists of. */
        fun passthroughIntent(inputId: String): Intent =
            Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(inputId))
    }
}

/**
 * The few facts about one `TvInputInfo` that the inputs menu is built from. A plain value, so the rules in
 * [InputMapper] can be tested without the TV input framework (whose classes cannot be constructed in a test).
 */
internal data class TvInputSnapshot(
    val id: String,
    /** One of the `TvInputInfo.TYPE_*` constants. */
    val type: Int,
    /** Id of the HDMI port this input hangs off; non-null for CEC devices behind a port. */
    val parentId: String?,
    /** The name the user gave the input in TV settings, or else the input's own label. */
    val label: String,
    val isPassthrough: Boolean,
    /** One of the `TvInputManager.INPUT_STATE_*` constants, null when the framework would not say. */
    val state: Int?,
)

/** Everything read from the platform in one go. */
internal class TvInputReading(
    val inputs: List<TvInputSnapshot>,
    /** Some activity is able to show the channel list (a live TV app is installed). */
    val tunerViewable: Boolean,
)

/** Where the repository gets its inputs from; the seam that lets tests stand in for the TV input framework. */
internal interface TvInputSource {
    /** The current inputs, or null when the device has no TV input framework at all. May block; may throw. */
    fun read(): TvInputReading?

    /** Starts calling [onChanged] (on the main thread) whenever an input appears, disappears or changes state. */
    fun register(onChanged: () -> Unit)

    fun unregister()
}

/** Turns raw TV inputs into the rows of the "Входы" menu. Pure. */
internal object InputMapper {

    /** One name for every tuner there is: the user thinks "TV channels", not "DVB-T2 input of the tuner service". */
    const val TUNER_LABEL = "Эфирное ТВ"

    /** Id of the single merged tuner row. Not a real input id — the tuner is opened as the channel list. */
    const val TUNER_ID = "monotv:tuner"

    /**
     * Shown when the device exposes no inputs to third-party apps (or has none, like a set-top box): the menu
     * keeps its familiar shape, with every row visibly unavailable instead of an unexplained empty panel.
     */
    val placeholders: List<InputItem> = listOf(
        placeholder("hdmi1", "HDMI 1", InputKind.Hdmi),
        placeholder("hdmi2", "HDMI 2", InputKind.Hdmi),
        placeholder("hdmi3", "HDMI 3", InputKind.Hdmi),
        placeholder("av", "AV", InputKind.Av),
        placeholder("tuner", TUNER_LABEL, InputKind.Tuner),
    )

    /**
     * The menu rows for [inputs]; empty when there is no hardware input among them.
     *
     *  - Only top-level inputs are listed. A CEC device behind an HDMI port is a child of that port; showing both
     *    would offer the same socket twice.
     *  - An input is switchable when it is a passthrough input, because that is the only kind a third-party app can
     *    ask the system to show. All tuners collapse into one row that is switchable when [tunerViewable].
     *  - Order: HDMI ports by name, then AV, then component-style inputs, then the tuner, then anything else.
     */
    fun toItems(inputs: List<TvInputSnapshot>, tunerViewable: Boolean): List<InputItem> {
        val items = ArrayList<InputItem>()
        val tunerStates = ArrayList<Boolean?>()
        val seenIds = HashSet<String>()
        for (input in inputs) {
            if (input.parentId != null || !seenIds.add(input.id)) continue
            val kind = kindOf(input.type)
            if (kind == InputKind.Tuner) {
                tunerStates += connectedOf(input.state)
            } else {
                items += InputItem(
                    id = input.id,
                    label = input.label.trim().ifEmpty { defaultLabel(kind) },
                    kind = kind,
                    connected = connectedOf(input.state),
                    switchable = input.isPassthrough,
                )
            }
        }
        if (tunerStates.isNotEmpty()) {
            val connected = when {
                tunerStates.any { it == true } -> true
                tunerStates.all { it == false } -> false
                else -> null
            }
            items += InputItem(TUNER_ID, TUNER_LABEL, InputKind.Tuner, connected, switchable = tunerViewable)
        }
        return items.sortedWith(
            compareBy<InputItem> { rankOf(it.kind) }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.label }
                .thenBy { it.id },
        )
    }

    fun kindOf(type: Int): InputKind = when (type) {
        TvInputInfo.TYPE_HDMI -> InputKind.Hdmi
        TvInputInfo.TYPE_COMPOSITE, TvInputInfo.TYPE_SVIDEO, TvInputInfo.TYPE_SCART -> InputKind.Av
        TvInputInfo.TYPE_COMPONENT, TvInputInfo.TYPE_VGA, TvInputInfo.TYPE_DVI, TvInputInfo.TYPE_DISPLAY_PORT -> InputKind.Component
        TvInputInfo.TYPE_TUNER -> InputKind.Tuner
        else -> InputKind.Other
    }

    /** "Standby" still means a device is plugged in — a console that is asleep, for instance. */
    fun connectedOf(state: Int?): Boolean? = when (state) {
        TvInputManager.INPUT_STATE_CONNECTED, TvInputManager.INPUT_STATE_CONNECTED_STANDBY -> true
        TvInputManager.INPUT_STATE_DISCONNECTED -> false
        else -> null
    }

    private fun rankOf(kind: InputKind): Int = when (kind) {
        InputKind.Hdmi -> 0
        InputKind.Av -> 1
        InputKind.Component -> 2
        InputKind.Tuner -> 3
        InputKind.Other -> 4
    }

    /** For the rare input service that ships without a label. */
    private fun defaultLabel(kind: InputKind): String = when (kind) {
        InputKind.Hdmi -> "HDMI"
        InputKind.Av -> "AV"
        InputKind.Component -> "Компонентный вход"
        InputKind.Tuner -> TUNER_LABEL
        InputKind.Other -> "Вход"
    }

    private fun placeholder(name: String, label: String, kind: InputKind) =
        InputItem(id = "monotv:placeholder:$name", label = label, kind = kind, connected = null, switchable = false)
}

/**
 * [TvInputSource] on the platform's `TvInputManager`. Reads only: the input list, each input's label and its
 * connection state. Hardware inputs live in a system service (`com.droidlogic.tvinput` on Amlogic,
 * `com.mediatek.tvinput` on MediaTek) and are visible to every app without any permission.
 */
internal class AndroidTvInputSource(context: Context) : TvInputSource {

    private val appContext: Context = context.applicationContext ?: context

    /** Null on devices without `android.software.live_tv` — phones, and some stripped-down boxes. */
    private val manager: TvInputManager? = try {
        appContext.getSystemService(Context.TV_INPUT_SERVICE) as? TvInputManager
    } catch (e: RuntimeException) {
        null
    }

    private var callback: TvInputManager.TvInputCallback? = null

    override fun read(): TvInputReading? {
        val manager = manager ?: return null
        val infos: List<TvInputInfo> = manager.tvInputList ?: return null
        val snapshots = infos.map { info ->
            TvInputSnapshot(
                id = info.id,
                type = info.type,
                parentId = info.parentId,
                label = labelOf(info),
                isPassthrough = info.isPassthroughInput,
                state = stateOf(manager, info.id),
            )
        }
        // Resolving the channel list asks the TV provider for a mime type; skip that when there is no tuner anyway.
        val hasTuner = snapshots.any { it.parentId == null && InputMapper.kindOf(it.type) == InputKind.Tuner }
        return TvInputReading(snapshots, tunerViewable = hasTuner && isTunerViewable())
    }

    override fun register(onChanged: () -> Unit) {
        val manager = manager ?: return
        if (callback != null) return
        val created = object : TvInputManager.TvInputCallback() {
            override fun onInputStateChanged(inputId: String, state: Int) = onChanged()
            override fun onInputAdded(inputId: String) = onChanged()
            override fun onInputRemoved(inputId: String) = onChanged()
            override fun onInputUpdated(inputId: String) = onChanged()
            override fun onTvInputInfoUpdated(inputInfo: TvInputInfo) = onChanged()
        }
        try {
            manager.registerCallback(created, Handler(Looper.getMainLooper()))
            callback = created
        } catch (e: RuntimeException) {
            // No live updates then; the list is still refreshed on every start().
        }
    }

    override fun unregister() {
        val registered = callback ?: return
        callback = null
        try {
            manager?.unregisterCallback(registered)
        } catch (e: RuntimeException) {
            // Already released by the framework.
        }
    }

    /** The user's own name for the input ("PlayStation") wins over the factory one ("HDMI 2"). */
    private fun labelOf(info: TvInputInfo): String {
        val custom = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                info.loadCustomLabel(appContext)?.toString()
            } catch (e: RuntimeException) {
                null
            }
        } else {
            null
        }
        if (!custom.isNullOrBlank()) return custom
        return try {
            info.loadLabel(appContext)?.toString().orEmpty()
        } catch (e: RuntimeException) {
            ""
        }
    }

    private fun stateOf(manager: TvInputManager, inputId: String): Int? = try {
        manager.getInputState(inputId)
    } catch (e: RuntimeException) {
        // The input was removed between listing it and asking for its state.
        null
    }

    private fun isTunerViewable(): Boolean = appContext.resolveSafeActivity(TifInputSwitcher.tunerIntent()) != null
}

/**
 * [InputsRepository] over the Android TV Input Framework.
 *
 * The list is re-read when the framework says something changed (an input appeared, a cable was plugged in) and on
 * every [start] — never on a timer. Reading it means loading labels from the input service's resources and a few
 * binder calls, which is too much for the main thread of a 1 GB TV in the middle of an animation, so it happens on
 * a background dispatcher; requests arriving while one is already waiting are folded into it.
 *
 * Until the first read completes, and whenever the device exposes no inputs, [inputs] holds
 * [InputMapper.placeholders].
 */
class AndroidInputsRepository internal constructor(
    private val source: TvInputSource,
    private val switcher: InputSwitcher,
    dispatcher: CoroutineDispatcher,
) : InputsRepository {

    constructor(context: Context) : this(
        source = AndroidTvInputSource(context),
        switcher = TifInputSwitcher(context),
        dispatcher = Dispatchers.IO,
    )

    /**
     * Lives as long as the repository (the whole process) and is never cancelled: a read that is already running
     * when [stop] is called just finishes and publishes a list nobody is looking at.
     */
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    /** Reads run one at a time, in request order, so an older snapshot can never overwrite a newer one. */
    private val readLock = Mutex()

    /** True while a read is waiting for its turn; it will see every change made before it starts. */
    private val readQueued = AtomicBoolean(false)

    private val inputsState = MutableStateFlow(InputMapper.placeholders)

    override val inputs: StateFlow<List<InputItem>> = inputsState.asStateFlow()

    private var started = false

    override fun start() {
        if (started) return
        started = true
        // Listen first, read second: a change that lands in between simply folds into the pending read.
        try {
            source.register(::requestRefresh)
        } catch (e: RuntimeException) {
            // Without live updates the list is still refreshed on every start().
        }
        requestRefresh()
    }

    override fun stop() {
        if (!started) return
        started = false
        try {
            source.unregister()
        } catch (e: RuntimeException) {
            // Nothing left to release.
        }
    }

    /**
     * Switches to [item] if — and only if — it is a switchable entry of the list currently published. Looking the
     * item up again by id means a stale row from an old frame (an input that has since been unplugged or removed)
     * cannot be acted on, and placeholders can never reach the [InputSwitcher].
     */
    override fun switchTo(item: InputItem): Boolean {
        val current = inputsState.value.firstOrNull { it.id == item.id } ?: return false
        if (!current.switchable) return false
        return try {
            switcher.switchTo(current)
        } catch (e: RuntimeException) {
            false
        }
    }

    private fun requestRefresh() {
        if (!readQueued.compareAndSet(false, true)) return
        scope.launch {
            readLock.withLock {
                readQueued.set(false)
                inputsState.value = load()
            }
        }
    }

    private fun load(): List<InputItem> {
        val reading = try {
            source.read()
        } catch (e: RuntimeException) {
            null
        }
        val items = if (reading == null) emptyList() else InputMapper.toItems(reading.inputs, reading.tunerViewable)
        return items.ifEmpty { InputMapper.placeholders }
    }
}
