package dev.aetherlab.aether.data

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

/**
 * Kotlin side of the "tree-sha256-v1" plugin digest.
 *
 * Must stay byte-for-byte compatible with `pi-bridge/src/plugin-integrity.ts`:
 * both are checked against the same test vector. For every regular file, sorted
 * by relative path (UTF-16 code unit order, "/" separators), the record is
 * `"<path>\n<size>\n<sha256 of content as lowercase hex>\n"`; the digest is the
 * SHA-256 of all records concatenated as UTF-8.
 */
object PluginDigest {
    const val Algorithm: String = "tree-sha256-v1"

    /**
     * Returns the digest of [root], or `null` when [root] is not a real
     * directory or contains a symbolic link or any other special entry.
     */
    fun compute(root: File): String? {
        if (Files.isSymbolicLink(root.toPath()) || !root.isDirectory) return null
        val files = mutableListOf<Pair<String, File>>()
        if (!collect(root, "", files)) return null
        val sorted = files.sortedBy { it.first }
        val outer = MessageDigest.getInstance("SHA-256")
        for ((relativePath, file) in sorted) {
            val inner = MessageDigest.getInstance("SHA-256")
            var size = 0L
            file.inputStream().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    inner.update(buffer, 0, read)
                    size += read
                }
            }
            val record = relativePath + "\n" + size + "\n" + hex(inner.digest()) + "\n"
            outer.update(record.toByteArray(Charsets.UTF_8))
        }
        return hex(outer.digest())
    }

    private fun collect(
        directory: File,
        prefix: String,
        out: MutableList<Pair<String, File>>,
    ): Boolean {
        val children = directory.listFiles() ?: return false
        for (child in children) {
            if (Files.isSymbolicLink(child.toPath())) return false
            val relativePath = if (prefix.isEmpty()) child.name else prefix + "/" + child.name
            when {
                child.isDirectory -> if (!collect(child, relativePath, out)) return false
                child.isFile -> out += relativePath to child
                else -> return false
            }
        }
        return true
    }

    private fun hex(bytes: ByteArray): String =
        bytes.joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}
