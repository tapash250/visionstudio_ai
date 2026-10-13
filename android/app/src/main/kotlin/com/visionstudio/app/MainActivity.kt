package com.visionstudio.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.visionstudio.core.designsystem.theme.VisionStudioTheme
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

/** Imagine is home; the Engine Room opens over it. Ticket 4.3 replaces this with the bottom-bar NavHost. */
@Composable
private fun InterimShell() {
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
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
        if (showSettings) SettingsRoute() else ImagineRoute(onOpenSettings = { settingsOpen = true })
    }
}
