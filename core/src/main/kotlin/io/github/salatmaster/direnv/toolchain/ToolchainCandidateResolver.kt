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

    /**
     * Finds the executable itself, rather than the directory a toolchain lives in.
     *
     * Node is configured by the path to `node` and has no `NODE_HOME` convention to consult, so
     * this walks `PATH` the way a shell would. It also avoids the assumption [resolve] makes, that
     * an executable sits in `<home>/bin`: true of a JDK, untrue of Node on Windows, where node.exe
     * sits directly on a `PATH` entry and taking the parent would name the wrong directory.
     */
    fun resolveExecutable(entries: Map<String, String?>, executable: String): Path? {
        val path = entries["PATH"]?.takeIf { it.isNotBlank() } ?: return null

        for (entry in path.split(File_PATH_SEPARATOR)) {
            val dir = entry.takeIf { it.isNotBlank() }?.toPathOrNull() ?: continue
            val candidate = dir.resolve(executable)
            // Checked against the filesystem for the same reason as everywhere else here: a Nix
            // store path routinely outlives the store entry it names.
            if (Files.isRegularFile(candidate)) return candidate
        }
        return null
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
