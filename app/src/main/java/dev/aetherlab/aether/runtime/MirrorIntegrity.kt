package dev.aetherlab.aether.runtime

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

/**
 * Content checks for directories mirrored into the Alpine guest.
 *
 * The mirror lives in the guest file system, which the agent can edit. The
 * `.aether-mirror-signature` file lives there too, so matching signatures alone
 * prove nothing: the agent can change a mirrored Skill and leave the signature
 * untouched. These helpers compare the real bytes of the mirror with the
 * trusted source directory that sits outside the guest.
 */
internal object MirrorIntegrity {
    /**
     * SHA-256 over every regular file below [root] (sorted by relative path,
     * including each path and size), or `null` when the tree contains anything
     * other than plain files and directories, such as a symbolic link. A `null`
     * result never equals another digest, so such a tree is always rebuilt.
     */
    fun directoryDigest(root: File): String? {
        if (Files.isSymbolicLink(root.toPath()) || !root.isDirectory) return null
        val files = mutableListOf<Pair<String, File>>()
        if (!collectFiles(root, "", files)) return null
        files.sortBy { it.first }
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        for ((relativePath, file) in files) {
            digest.update(relativePath.toByteArray(Charsets.UTF_8))
            digest.update(0.toByte())
            digest.update(file.length().toString().toByteArray(Charsets.UTF_8))
            digest.update(0.toByte())
            file.inputStream().use { input ->
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    /**
     * True when [mirrorRoot] holds exactly the directories named in [sources]
     * (plus [signatureFileName]) and each one has the same content as its source.
     */
    fun mirrorMatchesSources(
        mirrorRoot: File,
        signatureFileName: String,
        sources: Map<String, File>,
    ): Boolean {
        if (Files.isSymbolicLink(mirrorRoot.toPath()) || !mirrorRoot.isDirectory) return false
        val actualNames = mirrorRoot.list()
            ?.filter { it != signatureFileName }
            ?.toSet()
            ?: return false
        if (actualNames != sources.keys) return false
        return sources.all { (name, source) ->
            val expected = runCatching { directoryDigest(source.canonicalFile) }.getOrNull()
            val actual = runCatching { directoryDigest(File(mirrorRoot, name)) }.getOrNull()
            expected != null && actual != null && expected == actual
        }
    }

    private fun collectFiles(
        directory: File,
        prefix: String,
        out: MutableList<Pair<String, File>>,
    ): Boolean {
        val children = directory.listFiles() ?: return false
        for (child in children) {
            if (Files.isSymbolicLink(child.toPath())) return false
            val relativePath = if (prefix.isEmpty()) child.name else "$prefix/${child.name}"
            when {
                child.isDirectory -> if (!collectFiles(child, relativePath, out)) return false
                child.isFile -> out += relativePath to child
                else -> return false
            }
        }
        return true
    }
}
