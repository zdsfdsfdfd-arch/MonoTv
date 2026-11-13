package tv.monotv.launcher.data.apps

import android.content.Context
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import tv.monotv.launcher.core.KnownApp
import tv.monotv.launcher.core.LauncherConfig
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * Loads [LauncherConfig] from `assets/launcher-config.json` and lets a file with the same name in the app's external
 * files dir replace it, so the Dock priority and the known-apps registry can be changed with nothing but a USB stick
 * and a file manager — no rebuild, no ADB.
 *
 * The one thing an override cannot do is lift the factory / engineering menu blocklist: [withSafetyFloor] is applied
 * to whatever was loaded, because starting such a menu by accident can brick a TV.
 */
class LauncherConfigLoader(context: Context) {

    private val appContext: Context = context.applicationContext ?: context

    /**
     * Why the last [load] could not use a config file as written (in Russian, ready for the diagnostics screen), or
     * null when everything was read cleanly. A broken override never breaks the launcher — it is skipped and
     * reported here.
     */
    @Volatile
    var lastError: String? = null
        private set

    /**
     * Reads and parses the configuration. Touches storage, so call it off the main thread; never throws.
     *
     * The override REPLACES the bundled file as a whole (no merging): a user who trims the list of known apps must
     * get exactly that list. Only the mandatory blocklist survives a replacement, see [withSafetyFloor].
     */
    fun load(): LauncherConfig {
        val errors = ArrayList<String>(2)
        val bundled = read("встроенный файл $FILE_NAME", errors) { appContext.assets.open(FILE_NAME) }
        val override = overrideFile()?.let { file -> read("файл ${file.path}", errors) { file.inputStream() } }
        lastError = if (errors.isEmpty()) null else errors.joinToString(separator = " ")
        return withSafetyFloor(override ?: bundled ?: LauncherConfig())
    }

    /** The override file when it exists; storage being unmounted or unreadable simply means "no override". */
    private fun overrideFile(): File? = try {
        appContext.getExternalFilesDir(null)?.let { File(it, FILE_NAME) }?.takeIf { it.isFile }
    } catch (e: RuntimeException) {
        null
    }

    private fun read(what: String, errors: MutableList<String>, open: () -> InputStream): LauncherConfig? {
        val result = try {
            tryParse(open().use { it.readBoundedText() })
        } catch (e: IOException) {
            Result.failure(e)
        } catch (e: RuntimeException) {
            Result.failure(e)
        }
        result.exceptionOrNull()?.let { cause ->
            errors += "Не удалось прочитать $what: ${cause.message ?: cause.javaClass.simpleName}."
        }
        return result.getOrNull()
    }

    /** Reads at most [MAX_CONFIG_BYTES]: a config is a few kilobytes, anything bigger is somebody's mistake. */
    private fun InputStream.readBoundedText(): String {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        while (true) {
            val read = read(buffer)
            if (read < 0) break
            out.write(buffer, 0, read)
            if (out.size() > MAX_CONFIG_BYTES) throw IOException("файл больше ${MAX_CONFIG_BYTES / 1024} КБ")
        }
        return out.toString(Charsets.UTF_8.name())
    }

    companion object {
        const val FILE_NAME = "launcher-config.json"

        private const val MAX_CONFIG_BYTES = 512 * 1024

        /** Windows editors like to prepend a byte-order mark, which org.json rejects. */
        private const val BYTE_ORDER_MARK = "\uFEFF"

        /**
         * Packages that are blocked no matter what any config file says: factory, engineering and service tools of
         * the chipset / board vendors found in Sber TVs and their relatives. Exact names, or prefixes ending in '*'.
         */
        val MANDATORY_BLOCKED_PACKAGES: List<String> = listOf(
            "com.cvte.fac.*",
            "com.cvte.tv.hkcfac",
            "com.stb.factorymenu",
            "ru.sberdevices.TvFactoryHelper",
            "mediatek.factorymenu.ui",
            "com.mediatek.tv.factory",
            "com.mediatek.testpq",
            "com.mediatek.factorymode*",
            "com.mediatek.engineermode*",
            "com.at.factory.factorymanager",
            "com.tcl.factory.view",
            "com.homwee.factory",
            "com.smartdevice.factory",
            "com.duokan.factorytest",
            "com.hisense.autotest",
            "com.realtek.factorytools",
            "com.rtk.engmenu",
            "com.apps.factory.ui",
            "com.realtek.servicemenu",
            "com.toptech.tvfactory",
            "com.sdt.producttest",
            "com.sdmc.facTest",
            "com.utsmta.app",
            "com.droidlogic.readlog",
            "com.droidlogic.factory*",
            "com.fengmi.testsuite",
            "com.android.factorytest",
        )

        /**
         * Package-name fragments that are always blocked — the safety net for firmwares nobody has seen yet.
         *
         * "engmenu" is deliberately absent: the popular launcher Projectivy is `com.spocky.projengmenu` and is an
         * ordinary user app.
         */
        val MANDATORY_BLOCKED_KEYWORDS: List<String> = listOf(
            "factory",
            "factorytest",
            "engineermode",
            "engmode",
            "servicemenu",
            "hwtest",
            "burnin",
            "agingtest",
            "producttest",
        )

        /** Adds the non-negotiable blocklist entries to [config] (idempotent). */
        fun withSafetyFloor(config: LauncherConfig): LauncherConfig = config.copy(
            blockedPackages = (config.blockedPackages + MANDATORY_BLOCKED_PACKAGES).distinct(),
            blockedKeywords = (config.blockedKeywords + MANDATORY_BLOCKED_KEYWORDS).distinct(),
        )

        /**
         * Lenient parser: unknown fields are ignored, missing or mistyped fields fall back to their defaults and
         * text that is not a JSON object at all yields an empty [LauncherConfig]. Never throws; use [tryParse] to
         * learn why a text was rejected.
         *
         * The result is exactly what the text says — the mandatory blocklist is added by [load], not here.
         */
        fun parse(json: String): LauncherConfig = tryParse(json).getOrElse { LauncherConfig() }

        /** Like [parse], but reports text that is not a JSON object as a failure instead of hiding it. */
        fun tryParse(json: String): Result<LauncherConfig> = try {
            Result.success(fromJson(JSONObject(json.removePrefix(BYTE_ORDER_MARK))))
        } catch (e: JSONException) {
            // Checked on Android, unchecked in the reference org.json used by JVM tests — hence both clauses.
            Result.failure(e)
        } catch (e: RuntimeException) {
            Result.failure(e)
        }

        private fun fromJson(root: JSONObject): LauncherConfig {
            val knownApps = ArrayList<KnownApp>()
            val seenIds = HashSet<String>()
            root.optJSONArray("knownApps")?.let { array ->
                for (i in 0 until array.length()) {
                    val app = knownApp(array.optJSONObject(i) ?: continue) ?: continue
                    if (seenIds.add(app.id)) knownApps += app
                }
            }
            return LauncherConfig(
                version = root.optInt("version", 1),
                dockPriority = root.strings("dockPriority"),
                knownApps = knownApps,
                blockedPackages = root.strings("blockedPackages"),
                // The contract of LauncherConfig.isBlocked compares against the lower-cased package name.
                blockedKeywords = root.strings("blockedKeywords").map { it.lowercase() }.distinct(),
                extraLauncherCategories = root.strings("extraLauncherCategories"),
            )
        }

        private fun knownApp(json: JSONObject): KnownApp? {
            val id = json.string("id") ?: return null
            return KnownApp(
                id = id,
                title = json.string("title") ?: id,
                packages = json.strings("packages"),
                tagline = json.string("tagline"),
            )
        }

        /**
         * Only real, non-blank JSON strings count. `optString` is avoided on purpose: Android's org.json turns an
         * explicit `null` into the text "null", the reference implementation does not.
         */
        private fun JSONObject.string(name: String): String? =
            (opt(name) as? String)?.trim()?.takeIf { it.isNotEmpty() }

        private fun JSONObject.strings(name: String): List<String> = optJSONArray(name).strings()

        /** Non-blank strings of the array, trimmed, in order, without duplicates. */
        private fun JSONArray?.strings(): List<String> {
            if (this == null) return emptyList()
            val result = LinkedHashSet<String>()
            for (i in 0 until length()) {
                (opt(i) as? String)?.trim()?.takeIf { it.isNotEmpty() }?.let(result::add)
            }
            return result.toList()
        }
    }
}
