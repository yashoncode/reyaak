package io.reyaak.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em

private const val GITHUB = "https://github.com/yashoncode"

/**
 * What Reyaak is, and who built it.
 *
 * Its own page rather than a paragraph in Settings: Settings is for things you
 * change, and none of this is.
 */
@Composable
fun AboutScreen(version: String, onOpenUrl: (String) -> Unit) {
    val t = LocalTokens.current

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 30.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Glass(radius = 20.dp, padding = PaddingValues(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconTile(44.dp)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            "Reyaak",
                            color = t.ink,
                            style = rk(600, 22.0, 1.1, tracking = (-0.02).em),
                        )
                        Spacer(Modifier.height(5.dp))
                        MonoText(version, size = 11.0, tint = t.faint)
                    }
                }
                Spacer(Modifier.height(15.dp))
                Paragraph(
                    "An on-device autonomous agent. It runs its loop as a foreground " +
                        "service, so it keeps working while the app is in the background."
                )
                Spacer(Modifier.height(11.dp))
                Paragraph(
                    "Every turn goes through its own multi-provider router, which scores " +
                        "free-tier models on reliability, speed and intelligence, benches " +
                        "whatever breaks, and falls through to the next candidate."
                )
                Spacer(Modifier.height(11.dp))
                Paragraph(
                    "Conversations live in a local database. Provider keys are encrypted " +
                        "with a hardware-backed Keystore key. There is no telemetry."
                )
            }
        }

        item {
            BulletCard(
                "How it fits together",
                listOf(
                    "The router owns provider catalogues, scoring and fallback.",
                    "The core owns the agent loop, tools and the local database.",
                    "The UI only reads state; it never calls a provider directly.",
                    "The agent reaches models only through the router.",
                ),
            )
        }

        item {
            BulletCard(
                "Privacy",
                listOf(
                    "The Keystore key never leaves the device.",
                    "Backup and device transfer are disabled for the key store.",
                    "No telemetry, no crash reporting, no analytics.",
                ),
            )
        }

        item {
            Glass(radius = 20.dp) {
                SectionLabel("Built by")
                Spacer(Modifier.height(11.dp))
                Text("Yashwanth", color = t.ink, style = rk(500, 15.0, 1.2))
                Spacer(Modifier.height(6.dp))
                MonoText("github.com/yashoncode", size = 11.5)
                Spacer(Modifier.height(14.dp))
                RkButton(
                    label = "Open GitHub",
                    onClick = { onOpenUrl(GITHUB) },
                    glyph = Ph.GITHUB,
                    tone = ButtonTone.NEUTRAL,
                    radius = 13.dp,
                    fontSize = 13.0,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun Paragraph(text: String) {
    Text(text, color = LocalTokens.current.mut, style = rk(400, 13.0, 1.65))
}

@Composable
private fun BulletCard(title: String, bullets: List<String>) {
    val t = LocalTokens.current
    Glass(radius = 20.dp) {
        SectionLabel(title)
        Spacer(Modifier.height(12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
            bullets.forEach { line ->
                Row {
                    Box(
                        Modifier
                            .padding(top = 7.dp)
                            .size(4.dp)
                            .clip(CircleShape)
                            .background(t.acc)
                    )
                    Spacer(Modifier.width(9.dp))
                    Text(line, color = t.mut, style = rk(400, 12.5, 1.6))
                }
            }
        }
    }
}
