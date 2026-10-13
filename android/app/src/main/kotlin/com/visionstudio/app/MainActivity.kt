package com.visionstudio.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.visionstudio.core.designsystem.component.PrismSegmentedControl
import com.visionstudio.core.designsystem.theme.VisionStudioTheme
import com.visionstudio.feature.imageedit.EditRoute
import com.visionstudio.feature.settings.SettingsRoute
import com.visionstudio.feature.t2i.ImagineRoute
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        setContent {
            VisionStudioTheme {
                InterimShell()
            }
        }
    }
}

private enum class Studio(val label: String) {
    Imagine("Imagine"),
    Edit("Edit"),
}

/**
 * Temporary host: Imagine and Edit switch from a glass bar, and the Engine Room opens over the
 * top. Ticket 4.3 replaces this with the real bottom-navigation NavHost (Generate, Edit, Video,
 * Vault, Settings).
 */
@Composable
private fun InterimShell() {
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var studio by rememberSaveable { mutableStateOf(Studio.Imagine) }
    BackHandler(enabled = settingsOpen) { settingsOpen = false }

    AnimatedContent(
        targetState = settingsOpen,
        transitionSpec = {
            val zoom = spring<Float>(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMediumLow)
            (fadeIn() + scaleIn(zoom, initialScale = if (targetState) 1.06f else 0.94f)) togetherWith
                (fadeOut() + scaleOut(zoom, targetScale = if (targetState) 0.94f else 1.06f))
        },
        label = "shell",
    ) { showSettings ->
        if (showSettings) {
            SettingsRoute()
        } else {
            Column(Modifier.fillMaxSize()) {
                // Each studio applies the navigation-bar inset itself; the bar below owns it here instead.
                Box(Modifier.weight(1f).consumeWindowInsets(WindowInsets.navigationBars)) {
                    AnimatedContent(
                        targetState = studio,
                        transitionSpec = { fadeIn() togetherWith fadeOut() },
                        label = "studio",
                    ) { current ->
                        when (current) {
                            Studio.Imagine -> ImagineRoute(onOpenSettings = { settingsOpen = true })
                            Studio.Edit -> EditRoute(onOpenSettings = { settingsOpen = true })
                        }
                    }
                }
                // The keyboard owns the bottom edge while typing; screens handle it with their own imePadding.
                val keyboardOpen = WindowInsets.ime.getBottom(LocalDensity.current) > 0
                AnimatedVisibility(visible = !keyboardOpen) {
                    PrismSegmentedControl(
                        options = Studio.entries,
                        selected = studio,
                        onSelect = { studio = it },
                        label = { it.label },
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(horizontal = 40.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }
}
