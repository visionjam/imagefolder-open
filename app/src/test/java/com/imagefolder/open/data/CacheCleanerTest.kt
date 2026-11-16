package com.imagefolder.open.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CacheCleanerTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun file(path: String, size: Int, lastModified: Long): File {
        val file = File(temporaryFolder.root, path)
        file.parentFile?.mkdirs()
        file.writeBytes(ByteArray(size) { 1 })
        file.setLastModified(lastModified)
        return file
    }

    @Test
    fun onlyCountsFilesTheAppOwns() {
        val now = System.currentTimeMillis()
        file("shared-in/a.bin", 10, now)
        file("shared/b.bin", 20, now)
        file("imagefolder-123.zip", 30, now)
        file("other/c.bin", 40, now)

        assertEquals(60L, CacheCleaner.totalBytes(temporaryFolder.root))
    }

    @Test
    fun dropsStaleFilesAndKeepsFreshOnes() {
        val now = System.currentTimeMillis()
        val old = file("shared-in/old.bin", 10, now - CacheCleaner.MAX_AGE_MILLIS - 1000)
        val fresh = file("shared-in/fresh.bin", 20, now)
        val foreign = file("other/keep.bin", 30, now)

        val freed = CacheCleaner.prune(temporaryFolder.root, now)

        assertEquals(10L, freed)
        assertFalse(old.exists())
        assertTrue(fresh.exists())
        assertTrue(foreign.exists())
    }

    @Test
    fun enforcesTheSizeBudgetOldestFirst() {
        val now = System.currentTimeMillis()
        val oldest = file("shared-in/oldest.bin", 40, now - 3000)
        val middle = file("shared-in/middle.bin", 40, now - 2000)
        val newest = file("shared-in/newest.bin", 40, now - 1000)

        CacheCleaner.prune(temporaryFolder.root, now, maxBytes = 80)

        assertFalse(oldest.exists())
        assertTrue(middle.exists())
        assertTrue(newest.exists())
    }

    @Test
    fun leavesForeignFilesUntouchedAndRemovesEmptyOwnedDirectories() {
        val now = System.currentTimeMillis()
        file("shared-in/old.bin", 10, now - CacheCleaner.MAX_AGE_MILLIS - 1000)
        val foreign = file("other/keep.bin", 30, now)

        CacheCleaner.prune(temporaryFolder.root, now)

        assertTrue(foreign.exists())
        assertFalse(File(temporaryFolder.root, "shared-in").exists())
    }

    @Test
    fun clearRemovesEverythingOwned() {
        val now = System.currentTimeMillis()
        file("shared-in/a.bin", 10, now)
        file("imagefolder-9.tmp", 20, now)
        val foreign = file("other/keep.bin", 30, now)

        val freed = CacheCleaner.clear(temporaryFolder.root)

        assertEquals(30L, freed)
        assertFalse(File(temporaryFolder.root, "shared-in").exists())
        assertFalse(File(temporaryFolder.root, "imagefolder-9.tmp").exists())
        assertTrue(foreign.exists())
    }
}
