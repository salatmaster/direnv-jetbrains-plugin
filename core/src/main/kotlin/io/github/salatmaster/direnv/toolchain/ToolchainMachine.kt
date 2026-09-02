package io.github.salatmaster.direnv.toolchain

import com.intellij.openapi.util.SystemInfo
import java.nio.file.Path
import java.nio.file.Paths

/**
 * How to read the paths inside an environment, on the machine that produced it.
 *
 * `PATH` is a string written by the machine direnv ran on, in that machine's conventions: separated
 * by `:` rather than `;`, naming `java` rather than `java.exe`, and spelled the way that machine
 * spells paths. Reading it with this JVM's conventions is not merely imprecise — a POSIX `PATH`
 * split on `;` is one entry containing colons, which is not a legal path on Windows at all, so the
 * whole toolchain search quietly returned nothing for every project in WSL.
 */
class ToolchainMachine(
    /** True when that machine is Windows, which decides both conventions above. */
    private val isWindows: Boolean,
    private val toPath: (String) -> Path?,
) {

    /** Splits a `PATH` value the way that machine writes it. */
    fun splitPath(value: String): List<String> = value.split(if (isWindows) ';' else ':')

    /** Names an executable the way that machine names it. */
    fun executable(name: String): String = if (isWindows) "$name.exe" else name

    /** Turns a path from that environment into one this JVM can use, or null when it cannot. */
    fun path(value: String): Path? = toPath(value)

    companion object {

        /** The machine the IDE runs on, which is where the environment comes from in most projects. */
        val Local = ToolchainMachine(
            isWindows = SystemInfo.isWindows,
            toPath = { runCatching { Paths.get(it) }.getOrNull() },
        )
    }
}
