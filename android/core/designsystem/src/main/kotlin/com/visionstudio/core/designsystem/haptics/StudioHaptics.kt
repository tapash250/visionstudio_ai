package com.visionstudio.core.designsystem.haptics

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView

/** Semantic haptic vocabulary used across the studios. */
enum class HapticPattern {
    /** Lightweight button tap. */
    Click,

    /** Detent on sliders, chips and steppers. */
    Tick,

    /** Begin of a brush stroke or drag. */
    GestureStart,

    /** End of a brush stroke or drag. */
    GestureEnd,

    /** Generation completed, settings saved, connection test passed. */
    Confirm,

    /** Validation failure or connection test failed. */
    Reject,

    LongPress,

    /** No feedback. */
    None,
}

/**
 * Maps [HapticPattern] onto the best [HapticFeedbackConstants] available on the running API
 * level. Uses View-based feedback, which honours the user's system haptic setting and needs
 * no VIBRATE permission.
 */
@Stable
class StudioHaptics(private val view: View) {

    fun perform(pattern: HapticPattern) {
        val constant = pattern.toFeedbackConstant() ?: return
        view.performHapticFeedback(constant)
    }

    fun click() = perform(HapticPattern.Click)
    fun tick() = perform(HapticPattern.Tick)
    fun confirm() = perform(HapticPattern.Confirm)
    fun reject() = perform(HapticPattern.Reject)
    fun gestureStart() = perform(HapticPattern.GestureStart)
    fun gestureEnd() = perform(HapticPattern.GestureEnd)

    private fun HapticPattern.toFeedbackConstant(): Int? {
        val sdk = Build.VERSION.SDK_INT
        return when (this) {
            HapticPattern.Click -> HapticFeedbackConstants.VIRTUAL_KEY
            HapticPattern.Tick -> when {
                sdk >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> HapticFeedbackConstants.SEGMENT_FREQUENT_TICK
                sdk >= Build.VERSION_CODES.O_MR1 -> HapticFeedbackConstants.TEXT_HANDLE_MOVE
                else -> HapticFeedbackConstants.CLOCK_TICK
            }
            HapticPattern.GestureStart ->
                if (sdk >= Build.VERSION_CODES.R) HapticFeedbackConstants.GESTURE_START else HapticFeedbackConstants.VIRTUAL_KEY
            HapticPattern.GestureEnd ->
                if (sdk >= Build.VERSION_CODES.R) HapticFeedbackConstants.GESTURE_END else HapticFeedbackConstants.CLOCK_TICK
            HapticPattern.Confirm ->
                if (sdk >= Build.VERSION_CODES.R) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY
            HapticPattern.Reject ->
                if (sdk >= Build.VERSION_CODES.R) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS
            HapticPattern.LongPress -> HapticFeedbackConstants.LONG_PRESS
            HapticPattern.None -> null
        }
    }
}

@Composable
fun rememberStudioHaptics(): StudioHaptics {
    val view = LocalView.current
    return remember(view) { StudioHaptics(view) }
}
