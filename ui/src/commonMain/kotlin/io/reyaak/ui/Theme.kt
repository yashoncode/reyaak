package io.reyaak.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

// Palette taken from the launcher icon rather than invented alongside it: the
// navy is the exact badge interior (#080A1B) and the accents are the saturated
// violet and blue sampled out of the cursor glyph. Dynamic colour is left off on
// purpose: letting the wallpaper repaint the app would throw away the one bit
// of visual identity it has.

private val Navy = Color(0xFF080A1B)
private val NavySurface = Color(0xFF11152A)
private val NavyRaised = Color(0xFF1A1F38)
private val Violet = Color(0xFFB49BF5)
private val Blue = Color(0xFF6FA8E8)
private val Mist = Color(0xFFE6E9F5)
private val MistDim = Color(0xFF9AA2C0)

private val VioletDeep = Color(0xFF5B4BC4)
private val BlueDeep = Color(0xFF1F6BB0)
private val Paper = Color(0xFFF7F7FC)
private val Ink = Color(0xFF14162B)
private val InkDim = Color(0xFF585E7A)

private val ReyaakDark = darkColorScheme(
    primary = Violet,
    onPrimary = Navy,
    primaryContainer = NavyRaised,
    onPrimaryContainer = Violet,
    secondary = Blue,
    onSecondary = Navy,
    background = Navy,
    onBackground = Mist,
    surface = NavySurface,
    onSurface = Mist,
    surfaceVariant = NavyRaised,
    onSurfaceVariant = MistDim,
    outline = Color(0xFF2E3552),
    error = Color(0xFFE9899B),
    onError = Navy,
)

private val ReyaakLight = lightColorScheme(
    primary = VioletDeep,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE7E2FB),
    onPrimaryContainer = VioletDeep,
    secondary = BlueDeep,
    onSecondary = Color.White,
    background = Paper,
    onBackground = Ink,
    surface = Color.White,
    onSurface = Ink,
    surfaceVariant = Color(0xFFECEDF6),
    onSurfaceVariant = InkDim,
    outline = Color(0xFFC9CCDE),
    error = Color(0xFFA3324A),
    onError = Color.White,
)

@Composable
fun ReyaakTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) ReyaakDark else ReyaakLight,
        content = content,
    )
}

/**
 * Fixed-decimal formatting. Common stdlib has no printf, and String.format is
 * JVM-only: using it in commonMain compiles on Android and breaks on iOS.
 * Values here are non-negative rates, scores, and rates-per-second.
 */
internal fun Double.fmt(decimals: Int): String {
    if (decimals == 0) return this.roundToInt().toString()
    var f = 1
    repeat(decimals) { f *= 10 }
    val v = (this * f).roundToInt()
    return "${v / f}.${(v % f).toString().padStart(decimals, '0')}"
}

/**
 * Vertical space the floating nav bar occupies.
 *
 * The bar draws OVER the content rather than displacing it, so anything that
 * scrolls to the bottom has to reserve this itself. One constant so the screens
 * and the bar cannot drift apart.
 */
val NavBarSpace = 88.dp
