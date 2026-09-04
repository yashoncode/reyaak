package io.reyaak.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

// The Nocturne palette, transcribed from the design rather than sampled again.
// Two things it does that a Material colour scheme has no slot for, and which
// the tokens below exist to carry:
//
//   1. Surfaces are not colours, they are white (or ink) at a low alpha over one
//      shared background. g1/g2/g3 are those alphas. A card is the same token
//      everywhere, so nesting one inside another reads as depth for free.
//   2. Borders are a token too (line/line2), because every glass panel is a fill
//      plus a hairline. Dropping the hairline is what makes faux glass look flat.
//
// Dynamic colour stays off: letting the wallpaper repaint this would throw away
// the whole identity.

private val Accent = Color(0xFF5C8CF0)
private val AccentLightOnDark = Color(0xFFA9C4FB)
// color-mix(in oklab, #5c8cf0 56%, #000): the design computes the light-scheme
// accent this way rather than picking a second blue by hand.
private val AccentLightOnLight = Color(0xFF253D6D)
private val Err = Color(0xFFE9899B)

// Not redefined by the light scheme on purpose: the icon tile and the playground
// result panel stay near-black in both themes, because the launcher icon is lit
// for a dark ground and inverts badly.
private val Tile = Color(0xFF06070B)

/**
 * The design tokens Material 3 has nowhere to put.
 *
 * [g1] to [g3] are the glass fills, [line] and [line2] the hairlines that make
 * them read as panels. [accLt] is the accent as text and iconography; [acc] is
 * the accent as a fill, which is why they differ per theme.
 */
@Immutable
data class ReyaakTokens(
    val bg: Color,
    val ink: Color,
    val mut: Color,
    val faint: Color,
    val g1: Color,
    val g2: Color,
    val g3: Color,
    val line: Color,
    val line2: Color,
    val acc: Color,
    val accLt: Color,
    val accSoft: Color,
    val accLine: Color,
    val err: Color,
    val errSoft: Color,
    val errLine: Color,
    val tile: Color,
    val sheet: Color,
    val dark: Boolean,
)

private val DarkTokens = run {
    val ink = Color(0xFFE8ECF4)
    ReyaakTokens(
        bg = Color(0xFF07080C),
        ink = ink,
        mut = ink.copy(alpha = 0.56f),
        faint = ink.copy(alpha = 0.32f),
        g1 = Color.White.copy(alpha = 0.055f),
        g2 = Color.White.copy(alpha = 0.10f),
        g3 = Color.White.copy(alpha = 0.16f),
        line = Color.White.copy(alpha = 0.10f),
        line2 = Color.White.copy(alpha = 0.055f),
        acc = Accent,
        accLt = AccentLightOnDark,
        accSoft = Accent.copy(alpha = 0.16f),
        accLine = Accent.copy(alpha = 0.46f),
        err = Err,
        errSoft = Err.copy(alpha = 0.14f),
        errLine = Err.copy(alpha = 0.34f),
        tile = Tile,
        sheet = Color(0xFF101319),
        dark = true,
    )
}

private val LightTokens = run {
    val ink = Color(0xFF191B29)
    ReyaakTokens(
        bg = Color(0xFFF4F4F9),
        ink = ink,
        mut = ink.copy(alpha = 0.60f),
        faint = ink.copy(alpha = 0.40f),
        g1 = Color.White.copy(alpha = 0.58f),
        g2 = Color.White.copy(alpha = 0.80f),
        g3 = ink.copy(alpha = 0.12f),
        line = ink.copy(alpha = 0.10f),
        line2 = ink.copy(alpha = 0.07f),
        acc = Accent,
        accLt = AccentLightOnLight,
        accSoft = Accent.copy(alpha = 0.16f),
        accLine = Accent.copy(alpha = 0.46f),
        err = Err,
        errSoft = Err.copy(alpha = 0.14f),
        errLine = Err.copy(alpha = 0.34f),
        tile = Tile,
        sheet = Color.White,
        dark = false,
    )
}

val LocalTokens = staticCompositionLocalOf { DarkTokens }

/**
 * The three families the design names, supplied by the host.
 *
 * :ui has no androidMain, so it cannot open a font resource itself. The host
 * passes them the same way it passes "open this URL": as something only an OS
 * can provide. The defaults keep this module compiling and previewable alone.
 */
@Immutable
data class ReyaakFonts(
    val sans: FontFamily = FontFamily.Default,
    val mono: FontFamily = FontFamily.Monospace,
    val icons: FontFamily = FontFamily.Default,
    val iconsFill: FontFamily = FontFamily.Default,
)

val LocalFonts = staticCompositionLocalOf { ReyaakFonts() }

/** Shorthand for the design's `font:` declarations: weight, size, line height. */
@Composable
fun rk(
    weight: Int,
    size: Double,
    lineHeight: Double = 1.3,
    mono: Boolean = false,
    tracking: TextUnit = TextUnit.Unspecified,
): TextStyle = TextStyle(
    fontFamily = if (mono) LocalFonts.current.mono else LocalFonts.current.sans,
    fontWeight = FontWeight(weight),
    fontSize = size.sp,
    lineHeight = (size * lineHeight).sp,
    letterSpacing = tracking,
)

/** The uppercase mono label the design uses for every section heading. */
@Composable
fun rkSectionLabel(): TextStyle = rk(500, 10.5, 1.0, mono = true, tracking = 0.14.em)

@Composable
fun ReyaakTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    fonts: ReyaakFonts = ReyaakFonts(),
    content: @Composable () -> Unit,
) {
    val tokens = if (darkTheme) DarkTokens else LightTokens
    // Material's own scheme is still filled in, because the stock components
    // that survive the redesign (Switch, ripples, text selection) read from it.
    val scheme = if (darkTheme) {
        darkColorScheme(
            primary = tokens.acc,
            onPrimary = tokens.tile,
            primaryContainer = tokens.accSoft,
            onPrimaryContainer = tokens.accLt,
            secondary = tokens.accLt,
            onSecondary = tokens.tile,
            background = tokens.bg,
            onBackground = tokens.ink,
            surface = tokens.bg,
            onSurface = tokens.ink,
            surfaceVariant = tokens.g2,
            onSurfaceVariant = tokens.mut,
            outline = tokens.line,
            outlineVariant = tokens.line2,
            error = tokens.err,
            onError = tokens.tile,
            errorContainer = tokens.errSoft,
            onErrorContainer = tokens.err,
        )
    } else {
        lightColorScheme(
            primary = tokens.acc,
            onPrimary = Color.White,
            primaryContainer = tokens.accSoft,
            onPrimaryContainer = tokens.accLt,
            secondary = tokens.accLt,
            onSecondary = Color.White,
            background = tokens.bg,
            onBackground = tokens.ink,
            surface = tokens.bg,
            onSurface = tokens.ink,
            surfaceVariant = tokens.g2,
            onSurfaceVariant = tokens.mut,
            outline = tokens.line,
            outlineVariant = tokens.line2,
            error = tokens.err,
            onError = Color.White,
            errorContainer = tokens.errSoft,
            onErrorContainer = tokens.err,
        )
    }

    androidx.compose.runtime.CompositionLocalProvider(
        LocalTokens provides tokens,
        LocalFonts provides fonts,
    ) {
        MaterialTheme(
            colorScheme = scheme,
            typography = MaterialTheme.typography.copy(),
            content = content,
        )
    }
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
 * Height of the floating tab bar, above the system navigation inset.
 *
 * The bar draws OVER the content rather than displacing it, so anything that
 * scrolls to the bottom has to reserve this itself, and add the system inset on
 * top. One constant so the screens and the bar cannot drift apart:
 * 16dp margin + 7dp padding + 50dp pill + 7dp padding + 16dp margin.
 */
val NavBarSpace = 96.dp
