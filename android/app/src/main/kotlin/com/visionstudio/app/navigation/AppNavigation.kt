package com.visionstudio.app.navigation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Tune
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
import com.visionstudio.core.designsystem.component.StudioBottomBar
import com.visionstudio.feature.gallery.GalleryRoute
import com.visionstudio.feature.imageedit.EditRoute
import com.visionstudio.feature.settings.SettingsRoute
import com.visionstudio.feature.t2i.ImagineRoute
import com.visionstudio.feature.video.VideoRoute

/**
 * The app's single navigation graph: Generate, Edit, Video, Vault and Settings behind a floating
 * bottom bar.
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

    val items = remember {
        Destination.entries.map { BottomBarItem(it.label, it.icon()) }
    }
    val openSettings = remember(navController) { { navController.switchTo(Destination.Settings) } }

    Column(modifier.fillMaxSize()) {
        // Each studio applies the navigation-bar inset itself; the bar below owns it instead.
        Box(Modifier.weight(1f).consumeWindowInsets(WindowInsets.navigationBars)) {
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

        // The keyboard owns the bottom edge while typing; the studios handle it with their own imePadding.
        val keyboardOpen = WindowInsets.ime.getBottom(LocalDensity.current) > 0
        AnimatedVisibility(
            visible = !keyboardOpen,
            enter = fadeIn() + slideInVertically { it / 2 },
            exit = fadeOut() + slideOutVertically { it / 2 },
        ) {
            StudioBottomBar(
                items = items,
                selectedIndex = current.ordinal,
                onSelect = { index -> navController.switchTo(Destination.entries[index]) },
                modifier = Modifier
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}

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
    Destination.Edit -> Icons.Rounded.AutoFixHigh
    Destination.Video -> Icons.Rounded.Movie
    Destination.Vault -> Icons.Rounded.PhotoLibrary
    Destination.Settings -> Icons.Rounded.Tune
}

private const val TAB_FADE_MILLIS = 220
