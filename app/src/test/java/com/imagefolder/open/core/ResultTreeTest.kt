package com.imagefolder.open.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ResultTreeTest {

    private fun entries(): List<ResultEntry> = listOf(
        ResultEntry("payload/", true, 0),
        ResultEntry("payload/hello.txt", false, 1),
        ResultEntry("payload/data.bin", false, 2),
        ResultEntry("payload/sub/", true, 3),
        ResultEntry("payload/sub/nested.txt", false, 4),
        ResultEntry("readme.md", false, 5),
    )

    @Test
    fun rootListsFoldersFirstThenFiles() {
        val nodes = ResultTree.children(entries(), "")
        assertEquals(listOf("payload", "readme.md"), nodes.map { it.name })
        assertTrue(nodes[0].isDirectory)
        assertEquals("payload/", nodes[0].path)
        assertEquals(0, nodes[0].itemIndex)
        assertEquals(3, nodes[0].childCount)
        assertEquals(5, nodes[1].itemIndex)
        assertEquals(0, nodes[1].childCount)
    }

    @Test
    fun folderListsItsOwnChildrenOnly() {
        val nodes = ResultTree.children(entries(), "payload/")
        assertEquals(listOf("sub", "data.bin", "hello.txt"), nodes.map { it.name })
        assertEquals(3, nodes[0].itemIndex)
        assertEquals(1, nodes[0].childCount)
        assertEquals(1, nodes[2].itemIndex)
    }

    @Test
    fun nestedFolderIsReachableOneLevelAtATime() {
        val nodes = ResultTree.children(entries(), "payload/sub/")
        assertEquals(listOf("nested.txt"), nodes.map { it.name })
        assertEquals(4, nodes[0].itemIndex)
    }

    @Test
    fun missingDirectoryEntriesAreSynthesized() {
        val flat = listOf(ResultEntry("a/b/c.txt", false, 0))
        val root = ResultTree.children(flat, "")
        assertEquals(1, root.size)
        assertEquals("a", root[0].name)
        assertTrue(root[0].isDirectory)
        assertEquals(-1, root[0].itemIndex)
        assertEquals(1, root[0].childCount)

        val inner = ResultTree.children(flat, "a/")
        assertEquals(listOf("b"), inner.map { it.name })
        assertEquals(-1, inner[0].itemIndex)

        val leaf = ResultTree.children(flat, "a/b/")
        assertEquals(listOf("c.txt"), leaf.map { it.name })
        assertEquals(0, leaf[0].itemIndex)
    }

    @Test
    fun emptyFolderHasNoChildren() {
        assertTrue(ResultTree.children(listOf(ResultEntry("empty/", true, 0)), "empty/").isEmpty())
        assertTrue(ResultTree.children(emptyList(), "").isEmpty())
    }

    @Test
    fun normalizeFolderAcceptsCommonShapes() {
        assertEquals("", ResultTree.normalizeFolder(""))
        assertEquals("", ResultTree.normalizeFolder("/"))
        assertEquals("payload/", ResultTree.normalizeFolder("payload"))
        assertEquals("payload/", ResultTree.normalizeFolder("/payload/"))
        assertEquals("a/b/", ResultTree.normalizeFolder(" a/b/ "))
    }

    @Test
    fun parentFolderWalksUpToTheRoot() {
        assertEquals("", ResultTree.parentFolder(""))
        assertEquals("", ResultTree.parentFolder("payload/"))
        assertEquals("payload/", ResultTree.parentFolder("payload/sub/"))
        assertEquals("payload/sub/", ResultTree.parentFolder("payload/sub/deep/"))
    }

    @Test
    fun folderNameIsTheLastSegment() {
        assertEquals("payload", ResultTree.folderName("payload/"))
        assertEquals("sub", ResultTree.folderName("payload/sub/"))
        assertEquals("", ResultTree.folderName(""))
    }
}
