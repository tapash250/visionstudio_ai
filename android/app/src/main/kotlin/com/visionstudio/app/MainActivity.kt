package com.visionstudio.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.visionstudio.app.navigation.AppNavigation
import com.visionstudio.core.designsystem.theme.VisionStudioTheme
import com.visionstudio.feature.video.VideoDeepLink
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    /** Where a launching intent (such as a "video ready" notification) wants to go; consumed by the navigation host. */
    private val openRequest = mutableStateOf<String?>(null)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openRequest.value = intent.getStringExtra(VideoDeepLink.EXTRA_OPEN)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        // Only a fresh launch carries a request; a recreated activity must not replay an old one.
        if (savedInstanceState == null) openRequest.value = intent?.getStringExtra(VideoDeepLink.EXTRA_OPEN)
        setContent {
            VisionStudioTheme {
                AppNavigation(
                    openRequest = openRequest.value,
                    onOpenRequestHandled = { openRequest.value = null },
                )
            }
        }
    }
}
