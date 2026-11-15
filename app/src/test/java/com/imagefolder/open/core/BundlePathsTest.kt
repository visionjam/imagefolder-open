package com.imagefolder.open.core

import org.junit.Assert.assertEquals
import org.junit.Test

class BundlePathsTest {

    @Test
    fun `sanitize removes separators and control characters`() {
        assertEquals("a_b_c", BundlePaths.sanitizeSegment("a/b\\c"))
        assertEquals("未命名", BundlePaths.sanitizeSegment("   "))
        assertEquals("_..", BundlePaths.sanitizeSegment(".."))
    }

    @Test
    fun `unique file path keeps the extension`() {
        val used = mutableSetOf("photo.png")
        assertEquals("photo (2).png", BundlePaths.uniqueFilePath("photo.png", used))
        assertEquals("photo (3).png", BundlePaths.uniqueFilePath("photo.png", used))
    }

    @Test
    fun `unique file path keeps the directory`() {
        val used = mutableSetOf("dir/a.txt")
        assertEquals("dir/a (2).txt", BundlePaths.uniqueFilePath("dir/a.txt", used))
    }

    @Test
    fun `unique directory path adds a suffix`() {
        val used = mutableSetOf("Photos/")
        assertEquals("Photos (2)/", BundlePaths.uniqueDirectoryPath("Photos/", used))
    }
}
