package app.opentreadmill

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.opentreadmill.core.Workout
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

// ---- palette ------------------------------------------------------------------------

/** A colour theme. STOP red and the warning amber are not part of it: they never change. */
data class Palette(
    val name: String, val light: Boolean,
    val bg: Color, val panel: Color, val panelHi: Color, val line: Color, val textHi: Color, val muted: Color,
    val speed: Color, val incline: Color, val go: Color,
)

object Palettes {
    val all = listOf(
        Palette("Midnight", false, Color(0xFF0B0D10), Color(0xFF171B21), Color(0xFF222831), Color(0xFF2C333D), Color(0xFFF2F4F7), Color(0xFF8E98A6),
            Color(0xFF3DB8F5), Color(0xFFFF9F43), Color(0xFF2FC57A)),
        Palette("Ocean", false, Color(0xFF07121C), Color(0xFF0F2131), Color(0xFF16304A), Color(0xFF1E3D5C), Color(0xFFEAF4FF), Color(0xFF86A3BF),
            Color(0xFF4FD1FF), Color(0xFFFFB86B), Color(0xFF3EE0B5)),
        Palette("Ember", false, Color(0xFF120B0A), Color(0xFF221613), Color(0xFF30201B), Color(0xFF3D2A24), Color(0xFFFFF3EE), Color(0xFFB39A90),
            Color(0xFFFF8A5C), Color(0xFFFFC94D), Color(0xFFFF6B6B)),
        Palette("Forest", false, Color(0xFF0A110D), Color(0xFF142019), Color(0xFF1C2D23), Color(0xFF26392D), Color(0xFFEFF7F1), Color(0xFF8FA899),
            Color(0xFF6FD3A0), Color(0xFFE8C468), Color(0xFF9BE15D)),
        Palette("Violet", false, Color(0xFF0E0B14), Color(0xFF1A1524), Color(0xFF261E34), Color(0xFF322842), Color(0xFFF5F0FF), Color(0xFFA197B5),
            Color(0xFFB18CFF), Color(0xFFFF9FD2), Color(0xFF7CE0FF)),
        Palette("Daylight", true, Color(0xFFEEF1F5), Color(0xFFFFFFFF), Color(0xFFE2E7EE), Color(0xFFD0D7E1), Color(0xFF10151B), Color(0xFF5A6573),
            Color(0xFF0B84D9), Color(0xFFE07A10), Color(0xFF13A05F)),
    )
    fun named(n: String?) = all.firstOrNull { it.name == n } ?: all.first()
}

/** The theme in use. Reading it in composition or drawing redraws when it changes. */
var palette by mutableStateOf(Palettes.all.first())

val Bg get() = palette.bg
val Panel get() = palette.panel
val PanelHi get() = palette.panelHi
val Line get() = palette.line
val TextHi get() = palette.textHi
val Muted get() = palette.muted
val SpeedColor get() = palette.speed
val InclineColor get() = palette.incline
val Go get() = palette.go
/** Text on a coloured button: always dark, so it reads on every accent. */
val OnAccent = Color(0xFF0B0D10)
val StopRed = Color(0xFFE5484D)
val Warn = Color(0xFFF5A623)

fun categoryColor(c: String) = when (c) {
    WorkoutLibrary.WALK -> Color(0xFF2FC57A)
    WorkoutLibrary.HILLS -> InclineColor
    WorkoutLibrary.INTERVALS -> Color(0xFFB57BFF)
    WorkoutLibrary.RUN -> SpeedColor
    else -> Color(0xFFE6C84A)
}

// ---- building blocks ----------------------------------------------------------------

@Composable
fun Tile(modifier: Modifier = Modifier, color: Color = Panel, padding: Dp = 16.dp, content: @Composable ColumnScope.() -> Unit) =
    Column(modifier.background(color, RoundedCornerShape(14.dp)).padding(padding), content = content)

@Composable
fun Caps(text: String, color: Color = Muted, size: TextUnit = 15.sp) = Text(text.uppercase(), color = color, fontSize = size, letterSpacing = 1.sp, fontWeight = FontWeight.Medium)

@Composable
fun BigText(text: String, size: TextUnit, color: Color = TextHi) = Text(text, color = color, fontSize = size, fontWeight = FontWeight.Bold, maxLines = 1)

/** A large touch button. Colours are explicit so it reads at a glance from the belt. */
@Composable
fun PadButton(
    label: String, modifier: Modifier = Modifier, color: Color = PanelHi, textColor: Color = TextHi,
    enabled: Boolean = true, fontSize: TextUnit = 26.sp, onClick: () -> Unit,
) {
    Box(
        modifier
            .background(if (enabled) color else color.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = if (enabled) textColor else textColor.copy(alpha = 0.4f), fontSize = fontSize, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center) }
}

@Composable
fun Pill(label: String, selected: Boolean, color: Color = TextHi, onClick: () -> Unit) {
    Box(
        Modifier
            .background(if (selected) color.copy(alpha = 0.18f) else Color.Transparent, RoundedCornerShape(50))
            .border(1.dp, if (selected) color else Line, RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
    ) { Text(label, color = if (selected) color else Muted, fontSize = 17.sp, fontWeight = FontWeight.Medium) }
}

@Composable
fun Stepper(label: String, value: String, onMinus: () -> Unit, onPlus: () -> Unit, modifier: Modifier = Modifier, color: Color = TextHi) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Muted, fontSize = 16.sp, modifier = Modifier.weight(1f))
        PadButton("–", Modifier.size(48.dp), fontSize = 24.sp, onClick = onMinus)
        Text(value, color = color, fontSize = 20.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.width(96.dp))
        PadButton("+", Modifier.size(48.dp), fontSize = 24.sp, onClick = onPlus)
    }
}

// ---- charts -------------------------------------------------------------------------

/**
 * A workout's shape: speed bars on top, incline bars below, each bar as wide as its segment
 * is long. Everything before [elapsed] seconds is drawn solid, the rest dimmed.
 */
@Composable
fun ProfileChart(w: Workout, modifier: Modifier, elapsed: Int = -1, gap: Dp = 2.dp) {
    Canvas(modifier) {
        val total = w.totalSeconds.toFloat().coerceAtLeast(1f)
        val speedMax = maxOf(w.maxKph, 8.0).toFloat()
        val inclineMax = maxOf(w.maxIncline, 6.0).toFloat()
        val speedH = size.height * 0.62f
        val inclineTop = speedH + size.height * 0.06f
        val inclineH = size.height - inclineTop
        val g = gap.toPx()
        var t = 0f
        w.segments.forEach { s ->
            val x = t / total * size.width
            val wPx = (s.seconds / total * size.width - g).coerceAtLeast(1f)
            val done = elapsed >= 0 && t < elapsed
            val a = if (elapsed < 0 || done) 1f else 0.35f
            val sh = (s.kph.toFloat() / speedMax).coerceIn(0.04f, 1f) * speedH
            drawRoundRect(SpeedColor.copy(alpha = a), Offset(x, speedH - sh), Size(wPx, sh), CornerRadius(3f))
            val ih = (s.inclinePct.toFloat().coerceAtLeast(0f) / inclineMax).coerceIn(0.03f, 1f) * inclineH
            drawRoundRect(InclineColor.copy(alpha = a * 0.9f), Offset(x, inclineTop + inclineH - ih), Size(wPx, ih), CornerRadius(3f))
            t += s.seconds
        }
        if (elapsed >= 0) {
            val x = (elapsed / total).coerceIn(0f, 1f) * size.width
            drawLine(TextHi, Offset(x, 0f), Offset(x, size.height), strokeWidth = 3f)
        }
    }
}

/** A 400 m running track with a marker for where you'd be on it. */
@Composable
fun TrackChart(meters: Double, modifier: Modifier) {
    Canvas(modifier) {
        val stroke = 18f
        val h = size.height - stroke * 2
        val r = h / 2
        val straight = (size.width - stroke * 2 - h).coerceAtLeast(0f)
        val left = stroke + r
        val top = stroke
        val path = Path().apply {
            moveTo(left, top)
            lineTo(left + straight, top)
            arcTo(androidx.compose.ui.geometry.Rect(left + straight - r, top, left + straight + r, top + h), -90f, 180f, false)
            lineTo(left, top + h)
            arcTo(androidx.compose.ui.geometry.Rect(left - r, top, left + r, top + h), 90f, 180f, false)
            close()
        }
        drawPath(path, Line, style = Stroke(stroke))
        // Position along the lap: top straight, right bend, bottom straight, left bend.
        val per = 2 * straight + 2 * PI.toFloat() * r
        var d = ((meters % 400.0) / 400.0).toFloat() * per
        val p = when {
            d < straight -> Offset(left + d, top)
            d < straight + PI.toFloat() * r -> { d -= straight; val a = -PI / 2 + d / r; Offset(left + straight + r * cos(a).toFloat(), top + r + r * sin(a).toFloat()) }
            d < 2 * straight + PI.toFloat() * r -> { d -= straight + PI.toFloat() * r; Offset(left + straight - d, top + h) }
            else -> { d -= 2 * straight + PI.toFloat() * r; val a = PI / 2 + d / r; Offset(left + r * cos(a).toFloat(), top + r + r * sin(a).toFloat()) }
        }
        drawCircle(Go, radius = stroke * 0.9f, center = p)
        drawCircle(Bg, radius = stroke * 0.35f, center = p)
    }
}
