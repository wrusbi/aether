package dev.aetherlab.aether.runtime

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MirrorIntegrityTest {
    private val temporaryRoots = mutableListOf<File>()

    @After
    fun cleanUp() {
        temporaryRoots.forEach(File::deleteRecursively)
    }

    @Test
    fun identicalTreesShareADigestRegardlessOfCreationOrder() {
        val first = tree("one" to "alpha", "dir/two" to "beta")
        val second = tree("dir/two" to "beta", "one" to "alpha")

        assertNotNull(MirrorIntegrity.directoryDigest(first))
        assertEquals(MirrorIntegrity.directoryDigest(first), MirrorIntegrity.directoryDigest(second))
    }

    @Test
    fun changedContentOrNameChangesTheDigest() {
        val original = tree("SKILL.md" to "safe")
        val edited = tree("SKILL.md" to "evil")
        val renamed = tree("OTHER.md" to "safe")

        val digest = MirrorIntegrity.directoryDigest(original)
        assertNotEquals(digest, MirrorIntegrity.directoryDigest(edited))
        assertNotEquals(digest, MirrorIntegrity.directoryDigest(renamed))
    }

    @Test
    fun symbolicLinksMakeTheDigestUnavailable() {
        val root = tree("SKILL.md" to "safe")
        val link = File(root, "escape")
        val created = runCatching {
            Files.createSymbolicLink(link.toPath(), File("/tmp").toPath())
        }.isSuccess
        if (!created) return

        assertNull(MirrorIntegrity.directoryDigest(root))
    }

    @Test
    fun mirrorWithMatchingSignatureButEditedContentIsRejected() {
        val source = tree("SKILL.md" to "safe")
        val mirror = temporaryRoot().apply {
            File(this, "skill-a").also { copy ->
                copy.mkdirs()
                File(copy, "SKILL.md").writeText("safe")
            }
            File(this, ".sig").writeText("same-signature")
        }
        val sources = mapOf("skill-a" to source)

        assertTrue(MirrorIntegrity.mirrorMatchesSources(mirror, ".sig", sources))

        File(mirror, "skill-a/SKILL.md").writeText("tampered")
        assertFalse(MirrorIntegrity.mirrorMatchesSources(mirror, ".sig", sources))
    }

    @Test
    fun mirrorWithExtraOrMissingDirectoriesIsRejected() {
        val source = tree("SKILL.md" to "safe")
        val mirror = temporaryRoot().apply {
            File(this, "skill-a").also { copy ->
                copy.mkdirs()
                File(copy, "SKILL.md").writeText("safe")
            }
            File(this, "planted").mkdirs()
        }

        assertFalse(MirrorIntegrity.mirrorMatchesSources(mirror, ".sig", mapOf("skill-a" to source)))
        assertFalse(
            MirrorIntegrity.mirrorMatchesSources(
                mirror,
                ".sig",
                mapOf("skill-a" to source, "skill-b" to source),
            ),
        )
    }

    private fun temporaryRoot(): File =
        Files.createTempDirectory("mirror-integrity").toFile().also(temporaryRoots::add)

    private fun tree(vararg files: Pair<String, String>): File =
        temporaryRoot().also { root ->
            files.forEach { (path, content) ->
                File(root, path).apply {
                    parentFile?.mkdirs()
                    writeText(content)
                }
            }
        }
}
