package tv.monotv.launcher

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-xhdpi")
class SmokeScreenshotTest {
    @get:Rule val rule = createComposeRule()

    @Test fun smoke() {
        rule.setContent {
            Box(
                Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF05070A), Color(0xFF0B3D2E)))),
                contentAlignment = Alignment.Center,
            ) {
                BasicText(
                    "19:42 · Добрый вечер",
                    style = TextStyle(color = Color.White, fontSize = 64.sp, fontFamily = FontFamily(Font(R.font.inter_display_thin))),
                )
            }
        }
        rule.onRoot().captureRoboImage("build/screenshots/smoke.png")
    }
}
