package io.github.salatmaster.direnv.go

import io.github.salatmaster.direnv.toolchain.ToolchainCandidateResolver
import io.github.salatmaster.direnv.toolchain.ToolchainMachine
import java.nio.file.Path

/**
 * Finds the GOROOT of the Go that direnv provides.
 *
 * Kept apart from the suggester so the decision can be tested without an IDE, as with Node and Rust.
 */
object DirenvGoRoots {

    /**
     * GOROOT itself when the `.envrc` sets it, as devenv does: the go command takes that over its own
     * location, so it is the GOROOT a terminal in the project uses.
     *
     * Otherwise the root of the first `go` on the `PATH` the `.envrc` added — found through the file
     * itself, not the directory `PATH` lists it in. nixpkgs links `$out/bin/go` to
     * `$out/share/go/bin/go`, and Homebrew links into `libexec`, so the parent of that directory is
     * no GOROOT; [ToolchainCandidateResolver.resolve] would offer `$out`, which GoLand turns down.
     * The real file is in `GOROOT/bin` wherever Go comes from.
     *
     * Whether the answer is a GOROOT at all is left to GoLand, which has its own test for it.
     */
    fun locate(entries: Map<String, String?>, machine: ToolchainMachine): Path? {
        entries["GOROOT"]?.takeIf { it.isNotBlank() }?.let(machine::path)?.let { return it }

        val go = ToolchainCandidateResolver.resolveExecutable(entries, machine.executable("go"), machine)
            ?: return null
        return runCatching { go.toRealPath() }.getOrDefault(go).parent?.parent
    }
}
