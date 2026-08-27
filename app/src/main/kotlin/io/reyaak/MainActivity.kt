package io.reyaak

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import io.reyaak.core.ReyaakCore
import io.reyaak.runtime.AgentService
import io.reyaak.ui.AboutScreen
import io.reyaak.ui.AgentScreen
import io.reyaak.ui.ChatScreen
import io.reyaak.ui.HistoryScreen
import io.reyaak.ui.NavDestination
import io.reyaak.ui.PlaygroundScreen
import io.reyaak.ui.ReyaakNavBar
import io.reyaak.ui.ReyaakTheme
import io.reyaak.ui.RouterScreen
import io.reyaak.ui.SettingsScreen
import io.reyaak.vm.ChatViewModel
import io.reyaak.vm.RouterViewModel

private enum class Tab(val label: String, val glyph: String) {
    CHAT("Chat", "◆"),
    ROUTER("Router", "⇄"),
    AGENT("Agent", "◉"),
    SETTINGS("Settings", "⚙"),
}

/**
 * A page pushed over a tab: reached from one, returns to it.
 *
 * Still not a NavHost. These have no arguments and one level of depth, so a
 * nullable enum plus a BackHandler is the whole navigation model.
 */
private enum class Page(val title: String) {
    HISTORY("History"),
    ABOUT("About"),
    PLAYGROUND("Playground"),
}

private const val PREFS = "reyaak.ui"
private const val KEY_DARK = "darkTheme"

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        AgentService.ensureChannel(this)

        val core = (application as ReyaakApp).core
        // The theme choice is a host preference, not app state: it belongs to
        // this device, is read before the first frame, and never leaves. That is
        // exactly what SharedPreferences is, so nothing is built for it.
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        setContent {
            var dark by rememberSaveable { mutableStateOf(prefs.getBoolean(KEY_DARK, true)) }

            ReyaakTheme(darkTheme = dark) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    ReyaakShell(
                        core = core,
                        darkTheme = dark,
                        onToggleTheme = {
                            dark = it
                            prefs.edit().putBoolean(KEY_DARK, it).apply()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ReyaakShell(
    core: ReyaakCore,
    darkTheme: Boolean,
    onToggleTheme: (Boolean) -> Unit,
) {
    var tab by rememberSaveable { mutableStateOf(Tab.CHAT) }
    var page by rememberSaveable { mutableStateOf<Page?>(null) }
    var cli by rememberSaveable { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current

    val factory = remember(core) { coreViewModelFactory(core) }
    val chatVm: ChatViewModel = viewModel(factory = factory)
    val routerVm: RouterViewModel = viewModel(factory = factory)

    // A pushed page is what back means here; without this the system back would
    // leave the app from a subpage.
    BackHandler(enabled = page != null) { page = null }

    val context = LocalContext.current
    val openUrl: (String) -> Unit = { url ->
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    // The OS half of what :ui asks for as lambdas. Launchers must be created in
    // composition, so the pending callback is held in state and invoked on result.
    var onPicked by remember { mutableStateOf<((String?) -> Unit)?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val text = uri?.let {
            runCatching {
                context.contentResolver.openInputStream(it)?.bufferedReader()?.use { r -> r.readText() }
            }.getOrNull()
        }
        onPicked?.invoke(text)
        onPicked = null
    }
    var pendingSave by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    val saver = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        val save = pendingSave
        pendingSave = null
        if (uri != null && save != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { it.write(save.first.toByteArray()) }
            }
            save.second()
        }
    }

    val destinations = remember {
        Tab.entries.map { NavDestination(it.name, it.label, it.glyph) }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        // The floating bar draws over the content, so the Scaffold must not
        // reserve a strip for it: the screens pad for it themselves.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = page?.title ?: if (tab == Tab.CHAT) "Reyaak" else tab.label,
                        fontWeight = FontWeight.Bold,
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                ),
                navigationIcon = {
                    when {
                        // Back out of a pushed page.
                        page != null -> TextButton(onClick = { page = null }) {
                            Text("←", fontWeight = FontWeight.Bold)
                        }
                        // Conversations, where the drawer handle lives in every
                        // chat app: left of the title, one tap from the thread.
                        tab == Tab.CHAT -> TextButton(
                            onClick = {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                page = Page.HISTORY
                            }
                        ) { Text("☰", fontWeight = FontWeight.Bold) }
                    }
                },
                // Chat-only actions: a fresh conversation, and the same
                // conversation rendered as a terminal.
                actions = {
                    if (tab == Tab.CHAT && page == null) {
                        TextButton(
                            onClick = {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                chatVm.newConversation()
                            }
                        ) { Text("+", fontWeight = FontWeight.Bold) }
                        TextButton(
                            onClick = {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                cli = !cli
                            }
                        ) { Text(if (cli) "◆" else ">_", fontWeight = FontWeight.Bold) }
                    }
                },
            )
        },
    ) { inner ->
        Box(Modifier.fillMaxSize().padding(inner)) {
            // A pushed page slides in from the right and back out again; tabs
            // cross-fade, because they are siblings and a slide would imply an
            // order they do not have.
            AnimatedContent(
                targetState = page to tab,
                transitionSpec = {
                    val pushing = initialState.first == null && targetState.first != null
                    val popping = initialState.first != null && targetState.first == null
                    when {
                        pushing -> (slideInHorizontally(tween(260)) { it / 3 } + fadeIn(tween(200)))
                            .togetherWith(fadeOut(tween(160)))
                        popping -> fadeIn(tween(220)).togetherWith(
                            slideOutHorizontally(tween(240)) { it / 3 } + fadeOut(tween(180))
                        )
                        else -> fadeIn(tween(200)).togetherWith(fadeOut(tween(160)))
                    }
                },
                label = "shell",
            ) { (currentPage, currentTab) ->
                when (currentPage) {
                    Page.HISTORY -> HistoryScreen(chatVm) { page = null }
                    Page.ABOUT -> AboutScreen(
                        version = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                        onOpenUrl = openUrl,
                    )
                    Page.PLAYGROUND -> PlaygroundScreen(core)
                    null -> when (currentTab) {
                        Tab.CHAT -> ChatScreen(
                            vm = chatVm,
                            onOpenRouter = { tab = Tab.ROUTER },
                            onOpenAgent = { tab = Tab.AGENT },
                            cli = cli,
                        )
                        Tab.ROUTER -> RouterScreen(
                            vm = routerVm,
                            onOpenUrl = openUrl,
                            onPickDocument = { onText ->
                                onPicked = onText
                                picker.launch(arrayOf("application/json", "text/plain", "*/*"))
                            },
                            onSaveDocument = { fileName, content, onSaved ->
                                pendingSave = content to onSaved
                                saver.launch(fileName)
                            },
                        )
                        Tab.AGENT -> AgentScreen(core, onOpenUrl = openUrl)
                        Tab.SETTINGS -> SettingsScreen(
                            core = core,
                            darkTheme = darkTheme,
                            onToggleTheme = onToggleTheme,
                            onOpenAbout = { page = Page.ABOUT },
                            onOpenPlayground = { page = Page.PLAYGROUND },
                        )
                    }
                }
            }

            // The bar would otherwise sit behind the keyboard, holding a strip
            // of dead space above the composer for something nobody can see.
            if (WindowInsets.ime.getBottom(LocalDensity.current) == 0) ReyaakNavBar(
                destinations = destinations,
                selectedId = tab.name,
                onSelect = { id ->
                    val entry = Tab.valueOf(id)
                    // Switching tabs drops any pushed page, the way tapping a
                    // tab twice takes you back to its root everywhere else.
                    page = null
                    tab = entry
                    // Health and quota are sampled, not observed, so arriving
                    // on the Router tab re-reads them.
                    if (entry == Tab.ROUTER) routerVm.refresh()
                },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

/** Hand-wired factory: two ViewModels, one dependency each. */
private fun coreViewModelFactory(core: ReyaakCore) = object : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = when {
        modelClass.isAssignableFrom(ChatViewModel::class.java) -> ChatViewModel(core) as T
        modelClass.isAssignableFrom(RouterViewModel::class.java) -> RouterViewModel(core) as T
        else -> throw IllegalArgumentException("unknown ViewModel: ${modelClass.name}")
    }
}
