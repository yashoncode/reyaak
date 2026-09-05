package io.reyaak.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * Brand artwork the host owns.
 *
 * Same reasoning as [ReyaakFonts]: :ui has no resource table of its own, so the
 * launcher mark arrives as a painter rather than an id.
 */
@Immutable
data class ReyaakBrand(val icon: Painter? = null)

val LocalBrand = staticCompositionLocalOf { ReyaakBrand() }

/**
 * The Phosphor codepoints the app uses.
 *
 * The font shipped with the app is subsetted to exactly these glyphs, so adding
 * a name here without re-subsetting renders a blank box. The subsetting command
 * lives in the release notes for this change.
 */
object Ph {
    const val LIST = "\uE2F0"
    const val TERMINAL = "\uEAE8"
    const val PLUS = "\uE3D4"
    const val ARROW_RIGHT = "\uE06C"
    const val COPY = "\uE1CA"
    const val REFRESH = "\uE094"
    const val WARNING_CIRCLE = "\uE4E2"
    const val COUNTER_CLOCKWISE = "\uE038"
    const val CHECK = "\uE182"
    const val SEARCH = "\uE30C"
    const val GLOBE = "\uE288"
    const val ARROW_DOWN = "\uE03E"
    const val FILE_PDF = "\uE702"
    const val X = "\uE4F6"
    const val CARET_UP_DOWN = "\uE140"
    const val PAPERCLIP = "\uE39A"
    const val MICROPHONE = "\uE326"
    const val SNOWFLAKE = "\uE5AA"
    const val QUESTION = "\uE3E8"
    const val BELL_SLASH = "\uE0D4"
    const val BATTERY_WARNING = "\uE0C8"
    const val SHIELD_CHECK = "\uE40C"
    const val LOCK = "\uE308"
    const val DOWNLOAD = "\uE20C"
    const val UPLOAD = "\uE4C0"
    const val MOON_STARS = "\uE58E"
    const val CARET_RIGHT = "\uE13A"
    const val CARET_DOWN = "\uE136"
    const val TRASH = "\uE4A6"
    const val FLASK = "\uE79E"
    const val HISTORY = "\uE1A0"
    const val INFO = "\uE2CE"
    const val GITHUB = "\uE576"
    const val CHECK_CIRCLE = "\uE184"
    const val ARROW_LEFT = "\uE058"
    const val CHAT = "\uE176"
    const val SHUFFLE = "\uE422"
    const val PULSE = "\uE000"
    const val GEAR = "\uE272"
    const val STOP = "\uE46C"
    const val PLAY = "\uE3D0"
    const val ARROW_UP = "\uE08E"
    const val PIN = "\uE3E2"
    const val PIN_SLASH = "\uE3E4"
    const val ARCHIVE = "\uE00C"
    const val BRAIN = "\uE74E"
    const val PENCIL = "\uE3B4"
}

/** One Phosphor glyph. [fill] picks the solid cut, as the design does for tabs. */
@Composable
fun PhIcon(
    glyph: String,
    size: Double,
    tint: Color,
    modifier: Modifier = Modifier,
    fill: Boolean = false,
) {
    val fonts = LocalFonts.current
    Text(
        text = glyph,
        modifier = modifier,
        color = tint,
        style = TextStyle(
            fontFamily = if (fill) fonts.iconsFill else fonts.icons,
            fontSize = size.sp,
            // An icon font wants its own em box: any extra leading shifts the
            // glyph off the centre of whatever row it sits in.
            lineHeight = size.sp,
            letterSpacing = 0.sp,
        ),
    )
}

/** The app ground: two accent washes over the flat background. */
fun Modifier.reyaakBackground(tokens: ReyaakTokens): Modifier = this
    .background(tokens.bg)
    .background(
        Brush.radialGradient(
            colors = listOf(Color(0xFF5C8CF0).copy(alpha = 0.22f), Color.Transparent),
            center = Offset(0.08f * 1200f, -0.14f * 1200f),
            radius = 900f,
        )
    )
    .background(
        Brush.radialGradient(
            colors = listOf(Color(0xFF3A60B4).copy(alpha = 0.14f), Color.Transparent),
            center = Offset(1.04f * 1200f, 1.08f * 1800f),
            radius = 900f,
        )
    )

/**
 * A glass panel: a low-alpha fill plus the hairline that makes it read as one.
 *
 * Compose has no backdrop blur. `Modifier.blur` blurs an element's own content,
 * not what is behind it, and sampling the backdrop needs either a third-party
 * layer or a RenderEffect pass that is API 31+ and Android-only. So the glass
 * here is what shipping apps actually do: translucent fill, hairline border, and
 * for the raised pieces an inner top highlight. It reads as glass on both
 * platforms and costs nothing.
 */
@Composable
fun Glass(
    modifier: Modifier = Modifier,
    radius: Dp = 20.dp,
    fill: Color? = null,
    line: Color? = null,
    padding: PaddingValues = PaddingValues(16.dp),
    highlight: Boolean = false,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val t = LocalTokens.current
    val shape = RoundedCornerShape(radius)
    Box(
        // Cards fill their column by default, because every one of them does.
        // The caller's modifier still wins: it is applied after.
        Modifier
            .fillMaxWidth()
            .then(modifier)
            .clip(shape)
            .background(fill ?: t.g1)
            .then(
                if (highlight) Modifier.background(
                    Brush.verticalGradient(
                        0f to Color.White.copy(alpha = if (t.dark) 0.07f else 0.30f),
                        0.46f to Color.Transparent,
                    )
                ) else Modifier
            )
            .border(1.dp, line ?: t.line, shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(padding)
    ) { Column(Modifier.fillMaxWidth()) { content() } }
}

/** The 30px screen heading each tab opens with. */
@Composable
fun ScreenTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier,
        color = LocalTokens.current.ink,
        style = rk(500, 30.0, 1.15, tracking = (-0.025).em),
    )
}

/** The uppercase mono heading above each section. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, tint: Color? = null) {
    Text(
        text.uppercase(),
        modifier,
        color = tint ?: LocalTokens.current.accLt,
        style = rkSectionLabel(),
    )
}

/** A hairline divider inside a card. */
@Composable
fun GlassDivider(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(LocalTokens.current.line2))
}

/** The two-state presence dot, optionally breathing while it is live. */
@Composable
fun StatusDot(on: Boolean, size: Dp = 8.dp, modifier: Modifier = Modifier) {
    val t = LocalTokens.current
    val pulse = if (on) rememberPulse() else 1f
    Box(
        modifier
            .size(size)
            .scale(if (on) pulse else 1f)
            .alpha(if (on) (0.3f + 0.7f * pulse).coerceIn(0f, 1f) else 1f)
            .clip(CircleShape)
            .background(if (on) t.accLt else t.faint)
    )
}

/** rkPulse: 1 down to .85 and back, the design's "this is live" signal. */
@Composable
fun rememberPulse(durationMillis: Int = 2000): Float {
    val transition = rememberInfiniteTransition(label = "pulse")
    val v by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.85f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis / 2, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulseValue",
    )
    return v
}

/**
 * A 0-to-1-and-back progress, for anything that breathes.
 *
 * Returning the raw progress rather than a finished value is what lets one
 * transition drive an opacity and a scale that stay in phase, which is the
 * difference between a halo and two unrelated animations.
 */
@Composable
fun rememberBreath(durationMillis: Int): Float {
    val transition = rememberInfiniteTransition(label = "breath")
    val v by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis / 2, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "breathValue",
    )
    return v
}

/** The design's three button weights, as one composable with three fills. */
enum class ButtonTone { ACCENT, OUTLINE, NEUTRAL }

@Composable
fun RkButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tone: ButtonTone = ButtonTone.OUTLINE,
    glyph: String? = null,
    glyphFill: Boolean = false,
    height: Dp = 42.dp,
    radius: Dp = 14.dp,
    enabled: Boolean = true,
    fontSize: Double = 13.5,
) {
    val t = LocalTokens.current
    val shape = RoundedCornerShape(radius)
    val fg = when (tone) {
        ButtonTone.ACCENT -> t.tile
        ButtonTone.OUTLINE -> t.accLt
        ButtonTone.NEUTRAL -> t.mut
    }
    Row(
        modifier
            .height(height)
            .alpha(if (enabled) 1f else 0.45f)
            .clip(shape)
            .background(if (tone == ButtonTone.ACCENT) t.acc else Color.Transparent)
            .border(1.dp, if (tone == ButtonTone.ACCENT) Color.Transparent else if (tone == ButtonTone.OUTLINE) t.accLine else t.line, shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (glyph != null) {
            PhIcon(glyph, fontSize + 1.5, fg, fill = glyphFill)
            Spacer(Modifier.width(8.dp))
        }
        Text(
            label,
            color = fg,
            style = rk(if (tone == ButtonTone.ACCENT) 600 else 500, fontSize, 1.0),
        )
    }
}

/** The small bordered pill the design uses for Manage / Hide / Configure / New. */
@Composable
fun RkChipButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accent: Boolean = false,
    enabled: Boolean = true,
    glyph: String? = null,
) {
    val t = LocalTokens.current
    val shape = RoundedCornerShape(11.dp)
    val fg = if (accent) t.accLt else t.mut
    Row(
        modifier
            .alpha(if (enabled) 1f else 0.45f)
            .clip(shape)
            .border(1.dp, if (accent) t.accLine else t.line, shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (glyph != null) {
            PhIcon(glyph, 13.0, fg)
            Spacer(Modifier.width(6.dp))
        }
        Text(label, color = fg, style = rk(500, 12.0, 1.0))
    }
}

/** A bare tappable label, the design's inline text action. */
@Composable
fun RkTextAction(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color? = null,
    fontSize: Double = 12.5,
    enabled: Boolean = true,
) {
    val t = LocalTokens.current
    Text(
        label,
        modifier
            .alpha(if (enabled) 1f else 0.4f)
            .clip(RoundedCornerShape(9.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 5.dp),
        color = tint ?: t.accLt,
        style = rk(500, fontSize, 1.0),
    )
}

/**
 * The design's own toggle, 42 by 25 with a 19dp knob.
 *
 * Not Material's Switch: that one is 52 by 32 with a growing thumb, and next to
 * a 12dp row label it dominates the row it is supposed to annotate.
 */
@Composable
fun RkSwitch(checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val t = LocalTokens.current
    val shift by animateFloatAsState(if (checked) 1f else 0f, tween(180), label = "switch")
    Box(
        modifier
            .size(42.dp, 25.dp)
            .clip(RoundedCornerShape(99.dp))
            .background(if (checked) t.acc else t.g2)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onChange(!checked) },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .offset(x = 3.dp + 14.dp * shift)
                .size(19.dp)
                .clip(CircleShape)
                .background(if (checked) Color.White else t.faint)
        )
    }
}

/** Off / on-but-not-connected / active, as one colour-coded badge. */
enum class Readiness { ACTIVE, BLOCKED, OFF }

@Composable
fun ReadinessBadge(readiness: Readiness, label: String, modifier: Modifier = Modifier) {
    val t = LocalTokens.current
    val fg = when (readiness) {
        Readiness.ACTIVE -> t.accLt
        Readiness.BLOCKED -> t.err
        Readiness.OFF -> t.faint
    }
    val bg = when (readiness) {
        Readiness.ACTIVE -> t.accSoft
        Readiness.BLOCKED -> t.errSoft
        Readiness.OFF -> Color.Transparent
    }
    val line = when (readiness) {
        Readiness.ACTIVE -> t.accLine
        Readiness.BLOCKED -> t.errLine
        Readiness.OFF -> t.line
    }
    val shape = RoundedCornerShape(8.dp)
    Row(
        modifier
            .clip(shape)
            .background(bg)
            .border(1.dp, line, shape)
            .padding(start = 7.dp, end = 9.dp, top = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(5.dp).clip(CircleShape).background(fg))
        Spacer(Modifier.width(6.dp))
        Text(label, color = fg, style = rk(500, 10.5, 1.4))
    }
}

/** The glass text input. */
@Composable
fun RkField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    mono: Boolean = false,
    minLines: Int = 1,
    maxLines: Int = 1,
    enabled: Boolean = true,
    /** Hide what is typed. For a secret, not for a field that merely looks like one. */
    masked: Boolean = false,
) {
    val t = LocalTokens.current
    val shape = RoundedCornerShape(12.dp)
    val style = rk(400, 13.5, 1.5, mono = mono).copy(color = t.ink)
    Box(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(t.g1)
            .border(1.dp, t.line, shape)
            .padding(horizontal = 13.dp, vertical = if (maxLines > 1) 12.dp else 0.dp)
            .then(if (maxLines > 1) Modifier else Modifier.height(42.dp)),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (value.isEmpty()) Text(placeholder, color = t.faint, style = style)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            textStyle = style,
            cursorBrush = Brush.verticalGradient(listOf(t.accLt, t.accLt)),
            minLines = minLines,
            maxLines = maxLines,
            visualTransformation = if (masked) PasswordVisualTransformation()
            else VisualTransformation.None,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** A labelled field, the shape every form on Settings and Tools uses. */
@Composable
fun RkLabelledField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    maxLines: Int = 1,
) {
    val t = LocalTokens.current
    Column(modifier) {
        Text(label, color = t.mut, style = rk(400, 11.5, 1.0))
        Spacer(Modifier.height(6.dp))
        RkField(
            value = value,
            onValueChange = onValueChange,
            placeholder = placeholder,
            maxLines = maxLines,
            minLines = if (maxLines > 1) 2 else 1,
        )
    }
}

/**
 * Save, with the dirty state visible rather than only disabled.
 *
 * A dead grey button says "broken"; a dead grey button with a live accent dot
 * next to the word says "nothing to save yet", which is the actual state.
 */
@Composable
fun DirtySave(
    dirty: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    label: String = "Save",
) {
    val t = LocalTokens.current
    val shape = RoundedCornerShape(13.dp)
    val pulse = rememberPulse(1800)
    Row(
        modifier
            .height(42.dp)
            .alpha(if (dirty) 1f else 0.45f)
            .clip(shape)
            .background(if (dirty) t.accSoft else Color.Transparent)
            .border(1.dp, if (dirty) t.accLine else t.line, shape)
            .clickable(enabled = dirty, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (dirty) {
            Box(
                Modifier
                    .size(5.dp)
                    .alpha((0.3f + 0.7f * pulse).coerceIn(0f, 1f))
                    .clip(CircleShape)
                    .background(t.accLt)
            )
            Spacer(Modifier.width(8.dp))
        }
        Text(label, color = if (dirty) t.accLt else t.faint, style = rk(500, 13.5, 1.0))
    }
}

/** The 2dp indeterminate strip pinned to the top of the screen during any sync. */
@Composable
fun BusyBar(modifier: Modifier = Modifier) {
    val t = LocalTokens.current
    val transition = rememberInfiniteTransition(label = "busy")
    val x by transition.animateFloat(
        initialValue = -1f,
        targetValue = 3.2f,
        animationSpec = infiniteRepeatable(tween(1100, easing = LinearEasing)),
        label = "busyX",
    )
    BoxWithConstraints(modifier.fillMaxWidth().height(2.dp)) {
        val w = maxWidth
        Box(
            Modifier
                .offset(x = w * x)
                .width(w * 0.32f)
                .height(2.dp)
                .background(
                    Brush.horizontalGradient(listOf(Color.Transparent, t.acc, Color.Transparent))
                )
        )
    }
}

/** The bottom-anchored feedback banner, neutral or error. */
@Composable
fun RkToast(
    text: String,
    warnings: List<String>,
    isError: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = LocalTokens.current
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (isError) t.errSoft else t.g2)
            .border(1.dp, if (isError) t.errLine else t.line, shape)
            .padding(horizontal = 15.dp, vertical = 13.dp),
        verticalAlignment = Alignment.Top,
    ) {
        PhIcon(
            if (isError) Ph.WARNING_CIRCLE else Ph.CHECK_CIRCLE,
            16.0,
            if (isError) t.err else t.accLt,
            Modifier.padding(top = 1.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(text, color = t.ink, style = rk(400, 12.5, 1.5))
            warnings.take(6).forEach {
                Text(
                    "• $it",
                    color = t.mut,
                    style = rk(400, 11.0, 1.6, mono = true),
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            if (warnings.size > 6) Text(
                "…and ${warnings.size - 6} more",
                color = t.mut,
                style = rk(400, 11.0, 1.6, mono = true),
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(
            "Dismiss",
            Modifier.clickable(onClick = onDismiss).padding(top = 1.dp),
            color = t.accLt,
            style = rk(500, 12.0, 1.0),
        )
    }
}

/** What a destructive action asks before it happens. */
@Immutable
data class Confirmation(
    val title: String,
    val body: String,
    val cta: String,
    val danger: Boolean = false,
    val run: () -> Unit,
    /**
     * A second way to say yes, for the two questions that have one.
     *
     * Export asks with-or-without keys and import asks merge-or-replace: both
     * are a choice between two real answers, not a yes with a cancel, so
     * collapsing either into one button would drop a capability.
     */
    val alt: Pair<String, () -> Unit>? = null,
)

/**
 * The app's one feedback channel.
 *
 * A local rather than a pair of lambdas threaded through every screen: eight
 * screens need to say something went wrong and four need to ask before doing
 * something irreversible, and plumbing that as parameters means every
 * intermediate composable carries arguments it does not use.
 *
 * The default is deliberately silent so :ui still previews standalone.
 */
@Immutable
class ReyaakFeedback(
    val toast: (text: String, isError: Boolean, warnings: List<String>) -> Unit = { _, _, _ -> },
    val confirm: (Confirmation) -> Unit = {},
) {
    fun say(text: String) = toast(text, false, emptyList())
    fun fail(text: String) = toast(text, true, emptyList())
}

val LocalFeedback = staticCompositionLocalOf { ReyaakFeedback() }

@Composable
fun ConfirmSheet(confirmation: Confirmation, onDismiss: () -> Unit) {
    val t = LocalTokens.current
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF080910).copy(alpha = 0.6f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            ),
        contentAlignment = Alignment.BottomCenter,
    ) {
        val shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
        Column(
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(t.g2)
                .background(t.sheet.copy(alpha = if (t.dark) 0.86f else 0.94f))
                .border(1.dp, t.line, shape)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { }
                .padding(start = 18.dp, end = 18.dp, top = 20.dp, bottom = 26.dp),
        ) {
            Box(
                Modifier
                    .align(Alignment.CenterHorizontally)
                    .size(38.dp, 4.dp)
                    .clip(RoundedCornerShape(99.dp))
                    .background(t.g3)
            )
            Spacer(Modifier.height(16.dp))
            Text(
                confirmation.title,
                color = t.ink,
                style = rk(600, 18.0, 1.25, tracking = (-0.015).em),
            )
            Spacer(Modifier.height(8.dp))
            Text(confirmation.body, color = t.mut, style = rk(400, 13.0, 1.6))
            Spacer(Modifier.height(20.dp))
            val cancelShape = RoundedCornerShape(15.dp)
            confirmation.alt?.let { (altLabel, altRun) ->
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .clip(cancelShape)
                        .border(1.dp, t.line, cancelShape)
                        .clickable {
                            onDismiss()
                            altRun()
                        },
                    contentAlignment = Alignment.Center,
                ) { Text(altLabel, color = t.ink, style = rk(500, 14.0, 1.0)) }
                Spacer(Modifier.height(9.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                Box(
                    Modifier
                        .weight(1f)
                        .height(48.dp)
                        .clip(cancelShape)
                        .border(1.dp, t.line, cancelShape)
                        .clickable(onClick = onDismiss),
                    contentAlignment = Alignment.Center,
                ) { Text("Cancel", color = t.ink, style = rk(500, 14.0, 1.0)) }
                Box(
                    Modifier
                        .weight(1f)
                        .height(48.dp)
                        .clip(cancelShape)
                        .background(if (confirmation.danger) t.errSoft else t.accSoft)
                        .border(
                            1.dp,
                            if (confirmation.danger) Err40 else t.accLine,
                            cancelShape,
                        )
                        .clickable {
                            onDismiss()
                            confirmation.run()
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        confirmation.cta,
                        color = if (confirmation.danger) t.err else t.accLt,
                        style = rk(600, 14.0, 1.0),
                    )
                }
            }
        }
    }
}

private val Err40 = Color(0xFFE9899B).copy(alpha = 0.4f)

/**
 * A quiet square icon button for the trailing edge of a list row.
 *
 * Sized to the 28dp touch square the memory and skill rows share, and faint by
 * default: these sit next to content, and a row of bright glyphs would read as
 * the point of the row rather than what it says.
 */
@Composable
fun RowIcon(glyph: String, onClick: () -> Unit, active: Boolean = false) {
    val t = LocalTokens.current
    Box(
        Modifier
            .size(28.dp)
            .clip(RoundedCornerShape(9.dp))
            .then(
                if (active) {
                    Modifier
                        .background(t.accSoft)
                        .border(1.dp, t.accLine, RoundedCornerShape(9.dp))
                } else {
                    Modifier
                }
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { PhIcon(glyph, 13.0, if (active) t.accLt else t.faint, fill = active) }
}

/** The rounded launcher-mark tile, cropped the way the design crops it. */
@Composable
fun IconTile(size: Dp, modifier: Modifier = Modifier) {
    val t = LocalTokens.current
    val icon = LocalBrand.current.icon
    Box(
        modifier
            .size(size)
            // 26% of the box, which is the squircle radius the design uses.
            .clip(RoundedCornerShape(size * 0.26f))
            .background(t.tile),
        contentAlignment = Alignment.Center,
    ) {
        if (icon != null) androidx.compose.foundation.Image(
            painter = icon,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            // inset:-13%, width:126%: the mark is drawn with its own padding
            // baked in, and this crops it back off.
            modifier = Modifier.size(size * 1.26f),
        )
    }
}

/** The card body copy weight used across every screen. */
@Composable
fun CardTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, color = LocalTokens.current.ink, style = rk(500, 14.5, 1.2))
}

@Composable
fun CardBody(text: String, modifier: Modifier = Modifier, tint: Color? = null) {
    Text(text, modifier, color = tint ?: LocalTokens.current.mut, style = rk(400, 12.0, 1.5))
}

@Composable
fun FootNote(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier,
        color = LocalTokens.current.faint,
        style = rk(400, 11.5, 1.6),
    )
}

/** Mono machine truth: ids, scores, latencies, counts. */
@Composable
fun MonoText(
    text: String,
    modifier: Modifier = Modifier,
    size: Double = 11.0,
    tint: Color? = null,
    weight: Int = 400,
    maxLines: Int = Int.MAX_VALUE,
    align: TextAlign? = null,
) {
    Text(
        text,
        modifier,
        color = tint ?: LocalTokens.current.mut,
        style = rk(weight, size, 1.4, mono = true),
        maxLines = maxLines,
        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        textAlign = align,
    )
}
