package io.reyaak.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import io.reyaak.core.ReyaakCore

/**
 * Settings is only what you change: the persona, the theme, and the way out to
 * the three pages that are not settings at all.
 */
@Composable
fun SettingsScreen(
    core: ReyaakCore,
    darkTheme: Boolean,
    onToggleTheme: (Boolean) -> Unit,
    onOpenAbout: () -> Unit,
    onOpenPlayground: () -> Unit,
    onOpenHistory: () -> Unit,
    version: String,
    conversationCount: Int,
    bottomPadding: androidx.compose.ui.unit.Dp,
) {
    val t = LocalTokens.current

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 2.dp, bottom = bottomPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            ScreenTitle("Settings", Modifier.padding(top = 14.dp, bottom = 6.dp))
        }

        item { PersonalisationCard(core) }

        item {
            Glass(radius = 20.dp, padding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)) {
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PhIcon(Ph.MOON_STARS, 18.0, t.accLt)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Appearance", color = t.ink, style = rk(400, 14.0, 1.2))
                        Spacer(Modifier.height(3.dp))
                        Text(
                            if (darkTheme) "Dark" else "Light",
                            color = t.mut,
                            style = rk(400, 11.5, 1.4),
                        )
                    }
                    // A two-option segment rather than a switch: "Appearance
                    // [on/off]" never said which way was which.
                    val track = RoundedCornerShape(12.dp)
                    Row(
                        Modifier
                            .clip(track)
                            .background(t.g2)
                            .border(1.dp, t.line2, track)
                            .padding(3.dp),
                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        ThemeOption("Dark", darkTheme) { onToggleTheme(true) }
                        ThemeOption("Light", !darkTheme) { onToggleTheme(false) }
                    }
                }
            }
        }

        item {
            Glass(radius = 20.dp, padding = PaddingValues(horizontal = 16.dp, vertical = 2.dp)) {
                NavRow(Ph.FLASK, "Playground", "One prompt", onOpenPlayground)
                GlassDivider()
                NavRow(Ph.HISTORY, "History", conversationCount.toString(), onOpenHistory)
                GlassDivider()
                NavRow(Ph.INFO, "About", version, onOpenAbout)
            }
        }

        item {
            Column(
                Modifier.fillMaxWidth().padding(top = 18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // The lockup, composed rather than shipped as artwork: the mark
                // plus the wordmark in the app's own type, so it stays crisp at
                // any density and needs no second asset per theme.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconTile(34.dp)
                    Spacer(Modifier.width(11.dp))
                    Text(
                        "Reyaak",
                        color = t.ink,
                        style = rk(600, 26.0, 1.0, tracking = (-0.03).em),
                    )
                }
                Spacer(Modifier.height(12.dp))
                MonoText(
                    "$version · local-only · no telemetry",
                    size = 11.0,
                    tint = t.faint,
                    align = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun ThemeOption(label: String, selected: Boolean, onClick: () -> Unit) {
    val t = LocalTokens.current
    val shape = RoundedCornerShape(9.dp)
    Box(
        Modifier
            .clip(shape)
            .background(if (selected) t.accSoft else androidx.compose.ui.graphics.Color.Transparent)
            .then(if (selected) Modifier.border(1.dp, t.accLine, shape) else Modifier)
            .clickable(enabled = !selected, onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 7.dp),
    ) {
        Text(
            label,
            color = if (selected) t.accLt else t.mut,
            style = rk(if (selected) 500 else 400, 12.0, 1.0),
        )
    }
}

@Composable
private fun NavRow(glyph: String, label: String, value: String, onClick: () -> Unit) {
    val t = LocalTokens.current
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PhIcon(glyph, 18.0, t.accLt)
        Spacer(Modifier.width(12.dp))
        Text(label, Modifier.weight(1f), color = t.ink, style = rk(400, 14.5, 1.2))
        Text(value, color = t.faint, style = rk(400, 11.5, 1.0), maxLines = 1)
        Spacer(Modifier.width(8.dp))
        PhIcon(Ph.CARET_RIGHT, 13.0, t.faint)
    }
}
