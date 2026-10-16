package io.github.salatmaster.direnv.toolchain

import io.github.salatmaster.direnv.direnv.DirenvWatchesCodec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.nio.file.Files
import java.nio.file.Path

/**
 * Finds the home directory of a toolchain that the direnv environment makes available.
 *
 * Language-agnostic so each product module supplies only its variable and executable name
 * (`JAVA_HOME`/`java`, `GOROOT`/`go`, and so on) and gets the same resolution rules.
 *
 * Only the part of `PATH` the `.envrc` added is searched; see [envrcPath].
 *
 * Every candidate is checked against the filesystem before being returned. In Nix and Devbox
 * setups a variable frequently names a store path that garbage collection has already removed, and
 * offering the user a toolchain that no longer exists is worse than offering nothing.
 */
object ToolchainCandidateResolver {

    fun resolve(
        entries: Map<String, String?>,
        homeVariable: String,
        executable: String,
        machine: ToolchainMachine = ToolchainMachine.Local,
    ): Path? {
        fromHomeVariable(entries[homeVariable], executable, machine)?.let { return it }
        return fromPath(entries, executable, machine)
    }

    /**
     * Finds the executable itself, rather than the directory a toolchain lives in.
     *
     * Node is configured by the path to `node` and has no `NODE_HOME` convention to consult, so
     * this walks `PATH` the way a shell would. It also avoids the assumption [resolve] makes, that
     * an executable sits in `<home>/bin`: true of a JDK, untrue of Node on Windows, where node.exe
     * sits directly on a `PATH` entry and taking the parent would name the wrong directory.
     */
    fun resolveExecutable(
        entries: Map<String, String?>,
        executable: String,
        machine: ToolchainMachine = ToolchainMachine.Local,
    ): Path? {
        for (entry in envrcPath(entries, machine)) {
            val dir = machine.path(entry) ?: continue
            val candidate = dir.resolve(executable)
            // Checked against the filesystem for the same reason as everywhere else here: a Nix
            // store path routinely outlives the store entry it names.
            if (Files.isRegularFile(candidate)) return candidate
        }
        return null
    }

    private fun fromHomeVariable(
        value: String?,
        executable: String,
        machine: ToolchainMachine,
    ): Path? {
        val home = value?.takeIf { it.isNotBlank() }?.let(machine::path) ?: return null
        return home.takeIf { containsExecutable(it, executable) }
    }

    private fun fromPath(
        entries: Map<String, String?>,
        executable: String,
        machine: ToolchainMachine,
    ): Path? {
        for (entry in envrcPath(entries, machine)) {
            val bin = machine.path(entry) ?: continue
            if (!Files.isRegularFile(bin.resolve(executable))) continue

            // The toolchain home is the parent of bin/, which is what IDE SDK settings expect.
            val home = bin.parent ?: continue
            return home
        }
        return null
    }

    private fun containsExecutable(home: Path, executable: String): Boolean =
        Files.isRegularFile(home.resolve("bin").resolve(executable))

    /**
     * The `PATH` entries the `.envrc` added, in order.
     *
     * direnv exports `PATH` whole, and the whole of it holds everything the login shell already had:
     * `/usr/bin`, Homebrew, rustup's `~/.cargo/bin`, a NixOS user profile. Searching all of it offered
     * those as toolchains direnv provides, in projects whose `.envrc` provides none (#33). direnv also
     * exports, in DIRENV_DIFF, what `PATH` was before it changed it, so what the `.envrc` added is
     * the difference. Without a readable DIRENV_DIFF the whole `PATH` is searched, as it always was.
     */
    private fun envrcPath(entries: Map<String, String?>, machine: ToolchainMachine): List<String> {
        val path = entries["PATH"]?.takeIf { it.isNotBlank() } ?: return emptyList()
        val all = machine.splitPath(path).filter { it.isNotBlank() }
        val before = previousPath(entries["DIRENV_DIFF"]) ?: return all
        val existing = machine.splitPath(before).toSet()
        return all.filter { it !in existing }
    }

    /** `PATH` as it was before direnv changed it, which DIRENV_DIFF keeps under "p". */
    private fun previousPath(diff: String?): String? {
        val text = diff?.let(DirenvWatchesCodec::decodeText) ?: return null
        val previous = runCatching { Json.parseToJsonElement(text).jsonObject["p"]?.jsonObject }.getOrNull()
        return (previous?.get("PATH") as? JsonPrimitive)?.contentOrNull
    }
}
