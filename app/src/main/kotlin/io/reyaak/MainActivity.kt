package io.reyaak

import android.Manifest
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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.reyaak.core.ReyaakCore
import io.reyaak.runtime.AgentService
import io.reyaak.ui.AboutScreen
import io.reyaak.ui.AgentScreen
import io.reyaak.ui.ChatScreen
import io.reyaak.ui.Confirmation
import io.reyaak.ui.ConfirmSheet
import io.reyaak.ui.GlassDivider
import io.reyaak.ui.HistoryScreen
import io.reyaak.ui.LocalBrand
import io.reyaak.ui.LocalFeedback
import io.reyaak.ui.LocalTokens
import io.reyaak.ui.NavBarSpace
import io.reyaak.ui.NavDestination
import io.reyaak.ui.Ph
import io.reyaak.ui.PhIcon
import io.reyaak.ui.PlaygroundScreen
import io.reyaak.ui.ReyaakBrand
import io.reyaak.ui.ReyaakFeedback
import io.reyaak.ui.ReyaakFonts
import io.reyaak.ui.ReyaakNavBar
import io.reyaak.ui.ReyaakTheme
import io.reyaak.ui.RkToast
import io.reyaak.ui.RouterScreen
import io.reyaak.ui.SettingsScreen
import io.reyaak.ui.needsNotificationPermission
import io.reyaak.ui.notificationsEnabled
import io.reyaak.ui.reyaakBackground
import io.reyaak.ui.rk
import io.reyaak.vm.ChatViewModel
import io.reyaak.vm.RouterViewModel

private enum class Tab(val label: String, val glyph: String) {
    CHAT("Chat", Ph.CHAT),
    ROUTER("Router", Ph.SHUFFLE),
    AGENT("Agent", Ph.PULSE),
    SETTINGS("Settings", Ph.GEAR),
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

            // The families :ui asks the host for. Loaded once here rather than
            // per screen, because a FontFamily that is rebuilt on recomposition
            // re-resolves every glyph.
            val fonts = remember {
                ReyaakFonts(
                    sans = FontFamily(
                        Font(R.font.manrope_regular, FontWeight.Normal),
                        Font(R.font.manrope_medium, FontWeight.Medium),
                        Font(R.font.manrope_semibold, FontWeight.SemiBold),
                        Font(R.font.manrope_bold, FontWeight.Bold),
                    ),
                    mono = FontFamily.Monospace,
                    icons = FontFamily(Font(R.font.phosphor)),
                    iconsFill = FontFamily(Font(R.font.phosphor_fill)),
                )
            }
            val brand = ReyaakBrand(icon = painterResource(R.drawable.reyaak_icon))

            ReyaakTheme(darkTheme = dark, fonts = fonts) {
                CompositionLocalProvider(LocalBrand provides brand) {
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
    val tokens = LocalTokens.current
    var tab by rememberSaveable { mutableStateOf(Tab.CHAT) }
    var page by rememberSaveable { mutableStateOf<Page?>(null) }
    var cli by rememberSaveable { mutableStateOf(false) }

    val factory = remember(core) { coreViewModelFactory(core) }
    val chatVm: ChatViewModel = viewModel(factory = factory)
    val routerVm: RouterViewModel = viewModel(factory = factory)
    val conversations by chatVm.history.collectAsStateWithLifecycle()

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

    // Starting the agent needs a permission on API 33+, and it is now reachable
    // from two places, so the launcher lives here rather than in either of them.
    val askNotifications = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { AgentService.start(context) }
    val startAgent: () -> Unit = {
        if (needsNotificationPermission(notificationsEnabled(context))) {
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            AgentService.start(context)
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

    // The app's one feedback channel, hosted here because it draws over every
    // screen and has to outlive the one that raised it.
    var toast by remember { mutableStateOf<Toast?>(null) }
    var confirmation by remember { mutableStateOf<Confirmation?>(null) }
    val feedback = remember {
        ReyaakFeedback(
            toast = { text, isError, warnings -> toast = Toast(text, isError, warnings) },
            confirm = { confirmation = it },
        )
    }
    LaunchedEffect(toast) {
        // Long enough to read a sentence and a warning or two, short enough that
        // it is gone before it becomes furniture.
        if (toast != null) {
            kotlinx.coroutines.delay(4200)
            toast = null
        }
    }

    val destinations = remember {
        Tab.entries.map { NavDestination(it.name, it.label, it.glyph) }
    }
    val systemNav = with(LocalDensity.current) {
        WindowInsets.navigationBars.getBottom(this).toDp()
    }
    // Every scrolling screen reserves the floating bar itself, since the bar
    // draws over content rather than displacing it.
    val bottomPadding = NavBarSpace + systemNav

    CompositionLocalProvider(LocalFeedback provides feedback) {
        Box(Modifier.fillMaxSize().reyaakBackground(tokens)) {
            Box(Modifier.fillMaxSize().statusBarsPadding()) {
                // A pushed page slides in from the right and back out again; tabs
                // cross-fade, because they are siblings and a slide would imply an
                // order they do not have.
                AnimatedContent(
                    targetState = tab,
                    transitionSpec = { fadeIn(tween(200)).togetherWith(fadeOut(tween(160))) },
                    label = "tabs",
                ) { currentTab ->
                    when (currentTab) {
                        Tab.CHAT -> ChatScreen(
                            vm = chatVm,
                            onOpenRouter = { tab = Tab.ROUTER; routerVm.refresh() },
                            onOpenAgent = { tab = Tab.AGENT },
                            onStartAgent = startAgent,
                            onOpenHistory = { page = Page.HISTORY },
                            cli = cli,
                            onToggleCli = { cli = !cli },
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
                            bottomPadding = bottomPadding,
                        )
                        Tab.AGENT -> AgentScreen(
                            core = core,
                            onOpenUrl = openUrl,
                            onStartAgent = startAgent,
                            bottomPadding = bottomPadding,
                        )
                        Tab.SETTINGS -> SettingsScreen(
                            core = core,
                            darkTheme = darkTheme,
                            onToggleTheme = onToggleTheme,
                            onOpenAbout = { page = Page.ABOUT },
                            onOpenPlayground = { page = Page.PLAYGROUND },
                            onOpenHistory = { page = Page.HISTORY },
                            version = BuildConfig.VERSION_NAME,
                            conversationCount = conversations.size,
                            bottomPadding = bottomPadding,
                        )
                    }
                }
            }

            // The floating bar draws over the content and is hidden while the
            // keyboard is up, so it never holds a strip of dead space above the
            // composer for something nobody can see.
            val keyboardOpen = WindowInsets.ime.getBottom(LocalDensity.current) > 0
            if (page == null && !keyboardOpen) ReyaakNavBar(
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

            AnimatedVisibility(
                visible = page != null,
                enter = slideInHorizontally(tween(260)) { it / 3 } + fadeIn(tween(200)),
                exit = slideOutHorizontally(tween(240)) { it / 3 } + fadeOut(tween(180)),
            ) {
                PushedPage(
                    title = page?.title.orEmpty(),
                    onClose = { page = null },
                ) {
                    when (page) {
                        Page.HISTORY -> HistoryScreen(chatVm) { page = null; tab = Tab.CHAT }
                        Page.ABOUT -> AboutScreen(
                            version = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                            onOpenUrl = openUrl,
                        )
                        Page.PLAYGROUND -> PlaygroundScreen(core)
                        null -> Unit
                    }
                }
            }

            AnimatedVisibility(
                visible = toast != null,
                enter = slideInVertically(tween(260)) { it / 3 } + fadeIn(tween(260)),
                exit = fadeOut(tween(180)),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(start = 16.dp, end = 16.dp, bottom = bottomPadding),
            ) {
                toast?.let { current ->
                    RkToast(
                        text = current.text,
                        warnings = current.warnings,
                        isError = current.isError,
                        onDismiss = { toast = null },
                    )
                }
            }

            confirmation?.let { current ->
                ConfirmSheet(current) { confirmation = null }
            }
        }
    }
}

/** One transient message. Kept as a value so a repeat of the same text re-shows. */
private data class Toast(
    val text: String,
    val isError: Boolean,
    val warnings: List<String>,
)

/**
 * The chrome for a page pushed over a tab.
 *
 * There is no shared app bar any more: three of the four tabs open with their
 * own large title and no actions, so a bar across all of them was a mostly empty
 * strip. A pushed page still needs one, because back has to live somewhere.
 */
@Composable
private fun PushedPage(
    title: String,
    onClose: () -> Unit,
    content: @Composable () -> Unit,
) {
    val t = LocalTokens.current
    Column(Modifier.fillMaxSize().background(t.bg)) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(t.g1)
                .statusBarsPadding()
                .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val shape = RoundedCornerShape(13.dp)
            Box(
                Modifier
                    .size(36.dp)
                    .clip(shape)
                    .background(t.g1)
                    .border(1.dp, t.line, shape)
                    .clickable(onClick = onClose),
                contentAlignment = Alignment.Center,
            ) { PhIcon(Ph.ARROW_LEFT, 17.0, t.ink) }
            Spacer(Modifier.width(10.dp))
            Text(title, color = t.ink, style = rk(600, 16.0, 1.0, tracking = (-0.01).em))
        }
        GlassDivider()
        Box(Modifier.fillMaxSize()) { content() }
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
