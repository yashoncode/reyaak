package io.reyaak.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.reyaak.core.ReyaakCore

/** The playground on its own page. Scrolls, because a long reply needs the room. */
@Composable
fun PlaygroundScreen(core: ReyaakCore) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { PlaygroundCard(core) }
        item { Spacer(Modifier.height(NavBarSpace)) }
    }
}
