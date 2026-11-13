package tv.monotv.launcher.data.content

import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.database.Cursor
import android.media.tv.TvContract
import android.net.Uri
import android.os.Build
import android.provider.BaseColumns
import androidx.annotation.RequiresApi
import androidx.core.net.toUri
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import tv.monotv.launcher.core.AppEntry
import tv.monotv.launcher.core.AppKey
import tv.monotv.launcher.core.ArtSource
import tv.monotv.launcher.core.ContinueItem
import tv.monotv.launcher.data.ContentRepository
import java.util.Locale
import java.util.PriorityQueue

/**
 * Reads "continue watching" entries that other apps publish through the platform TV provider (the Watch Next table
 * of `android.media.tv`, API 26+).
 *
 * What a sideloaded launcher may see is decided by the provider, not by us (AOSP `TvProvider.createSqlParams`):
 *  - without the signature-level `ACCESS_ALL_EPG_DATA` permission a caller only gets its own rows, i.e. nothing;
 *  - with [READ_TV_LISTINGS] (a runtime permission the user can grant, see [hasTvListingsPermission]) rows of other
 *    packages marked searchable are returned as well;
 *  - for such callers any selection is rejected with a SecurityException and the sort order is validated.
 *
 * So the query is sent with no selection and no sort order, and filtering and ordering happen here. When the
 * provider is missing (non-TV builds) or refuses, the result is simply empty — this class never tries to get
 * around the restriction, and the home screen falls back to recently opened apps.
 *
 * Posters are used only when they are local (content / file / android.resource); http(s) posters are replaced by
 * the app banner because the launcher has no network access by design.
 *
 * @param appForPackage resolves a package to the app the launcher lists for it, or null when the launcher does not
 *   show that app (not installed, blocked, not launchable). Called on [io], so it must be thread-safe; rows of
 *   unknown packages are dropped, which keeps blocked packages out of the home screen here too.
 * @param io dispatcher for the provider round trip (binder call + cursor walk), never the main thread.
 */
class AndroidContentRepository(
    context: Context,
    private val appForPackage: (String) -> AppEntry?,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ContentRepository {

    // Application context only: this object outlives every activity.
    private val appContext: Context = context.applicationContext ?: context

    /** Our own rows are never "something to continue in another app". */
    private val ownPackage: String = appContext.packageName

    private val invalidations = MutableStateFlow(0)
    override val watchNextInvalidations: StateFlow<Int> = invalidations.asStateFlow()

    /**
     * No handler: the callback only bumps an atomic counter, so it can run on the binder thread that delivers it
     * and costs the main thread nothing. Bursts are harmless — StateFlow conflates them for the collector.
     */
    private val observer = object : ContentObserver(null) {
        override fun onChange(selfChange: Boolean) {
            invalidations.update { it + 1 }
        }
    }

    /** Touched from the main thread only (start / stop contract). */
    private var observing = false

    override suspend fun loadWatchNext(limit: Int): List<ContinueItem> {
        if (limit <= 0 || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return emptyList()
        return withContext(io) {
            try {
                queryWatchNext(limit.coerceAtMost(MAX_ITEMS))
            } catch (_: RuntimeException) {
                // SecurityException (not allowed), IllegalArgumentException (unknown URI or column on a vendor
                // provider), a provider process that died mid-read...: the row is just not shown.
                emptyList()
            }
        }
    }

    /**
     * True when the user granted [READ_TV_LISTINGS]. Without it the provider hides other apps' rows, so the
     * settings screen uses this to explain why "Продолжить просмотр" shows recently opened apps only.
     */
    fun hasTvListingsPermission(): Boolean = try {
        appContext.checkSelfPermission(READ_TV_LISTINGS) == PackageManager.PERMISSION_GRANTED
    } catch (_: RuntimeException) {
        false
    }

    override fun start() {
        if (observing || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        observing = try {
            appContext.contentResolver.registerContentObserver(WatchNext.uri, true, observer)
            true
        } catch (_: RuntimeException) {
            // Since Android 8 registering for an authority nobody provides throws SecurityException. Without the
            // observer the row still refreshes whenever the launcher comes back to the foreground.
            false
        }
    }

    override fun stop() {
        if (!observing) return
        observing = false
        try {
            appContext.contentResolver.unregisterContentObserver(observer)
        } catch (_: RuntimeException) {
            // The content service is unreachable, so there is nothing left to unregister from.
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun queryWatchNext(limit: Int): List<ContinueItem> {
        // selection / selectionArgs / sortOrder must stay null, see the class comment.
        val cursor = appContext.contentResolver.query(WatchNext.uri, WatchNext.projection, null, null, null)
            ?: return emptyList()
        return cursor.use { rows ->
            val columns = Columns(rows)
            val apps = HashMap<String, AppEntry?>()
            // Min-heap of the newest `limit` entries: memory stays bounded however many rows the provider holds.
            val newest = PriorityQueue(limit + 1, OLDEST_FIRST)
            while (rows.moveToNext()) {
                val entry = readEntry(rows, columns, apps) ?: continue
                newest.add(entry)
                if (newest.size > limit) newest.poll()
            }
            newest.sortedWith(OLDEST_FIRST.reversed()).map { it.item }
        }
    }

    /** Maps the current row, or returns null when it must not be shown. */
    @RequiresApi(Build.VERSION_CODES.O)
    private fun readEntry(row: Cursor, columns: Columns, apps: MutableMap<String, AppEntry?>): Entry? {
        // Browsable = 0 means the user (or the system) removed the entry from "continue watching".
        if (row.longOrNull(columns.browsable) == 0L) return null
        val id = row.longOrNull(columns.id) ?: return null
        val title = row.stringOrNull(columns.title)?.trim()
        if (title.isNullOrEmpty()) return null
        val packageName = row.stringOrNull(columns.packageName) ?: return null
        if (packageName == ownPackage) return null
        // Rows usually come from a handful of apps: resolve each package once per query.
        val app = if (packageName in apps) apps[packageName] else appForPackage(packageName).also { apps[packageName] = it }
        if (app == null) return null
        val episode = row.stringOrNull(columns.episodeTitle)?.trim()
        val item = ContinueItem(
            id = ContentComposer.WATCH_NEXT_ID_PREFIX + id,
            title = title,
            subtitle = if (episode.isNullOrEmpty()) app.label else episode,
            art = posterArt(row.stringOrNull(columns.posterArtUri), app.key),
            progress = progress(
                positionMillis = row.longOrNull(columns.positionMillis),
                durationMillis = row.longOrNull(columns.durationMillis),
                continuing = row.longOrNull(columns.watchNextType) == WatchNext.TYPE_CONTINUE,
            ),
            appKey = app.key,
            intentUri = row.stringOrNull(columns.intentUri)?.takeIf { it.isNotBlank() },
        )
        return Entry(engagedAt = row.longOrNull(columns.engagedAtMillis) ?: 0L, rowId = id, item = item)
    }

    /** A mapped row together with what it is ordered by. */
    private class Entry(val engagedAt: Long, val rowId: Long, val item: ContinueItem)

    /** Column positions of one cursor, looked up by name once (-1 = the provider did not return the column). */
    @RequiresApi(Build.VERSION_CODES.O)
    private class Columns(cursor: Cursor) {
        val id = cursor.getColumnIndex(BaseColumns._ID)
        val packageName = cursor.getColumnIndex(TvContract.BaseTvColumns.COLUMN_PACKAGE_NAME)
        val title = cursor.getColumnIndex(TvContract.WatchNextPrograms.COLUMN_TITLE)
        val episodeTitle = cursor.getColumnIndex(TvContract.WatchNextPrograms.COLUMN_EPISODE_TITLE)
        val posterArtUri = cursor.getColumnIndex(TvContract.WatchNextPrograms.COLUMN_POSTER_ART_URI)
        val positionMillis = cursor.getColumnIndex(TvContract.WatchNextPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS)
        val durationMillis = cursor.getColumnIndex(TvContract.WatchNextPrograms.COLUMN_DURATION_MILLIS)
        val intentUri = cursor.getColumnIndex(TvContract.WatchNextPrograms.COLUMN_INTENT_URI)
        val watchNextType = cursor.getColumnIndex(TvContract.WatchNextPrograms.COLUMN_WATCH_NEXT_TYPE)
        val engagedAtMillis = cursor.getColumnIndex(TvContract.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS)
        val browsable = cursor.getColumnIndex(TvContract.WatchNextPrograms.COLUMN_BROWSABLE)
    }

    /**
     * Everything that references the API 26 `WatchNextPrograms` class at run time lives here, so the outer class
     * loads and verifies cleanly on the API 23–25 devices that minSdk still admits.
     */
    @RequiresApi(Build.VERSION_CODES.O)
    private object WatchNext {
        val uri: Uri = TvContract.WatchNextPrograms.CONTENT_URI

        /** The user already started this program (as opposed to "next episode", "new" and watch-list rows). */
        const val TYPE_CONTINUE = TvContract.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE.toLong()

        /**
         * Exactly the columns the mapping reads: the table has several dozen, the provider cannot limit the row
         * count for us, and every byte travels through a shared 2 MB cursor window on each refresh.
         */
        val projection: Array<String> = arrayOf(
            BaseColumns._ID,
            TvContract.BaseTvColumns.COLUMN_PACKAGE_NAME,
            TvContract.WatchNextPrograms.COLUMN_TITLE,
            TvContract.WatchNextPrograms.COLUMN_EPISODE_TITLE,
            TvContract.WatchNextPrograms.COLUMN_POSTER_ART_URI,
            TvContract.WatchNextPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS,
            TvContract.WatchNextPrograms.COLUMN_DURATION_MILLIS,
            TvContract.WatchNextPrograms.COLUMN_INTENT_URI,
            TvContract.WatchNextPrograms.COLUMN_WATCH_NEXT_TYPE,
            TvContract.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS,
            TvContract.WatchNextPrograms.COLUMN_BROWSABLE,
        )
    }

    companion object {
        /**
         * Dangerous-level permission declared by the platform TV provider. The manifest requests it; the user grants
         * it from a runtime prompt or with `adb shell pm grant tv.monotv.launcher android.permission.READ_TV_LISTINGS`.
         */
        const val READ_TV_LISTINGS = "android.permission.READ_TV_LISTINGS"

        /** Hard ceiling for one load, whatever the caller asks for: a home-screen row never needs more. */
        const val MAX_ITEMS = 50

        /** Poster schemes that can be read without a network. */
        private val LOCAL_SCHEMES = setOf("content", "file", "android.resource")

        /** Oldest engagement first; the row id breaks ties so the order is stable between loads. */
        private val OLDEST_FIRST: Comparator<Entry> = compareBy<Entry> { it.engagedAt }.thenBy { it.rowId }

        /**
         * Watched fraction, or null when the row does not say: no known position, or no (positive) duration to
         * relate it to. Null means "draw no progress bar" — the launcher never invents one.
         *
         * @param continuing the row is of the "continue" type, i.e. its app states that playback was started. For
         *   the other types (next episode, new, watch list) a position of 0 is a default rather than a fact, and
         *   presenting it as "0 % watched — continue" would claim a playback that never happened.
         */
        internal fun progress(positionMillis: Long?, durationMillis: Long?, continuing: Boolean): Float? {
            if (positionMillis == null || durationMillis == null || durationMillis <= 0L) return null
            if (positionMillis <= 0L && !continuing) return null
            return (positionMillis.toDouble() / durationMillis.toDouble()).toFloat().coerceIn(0f, 1f)
        }

        /** The poster when it is a local uri, otherwise the banner of the app that published the row. */
        internal fun posterArt(posterUri: String?, app: AppKey): ArtSource {
            val poster = posterUri?.trim().orEmpty()
            val scheme = poster.toUri().scheme?.lowercase(Locale.ROOT)
            return if (scheme in LOCAL_SCHEMES) ArtSource.LocalUri(poster) else ArtSource.AppBanner(app)
        }

        private fun Cursor.stringOrNull(column: Int): String? =
            if (column < 0 || isNull(column)) null else getString(column)

        private fun Cursor.longOrNull(column: Int): Long? =
            if (column < 0 || isNull(column)) null else getLong(column)
    }
}
