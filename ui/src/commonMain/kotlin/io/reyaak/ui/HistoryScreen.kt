package io.reyaak.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.reyaak.vm.ChatViewModel

/**
 * Every conversation, newest first.
 *
 * Titles come from the first message the user sent, which is why there is no
 * rename here: the transcript already named itself, and a list of hand-typed
 * labels is a chore nobody keeps up.
 */
@Composable
fun HistoryScreen(vm: ChatViewModel, onOpen: () -> Unit) {
    val conversations by vm.history.collectAsStateWithLifecycle()
    val active by vm.activeConversation.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            OutlinedButton(
                onClick = {
                    vm.newConversation()
                    onOpen()
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("New conversation") }
        }

        if (conversations.isEmpty()) {
            item {
                Text(
                    "Nothing yet. Anything you send in Chat shows up here.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        items(conversations, key = { it.id }) { row ->
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (row.id == active) MaterialTheme.colorScheme.surfaceVariant
                    else MaterialTheme.colorScheme.surface,
                ),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    // Item removal animates the height rather than snapping the
                    // list, which is what makes a delete read as a delete.
                    .animateContentSize()
                    .clickable {
                        vm.openConversation(row.id)
                        onOpen()
                    },
            ) {
                Row(
                    Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            row.title,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = if (row.id == active) FontWeight.SemiBold
                            else FontWeight.Normal,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                        )
                        row.lastMessage?.takeIf { it.isNotBlank() }?.let {
                            Text(
                                it.replace('\n', ' ').take(90),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                        Text(
                            "${row.messageCount} message" + if (row.messageCount == 1) "" else "s",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { vm.deleteConversation(row.id) }) { Text("Delete") }
                }
            }
        }

        item { Spacer(Modifier.height(NavBarSpace)) }
    }
}
