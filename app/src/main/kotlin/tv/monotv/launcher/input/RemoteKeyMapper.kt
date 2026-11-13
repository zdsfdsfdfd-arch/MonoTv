package tv.monotv.launcher.input

import android.view.KeyEvent
import tv.monotv.launcher.nav.NavKey

/**
 * Turns raw remote-control key events into [NavKey]s.
 *
 * The launcher consumes ONLY the keys it navigates with: the D-pad, the confirm keys, BACK and MENU. Everything else
 * — HOME, the assistant button (KEYCODE_SEARCH on Sber remotes, VOICE_ASSIST / ASSIST elsewhere), the service
 * shortcut buttons (F5 / F6 ...), volume, power, TV_INPUT, SETTINGS — is deliberately reported as "not handled" so
 * the framework and the vendor services keep full control of them. In particular an unhandled KEYCODE_SEARCH is what
 * makes the platform launch the system assistant, so it must never be swallowed here.
 *
 * Works on plain ints / longs (no KeyEvent instance needed) so the whole policy is unit-testable on the JVM.
 */
class RemoteKeyMapper(private val onKey: (NavKey) -> Unit) {
    private var okDown = false
    private var okDownTime = 0L
    private var okLongFired = false
    private var backDown = false
    private var menuDown = false
    private var lastMoveTime = Long.MIN_VALUE

    fun handle(event: KeyEvent): Boolean =
        handle(event.action, event.keyCode, event.repeatCount, event.eventTime, event.isCanceled)

    /** @return true when the event was consumed by the launcher. */
    fun handle(action: Int, keyCode: Int, repeatCount: Int, eventTime: Long, canceled: Boolean): Boolean {
        val direction = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> NavKey.Up
            KeyEvent.KEYCODE_DPAD_DOWN -> NavKey.Down
            KeyEvent.KEYCODE_DPAD_LEFT -> NavKey.Left
            KeyEvent.KEYCODE_DPAD_RIGHT -> NavKey.Right
            else -> null
        }
        if (direction != null) {
            if (action == KeyEvent.ACTION_DOWN) {
                // A held arrow auto-repeats about every 50 ms; let focus travel at a pace the springs can follow.
                if (repeatCount == 0 || eventTime - lastMoveTime >= REPEAT_INTERVAL_MS) {
                    lastMoveTime = eventTime
                    onKey(direction)
                }
            }
            return true
        }
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_SELECT, KeyEvent.KEYCODE_SPACE,
            -> {
                handleOk(action, repeatCount, eventTime, canceled)
                true
            }
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_BUTTON_B -> {
                // Act on release, and only when we also saw the press: the UP of a BACK that closed another app must
                // not navigate here.
                if (action == KeyEvent.ACTION_DOWN) {
                    if (repeatCount == 0) backDown = true
                } else if (action == KeyEvent.ACTION_UP) {
                    if (backDown && !canceled) onKey(NavKey.Back)
                    backDown = false
                }
                true
            }
            KeyEvent.KEYCODE_MENU -> {
                if (action == KeyEvent.ACTION_DOWN) {
                    if (repeatCount == 0) menuDown = true
                } else if (action == KeyEvent.ACTION_UP) {
                    if (menuDown && !canceled) onKey(NavKey.Menu)
                    menuDown = false
                }
                true
            }
            else -> false
        }
    }

    private fun handleOk(action: Int, repeatCount: Int, eventTime: Long, canceled: Boolean) {
        if (action == KeyEvent.ACTION_DOWN) {
            if (repeatCount == 0) {
                okDown = true
                okDownTime = eventTime
                okLongFired = false
            } else if (okDown && !okLongFired && eventTime - okDownTime >= LONG_PRESS_MS) {
                okLongFired = true
                onKey(NavKey.OkLong)
            }
        } else if (action == KeyEvent.ACTION_UP) {
            if (okDown && !canceled && !okLongFired) {
                // Remotes that do not auto-repeat still get a long press: it is recognised on release.
                onKey(if (eventTime - okDownTime >= LONG_PRESS_MS) NavKey.OkLong else NavKey.Ok)
            }
            okDown = false
            okLongFired = false
        }
    }

    /** Forget half-finished presses (the window lost focus while a key was down). */
    fun reset() {
        okDown = false
        okLongFired = false
        backDown = false
        menuDown = false
    }

    companion object {
        const val LONG_PRESS_MS = 520L
        const val REPEAT_INTERVAL_MS = 95L
    }
}
