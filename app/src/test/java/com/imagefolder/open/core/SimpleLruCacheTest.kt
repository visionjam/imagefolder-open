package com.imagefolder.open.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class SimpleLruCacheTest {

    @Test
    fun `evicts the least recently used entry`() {
        val cache = SimpleLruCache<String, Int>(2)
        cache.put("a", 1)
        cache.put("b", 2)
        // Touch "a" so "b" becomes the oldest entry.
        assertEquals(1, cache.get("a"))
        cache.put("c", 3)

        assertEquals(2, cache.size)
        assertEquals(1, cache.get("a"))
        assertNull(cache.get("b"))
        assertEquals(3, cache.get("c"))
    }

    @Test
    fun `overwrites an existing key without growing`() {
        val cache = SimpleLruCache<String, Int>(2)
        cache.put("a", 1)
        cache.put("a", 9)

        assertEquals(1, cache.size)
        assertEquals(9, cache.get("a"))
    }

    @Test
    fun `clear drops every entry`() {
        val cache = SimpleLruCache<String, Int>(4)
        cache.put("a", 1)
        cache.put("b", 2)
        cache.clear()

        assertEquals(0, cache.size)
        assertNull(cache.get("a"))
    }

    @Test
    fun `rejects a non-positive capacity`() {
        assertThrows(IllegalArgumentException::class.java) { SimpleLruCache<String, Int>(0) }
    }
}
