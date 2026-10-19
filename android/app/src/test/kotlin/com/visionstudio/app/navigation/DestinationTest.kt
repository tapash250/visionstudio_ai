package com.visionstudio.app.navigation

import com.visionstudio.feature.video.VideoDeepLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DestinationTest {

    @Test
    fun `the bar order matches the product spec`() {
        assertEquals(listOf("Generate", "Edit", "Video", "Vault", "Settings"), Destination.entries.map { it.label })
    }

    @Test
    fun `routes are unique and round trip`() {
        val routes = Destination.entries.map { it.route }
        assertEquals(routes.size, routes.toSet().size)
        Destination.entries.forEach { assertEquals(it, Destination.fromRoute(it.route)) }
    }

    @Test
    fun `unknown or missing routes resolve to nothing instead of crashing`() {
        assertNull(Destination.fromRoute(null))
        assertNull(Destination.fromRoute("nope"))
        assertNull(Destination.fromRoute(""))
    }

    @Test
    fun `the app opens on Generate`() {
        assertEquals(Destination.Generate, Destination.START)
    }

    @Test
    fun `a video notification opens the Video tab and unknown requests are ignored`() {
        assertEquals(Destination.Video, Destination.fromOpenRequest(VideoDeepLink.OPEN_VIDEO))
        assertNull(Destination.fromOpenRequest("something-else"))
        assertNull(Destination.fromOpenRequest(null))
    }
}
