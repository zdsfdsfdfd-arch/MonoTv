package tv.monotv.launcher.data.content

import android.app.Application
import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.database.Cursor
import android.database.MatrixCursor
import android.media.tv.TvContract
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import tv.monotv.launcher.core.AppEntry
import tv.monotv.launcher.core.AppKey
import tv.monotv.launcher.core.ArtSource
import tv.monotv.launcher.core.ContinueItem

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidContentRepositoryTest {
    private val context: Application get() = ApplicationProvider.getApplicationContext()

    private val kinopoisk = app("ru.kinopoisk.tv", "Кинопоиск")
    private val okko = app("ru.more.play", "Okko")
    private val known = listOf(kinopoisk, okko).associateBy { it.key.packageName }
    private val lookups = ArrayList<String>()

    private fun app(pkg: String, label: String) = AppEntry(
        key = AppKey(pkg, "$pkg.Main"), label = label, isLeanback = true, hasBanner = true, isSystem = false,
        knownId = null, versionCode = 1, installedAt = 0,
    )

    private fun TestScope.repository(context: Context = this@AndroidContentRepositoryTest.context) = AndroidContentRepository(
        context = context,
        appForPackage = { pkg -> lookups += pkg; known[pkg] },
        io = StandardTestDispatcher(testScheduler),
    )

    private fun installProvider(): FakeTvProvider =
        Robolectric.buildContentProvider(FakeTvProvider::class.java).create(TV_AUTHORITY).get()

    private fun row(
        id: Long,
        pkg: String? = kinopoisk.key.packageName,
        title: String? = "Фильм $id",
        episode: String? = null,
        poster: String? = null,
        position: Long? = null,
        duration: Long? = null,
        intent: String? = null,
        type: Int? = TvContract.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE,
        engaged: Long? = id,
        browsable: Int? = 1,
    ): Map<String, Any?> = mapOf(
        "_id" to id,
        "package_name" to pkg,
        "title" to title,
        "episode_title" to episode,
        "poster_art_uri" to poster,
        "last_playback_position_millis" to position,
        "duration_millis" to duration,
        "intent_uri" to intent,
        "watch_next_type" to type,
        "last_engagement_time_utc_millis" to engaged,
        "browsable" to browsable,
    )

    // ---- mapping ---------------------------------------------------------------------------------------------------

    @Test
    fun `maps a watch next row to a continue item`() = runTest {
        installProvider().rows = listOf(
            row(
                id = 42, pkg = okko.key.packageName, title = "Тихий океан", episode = "3 серия",
                poster = "content://ru.more.play.posters/42", position = 600_000, duration = 2_400_000,
                intent = "intent://play/42#Intent;package=ru.more.play;end", engaged = 1_000,
            ),
        )
        assertEquals(
            listOf(
                ContinueItem(
                    id = "wn:42",
                    title = "Тихий океан",
                    subtitle = "3 серия",
                    art = ArtSource.LocalUri("content://ru.more.play.posters/42"),
                    progress = 0.25f,
                    appKey = okko.key,
                    intentUri = "intent://play/42#Intent;package=ru.more.play;end",
                ),
            ),
            repository().loadWatchNext(),
        )
    }

    @Test
    @Config(sdk = [28, 30, 33])
    fun `reads the provider on the android versions of the target devices`() = runTest {
        installProvider().rows = listOf(row(id = 1, episode = "Пилот", position = 50, duration = 100), row(id = 2))
        val items = repository().loadWatchNext()
        assertEquals(listOf("wn:2", "wn:1"), items.map { it.id })
        assertEquals(listOf(null, 0.5f), items.map { it.progress })
        assertEquals(listOf("Кинопоиск", "Пилот"), items.map { it.subtitle })
    }

    @Test
    fun `asks the provider the only way a non-system caller is allowed to`() = runTest {
        val provider = installProvider()
        provider.rows = listOf(row(id = 1))
        assertEquals(1, repository().loadWatchNext().size)

        val query = provider.queries.single()
        assertEquals(WATCH_NEXT_URI, query.uri.toString())
        assertEquals(TvContract.WatchNextPrograms.CONTENT_URI, query.uri)
        // TvProvider rejects any selection from callers without ACCESS_ALL_EPG_DATA and validates the sort order.
        assertNull(query.selection)
        assertNull(query.selectionArgs)
        assertNull(query.sortOrder)
        assertEquals(
            listOf(
                "_id", "package_name", "title", "episode_title", "poster_art_uri", "last_playback_position_millis",
                "duration_millis", "intent_uri", "watch_next_type", "last_engagement_time_utc_millis", "browsable",
            ),
            query.projection,
        )
    }

    @Test
    fun `newest engagement comes first and the limit keeps the newest`() = runTest {
        installProvider().rows = listOf(
            row(id = 1, engaged = 300),
            row(id = 2, engaged = 900),
            row(id = 3, engaged = null), // never engaged: oldest
            row(id = 4, engaged = 500),
            row(id = 5, engaged = 500), // tie: the later row wins
            row(id = 6, engaged = 100),
        )
        val repository = repository()
        assertEquals(listOf("wn:2", "wn:5", "wn:4", "wn:1", "wn:6", "wn:3"), repository.loadWatchNext().map { it.id })
        assertEquals(listOf("wn:2", "wn:5", "wn:4"), repository.loadWatchNext(limit = 3).map { it.id })
        assertEquals(listOf("wn:2"), repository.loadWatchNext(limit = 1).map { it.id })
        assertTrue(repository.loadWatchNext(limit = 0).isEmpty())
        assertTrue(repository.loadWatchNext(limit = -5).isEmpty())
    }

    @Test
    fun `an absurd limit is capped`() = runTest {
        installProvider().rows = (1L..80L).map { row(id = it) }
        val items = repository().loadWatchNext(limit = Int.MAX_VALUE)
        assertEquals(AndroidContentRepository.MAX_ITEMS, items.size)
        assertEquals("wn:80", items.first().id)
    }

    @Test
    fun `skips rows that must not be shown`() = runTest {
        installProvider().rows = listOf(
            row(id = 1, title = "   "),
            row(id = 2, title = null),
            row(id = 3, browsable = 0),
            row(id = 4, pkg = "com.example.unknown"),
            row(id = 5, pkg = context.packageName),
            row(id = 6, pkg = null),
            row(id = 7, title = "  Хребет  ", browsable = null),
        )
        val items = repository().loadWatchNext()
        assertEquals(listOf("wn:7"), items.map { it.id })
        assertEquals("Хребет", items.single().title)
        assertFalse("our own package is never looked up", context.packageName in lookups)
    }

    @Test
    fun `subtitle falls back to the app label`() = runTest {
        installProvider().rows = listOf(
            row(id = 3, episode = "5 серия"),
            row(id = 2, episode = "  "),
            row(id = 1, episode = null, pkg = okko.key.packageName),
        )
        assertEquals(listOf("5 серия", "Кинопоиск", "Okko"), repository().loadWatchNext().map { it.subtitle })
    }

    @Test
    fun `progress is reported only when the row really tells it`() = runTest {
        val next = TvContract.WatchNextPrograms.WATCH_NEXT_TYPE_NEXT
        val watchlist = TvContract.WatchNextPrograms.WATCH_NEXT_TYPE_WATCHLIST
        installProvider().rows = listOf(
            row(id = 10, position = 30, duration = 120),
            row(id = 9, position = 500, duration = 100), // beyond the end: clamped
            row(id = 8, position = -40, duration = 100), // garbage: clamped
            row(id = 7, position = 30, duration = 0),
            row(id = 6, position = 30, duration = null),
            row(id = 5, position = null, duration = 100),
            row(id = 4, position = 0, duration = 100), // started, at the very beginning
            row(id = 3, position = 0, duration = 100, type = next), // next episode: not started
            row(id = 2, position = 0, duration = 100, type = watchlist),
            row(id = 1, position = 25, duration = 100, type = null), // no type, but a real position
        )
        assertEquals(
            listOf(0.25f, 1f, 0f, null, null, null, 0f, null, null, 0.25f),
            repository().loadWatchNext().map { it.progress },
        )
    }

    @Test
    fun `only local posters are used, remote ones fall back to the app banner`() = runTest {
        installProvider().rows = listOf(
            row(id = 8, poster = "content://ru.kinopoisk.tv.art/poster/8"),
            row(id = 7, poster = "file:///storage/emulated/0/Android/data/ru.kinopoisk.tv/files/7.webp"),
            row(id = 6, poster = "android.resource://ru.kinopoisk.tv/drawable/poster"),
            row(id = 5, poster = "CONTENT://ru.kinopoisk.tv.art/poster/5"),
            row(id = 4, poster = "https://img.example.com/poster/4.jpg"),
            row(id = 3, poster = "http://img.example.com/poster/3.jpg"),
            row(id = 2, poster = null),
            row(id = 1, poster = "poster.jpg", pkg = okko.key.packageName),
        )
        assertEquals(
            listOf(
                ArtSource.LocalUri("content://ru.kinopoisk.tv.art/poster/8"),
                ArtSource.LocalUri("file:///storage/emulated/0/Android/data/ru.kinopoisk.tv/files/7.webp"),
                ArtSource.LocalUri("android.resource://ru.kinopoisk.tv/drawable/poster"),
                ArtSource.LocalUri("CONTENT://ru.kinopoisk.tv.art/poster/5"),
                ArtSource.AppBanner(kinopoisk.key),
                ArtSource.AppBanner(kinopoisk.key),
                ArtSource.AppBanner(kinopoisk.key),
                ArtSource.AppBanner(okko.key),
            ),
            repository().loadWatchNext().map { it.art },
        )
    }

    @Test
    fun `intent uri is passed through as stored and a blank one is dropped`() = runTest {
        installProvider().rows = listOf(
            row(id = 2, intent = "intent://title/2#Intent;scheme=kinopoisk;end"),
            row(id = 1, intent = " "),
        )
        assertEquals(
            listOf("intent://title/2#Intent;scheme=kinopoisk;end", null),
            repository().loadWatchNext().map { it.intentUri },
        )
    }

    @Test
    fun `every package is resolved once per load`() = runTest {
        installProvider().rows = (1L..6L).map { row(id = it, pkg = if (it % 2 == 0L) okko.key.packageName else "com.example.unknown") }
        assertEquals(3, repository().loadWatchNext().size)
        assertEquals(listOf("com.example.unknown", okko.key.packageName), lookups)
    }

    @Test
    fun `tolerates a vendor provider that does not return every column`() = runTest {
        val provider = installProvider()
        provider.columns = listOf("_id", "package_name", "title")
        provider.rows = listOf(row(id = 1, position = 10, duration = 20, episode = "Серия"))
        assertEquals(
            listOf(ContinueItem("wn:1", "Фильм 1", "Кинопоиск", ArtSource.AppBanner(kinopoisk.key), null, kinopoisk.key, null)),
            repository().loadWatchNext(),
        )
    }

    // ---- failure modes ---------------------------------------------------------------------------------------------

    @Test
    fun `security exception from the provider means no items`() = runTest {
        val provider = installProvider()
        provider.rows = listOf(row(id = 1))
        provider.failure = SecurityException("Selection not allowed for $WATCH_NEXT_URI")
        assertTrue(repository().loadWatchNext().isEmpty())
        assertEquals(1, provider.queries.size)
    }

    @Test
    fun `unknown uri or column means no items`() = runTest {
        installProvider().failure = IllegalArgumentException("Unknown URI $WATCH_NEXT_URI")
        assertTrue(repository().loadWatchNext().isEmpty())
    }

    @Test
    fun `any other runtime failure of the provider means no items`() = runTest {
        installProvider().failure = IllegalStateException("provider died")
        assertTrue(repository().loadWatchNext().isEmpty())
    }

    @Test
    fun `null cursor means no items`() = runTest {
        val provider = installProvider()
        provider.returnNull = true
        assertTrue(repository().loadWatchNext().isEmpty())
        assertEquals(1, provider.queries.size)
    }

    @Test
    fun `a cursor that breaks while being read means no items`() = runTest {
        val provider = installProvider()
        provider.rows = listOf(row(id = 1), row(id = 2))
        provider.breakAfterRows = 1
        assertTrue(repository().loadWatchNext().isEmpty())
        assertTrue("the cursor is closed even then", provider.lastCursor!!.isClosed)
    }

    @Test
    fun `cursor is closed after a successful read`() = runTest {
        val provider = installProvider()
        provider.rows = listOf(row(id = 1))
        repository().loadWatchNext()
        assertTrue(provider.lastCursor!!.isClosed)
    }

    @Test
    fun `missing tv provider means no items`() = runTest {
        // No provider registered for the TV authority: a phone / tablet build or a stripped firmware.
        assertTrue(repository().loadWatchNext().isEmpty())
    }

    @Test
    fun `a lookup that throws means no items`() = runTest {
        installProvider().rows = listOf(row(id = 1))
        val repository = AndroidContentRepository(context, { error("apps are not loaded yet") }, StandardTestDispatcher(testScheduler))
        assertTrue(repository.loadWatchNext().isEmpty())
    }

    @Test
    @Config(sdk = [25])
    fun `watch next does not exist before android 8 and is never touched there`() = runTest {
        val provider = installProvider()
        provider.rows = listOf(mapOf("_id" to 1L, "package_name" to kinopoisk.key.packageName, "title" to "Фильм"))
        val repository = repository()
        assertTrue(repository.loadWatchNext().isEmpty())
        assertTrue(provider.queries.isEmpty())

        repository.start()
        assertTrue(shadowOf(context.contentResolver).getContentObservers(Uri.parse(WATCH_NEXT_URI)).isEmpty())
        repository.stop()
    }

    // ---- invalidation ----------------------------------------------------------------------------------------------

    @Test
    fun `observer bumps the counter between start and stop`() = runTest {
        val resolver = context.contentResolver
        val uri = Uri.parse(WATCH_NEXT_URI)
        val repository = repository()
        assertEquals(0, repository.watchNextInvalidations.value)
        resolver.notifyChange(uri, null)
        assertEquals("not observing before start", 0, repository.watchNextInvalidations.value)

        repository.start()
        repository.start()
        assertEquals("start is idempotent", 1, shadowOf(resolver).getContentObservers(uri).size)
        resolver.notifyChange(uri, null)
        assertEquals(1, repository.watchNextInvalidations.value)
        resolver.notifyChange(Uri.withAppendedPath(uri, "17"), null)
        assertEquals("a single row changing counts too", 2, repository.watchNextInvalidations.value)

        repository.stop()
        repository.stop()
        assertTrue(shadowOf(resolver).getContentObservers(uri).isEmpty())
        resolver.notifyChange(uri, null)
        assertEquals(2, repository.watchNextInvalidations.value)

        repository.start()
        resolver.notifyChange(uri, null)
        assertEquals("can be started again", 3, repository.watchNextInvalidations.value)
        repository.stop()
    }

    @Test
    fun `nothing is thrown when the system refuses everything`() = runTest {
        val hostile = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this

            override fun getContentResolver(): ContentResolver = throw SecurityException("no content access")

            override fun checkSelfPermission(permission: String): Int = throw IllegalStateException("package manager died")
        }
        val repository = repository(hostile)
        repository.start()
        repository.start()
        assertTrue(repository.loadWatchNext().isEmpty())
        assertFalse(repository.hasTvListingsPermission())
        repository.stop()
        assertEquals(0, repository.watchNextInvalidations.value)
    }

    // ---- permission ------------------------------------------------------------------------------------------------

    @Test
    fun `reports whether tv listings may be read`() = runTest {
        val repository = repository()
        assertFalse(repository.hasTvListingsPermission())
        shadowOf(context).grantPermissions(AndroidContentRepository.READ_TV_LISTINGS)
        assertTrue(repository.hasTvListingsPermission())
        assertEquals("android.permission.READ_TV_LISTINGS", AndroidContentRepository.READ_TV_LISTINGS)
    }

    private companion object {
        const val TV_AUTHORITY = "android.media.tv"
        const val WATCH_NEXT_URI = "content://android.media.tv/watch_next_program"
    }
}

/**
 * Stands in for the platform TvProvider. Like the real one it answers a query with exactly the requested projection
 * (NULL for columns it does not have) and refuses selections from ordinary callers.
 */
class FakeTvProvider : ContentProvider() {
    class Query(
        val uri: Uri,
        val projection: List<String>?,
        val selection: String?,
        val selectionArgs: List<String>?,
        val sortOrder: String?,
    )

    var rows: List<Map<String, Any?>> = emptyList()

    /** When set, the cursor has these columns whatever was asked for (a vendor provider with an older schema). */
    var columns: List<String>? = null
    var failure: RuntimeException? = null
    var returnNull = false

    /** When set, reading past this many rows fails the way a dead cursor window does. */
    var breakAfterRows: Int? = null
    val queries = ArrayList<Query>()
    var lastCursor: Cursor? = null

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        queries += Query(uri, projection?.toList(), selection, selectionArgs?.toList(), sortOrder)
        failure?.let { throw it }
        if (!selection.isNullOrEmpty()) throw SecurityException("Selection not allowed for $uri")
        if (returnNull) return null
        val names = columns ?: projection?.toList() ?: rows.flatMap { it.keys }.distinct()
        val limit = breakAfterRows
        val cursor = object : MatrixCursor(names.toTypedArray()) {
            override fun onMove(oldPosition: Int, newPosition: Int): Boolean {
                if (limit != null && newPosition >= limit) throw IllegalStateException("Couldn't read row $newPosition")
                return super.onMove(oldPosition, newPosition)
            }
        }
        rows.forEach { row -> cursor.addRow(names.map { row[it] }) }
        lastCursor = cursor
        return cursor
    }

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
