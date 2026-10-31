package com.visionstudio.app.screenshots

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.visionstudio.app.navigation.Destination
import com.visionstudio.app.navigation.StudioShell
import com.visionstudio.core.designsystem.theme.VisionStudioTheme
import com.visionstudio.core.model.AuthScheme
import com.visionstudio.core.model.EngineSlot
import com.visionstudio.core.model.ProviderProtocol
import com.visionstudio.feature.gallery.GalleryScreen
import com.visionstudio.feature.gallery.GalleryUiState
import com.visionstudio.feature.imageedit.EditScreen
import com.visionstudio.feature.imageedit.EditUiState
import com.visionstudio.feature.settings.ProbeUiState
import com.visionstudio.feature.settings.SettingsScreen
import com.visionstudio.feature.settings.SettingsUiState
import com.visionstudio.feature.settings.SlotDraft
import com.visionstudio.feature.t2i.EngineBadge
import com.visionstudio.feature.t2i.ImagineScreen
import com.visionstudio.feature.t2i.T2iUiState
import com.visionstudio.feature.video.VideoScreen
import com.visionstudio.feature.video.VideoUiState
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Renders every screen inside the app shell to PNG files under `app/build/screenshots`, for
 * reviewing the visual design. Opt-in, since rendering is slow: run with
 * `VISIONSTUDIO_SCREENSHOTS=1 ./gradlew :app:testDebugUnitTest --tests '*ScreenshotTest*'` (debug only:
 * the host activity comes from the debug-only ui-test-manifest).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h890dp-xxhdpi")
class ScreenshotTest {

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Before fun requireOptIn() = assumeTrue("set VISIONSTUDIO_SCREENSHOTS=1 to render", System.getenv("VISIONSTUDIO_SCREENSHOTS") == "1")

    @Test fun settings() = capture("settings", Destination.Settings) {
        SettingsScreen(
            state = SettingsUiState(
                loaded = true,
                drafts = EngineSlot.entries.associateWith { SlotDraft(it) } + (
                    EngineSlot.TEXT_TO_IMAGE to SlotDraft(
                        slot = EngineSlot.TEXT_TO_IMAGE,
                        endpointUrl = "https://api.example.com/v1",
                        modelId = "black-forest-labs/flux-3-image-klein",
                        hasStoredKey = true,
                        persisted = true,
                    )
                    ),
                probes = EngineSlot.entries.associateWith { ProbeUiState.Idle },
            ),
            onIntent = {},
        )
    }

    /** The whole form, MCP with a failed check, on a tall canvas. */
    @Test
    @Config(qualifiers = "w400dp-h1900dp-xxhdpi")
    fun settingsFull() = capture("settings_full", Destination.Settings) {
        SettingsScreen(
            state = SettingsUiState(
                loaded = true,
                drafts = EngineSlot.entries.associateWith { SlotDraft(it) } + (
                    EngineSlot.TEXT_TO_IMAGE to SlotDraft(
                        slot = EngineSlot.TEXT_TO_IMAGE,
                        protocol = ProviderProtocol.MCP,
                        authScheme = AuthScheme.BEARER,
                        endpointUrl = "https://black-forest-labs-flux-3-image-klein.hf.space/gradio_api/mcp/",
                        mcpToolName = "flux_3_image_klein_infer",
                        modelId = "black-forest-labs/flux-3-image-klein",
                        dirty = true,
                    )
                    ),
                probes = EngineSlot.entries.associateWith<EngineSlot, ProbeUiState> { ProbeUiState.Idle } + (
                    EngineSlot.TEXT_TO_IMAGE to ProbeUiState.Failed(
                        headline = "TLS certificate not trusted",
                        details = listOf("The server's certificate chain does not lead to a trusted certificate authority."),
                        latencyMillis = 212L,
                    )
                    ),
            ),
            onIntent = {},
        )
    }

    @Test fun imagine() = capture("imagine", Destination.Generate) {
        ImagineScreen(state = T2iUiState(engine = EngineBadge(configured = true, label = "REST • OAI")), onIntent = {}, onOpenSettings = {})
    }

    @Test fun edit() = capture("edit", Destination.Edit) {
        EditScreen(state = EditUiState(engineConfigured = true, engineLabel = "REST • FAL"), onIntent = {}, onOpenSettings = {})
    }

    @Test fun video() = capture("video", Destination.Video) {
        VideoScreen(state = VideoUiState(engineConfigured = false), onIntent = {}, onOpenSettings = {})
    }

    @Test fun vault() = capture("vault", Destination.Vault) {
        GalleryScreen(state = GalleryUiState(loaded = true), onIntent = {})
    }

    private fun capture(name: String, destination: Destination, screen: @Composable () -> Unit) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            VisionStudioTheme {
                StudioShell(current = destination, onNavigate = {}) { screen() }
            }
        }
        compose.mainClock.advanceTimeBy(SETTLE_MILLIS)
        val root = compose.activity.findViewById<View>(android.R.id.content)
        val full = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(full))
        val scaled = Bitmap.createScaledBitmap(full, root.width / 2, root.height / 2, true)
        File("build/screenshots").mkdirs()
        File("build/screenshots/$name.png").outputStream().use { scaled.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private companion object {
        const val SETTLE_MILLIS = 3_000L
    }
}
