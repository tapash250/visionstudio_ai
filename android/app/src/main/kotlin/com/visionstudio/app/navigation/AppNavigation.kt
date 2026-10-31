package com.visionstudio.app.navigation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.visionstudio.core.designsystem.component.BottomBarItem
import com.visionstudio.core.designsystem.component.StudioBackdrop
import com.visionstudio.core.designsystem.component.StudioBottomBar
import com.visionstudio.core.designsystem.component.StudioHeader
import com.visionstudio.feature.gallery.GalleryRoute
import com.visionstudio.feature.imageedit.EditRoute
import com.visionstudio.feature.settings.SettingsRoute
import com.visionstudio.feature.t2i.ImagineRoute
import com.visionstudio.feature.video.VideoRoute

/**
 * The app's single navigation graph: Generate, Edit, Video, Vault and Settings inside the
 * [StudioShell] (header card, backdrop and neon dock).
 *
 * - Each tab keeps its own state while you visit another (a half-written prompt, a painted mask,
 *   the Vault's scroll position), via `saveState`/`restoreState`.
 * - Back from any other tab returns to Generate; back from Generate leaves the app.
 * - Every studio's engine chip opens Settings through the same graph.
 * - [openRequest] lets a launching intent (a "video ready" notification) jump to a tab.
 */
@Composable
fun AppNavigation(
    openRequest: String?,
    onOpenRequestHandled: () -> Unit,
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
) {
    val backStack by navController.currentBackStackEntryAsState()
    val current = Destination.fromRoute(backStack?.destination?.route) ?: Destination.START

    LaunchedEffect(openRequest) {
        Destination.fromOpenRequest(openRequest)?.let(navController::switchTo)
        if (openRequest != null) onOpenRequestHandled()
    }

    val openSettings = remember(navController) { { navController.switchTo(Destination.Settings) } }

    StudioShell(current = current, onNavigate = navController::switchTo, modifier = modifier) {
        NavHost(
            navController = navController,
            startDestination = Destination.START.route,
            enterTransition = { fadeIn(tween(TAB_FADE_MILLIS)) },
            exitTransition = { fadeOut(tween(TAB_FADE_MILLIS)) },
            popEnterTransition = { fadeIn(tween(TAB_FADE_MILLIS)) },
            popExitTransition = { fadeOut(tween(TAB_FADE_MILLIS)) },
        ) {
            composable(Destination.Generate.route) { ImagineRoute(onOpenSettings = openSettings) }
            composable(Destination.Edit.route) { EditRoute(onOpenSettings = openSettings) }
            composable(Destination.Video.route) { VideoRoute(onOpenSettings = openSettings) }
            composable(Destination.Vault.route) { GalleryRoute() }
            composable(Destination.Settings.route) { SettingsRoute() }
        }
    }
}

/**
 * The frame around every screen: the deep-space backdrop, the branded header card on top and the
 * neon dock below, with Generate as the raised centre orb. The profile orb opens Settings.
 * Header and dock step aside while the keyboard is open.
 */
@Composable
internal fun StudioShell(
    current: Destination,
    onNavigate: (Destination) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val items = remember { BAR_ORDER.map { BottomBarItem(it.label, it.icon()) } }
    val keyboardOpen = WindowInsets.ime.getBottom(LocalDensity.current) > 0

    StudioBackdrop(modifier = modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            AnimatedVisibility(
                visible = !keyboardOpen,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                StudioHeader(
                    onProfileClick = { if (current != Destination.Settings) onNavigate(Destination.Settings) },
                    modifier = Modifier
                        .statusBarsPadding()
                        .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
                )
            }
            // Screens apply the system-bar insets themselves; the header and the dock own them here.
            Box(
                Modifier
                    .weight(1f)
                    .then(if (keyboardOpen) Modifier else Modifier.consumeWindowInsets(WindowInsets.statusBars))
                    .consumeWindowInsets(WindowInsets.navigationBars),
            ) {
                content()
            }
            // The keyboard owns the bottom edge while typing; the studios handle it with their own imePadding.
            AnimatedVisibility(
                visible = !keyboardOpen,
                enter = fadeIn() + slideInVertically { it / 2 },
                exit = fadeOut() + slideOutVertically { it / 2 },
            ) {
                StudioBottomBar(
                    items = items,
                    selectedIndex = BAR_ORDER.indexOf(current),
                    onSelect = { index -> onNavigate(BAR_ORDER[index]) },
                    centerIndex = BAR_ORDER.indexOf(Destination.Generate),
                    modifier = Modifier
                        .navigationBarsPadding()
                        .padding(start = 12.dp, end = 12.dp, top = 2.dp, bottom = 8.dp),
                )
            }
        }
    }
}

/** Dock order, left to right; Generate is the centre orb. */
private val BAR_ORDER = listOf(Destination.Vault, Destination.Edit, Destination.Generate, Destination.Video, Destination.Settings)

/**
 * Top-level tab switch: pop back to the start destination (so the back stack never grows with
 * every tap), keep one copy of the target, and restore the state it had when last left.
 */
internal fun NavHostController.switchTo(destination: Destination) {
    navigate(destination.route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

private fun Destination.icon() = when (this) {
    Destination.Generate -> Icons.Rounded.AutoAwesome
    Destination.Edit -> Icons.Outlined.Edit
    Destination.Video -> Icons.Outlined.Movie
    Destination.Vault -> Icons.Outlined.Image
    Destination.Settings -> Icons.Outlined.Settings
}

private const val TAB_FADE_MILLIS = 220
