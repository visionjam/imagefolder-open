package com.imagefolder.open.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ImageFilesTest {

    @Test
    fun `collects images recursively and ignores other files`() {
        val root = Files.createTempDirectory("image-files-test").toFile()
        try {
            val sub = File(root, "sub").apply { mkdirs() }
            File(root, "a.png").writeText("x")
            File(root, "b.txt").writeText("x")
            File(sub, "c.JPG").writeText("x")
            File(sub, "d.webp").writeText("x")

            val names = ImageFiles.collect(root).map { it.name }
            assertEquals(listOf("a.png", "c.JPG", "d.webp"), names)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `returns empty when cancelled before walking children`() {
        val root = Files.createTempDirectory("image-files-cancel").toFile()
        try {
            File(root, "a.png").writeText("x")
            var calls = 0
            val result = ImageFiles.collect(root) { calls++ < 1 }
            assertTrue(result.isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }
}
