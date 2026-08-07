package com.melt.callrecmailer.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FolderScannerTest {
    @get:Rule val tmp = TemporaryFolder()
    private val scanner = FolderScanner()

    @Test fun returns_only_matching_extensions_case_insensitive() {
        tmp.newFile("a.m4a"); tmp.newFile("b.txt"); tmp.newFile("c.AMR")
        val names = scanner.scan(tmp.root, setOf("m4a", "amr")).map { it.name }.toSet()
        assertEquals(setOf("a.m4a", "c.AMR"), names)
    }

    @Test fun returns_empty_when_dir_missing() {
        val res = scanner.scan(File(tmp.root, "nope"), setOf("m4a"))
        assertTrue(res.isEmpty())
    }

    @Test fun captures_size_and_lastModified() {
        val f = tmp.newFile("a.m4a"); f.writeBytes(ByteArray(5))
        val r = scanner.scan(tmp.root, setOf("m4a")).single()
        assertEquals(5L, r.size)
        assertEquals(f.lastModified(), r.lastModified)
        assertEquals(f.absolutePath, r.path)
    }
}
