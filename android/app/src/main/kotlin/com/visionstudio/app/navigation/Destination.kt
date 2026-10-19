package com.visionstudio.app.navigation

import androidx.compose.runtime.Immutable
import com.visionstudio.feature.video.VideoDeepLink

/**
 * The five top-level places in the app, in bottom-bar order. [route] is the navigation key and is
 * stable across releases, since it is also what saved navigation state refers to.
 */
@Immutable
enum class Destination(val route: String, val label: String) {
    Generate("generate", "Generate"),
    Edit("edit", "Edit"),
    Video("video", "Video"),
    Vault("vault", "Vault"),
    Settings("settings", "Settings"),
    ;

    companion object {
        val START: Destination = Generate

        fun fromRoute(route: String?): Destination? = entries.firstOrNull { it.route == route }

        /** Where a launching intent extra (for example a "video ready" notification) wants to go; null if it asks for nothing known. */
        fun fromOpenRequest(extra: String?): Destination? = when (extra) {
            VideoDeepLink.OPEN_VIDEO -> Video
            else -> null
        }
    }
}
