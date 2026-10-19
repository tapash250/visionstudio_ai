package com.visionstudio.baselineprofile

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Records the app's critical user journeys so ART can precompile them at install time:
 * cold start into Generate, every bottom-bar tab, the Vault grid scroll and the Edit canvas.
 *
 * Run on a device or emulator with: `./gradlew :app:generateReleaseBaselineProfile`.
 * The output is written to `app/src/release/generated/baselineProfiles/`.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generate() = rule.collect(
        packageName = TARGET_PACKAGE,
        includeInStartupProfile = true,
    ) {
        pressHome()
        startActivityAndWait()

        // Generate: the start destination, drawn with the aurora background and prompt dock.
        waitForTab(Destination.Generate)

        // Edit: the inpainting canvas and its brush controls.
        openTab(Destination.Edit)
        drawStrokeOnCanvas()

        // Video: motion picker and renders strip.
        openTab(Destination.Video)

        // Vault: staggered grid scrolling exercises item composition and image loading.
        openTab(Destination.Vault)
        scrollVault()

        // Settings: engine configuration forms.
        openTab(Destination.Settings)

        // Return to the start tab to cover save/restore of tab state.
        openTab(Destination.Generate)
    }

    private fun MacrobenchmarkScope.waitForTab(destination: Destination) {
        device.wait(Until.hasObject(By.text(destination.label)), UI_TIMEOUT_MS)
    }

    private fun MacrobenchmarkScope.openTab(destination: Destination) {
        waitForTab(destination)
        device.findObject(By.text(destination.label))?.click()
        device.waitForIdle()
    }

    private fun MacrobenchmarkScope.drawStrokeOnCanvas() {
        // The canvas only accepts strokes once an image is loaded; with none, the swipe is a
        // harmless no-op that still exercises the empty-state composition path.
        val width = device.displayWidth
        val height = device.displayHeight
        device.swipe(width / 4, height / 2, width * 3 / 4, height / 2, STROKE_STEPS)
        device.waitForIdle()
    }

    private fun MacrobenchmarkScope.scrollVault() {
        val content = device.findObject(By.scrollable(true)) ?: return
        content.setGestureMargin(device.displayWidth / GESTURE_MARGIN_DIVISOR)
        content.fling(Direction.DOWN)
        device.waitForIdle()
        content.fling(Direction.UP)
        device.waitForIdle()
    }

    private enum class Destination(val label: String) {
        Generate("Generate"),
        Edit("Edit"),
        Video("Video"),
        Vault("Vault"),
        Settings("Settings"),
    }

    private companion object {
        const val TARGET_PACKAGE = "com.visionstudio.ai"
        const val UI_TIMEOUT_MS = 5_000L
        const val STROKE_STEPS = 24
        const val GESTURE_MARGIN_DIVISOR = 10
    }
}
