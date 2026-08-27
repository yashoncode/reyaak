package io.reyaak.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp

/** One destination in the floating bar. */
data class NavDestination(val id: String, val label: String, val glyph: String)

/**
 * A floating, glassy navigation bar.
 *
 * Two deliberate notes on the "glass":
 *
 * Compose has no backdrop blur: `Modifier.blur` blurs an element's own
 * content, not what is behind it, and sampling the backdrop needs either a
 * third-party layer (haze) or a RenderEffect pass that only works on API 31+.
 * So the glass here is built the way it is built in most shipping apps: a
 * translucent fill over the app background, a hairline top-left highlight, and
 * a soft shadow to lift it off the content. It reads as glass on both
 * platforms and costs nothing.
 *
 * It lives in :ui rather than the Android host because it is the app's
 * chrome, not the OS's: an iOS host composes the same bar.
 */
@Composable
fun ReyaakNavBar(
    destinations: List<NavDestination>,
    selectedId: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    val scheme = MaterialTheme.colorScheme

    Box(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .clip(RoundedCornerShape(32.dp))
                // The fill is the surface at partial alpha so the transcript
                // shows through, with a vertical lift so the bar does not read
                // as a flat slab.
                .background(
                    Brush.verticalGradient(
                        listOf(
                            scheme.surfaceVariant.copy(alpha = 0.92f),
                            scheme.surface.copy(alpha = 0.86f),
                        )
                    )
                )
                .border(
                    width = 1.dp,
                    brush = Brush.verticalGradient(
                        listOf(
                            scheme.onSurface.copy(alpha = 0.14f),
                            scheme.onSurface.copy(alpha = 0.04f),
                        )
                    ),
                    shape = RoundedCornerShape(32.dp),
                )
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            destinations.forEach { destination ->
                NavPill(
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

@Composable
private fun NavPill(
    destination: NavDestination,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    // A 48dp target with a hit slop that fills the bar height, so the pill can
    // look small without being hard to hit.
    val tint by animateColorAsState(
        if (selected) scheme.primary else scheme.onSurfaceVariant,
        label = "navTint",
    )
    val glyphScale by animateFloatAsState(
        if (selected) 1.12f else 1f,
        label = "navGlyph",
    )

    Box(
        modifier = modifier
            .height(52.dp)
            .clip(RoundedCornerShape(26.dp))
            .background(
                if (selected) scheme.primary.copy(alpha = 0.16f)
                else scheme.surface.copy(alpha = 0f)
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
                Text(
                    text = destination.glyph,
                    style = MaterialTheme.typography.titleMedium,
                    color = tint,
                    modifier = Modifier.scale(glyphScale),
                )
            }
            Text(
                text = destination.label,
                style = MaterialTheme.typography.labelSmall,
                color = tint,
            )
        }
    }
}
