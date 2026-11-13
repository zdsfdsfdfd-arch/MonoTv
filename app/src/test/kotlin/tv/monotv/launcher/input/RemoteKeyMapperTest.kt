package tv.monotv.launcher.input

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.monotv.launcher.nav.NavKey

class RemoteKeyMapperTest {
    private val keys = ArrayList<NavKey>()
    private val mapper = RemoteKeyMapper { keys += it }

    private fun down(code: Int, repeat: Int = 0, time: Long = 0) = mapper.handle(KeyEvent.ACTION_DOWN, code, repeat, time, false)

    private fun up(code: Int, time: Long = 0, canceled: Boolean = false) = mapper.handle(KeyEvent.ACTION_UP, code, 0, time, canceled)

    @Test
    fun `arrows move on press and are consumed on release`() {
        assertTrue(down(KeyEvent.KEYCODE_DPAD_RIGHT))
        assertTrue(up(KeyEvent.KEYCODE_DPAD_RIGHT))
        assertTrue(down(KeyEvent.KEYCODE_DPAD_UP, time = 500))
        assertEquals(listOf(NavKey.Right, NavKey.Up), keys)
    }

    @Test
    fun `held arrow repeats are throttled`() {
        down(KeyEvent.KEYCODE_DPAD_DOWN, time = 1000)
        down(KeyEvent.KEYCODE_DPAD_DOWN, repeat = 1, time = 1050)
        down(KeyEvent.KEYCODE_DPAD_DOWN, repeat = 2, time = 1094)
        down(KeyEvent.KEYCODE_DPAD_DOWN, repeat = 3, time = 1100)
        down(KeyEvent.KEYCODE_DPAD_DOWN, repeat = 4, time = 1150)
        down(KeyEvent.KEYCODE_DPAD_DOWN, repeat = 5, time = 1200)
        assertEquals(listOf(NavKey.Down, NavKey.Down, NavKey.Down), keys)
    }

    @Test
    fun `every confirm key clicks on release`() {
        val codes = listOf(
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_SELECT, KeyEvent.KEYCODE_SPACE,
        )
        for (code in codes) {
            assertTrue(down(code, time = 10))
            assertTrue(keys.isEmpty())
            assertTrue(up(code, time = 80))
            assertEquals(listOf(NavKey.Ok), keys)
            keys.clear()
        }
    }

    @Test
    fun `long ok fires once while held and not again on release`() {
        down(KeyEvent.KEYCODE_DPAD_CENTER, time = 0)
        down(KeyEvent.KEYCODE_DPAD_CENTER, repeat = 1, time = 500)
        assertTrue(keys.isEmpty())
        down(KeyEvent.KEYCODE_DPAD_CENTER, repeat = 2, time = 550)
        down(KeyEvent.KEYCODE_DPAD_CENTER, repeat = 3, time = 600)
        up(KeyEvent.KEYCODE_DPAD_CENTER, time = 900)
        assertEquals(listOf(NavKey.OkLong), keys)
    }

    @Test
    fun `long ok is recognised on release when the remote does not repeat`() {
        down(KeyEvent.KEYCODE_DPAD_CENTER, time = 0)
        up(KeyEvent.KEYCODE_DPAD_CENTER, time = 700)
        assertEquals(listOf(NavKey.OkLong), keys)
    }

    @Test
    fun `ok release without a press or a canceled release does nothing`() {
        up(KeyEvent.KEYCODE_DPAD_CENTER, time = 5)
        down(KeyEvent.KEYCODE_DPAD_CENTER, time = 10)
        up(KeyEvent.KEYCODE_DPAD_CENTER, time = 20, canceled = true)
        assertTrue(keys.isEmpty())
    }

    @Test
    fun `back acts on release only after its own press`() {
        assertTrue(up(KeyEvent.KEYCODE_BACK))
        assertTrue(keys.isEmpty())
        assertTrue(down(KeyEvent.KEYCODE_BACK))
        assertTrue(keys.isEmpty())
        assertTrue(up(KeyEvent.KEYCODE_BACK))
        assertEquals(listOf(NavKey.Back), keys)
    }

    @Test
    fun `reset forgets half finished presses`() {
        down(KeyEvent.KEYCODE_BACK)
        down(KeyEvent.KEYCODE_DPAD_CENTER)
        mapper.reset()
        up(KeyEvent.KEYCODE_BACK)
        up(KeyEvent.KEYCODE_DPAD_CENTER)
        assertTrue(keys.isEmpty())
    }

    @Test
    fun `menu opens the menu on release`() {
        down(KeyEvent.KEYCODE_MENU)
        up(KeyEvent.KEYCODE_MENU)
        assertEquals(listOf(NavKey.Menu), keys)
    }

    @Test
    fun `system assistant and service keys are never consumed`() {
        val system = listOf(
            KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_SEARCH, KeyEvent.KEYCODE_VOICE_ASSIST, KeyEvent.KEYCODE_ASSIST,
            KeyEvent.KEYCODE_F5, KeyEvent.KEYCODE_F6, KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_VOLUME_MUTE, KeyEvent.KEYCODE_POWER, KeyEvent.KEYCODE_TV_INPUT, KeyEvent.KEYCODE_SETTINGS,
            KeyEvent.KEYCODE_TV, KeyEvent.KEYCODE_GUIDE, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_ALL_APPS,
        )
        for (code in system) {
            assertFalse("key $code must pass through", down(code))
            assertFalse("key $code must pass through", up(code))
        }
        assertTrue(keys.isEmpty())
    }
}
