package io.reyaak.ui

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp

/** One destination in the floating bar. [glyph] is a [Ph] codepoint. */
data class NavDestination(val id: String, val label: String, val glyph: String)

/**
 * The floating glass tab bar.
 *
 * The selection is one pill that slides between four fixed slots rather than a
 * background that fades in under each tab. That is the whole reason the bar
 * reads as a single object: the highlight is a thing that moves, so the eye
 * follows it instead of re-finding it.
 *
 * It lives in :ui rather than the Android host because it is the app's chrome,
 * not the OS's: an iOS host composes the same bar.
 */
@Composable
fun ReyaakNavBar(
    destinations: List<NavDestination>,
    selectedId: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    val t = LocalTokens.current
    val shape = RoundedCornerShape(28.dp)
    val index = destinations.indexOfFirst { it.id == selectedId }.coerceAtLeast(0)

    Box(
        modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 14.dp, vertical = 16.dp)
    ) {
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .shadow(18.dp, shape, clip = false)
                .clip(shape)
                .background(t.g1)
                // The bar sits over a transcript, so the fill alone would read
                // as a smear. A stronger base plus the top sheen is what gives
                // it an edge without a blur pass.
                .background(t.bg.copy(alpha = if (t.dark) 0.62f else 0.55f))
                .background(
                    Brush.verticalGradient(
                        0f to Color.White.copy(alpha = if (t.dark) 0.07f else 0.34f),
                        0.46f to Color.Transparent,
                    )
                )
                .border(1.dp, t.line, shape)
                .padding(horizontal = 8.dp, vertical = 7.dp)
        ) {
            // (bar - horizontal padding - the 2dp gaps between four slots) / 4
            val slot = (maxWidth - 6.dp) / destinations.size
            val offset by animateDpAsState(
                targetValue = (slot + 2.dp) * index,
                animationSpec = tween(480, easing = CubicBezierEasing(0.22f, 1.2f, 0.32f, 1f)),
                label = "pill",
            )
            val pillShape = RoundedCornerShape(20.dp)
            Box(
                Modifier
                    .offset(x = offset)
                    .width(slot)
                    .height(50.dp)
                    .clip(pillShape)
                    .background(t.accSoft)
                    .border(1.dp, t.accLine, pillShape)
            )
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                destinations.forEach { destination ->
                    NavTab(
                        destination = destination,
                        selected = destination.id == selectedId,
                        onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            onSelect(destination.id)
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun NavTab(
    destination: NavDestination,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = LocalTokens.current
    val tint = if (selected) t.accLt else t.mut
    val pop by animateFloatAsState(if (selected) 1f else 0.92f, tween(320), label = "tabPop")

    Column(
        modifier
            .clip(RoundedCornerShape(20.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(top = 8.dp, bottom = 7.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        PhIcon(
            glyph = destination.glyph,
            size = 20.0,
            tint = tint,
            fill = selected,
            modifier = Modifier.scale(pop),
        )
        Spacer(Modifier.height(5.dp))
        Text(
            destination.label,
            color = tint,
            style = rk(if (selected) 600 else 400, 10.0, 1.0),
        )
    }
}
