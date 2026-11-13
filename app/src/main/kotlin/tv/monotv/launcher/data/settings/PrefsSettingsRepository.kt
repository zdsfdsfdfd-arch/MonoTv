package tv.monotv.launcher.data.settings

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import tv.monotv.launcher.core.AppKey
import tv.monotv.launcher.core.LauncherSettings
import tv.monotv.launcher.core.RecentLaunch
import tv.monotv.launcher.data.SettingsRepository

/**
 * [SettingsRepository] on top of a private SharedPreferences file.
 *
 * Why SharedPreferences and not a database or DataStore: the whole state is a dozen short strings, it has to be
 * available before the first frame (the theme, the Dock and the wallpaper are all on it) and the project takes no
 * third-party dependencies. The file is therefore read synchronously in the constructor — a few kilobytes at most —
 * and from then on the in-memory [StateFlow]s are the source of truth; the file is only ever written, with `apply()`,
 * so a key press never waits for flash storage.
 *
 * Everything that is published has been through [SettingsCodec] first. What the UI shows is thus exactly what the
 * next start will restore: an out-of-range value is corrected the moment it is set, not silently after a reboot.
 *
 * Thread-safe: writers are serialised by one lock, which also keeps the order of the queued disk writes identical to
 * the order of the in-memory updates. Readers just read the flows.
 */
class PrefsSettingsRepository(context: Context) : SettingsRepository {

    /**
     * Null when the preferences cannot be opened at all (storage not yet unlocked, a damaged data directory). The
     * launcher then still works with defaults and simply forgets changes on restart — a home screen must come up
     * no matter what.
     */
    private val prefs: SharedPreferences? = try {
        (context.applicationContext ?: context).getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
    } catch (e: RuntimeException) {
        null
    }

    private val lock = Any()

    private val settingsState: MutableStateFlow<LauncherSettings>
    private val recentsState: MutableStateFlow<List<RecentLaunch>>

    init {
        val stored = readAll()
        settingsState = MutableStateFlow(SettingsCodec.decode(stored))
        recentsState = MutableStateFlow(SettingsCodec.decodeRecents(stored[KEY_RECENTS]))
    }

    override val settings: StateFlow<LauncherSettings> = settingsState.asStateFlow()

    override val recents: StateFlow<List<RecentLaunch>> = recentsState.asStateFlow()

    /**
     * Applies [transform] to the current settings; the new value is visible to every reader when this returns.
     *
     * [transform] runs under the repository lock, so two concurrent updates never lose each other's change. Keep it
     * a pure function of its argument. If it throws, the exception reaches the caller and nothing is changed.
     */
    override fun update(transform: (LauncherSettings) -> LauncherSettings) {
        synchronized(lock) {
            val before = settingsState.value
            val requested = transform(before)
            if (requested == before) return
            val encoded = SettingsCodec.encode(requested)
            val after = SettingsCodec.decode(encoded)
            if (after == before) return
            settingsState.value = after
            persist { editor ->
                for (key in SettingsCodec.KEYS) {
                    val value = encoded[key]
                    if (value == null) editor.remove(key) else editor.putString(key, value)
                }
            }
        }
    }

    /**
     * Remembers that [key] was just started: it moves to the front with its launch count raised by one, and the
     * list is cut to [SettingsCodec.MAX_RECENTS] entries.
     *
     * The position, not [now], defines recency — see [SettingsCodec.decodeRecents] for why the clock is not trusted.
     */
    override fun recordLaunch(key: AppKey, now: Long) {
        synchronized(lock) {
            val current = recentsState.value
            val previousCount = current.firstOrNull { it.key == key }?.launchCount ?: 0
            // Saturates instead of wrapping around: a negative count would sort a favourite app last.
            val count = if (previousCount >= Int.MAX_VALUE) Int.MAX_VALUE else previousCount.coerceAtLeast(0) + 1

            val updated = ArrayList<RecentLaunch>(minOf(current.size + 1, SettingsCodec.MAX_RECENTS))
            updated += RecentLaunch(key, now, count)
            for (recent in current) {
                if (updated.size >= SettingsCodec.MAX_RECENTS) break
                if (recent.key != key) updated += recent
            }
            publishRecents(updated)
        }
    }

    /**
     * Forgets launches of apps that are not in [installed]. Call it only with the result of a successful scan: an
     * empty set means "nothing is installed" and clears the history.
     */
    override fun pruneRecents(installed: Set<AppKey>) {
        synchronized(lock) {
            val current = recentsState.value
            val kept = current.filter { it.key in installed }
            if (kept.size != current.size) publishRecents(kept)
        }
    }

    /** Must be called with [lock] held. */
    private fun publishRecents(recents: List<RecentLaunch>) {
        val encoded = SettingsCodec.encodeRecents(recents)
        recentsState.value = SettingsCodec.decodeRecents(encoded)
        persist { editor -> editor.putString(KEY_RECENTS, encoded) }
    }

    /**
     * Queues an asynchronous write. `apply()` updates the preferences' own memory image at once and writes the file
     * on a background thread; a failure there costs the last change after a restart, never the running launcher.
     */
    private inline fun persist(write: (SharedPreferences.Editor) -> Unit) {
        val prefs = prefs ?: return
        try {
            val editor = prefs.edit()
            write(editor)
            editor.apply()
        } catch (e: RuntimeException) {
            // Nothing to roll back: the in-memory state stays authoritative for this run.
        }
    }

    /** Values of any type other than String (a file from some other build, a manual edit) count as absent. */
    private fun readAll(): Map<String, String?> {
        val prefs = prefs ?: return emptyMap()
        return try {
            prefs.all.mapValues { (_, value) -> value as? String }
        } catch (e: RuntimeException) {
            emptyMap()
        }
    }

    companion object {
        /** Name of the preferences file (`shared_prefs/monotv_settings.xml`). */
        const val FILE_NAME = "monotv_settings"

        /** Recent launches, see [SettingsCodec.encodeRecents]. Deliberately not one of [SettingsCodec.KEYS]. */
        private const val KEY_RECENTS = "recents"
    }
}
