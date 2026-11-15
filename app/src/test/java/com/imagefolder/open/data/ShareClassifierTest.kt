package com.imagefolder.open.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareClassifierTest {

    @Test
    fun `plain files go to pack without a choice`() {
        val plan = ShareClassifier.classify(
            listOf(
                SharedFile("content://a", "a.txt", "text/plain", 10, hasHiddenPayload = false),
                SharedFile("content://b", "b.bin", null, 20, hasHiddenPayload = false),
            ),
        )
        assertFalse(plan.needsChoice)
        assertEquals(listOf("content://a", "content://b"), plan.packUris)
        assertTrue(plan.extractUris.isEmpty())
    }

    @Test
    fun `hidden image offers extract and keeps all files for pack`() {
        val plan = ShareClassifier.classify(
            listOf(
                SharedFile("content://img", "tank.png", "image/png", 1000, hasHiddenPayload = true),
                SharedFile("content://doc", "doc.pdf", "application/pdf", 2000, hasHiddenPayload = false),
            ),
        )
        assertTrue(plan.needsChoice)
        assertEquals(listOf("content://img"), plan.extractUris)
        assertEquals(listOf("content://img", "content://doc"), plan.packUris)
    }

    @Test
    fun `duplicate uris are deduplicated`() {
        val item = SharedFile("content://same", "a.txt", "text/plain", 1, hasHiddenPayload = false)
        val plan = ShareClassifier.classify(listOf(item, item))
        assertEquals(listOf("content://same"), plan.packUris)
    }

    @Test
    fun `empty share has no plan`() {
        val plan = ShareClassifier.classify(emptyList())
        assertFalse(plan.needsChoice)
        assertTrue(plan.packUris.isEmpty())
    }

    @Test
    fun `only inspect images up to 64 MB or unknown size`() {
        assertTrue(ShareClassifier.shouldInspect(-1L))
        assertTrue(ShareClassifier.shouldInspect(64L * 1024 * 1024))
        assertFalse(ShareClassifier.shouldInspect(64L * 1024 * 1024 + 1))
    }
}
