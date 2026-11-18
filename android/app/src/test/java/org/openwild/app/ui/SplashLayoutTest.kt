package org.openwild.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SplashLayoutTest {
    @Test
    fun portraitShowsTheWholeLandscapePhoto() {
        val frame = SplashLayout.fit(360f, 800f)
        assertEquals(360f, frame.width, 0.01f)
        assertEquals(360f * SplashLayout.IMAGE_H / SplashLayout.IMAGE_W, frame.height, 0.01f)
        assertEquals(0f, frame.left, 0.01f)
        assertTrue(frame.top > 100f)
        assertTrue(frame.top + frame.height < 800f)
        assertEquals(SplashLayout.IMAGE_W / SplashLayout.IMAGE_H, frame.width / frame.height, 0.001f)
    }

    @Test
    fun landscapeShowsTheWholePhotoBetweenSideBars() {
        val frame = SplashLayout.fit(1280f, 800f)
        assertEquals(800f, frame.height, 0.01f)
        assertEquals(800f * SplashLayout.IMAGE_W / SplashLayout.IMAGE_H, frame.width, 0.01f)
        assertTrue(frame.left > 0f)
        assertEquals(0f, frame.top, 0.01f)
        assertTrue(frame.left + frame.width < 1280f)
    }

    @Test
    fun capStaysInsideThePhotoOnSmallAndLargeScreens() {
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
            val (ox, oy) = SplashLayout.capOrigin(frame, w, h)
            assertTrue("origin x on $w x $h", ox in 0f..1f)
            assertTrue("origin y on $w x $h", oy in 0f..1f)
            val capX = ox * w
            val capY = oy * h
            assertTrue(capX in frame.left..frame.left + frame.width)
            assertTrue(capY in frame.top..frame.top + frame.height)
        }
    }
}
