package com.agentdeck

import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.ui.platform.ComposeView
import com.agentdeck.ui.OverlayHost
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.agentdeck.ui.home.HomeScreen
import com.agentdeck.ui.onboarding.RiskDisclaimerScreen
import com.agentdeck.ui.settings.AboutScreen
import com.agentdeck.ui.settings.AuditLogScreen
import com.agentdeck.ui.settings.SettingsScreen
import com.agentdeck.ui.theme.AgentDeckTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * The single Activity hosting the whole app.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    companion object {
        /**
         * The card a debug deep link asked to open.
         *
         * It is consumed by the navigation graph on the next composition.
         */
        var deepLinkCardId: String? = null

        /**
         * Set when a debug deep link carries `?fill=1`.
         *
         * The session then writes the system prompt into the page by itself
         * once the page is up. That is the only way to exercise the input
         * path on a device whose touch injection cannot reach the app - on a
         * virtual display, for instance - and it is a diagnostic, not a
         * feature: the flag is only ever read from a debug build.
         */
        var deepLinkAutoFill: Boolean = false
    }

    /**
     * The window container the session page is parked on.
     *
     * The page is a `WebView`, and it is deliberately **not** mounted
     * through Compose: a WebView measured by Compose's `AndroidViewHolder`
     * records a layout viewport of zero height, and the engine never
     * recomputes it, so every `100vh` on every site resolves to `0px` and
     * the page collapses into a single column of un-styled elements. The
     * measurement is written up in `WebViewShelf`.
     *
     * The layout is therefore:
     *
     *   root (FrameLayout)
     *     ├── child 0: the session's WebView, added at runtime
     *     └── child 1: the Compose UI, transparent where it draws nothing
     */
    private lateinit var windowRoot: FrameLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        windowRoot = FrameLayout(this)
        // The page is inserted at index 0, so it lands underneath the
        // overlay; this only guards against a stale index from a previous
        // z-order change.
        windowRoot.clipChildren = true

        val compose = ComposeView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            setContent {
                AgentDeckTheme {
                    val appViewModel: AppViewModel = viewModel()
                    val state by appViewModel.state.collectAsStateWithLifecycle()

                    if (state.riskAccepted) {
                        AppNavigation(state = state, appViewModel = appViewModel)
                    } else {
                        RiskDisclaimerScreen(
                            onAcknowledge = { appViewModel.acceptRisk() },
                        )
                    }
                }
            }
        }

        // The Compose UI is wrapped, so a touch that lands on nothing can be
        // handed to the page instead of being swallowed by a full screen
        // layer. See `OverlayHost`.
        val overlay = OverlayHost(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            addView(compose)
        }
        windowRoot.addView(overlay)

        setContentView(windowRoot)
        com.agentdeck.web.WebViewShelf.attach(windowRoot)

        // A debug build can be sent straight into a session with
        // `am start -a android.intent.action.VIEW -d "agentdeck://session/<id>"`,
        // which is what the verification scripts use to inspect a site
        // without hunting for the card on the grid.
        if (BuildConfig.DEBUG) {
            handleDeepLink(intent)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (BuildConfig.DEBUG) {
            handleDeepLink(intent)
        }
    }

    override fun onDestroy() {
        // The shelf holds a static reference to a view; releasing it here
        // keeps a finished activity from being retained by it.
        com.agentdeck.web.WebViewShelf.detach(windowRoot)
        super.onDestroy()
    }

    /** Opens the session of the card named by a debug deep link. */
    private fun handleDeepLink(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme != "agentdeck") return
        // The link looks like `agentdeck://session/<cardId>`: the card id is
        // the last path segment, the host only names the target screen.
        val cardId = data.lastPathSegment?.takeIf { it.isNotBlank() } ?: return
        deepLinkCardId = cardId
        deepLinkAutoFill = data.getBooleanQueryParameter("fill", false)
    }}

/**
 * The navigation graph of the app.
 */
@androidx.compose.runtime.Composable
private fun AppNavigation(
    state: AppUiState,
    appViewModel: AppViewModel,
) {
    val navController = rememberNavController()

    // A debug deep link jumps straight into a session.
    val deepLinkCardId = MainActivity.deepLinkCardId
    LaunchedEffect(deepLinkCardId) {
        if (deepLinkCardId != null) {
            MainActivity.deepLinkCardId = null
            navController.navigate(Routes.session(deepLinkCardId))
        }
    }

    NavHost(navController = navController, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onOpenCard = { cardId -> navController.navigate(Routes.session(cardId)) },
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                state = state.settingsState,
                onBack = { navController.popBackStack() },
                onSandboxLevelChange = appViewModel::setSandboxLevel,
                onL2EnabledChange = appViewModel::setL2Enabled,
                onToolEnabledChange = appViewModel::setToolEnabled,
                onDomainAllowlistChange = appViewModel::setDomainAllowlist,
                onCustomPromptChange = appViewModel::setCustomPrompt,
                onSaveAdapter = appViewModel::saveAdapter,
                onExportAudit = appViewModel::exportAudit,
                onClearAudit = appViewModel::clearAudit,
                onOpenShizuku = appViewModel::openShizuku,
                onOpenRiskStatement = appViewModel::showRiskStatement,
                onOpenAbout = { navController.navigate(Routes.ABOUT) },
                // Calibration needs a live page: the user taps the target
                // element on the real site. The session screen hosts the
                // calibration overlay, so the settings entry sends the user
                // to the home grid where they can pick a site.
                onOpenAdapterCalibration = {
                    appViewModel.showMessage("请在会话页长按悬浮球 → 站点适配器 进行校准")
                    navController.popBackStack()
                },
            )
        }
        composable(Routes.AUDIT) {
            AuditLogScreen(
                entries = state.auditEntries,
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.ABOUT) {
            AboutScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.SESSION_PATTERN) { entry ->
            val cardId = entry.arguments?.getString("cardId").orEmpty()
            com.agentdeck.ui.session.SessionRoute(
                cardId = cardId,
                onBack = { navController.popBackStack() },
                onOpenAudit = { navController.navigate(Routes.AUDIT) },
            )
        }
    }
}

/**
 * Navigation routes.
 */
object Routes {
    const val HOME = "home"
    const val SETTINGS = "settings"
    const val AUDIT = "audit"
    const val ABOUT = "about"
    const val SESSION_PATTERN = "session/{cardId}"

    fun session(cardId: String) = "session/$cardId"
}
