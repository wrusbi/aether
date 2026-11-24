package dev.aetherlab.aether.data

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PluginDigestTest {
    private val temporaryRoots = mutableListOf<File>()

    @After
    fun cleanUp() {
        temporaryRoots.forEach(File::deleteRecursively)
    }

    @Test
    fun matchesTheVectorSharedWithTheTypeScriptImplementation() {
        // Same fixture and expected value as pi-bridge/tests/plugin-formats.test.mjs.
        val root = tree("dir/b.md" to "world", "a.txt" to "hello\n")

        assertEquals(
            "39151ee6fd4996e4705834d1744d117bc33a2a1e6f58173656f0dfdc94e24b69",
            PluginDigest.compute(root),
        )
        assertEquals("tree-sha256-v1", PluginDigest.Algorithm)
    }

    @Test
    fun ignoresCreationOrderButNotContentOrNames() {
        val first = tree("one.txt" to "alpha", "two/three.txt" to "beta")
        val second = tree("two/three.txt" to "beta", "one.txt" to "alpha")
        val edited = tree("one.txt" to "alpha", "two/three.txt" to "BETA")
        val renamed = tree("uno.txt" to "alpha", "two/three.txt" to "beta")

        assertNotNull(PluginDigest.compute(first))
        assertEquals(PluginDigest.compute(first), PluginDigest.compute(second))
        assertNotEquals(PluginDigest.compute(first), PluginDigest.compute(edited))
        assertNotEquals(PluginDigest.compute(first), PluginDigest.compute(renamed))
    }

    @Test
    fun symbolicLinksMakeTheDigestUnavailable() {
        val root = tree("SKILL.md" to "safe")
        val created = runCatching {
            Files.createSymbolicLink(File(root, "escape").toPath(), File("/tmp").toPath())
        }.isSuccess
        if (!created) return

        assertNull(PluginDigest.compute(root))
    }

    @Test
    fun missingFolderHasNoDigest() {
        assertNull(PluginDigest.compute(File("/definitely/not/here")))
    }

    private fun tree(vararg files: Pair<String, String>): File {
        val root = Files.createTempDirectory("plugin-digest").toFile().also(temporaryRoots::add)
        files.forEach { (path, content) ->
            File(root, path).apply {
                parentFile?.mkdirs()
                writeText(content)
            }
        }
        return root
    }
}
