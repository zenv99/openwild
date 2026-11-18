package org.openwild.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SplashLayoutTest {
    @Test
    fun wholeArtworkStaysInsideEveryScreen() {
        val screens = listOf(
            320f to 480f,
            360f to 640f,
            360f to 800f,
            411f to 891f,
            800f to 1280f,
            1280f to 800f,
            2400f to 1080f,
        )
        screens.forEach { (w, h) ->
            val frame = SplashLayout.fit(w, h)
            assertTrue("left $w x $h", frame.left >= -0.01f)
            assertTrue("top $w x $h", frame.top >= -0.01f)
            assertTrue("right $w x $h", frame.left + frame.width <= w + 0.5f)
            assertTrue("bottom $w x $h", frame.top + frame.height <= h + 0.5f)
            assertEquals(SplashLayout.IMAGE_W / SplashLayout.IMAGE_H, frame.width / frame.height, 0.001f)
            val (ox, oy) = SplashLayout.capOrigin(frame, w, h)
            assertTrue("origin x on $w x $h", ox in 0f..1f)
            assertTrue("origin y on $w x $h", oy in 0f..1f)
            val capX = ox * w
            val capY = oy * h
            assertTrue(capX in frame.left..frame.left + frame.width)
            assertTrue(capY in frame.top..frame.top + frame.height)
        }
    }

    @Test
    fun portraitPhoneLetterboxesTheTallArtwork() {
        val frame = SplashLayout.fit(360f, 800f)
        assertEquals(360f, frame.width, 0.01f)
        assertEquals(360f * SplashLayout.IMAGE_H / SplashLayout.IMAGE_W, frame.height, 0.01f)
        assertEquals(0f, frame.left, 0.01f)
        assertTrue(frame.top > 0f)
        assertTrue(frame.top + frame.height < 800f)
    }

    @Test
    fun landscapePhonePillarsTheTallArtwork() {
        val frame = SplashLayout.fit(1280f, 800f)
        assertEquals(800f, frame.height, 0.01f)
        assertEquals(800f * SplashLayout.IMAGE_W / SplashLayout.IMAGE_H, frame.width, 0.01f)
        assertTrue(frame.left > 0f)
        assertEquals(0f, frame.top, 0.01f)
        assertTrue(frame.left + frame.width < 1280f)
    }
}
