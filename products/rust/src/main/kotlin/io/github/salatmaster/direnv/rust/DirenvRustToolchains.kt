package io.github.salatmaster.direnv.rust

import io.github.salatmaster.direnv.toolchain.ToolchainCandidateResolver
import io.github.salatmaster.direnv.toolchain.ToolchainMachine
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat

/**
 * Finds the Rust toolchain direnv provides, and the one directory the Rust plugin can use for it.
 *
 * The Rust plugin takes a toolchain as a single directory holding both `cargo` and `rustc`, and
 * looks for `rustfmt` and `cargo-clippy` in it too. rustup and the Rust overlays for Nix put them
 * all in one `bin/`. A Nix shell built from nixpkgs' separate `cargo`, `rustc` and `rustfmt`
 * packages does not: each sits in a store path of its own, so no directory on `PATH` is a toolchain
 * the plugin would accept. For that case the tools are linked into a directory of the IDE's own.
 *
 * Kept apart from the suggester so the decision can be tested without an IDE, as with Node.
 */
object DirenvRustToolchains {

    /** cargo and rustc are what make a directory a toolchain to the Rust plugin. */
    private val REQUIRED = listOf("cargo", "rustc")

    /** Found in the toolchain directory when present; formatting and Clippy need them there. */
    private val OPTIONAL = listOf("rustfmt", "cargo-clippy")

    /**
     * The tools [entries] provides, each where a shell would find it first on `PATH`, or null
     * without both cargo and rustc.
     *
     * Each is looked up on its own rather than all of them in cargo's directory: that is what the
     * shell does, and a `PATH` mixing a Nix toolchain with rustup's proxies is exactly where taking
     * one directory for all of them would give a toolchain no terminal in the project has.
     */
    fun locate(entries: Map<String, String?>, machine: ToolchainMachine): List<Path>? {
        fun find(tool: String) =
            ToolchainCandidateResolver.resolveExecutable(entries, machine.executable(tool), machine)

        val required = REQUIRED.map { find(it) ?: return null }
        return required + OPTIONAL.mapNotNull(::find)
    }

    /** The directory that already holds every one of [tools], or null when they are spread out. */
    fun sharedDirectory(tools: List<Path>): Path? = tools.map { it.parent }.distinct().singleOrNull()

    /**
     * Where [link] puts [tools] under [root]: named after the files it links, so a toolchain that
     * changes — a `flake.lock` update moves every store path — gets a directory of its own rather
     * than one whose links quietly point somewhere else than the user agreed to.
     */
    fun linkedDirectory(root: Path, tools: List<Path>): Path {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(tools.map { it.toString() }.sorted().joinToString("\n").toByteArray())
        return root.resolve(HexFormat.of().formatHex(digest).take(16))
    }

    /** Links into [directory] each of [tools] that is not already in it. */
    fun link(directory: Path, tools: List<Path>) {
        val elsewhere = tools.filter { it.parent != directory }
        if (elsewhere.isEmpty()) return

        Files.createDirectories(directory)
        for (tool in elsewhere) {
            val link = directory.resolve(tool.fileName)
            // The directory is named after what it links, so a link already there is the same link.
            if (!Files.exists(link, LinkOption.NOFOLLOW_LINKS)) Files.createSymbolicLink(link, tool)
        }
    }
}
