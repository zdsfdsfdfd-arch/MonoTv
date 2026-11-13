package tv.monotv.launcher.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke

/** The launcher's tiny icon set, drawn as strokes on a 24-unit grid. No icon font, no vector resources. */
enum class MonoIcon { Wifi, Ethernet, Offline, Inputs, Settings, ChevronRight, ChevronDown, Check, Play, Reorder, Hdmi, Av, Tuner, Dot }

@Composable
fun Icon(icon: MonoIcon, tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) { drawMonoIcon(icon, tint) }
}

/** Tint read at draw time (for colours animated through a State). */
@Composable
fun Icon(icon: MonoIcon, modifier: Modifier = Modifier, tint: () -> Color) {
    Canvas(modifier) { drawMonoIcon(icon, tint()) }
}

fun DrawScope.drawMonoIcon(icon: MonoIcon, tint: Color) {
    val u = size.minDimension / 24f
    val stroke = Stroke(width = 1.8f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
    fun p(x: Float, y: Float) = Offset(x * u, y * u)
    fun line(x1: Float, y1: Float, x2: Float, y2: Float) =
        drawLine(tint, p(x1, y1), p(x2, y2), strokeWidth = stroke.width, cap = StrokeCap.Round)

    fun arc(radius: Float, cx: Float = 12f, cy: Float = 18.2f) = drawArc(
        color = tint, startAngle = -135f, sweepAngle = 90f, useCenter = false,
        topLeft = p(cx - radius, cy - radius), size = Size(2 * radius * u, 2 * radius * u), style = stroke,
    )

    when (icon) {
        MonoIcon.Wifi, MonoIcon.Offline -> {
            arc(13f); arc(8.6f); arc(4.2f)
            drawCircle(tint, radius = 1.25f * u, center = p(12f, 18.2f))
            if (icon == MonoIcon.Offline) line(4.5f, 4.5f, 19.5f, 20.5f)
        }
        MonoIcon.Ethernet -> {
            drawRoundRect(tint, topLeft = p(9f, 3.5f), size = Size(6 * u, 5 * u), cornerRadius = CornerRadius(1.2f * u), style = stroke)
            drawRoundRect(tint, topLeft = p(3.5f, 15.5f), size = Size(6 * u, 5 * u), cornerRadius = CornerRadius(1.2f * u), style = stroke)
            drawRoundRect(tint, topLeft = p(14.5f, 15.5f), size = Size(6 * u, 5 * u), cornerRadius = CornerRadius(1.2f * u), style = stroke)
            val path = Path().apply {
                moveTo(12f * u, 8.5f * u); lineTo(12f * u, 12f * u)
                moveTo(6.5f * u, 15.5f * u); lineTo(6.5f * u, 12f * u); lineTo(17.5f * u, 12f * u); lineTo(17.5f * u, 15.5f * u)
            }
            drawPath(path, tint, style = stroke)
        }
        MonoIcon.Inputs -> {
            // A screen with an arrow entering it.
            val path = Path().apply {
                moveTo(9f * u, 5f * u); lineTo(18f * u, 5f * u)
                quadraticTo(20.5f * u, 5f * u, 20.5f * u, 7.5f * u); lineTo(20.5f * u, 16.5f * u)
                quadraticTo(20.5f * u, 19f * u, 18f * u, 19f * u); lineTo(9f * u, 19f * u)
            }
            drawPath(path, tint, style = stroke)
            line(3.5f, 12f, 13.5f, 12f)
            val head = Path().apply { moveTo(10.3f * u, 8.6f * u); lineTo(13.7f * u, 12f * u); lineTo(10.3f * u, 15.4f * u) }
            drawPath(head, tint, style = stroke)
        }
        MonoIcon.Settings -> {
            // Two sliders (the track is drawn in pieces so the knobs stay hollow without any blend-mode tricks).
            line(4f, 8f, 5.6f, 8f); line(12.4f, 8f, 20f, 8f)
            line(4f, 16f, 11.6f, 16f); line(18.4f, 16f, 20f, 16f)
            drawCircle(tint, radius = 2.5f * u, center = p(9f, 8f), style = stroke)
            drawCircle(tint, radius = 2.5f * u, center = p(15f, 16f), style = stroke)
        }
        MonoIcon.ChevronRight -> {
            val path = Path().apply { moveTo(9.5f * u, 6.5f * u); lineTo(15f * u, 12f * u); lineTo(9.5f * u, 17.5f * u) }
            drawPath(path, tint, style = stroke)
        }
        MonoIcon.ChevronDown -> {
            val path = Path().apply { moveTo(6.5f * u, 9.5f * u); lineTo(12f * u, 15f * u); lineTo(17.5f * u, 9.5f * u) }
            drawPath(path, tint, style = stroke)
        }
        MonoIcon.Check -> {
            val path = Path().apply { moveTo(5.5f * u, 12.5f * u); lineTo(10f * u, 17f * u); lineTo(18.5f * u, 7.5f * u) }
            drawPath(path, tint, style = Stroke(width = 2.1f * u, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        MonoIcon.Play -> {
            val path = Path().apply {
                moveTo(8.2f * u, 5.6f * u); lineTo(18.6f * u, 12f * u); lineTo(8.2f * u, 18.4f * u); close()
            }
            drawPath(path, tint)
            drawPath(path, tint, style = Stroke(width = 1.6f * u, join = StrokeJoin.Round))
        }
        MonoIcon.Reorder -> {
            val up = Path().apply { moveTo(8f * u, 9f * u); lineTo(12f * u, 5f * u); lineTo(16f * u, 9f * u) }
            val down = Path().apply { moveTo(8f * u, 15f * u); lineTo(12f * u, 19f * u); lineTo(16f * u, 15f * u) }
            drawPath(up, tint, style = stroke); drawPath(down, tint, style = stroke)
        }
        MonoIcon.Hdmi -> {
            // HDMI plug silhouette.
            val path = Path().apply {
                moveTo(4f * u, 8.5f * u); lineTo(20f * u, 8.5f * u); lineTo(20f * u, 12.5f * u)
                lineTo(17.5f * u, 15.5f * u); lineTo(6.5f * u, 15.5f * u); lineTo(4f * u, 12.5f * u); close()
            }
            drawPath(path, tint, style = stroke)
            line(8f, 11.6f, 16f, 11.6f)
        }
        MonoIcon.Av -> {
            drawCircle(tint, radius = 3.1f * u, center = p(7f, 12f), style = stroke)
            drawCircle(tint, radius = 3.1f * u, center = p(17f, 12f), style = stroke)
            drawCircle(tint, radius = 0.9f * u, center = p(7f, 12f))
            drawCircle(tint, radius = 0.9f * u, center = p(17f, 12f))
        }
        MonoIcon.Tuner -> {
            drawRoundRect(tint, topLeft = p(3.5f, 8.5f), size = Size(17 * u, 11.5f * u), cornerRadius = CornerRadius(2.4f * u), style = stroke)
            line(8.5f, 3.5f, 12f, 8f); line(15.5f, 3.5f, 12f, 8f)
        }
        MonoIcon.Dot -> drawCircle(tint, radius = 3.2f * u, center = p(12f, 12f))
    }
}
