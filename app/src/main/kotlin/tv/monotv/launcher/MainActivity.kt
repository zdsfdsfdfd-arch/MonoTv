package tv.monotv.launcher

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import tv.monotv.launcher.input.RemoteKeyMapper
import tv.monotv.launcher.nav.SettingsCatalog
import tv.monotv.launcher.ui.LauncherScreen

/**
 * The single activity of the launcher.
 *
 * It owns no UI state. Key events go to [RemoteKeyMapper] -> [LauncherViewModel]; anything the mapper does not claim
 * (HOME, the assistant button, vendor shortcut keys, volume ...) continues to the framework untouched, which is what
 * keeps the Sber voice assistant and the remote's service buttons working while this screen is in front.
 */
class MainActivity : ComponentActivity() {
    private val container: AppContainer get() = (application as LauncherApp).container
    private val viewModel: LauncherViewModel by viewModels { container.viewModelFactory }
    private val keys = RemoteKeyMapper { viewModel.onKey(it) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enterImmersive()
        setContent {
            val state by viewModel.ui.collectAsStateWithLifecycle()
            LauncherScreen(
                state = state,
                artwork = container.artwork,
                settingsRows = { page -> SettingsCatalog.rows(page, state) },
                onPointer = viewModel::onPointer,
            )
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // BACK at the root of Home while we are an ordinary app (not the default HOME): leave.
                viewModel.exit.collect { finish() }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        viewModel.start()
    }

    override fun onStop() {
        viewModel.stop()
        keys.reset()
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // HOME pressed while we are already in front (only happens when we are the HOME app): back to the start.
        if (intent.hasCategory(Intent.CATEGORY_HOME)) viewModel.goHome()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean = keys.handle(event) || super.dispatchKeyEvent(event)

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterImmersive() else keys.reset()
    }

    /**
     * Full-screen immersive mode. On Sber devices this is also what keeps the assistant's bottom panel from being
     * drawn over the launcher (per the Salute TV app guidelines); the assistant itself keeps working.
     */
    private fun enterImmersive() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}
