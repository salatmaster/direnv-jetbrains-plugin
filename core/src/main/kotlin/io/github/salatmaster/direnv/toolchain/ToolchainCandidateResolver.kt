package io.github.salatmaster.direnv.toolchain

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Finds the home directory of a toolchain that the direnv environment makes available.
 *
 * Language-agnostic so each product module supplies only its variable and executable name
 * (`JAVA_HOME`/`java`, `GOROOT`/`go`, and so on) and gets the same resolution rules.
 *
 * Every candidate is checked against the filesystem before being returned. In Nix and Devbox
 * setups a variable frequently names a store path that garbage collection has already removed, and
 * offering the user a toolchain that no longer exists is worse than offering nothing.
 */
object ToolchainCandidateResolver {

    fun resolve(entries: Map<String, String?>, homeVariable: String, executable: String): Path? {
        fromHomeVariable(entries[homeVariable], executable)?.let { return it }
        return fromPath(entries["PATH"], executable)
    }

    private fun fromHomeVariable(value: String?, executable: String): Path? {
        val home = value?.takeIf { it.isNotBlank() }?.toPathOrNull() ?: return null
        return home.takeIf { containsExecutable(it, executable) }
    }

    private fun fromPath(pathValue: String?, executable: String): Path? {
        val path = pathValue?.takeIf { it.isNotBlank() } ?: return null

        for (entry in path.split(File_PATH_SEPARATOR)) {
            val bin = entry.takeIf { it.isNotBlank() }?.toPathOrNull() ?: continue
            if (!Files.isRegularFile(bin.resolve(executable))) continue

            // The toolchain home is the parent of bin/, which is what IDE SDK settings expect.
            val home = bin.parent ?: continue
            return home
        }
        return null
    }

    private fun containsExecutable(home: Path, executable: String): Boolean =
        Files.isRegularFile(home.resolve("bin").resolve(executable))

    private fun String.toPathOrNull(): Path? = runCatching { Paths.get(this) }.getOrNull()

    private val File_PATH_SEPARATOR: Char get() = java.io.File.pathSeparatorChar
}
