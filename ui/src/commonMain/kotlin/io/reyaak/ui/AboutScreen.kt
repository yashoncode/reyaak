package io.reyaak.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private const val GITHUB = "https://github.com/yashoncode"

/**
 * What Reyaak is, and who built it.
 *
 * Its own page rather than a paragraph in Settings: Settings is for things you
 * change, and none of this is.
 */
@Composable
fun AboutScreen(version: String, onOpenUrl: (String) -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            AboutCard {
                Text(
                    "Reyaak",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    version,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                Body(
                    "A self-learning autonomous agent that lives on your phone, with its " +
                        "own multi-provider LLM router underneath it."
                )
                Spacer(Modifier.height(8.dp))
                Body(
                    "The router is the point. Rather than one provider and one model, Reyaak " +
                        "keeps a catalog of free-tier models across many providers, scores each " +
                        "one on reliability, speed and intelligence from what it has actually " +
                        "observed, and routes every turn to the best candidate it can reach. " +
                        "When a model rate-limits, breaks, or gets retired upstream, it is " +
                        "benched and the next candidate answers, so the conversation continues."
                )
                Spacer(Modifier.height(8.dp))
                Body(
                    "Everything runs on this device. Conversations are stored in a local " +
                        "database, provider keys are encrypted with a hardware-backed Android " +
                        "Keystore key, and the only network calls the app makes are to the " +
                        "provider endpoints in your own router config."
                )
            }
        }

        item {
            AboutCard {
                Label("How it fits together")
                Spacer(Modifier.height(8.dp))
                listOf(
                    "Router: providers, keys, scoring, health, rate limits, fallback.",
                    "Core: the agent, the chat engine, memory, and the single door to a model.",
                    "UI: one Compose surface shared by every screen you are looking at.",
                    "The agent can only reach a model through the router, never a provider directly.",
                ).forEach { Bullet(it) }
            }
        }

        item {
            AboutCard {
                Label("Privacy")
                Spacer(Modifier.height(8.dp))
                listOf(
                    "Provider keys are encrypted with a Keystore key that never leaves the device.",
                    "Cloud backup and device transfer are disabled for this app.",
                    "No telemetry, no analytics, no phone-home.",
                ).forEach { Bullet(it) }
            }
        }

        item {
            AboutCard {
                Label("Built by")
                Spacer(Modifier.height(6.dp))
                Text(
                    "Yashwanth",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    "github.com/yashoncode",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = { onOpenUrl(GITHUB) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Open GitHub") }
            }
        }

        item { Spacer(Modifier.height(NavBarSpace)) }
    }
}

@Composable
private fun AboutCard(content: @Composable () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(Modifier.padding(16.dp)) { content() }
    }
}

@Composable
private fun Label(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        fontFamily = FontFamily.Monospace,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun Body(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun Bullet(text: String) {
    Text(
        "• $text",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 4.dp),
    )
}
