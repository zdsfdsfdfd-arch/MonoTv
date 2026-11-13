package tv.monotv.launcher.data.apps

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.annotation.VisibleForTesting
import androidx.core.content.ContextCompat
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import tv.monotv.launcher.core.AppArrangement
import tv.monotv.launcher.core.AppEntry
import tv.monotv.launcher.core.AppKey
import tv.monotv.launcher.core.LauncherConfig
import tv.monotv.launcher.data.AppsRepository
import java.net.URISyntaxException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Finds the apps a TV launcher should offer and starts them — and nothing more: this class only ever reads from the
 * package manager and calls `startActivity`. It never enables, disables, clears or uninstalls anything.
 *
 * Discovery asks for three kinds of entry points, best first: `LEANBACK_LAUNCHER` (real TV apps), the extra
 * categories from the config (Sber's own apps are only tagged `ru.sberdevices.category.STAR_LAUNCHER`) and plain
 * `LAUNCHER` (phone apps, reported with `isLeanback = false` so the ViewModel can hide them). A package gets exactly
 * one tile — the best entry point it has. Factory / engineering menus from the config blocklist are neither listed
 * nor started, not even through a deep link.
 *
 * The list is kept fresh by package broadcasts only (no polling). Requests to rescan go through a conflated channel
 * consumed by one coroutine, so scans never overlap and an app-store batch update collapses into a single rescan
 * instead of twenty.
 *
 * @param scope owns the scan worker; cancelling it ends the repository for good.
 * @param io dispatcher for package-manager queries, which are binder calls and may take tens of milliseconds.
 * @param onPackageChanged told (on the main thread) which package was added, removed or changed, so that caches
 *   keyed by package — in practice [AndroidArtworkLoader.invalidate] — can drop what they hold for it.
 */
class AndroidAppsRepository(
    context: Context,
    private val scope: CoroutineScope,
    configLoader: LauncherConfigLoader,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val onPackageChanged: (packageName: String) -> Unit = {},
) : AppsRepository {

    private val appContext: Context = context.applicationContext ?: context
    private val ownPackage: String = appContext.packageName
    private val packageManager: PackageManager get() = appContext.packageManager

    /**
     * Loaded once per process, on first use. The scan worker is normally the first user, which keeps the file read
     * off the main thread; an edited override file is picked up at the next start of the app.
     */
    override val config: LauncherConfig by lazy { configLoader.load() }

    private val _apps = MutableStateFlow<List<AppEntry>?>(null)
    override val apps: StateFlow<List<AppEntry>?> = _apps.asStateFlow()

    private val scanRequests = Channel<Unit>(Channel.CONFLATED)
    private val workerStarted = AtomicBoolean(false)

    /** Launcher category each listed activity was found under, see [launch]. Replaced as a whole by every scan. */
    @Volatile
    private var launchCategories: Map<AppKey, String> = emptyMap()

    /** Touched from the main thread only ([start] / [stop] contract). */
    private var receiverRegistered = false

    /** Number of scans run so far; lets tests prove that a burst of broadcasts is coalesced. */
    @VisibleForTesting
    @Volatile
    internal var completedScans: Int = 0
        private set

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            // An update is announced as REMOVED + ADDED + REPLACED. The app never really left, so the first of the
            // three is skipped: nothing may see the app as "gone" and forget its place in the Dock.
            if (action == Intent.ACTION_PACKAGE_REMOVED && intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return
            intent.data?.schemeSpecificPart?.let(onPackageChanged)
            requestScan()
        }
    }

    override fun start() {
        if (!receiverRegistered) receiverRegistered = registerPackageReceiver()
        // Always rescan: packages may have changed while the receiver was unregistered.
        requestScan()
    }

    override fun stop() {
        if (!receiverRegistered) return
        receiverRegistered = false
        try {
            appContext.unregisterReceiver(packageReceiver)
        } catch (e: RuntimeException) {
            // The system already dropped the registration; there is nothing left to undo.
        }
    }

    override fun refresh() = requestScan()

    /**
     * Starts the activity with the very intent the stock launcher would use (MAIN + the category it was found
     * under + the component). Android only brings an existing task back where the user left it when the new intent
     * equals the one the task was created with; anything else restarts the app from its first screen.
     *
     * A stale key (the activity was renamed by an update) falls back to whatever the package launches with now.
     */
    override fun launch(key: AppKey): Boolean {
        if (!mayStart(key.packageName)) return false
        val explicit = Intent(Intent.ACTION_MAIN)
            .setComponent(ComponentName(key.packageName, key.activityName))
            .addFlags(LAUNCH_FLAGS)
        launchCategories[key]?.let { explicit.addCategory(it) }
        if (startActivity(explicit)) return true

        val fallback = packageLaunchIntent(key.packageName) ?: return false
        return startActivity(fallback.addFlags(LAUNCH_FLAGS))
    }

    /**
     * Deep links come from other apps' data (e.g. Watch Next rows), so the intent is treated as untrusted input and
     * hardened before it is started, see [hardenedDeepLink]. Anything suspicious or unresolvable degrades to simply
     * opening [fallback].
     */
    override fun launchUri(intentUri: String?, fallback: AppKey?): Boolean {
        val deepLink = if (intentUri.isNullOrBlank()) null else hardenedDeepLink(intentUri)
        if (deepLink != null && startActivity(deepLink)) return true
        return fallback != null && launch(fallback)
    }

    override fun openAppInfo(key: AppKey): Boolean {
        if (!mayStart(key.packageName)) return false
        val details = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", key.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return startActivity(details)
    }

    // -----------------------------------------------------------------------------------------------------------------
    // Scanning
    // -----------------------------------------------------------------------------------------------------------------

    private fun registerPackageReceiver(): Boolean = try {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        // Package broadcasts are sent by the system itself, which a non-exported receiver still hears; no other
        // app gets to poke the launcher into rescanning.
        ContextCompat.registerReceiver(appContext, packageReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        true
    } catch (e: RuntimeException) {
        false
    }

    private fun requestScan() {
        if (workerStarted.compareAndSet(false, true)) {
            scope.launch(io) {
                for (request in scanRequests) {
                    // A request that arrived while this one was waiting for a thread is answered by the same scan.
                    scanRequests.tryReceive()
                    publish(scan())
                }
            }
        }
        scanRequests.trySend(Unit)
    }

    private fun publish(scan: Scan?) {
        if (scan != null) {
            launchCategories = scan.categories
            _apps.value = scan.apps
        } else if (_apps.value == null) {
            // Even a failed first scan ends the "loading" state; the next broadcast or start() tries again.
            _apps.value = emptyList()
        }
        completedScans++
    }

    /**
     * One complete pass over the package manager, or null when any query failed. A partial result is worse than
     * none: an app that seems to have lost its TV entry point would get a different key and drop out of the Dock,
     * so on failure the previous list simply stays.
     */
    private fun scan(): Scan? = try {
        val config = config
        val chosen = LinkedHashMap<String, Candidate>()
        collect(chosen, Intent.CATEGORY_LEANBACK_LAUNCHER, isTv = true, config)
        for (category in config.extraLauncherCategories) collect(chosen, category, isTv = true, config)
        collect(chosen, Intent.CATEGORY_LAUNCHER, isTv = false, config)

        val entries = ArrayList<AppEntry>(chosen.size)
        val categories = HashMap<AppKey, String>(chosen.size * 2)
        for (candidate in chosen.values) {
            val entry = toEntry(candidate, config) ?: continue
            entries += entry
            categories[entry.key] = candidate.category
        }
        Scan(entries.sortedWith(AppArrangement.labelComparator()), categories)
    } catch (e: RuntimeException) {
        null
    }

    /** Adds the entry points of [category] for packages that have none yet — earlier (better) categories win. */
    private fun collect(into: MutableMap<String, Candidate>, category: String, isTv: Boolean, config: LauncherConfig) {
        val query = Intent(Intent.ACTION_MAIN).addCategory(category)
        for (info in queryActivities(query)) {
            val activity = info.activityInfo ?: continue
            val packageName = activity.packageName ?: continue
            if (packageName in into || packageName == ownPackage || config.isBlocked(packageName)) continue
            // A non-exported or disabled entry point cannot be started from outside its own app.
            if (!activity.exported || !activity.isEnabled) continue
            into[packageName] = Candidate(activity, category, isTv)
        }
    }

    private fun toEntry(candidate: Candidate, config: LauncherConfig): AppEntry? {
        val activity = candidate.activity
        val packageName = activity.packageName
        // Gone between the query and now (uninstall in progress): not an app any more.
        val packageInfo = packageInfo(packageName) ?: return null
        val application: ApplicationInfo? = activity.applicationInfo
        val knownId = config.knownIdFor(packageName)
        return AppEntry(
            key = AppKey(packageName, activity.name),
            label = systemLabel(activity) ?: config.known(knownId)?.title ?: packageName,
            isLeanback = candidate.isTv,
            hasBanner = activity.banner != 0 || (application != null && application.banner != 0),
            isSystem = application != null && (application.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
            knownId = knownId,
            versionCode = PackageInfoCompat.getLongVersionCode(packageInfo),
            installedAt = packageInfo.firstInstallTime,
        )
    }

    /** The label the system shows for the activity, or null when the app has none worth showing. */
    private fun systemLabel(activity: ActivityInfo): String? {
        val raw = try {
            activity.loadLabel(packageManager)
        } catch (e: RuntimeException) {
            return null
        }
        val text = raw.toString().replace(WHITESPACE, " ").trim()
        // Without any label the framework answers with the class name, which is no name for a tile.
        return text.takeIf { it.isNotEmpty() && it != activity.name }?.take(MAX_LABEL_LENGTH)
    }

    private fun queryActivities(intent: Intent): List<ResolveInfo> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            packageManager.queryIntentActivities(intent, 0)
        }.orEmpty()

    private fun packageInfo(packageName: String): PackageInfo? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, 0)
        }
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }

    // -----------------------------------------------------------------------------------------------------------------
    // Launching
    // -----------------------------------------------------------------------------------------------------------------

    private fun mayStart(packageName: String): Boolean = packageName.isNotEmpty() && !config.isBlocked(packageName)

    private fun packageLaunchIntent(packageName: String): Intent? = try {
        packageManager.getLeanbackLaunchIntentForPackage(packageName)
            ?: packageManager.getLaunchIntentForPackage(packageName)
    } catch (e: RuntimeException) {
        null
    }

    /**
     * Parses an `intent:` uri and strips everything a "continue watching" link has no business carrying:
     *  - the selector (it would let the uri name one target and start another) and clip data;
     *  - uri permission grants — the launcher must never hand its own access to someone else;
     *  - package-management actions (install / uninstall dialogs);
     *  - any target inside this app (its internal activities are not a public API) or on the blocklist.
     * Returns null when the link is refused or does not resolve to an activity.
     */
    private fun hardenedDeepLink(uri: String): Intent? {
        val intent = try {
            Intent.parseUri(uri, Intent.URI_INTENT_SCHEME)
        } catch (e: URISyntaxException) {
            return null
        } catch (e: RuntimeException) {
            return null
        }
        if (intent.action in REFUSED_ACTIONS) return null
        intent.selector = null
        intent.clipData = null
        intent.flags = (intent.flags and URI_GRANT_FLAGS.inv()) or Intent.FLAG_ACTIVITY_NEW_TASK

        val target = resolveActivity(intent)?.activityInfo ?: return null
        val packages = listOfNotNull(intent.component?.packageName, intent.`package`, target.packageName)
        if (packages.any { it == ownPackage || config.isBlocked(it) }) return null
        return intent
    }

    /** Resolves like `startActivity` does: implicit intents only match activities that accept CATEGORY_DEFAULT. */
    private fun resolveActivity(intent: Intent): ResolveInfo? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val flags = PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong())
            packageManager.resolveActivity(intent, flags)
        } else {
            @Suppress("DEPRECATION")
            packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        }
    } catch (e: RuntimeException) {
        null
    }

    /** ActivityNotFoundException, SecurityException and dead-binder errors are all RuntimeExceptions. */
    private fun startActivity(intent: Intent): Boolean = try {
        appContext.startActivity(intent)
        true
    } catch (e: RuntimeException) {
        false
    }

    private class Candidate(val activity: ActivityInfo, val category: String, val isTv: Boolean)

    private class Scan(val apps: List<AppEntry>, val categories: Map<AppKey, String>)

    private companion object {
        const val LAUNCH_FLAGS = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED

        const val URI_GRANT_FLAGS = Intent.FLAG_GRANT_READ_URI_PERMISSION or
            Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
            Intent.FLAG_GRANT_PREFIX_URI_PERMISSION

        /** Spelled out because two of the three constants are deprecated in the SDK while the actions still work. */
        val REFUSED_ACTIONS = setOf(
            "android.intent.action.DELETE",
            "android.intent.action.UNINSTALL_PACKAGE",
            "android.intent.action.INSTALL_PACKAGE",
        )

        /** Some apps ship labels with line breaks or padding; a tile title is one clean line. */
        val WHITESPACE = Regex("\\s+")

        const val MAX_LABEL_LENGTH = 80
    }
}
