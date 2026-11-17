package com.imagefolder.open.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets

class MimeTypesTest {

    @Test
    fun extensionOfIgnoresPathsAndDotFiles() {
        assertEquals("txt", MimeTypes.extensionOf("notes.txt"))
        assertEquals("txt", MimeTypes.extensionOf("payload/sub/notes.TXT"))
        assertEquals("gz", MimeTypes.extensionOf("archive.tar.gz"))
        assertEquals("", MimeTypes.extensionOf("README"))
        assertEquals("", MimeTypes.extensionOf(".gitignore"))
        assertEquals("", MimeTypes.extensionOf("trailing."))
    }

    @Test
    fun fallbackCoversNamesThePlatformTableMisses() {
        assertEquals("text/markdown", MimeTypes.fallbackForFileName("notes.md"))
        assertEquals("text/plain", MimeTypes.fallbackForFileName("build.gradle"))
        assertEquals("text/plain", MimeTypes.fallbackForFileName("settings.yaml"))
        assertEquals("text/plain", MimeTypes.fallbackForFileName("Main.kt"))
        assertEquals("text/plain", MimeTypes.fallbackForFileName("运行日志.log"))
    }

    @Test
    fun fallbackLeavesKnownAndUnknownExtensionsAlone() {
        // The platform table owns png/json/txt; the fallback must not shadow it.
        assertNull(MimeTypes.fallbackForFileName("cover.png"))
        assertNull(MimeTypes.fallbackForFileName("README"))
        assertNull(MimeTypes.fallbackForFileName("data.bin"))
    }

    @Test
    fun textExtensionsCoverThePreviewList() {
        assertTrue(MimeTypes.isTextExtension("txt"))
        assertTrue(MimeTypes.isTextExtension("md"))
        assertTrue(MimeTypes.isTextExtension("json"))
        assertTrue(MimeTypes.isTextExtension("kt"))
        assertFalse(MimeTypes.isTextExtension("png"))
        assertFalse(MimeTypes.isTextExtension(""))
    }

    @Test
    fun looksLikeTextAcceptsUtf8IncludingChinese() {
        assertTrue(MimeTypes.looksLikeText("hello".toByteArray(StandardCharsets.UTF_8)))
        assertTrue(MimeTypes.looksLikeText("中文内容\n第二行".toByteArray(StandardCharsets.UTF_8)))
        assertTrue(MimeTypes.looksLikeText(ByteArray(0)))
    }

    @Test
    fun looksLikeTextRejectsBinaryAndInvalidUtf8() {
        assertFalse(MimeTypes.looksLikeText(byteArrayOf(0x50, 0x4B, 0x00, 0x01)))
        assertFalse(MimeTypes.looksLikeText(byteArrayOf(0xC3.toByte(), 0x28)))
    }

    @Test
    fun looksLikeTextDoesNotSplitAMultiByteCharacterAtTheSampleEdge() {
        // 4095 ASCII bytes put a three-byte character across the 4096-byte edge.
        val bytes = ByteArray(MimeTypes.TEXT_SNIFF_BYTES - 1) { 'a'.code.toByte() } +
            "中".toByteArray(StandardCharsets.UTF_8)
        assertTrue(MimeTypes.looksLikeText(bytes))
    }
}
