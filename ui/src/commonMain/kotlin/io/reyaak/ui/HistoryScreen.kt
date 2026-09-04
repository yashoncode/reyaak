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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
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
    val t = LocalTokens.current
    val feedback = LocalFeedback.current
    val conversations by vm.history.collectAsStateWithLifecycle()
    val active by vm.activeConversation.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 30.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        item {
            RkButton(
                label = "New conversation",
                onClick = {
                    vm.newConversation()
                    onOpen()
                },
                glyph = Ph.PLUS,
                height = 44.dp,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(5.dp))
        }

        if (conversations.isEmpty()) {
            item {
                Text(
                    "Nothing yet. Anything you send in Chat shows up here.",
                    color = t.mut,
                    style = rk(400, 12.5, 1.5),
                )
            }
        }

        items(conversations, key = { it.id }) { row ->
            val isActive = row.id == active
            val shape = RoundedCornerShape(16.dp)
            val count = "${row.messageCount} message" + if (row.messageCount == 1) "" else "s"
            Row(
                Modifier
                    .fillMaxWidth()
                    // A keyed item animates itself, so a delete closes the gap
                    // rather than snapping the list.
                    .animateItem()
                    .clip(shape)
                    .background(if (isActive) t.accSoft else t.g1)
                    .border(1.dp, if (isActive) t.accLine else t.line, shape)
                    .clickable {
                        vm.openConversation(row.id)
                        onOpen()
                    }
                    .padding(14.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        row.title,
                        color = t.ink,
                        style = rk(if (isActive) 600 else 400, 14.0, 1.3),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    row.lastMessage?.takeIf { it.isNotBlank() }?.let {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            it.replace('\n', ' ').take(90),
                            color = t.mut,
                            style = rk(400, 12.0, 1.45),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(Modifier.height(7.dp))
                    MonoText(count, size = 10.5, tint = t.faint)
                }
                Spacer(Modifier.size(10.dp))
                // Deleting used to fire on the tap. A conversation is the only
                // copy of itself, so it now asks first.
                Box(
                    Modifier
                        .size(30.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .clickable {
                            feedback.confirm(
                                Confirmation(
                                    title = "Delete this conversation?",
                                    body = "“${row.title}” and its $count are removed " +
                                        "from the local database. This cannot be undone.",
                                    cta = "Delete",
                                    danger = true,
                                    run = { vm.deleteConversation(row.id) },
                                )
                            )
                        },
                    contentAlignment = Alignment.Center,
                ) { PhIcon(Ph.TRASH, 14.0, t.faint) }
            }
        }
    }
}
