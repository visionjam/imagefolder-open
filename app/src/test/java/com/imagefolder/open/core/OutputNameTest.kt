package com.imagefolder.open.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OutputNameTest {

    @Test
    fun blankNameFallsBackToTimestampStem() {
        assertEquals("Tank_20261116_233000.png", OutputName.build("", "png", "Tank_20261116_233000"))
        assertEquals("Tank_x.jpg", OutputName.build("   ", "jpg", "Tank_x"))
    }

    @Test
    fun keepsChineseNames() {
        assertEquals("我的图片.png", OutputName.build("我的图片", "png", "Tank_x"))
        assertEquals("春游 2026.webp", OutputName.build("春游 2026", "webp", "Tank_x"))
    }

    @Test
    fun replacesIllegalCharacters() {
        assertEquals("a_b_c__.png", OutputName.build("a/b:c*?", "png", "Tank_x"))
        assertEquals("tab_here.png", OutputName.build("tab\there", "png", "Tank_x"))
        assertEquals("new_line.png", OutputName.build("new\nline", "png", "Tank_x"))
    }

    @Test
    fun carrierExtensionWins() {
        assertEquals("photo.png", OutputName.build("photo.jpg", "png", "Tank_x"))
        assertEquals("photo.jpeg", OutputName.build("photo.PNG", "jpeg", "Tank_x"))
    }

    @Test
    fun keepsDotsThatAreNotImageExtensions() {
        assertEquals("v1.2 photo.png", OutputName.build("v1.2 photo", "png", "Tank_x"))
    }

    @Test
    fun stripsLeadingAndTrailingDots() {
        assertEquals("fb.png", OutputName.build("...", "png", "fb"))
        assertEquals("name.png", OutputName.build(".name.", "png", "fb"))
    }

    @Test
    fun capsStemLength() {
        val long = "a".repeat(200)
        val built = OutputName.build(long, "png", "fb")
        assertEquals(OutputName.MAX_STEM + ".png".length, built.length)
        assertTrue(built.endsWith(".png"))
    }

    @Test
    fun detectsKnownImageExtensions() {
        assertTrue(OutputName.hasImageExtension("a.PNG"))
        assertTrue(OutputName.hasImageExtension("a.jpeg"))
        assertFalse(OutputName.hasImageExtension("a.zip"))
        assertFalse(OutputName.hasImageExtension("noext"))
    }
}
