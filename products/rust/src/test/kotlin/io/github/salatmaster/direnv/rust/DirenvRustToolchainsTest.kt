package io.github.salatmaster.direnv.rust

import io.github.salatmaster.direnv.toolchain.ToolchainMachine
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

class DirenvRustToolchainsTest {

    @TempDir
    lateinit var temp: Path

    private val machine = ToolchainMachine.Local

    /** A directory holding the given tools, the way a store path's bin/ does. */
    private fun bin(name: String, vararg tools: String): Path {
        val dir = Files.createDirectories(temp.resolve(name).resolve("bin"))
        for (tool in tools) Files.createFile(dir.resolve(machine.executable(tool))).toFile().setExecutable(true)
        return dir
    }

    private fun path(vararg dirs: Path) = mapOf("PATH" to dirs.joinToString(File.pathSeparator))

    @Test
    fun `a toolchain in one directory is used as it is`() {
        // rustup, or a Rust overlay for Nix: everything in a single bin/.
        val bin = bin("rust", "cargo", "rustc", "rustfmt", "cargo-clippy")

        val tools = DirenvRustToolchains.locate(path(bin), machine)!!

        assertThat(tools).hasSize(4)
        assertThat(DirenvRustToolchains.sharedDirectory(tools)).isEqualTo(bin)
    }

    @Test
    fun `a toolchain spread across store paths has no directory of its own`() {
        // nixpkgs' separate cargo and rustc packages, which is how the issue was reported.
        val cargo = bin("cargo", "cargo")
        val rustc = bin("rustc", "rustc")

        val tools = DirenvRustToolchains.locate(path(cargo, rustc), machine)!!

        assertThat(DirenvRustToolchains.sharedDirectory(tools)).isNull()
    }

    @Test
    fun `cargo without rustc is no toolchain`() {
        assertThat(DirenvRustToolchains.locate(path(bin("cargo", "cargo")), machine)).isNull()
    }

    @Test
    fun `each tool is the one a shell would run, not the one beside cargo`() {
        // A Nix toolchain without rustfmt in front of rustup's proxies: the shell runs Nix's cargo
        // and rustup's rustfmt, and so must the IDE.
        val nix = bin("nix", "cargo", "rustc")
        val rustup = bin("rustup", "cargo", "rustc", "rustfmt")

        val tools = DirenvRustToolchains.locate(path(nix, rustup), machine)!!

        assertThat(tools).containsExactly(
            nix.resolve(machine.executable("cargo")),
            nix.resolve(machine.executable("rustc")),
            rustup.resolve(machine.executable("rustfmt")),
        )
    }

    @Test
    fun `linking puts every tool in one directory the Rust plugin accepts`() {
        val tools = DirenvRustToolchains.locate(path(bin("cargo", "cargo"), bin("rustc", "rustc")), machine)!!
        val directory = DirenvRustToolchains.linkedDirectory(temp.resolve("links"), tools)

        DirenvRustToolchains.link(directory, tools)

        // What the Rust plugin checks before it calls a directory a toolchain.
        assertThat(Files.isExecutable(directory.resolve(machine.executable("cargo")))).isTrue()
        assertThat(Files.isExecutable(directory.resolve(machine.executable("rustc")))).isTrue()
    }

    @Test
    fun `linking again is harmless`() {
        val tools = DirenvRustToolchains.locate(path(bin("cargo", "cargo"), bin("rustc", "rustc")), machine)!!
        val directory = DirenvRustToolchains.linkedDirectory(temp.resolve("links"), tools)

        DirenvRustToolchains.link(directory, tools)
        DirenvRustToolchains.link(directory, tools)

        assertThat(Files.list(directory).use { it.count() }).isEqualTo(2)
    }

    @Test
    fun `a toolchain in one directory is not linked anywhere`() {
        val bin = bin("rust", "cargo", "rustc")
        val tools = DirenvRustToolchains.locate(path(bin), machine)!!

        DirenvRustToolchains.link(bin, tools)

        assertThat(Files.list(bin).use { it.count() }).isEqualTo(2)
    }

    @Test
    fun `different tools get a different directory`() {
        // After a flake.lock update every store path moves; the links the user agreed to must not.
        val root = temp.resolve("links")
        val before = listOf(Path.of("/nix/store/a-cargo/bin/cargo"), Path.of("/nix/store/a-rustc/bin/rustc"))
        val after = listOf(Path.of("/nix/store/b-cargo/bin/cargo"), Path.of("/nix/store/a-rustc/bin/rustc"))

        assertThat(DirenvRustToolchains.linkedDirectory(root, before))
            .isEqualTo(DirenvRustToolchains.linkedDirectory(root, before.reversed()))
            .isNotEqualTo(DirenvRustToolchains.linkedDirectory(root, after))
    }
}
