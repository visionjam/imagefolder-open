package com.imagefolder.open.core

import java.io.File

/**
 * RAR extraction backend. Kept behind an interface so the JVM unit tests can exercise
 * [ArchiveExtractor] without loading the Android-only libarchive native library.
 */
internal interface RarExtractor {
    fun extract(
        data: ByteArray,
        fileName: String?,
        tempDir: File?,
        limits: ArchiveExtractor.ExtractionLimits,
    ): List<ArchiveEntry>

    fun extractGroup(
        parts: List<ArchivePart>,
        tempDir: File?,
        limits: ArchiveExtractor.ExtractionLimits,
    ): List<ArchiveEntry>
}
