package io.github.salatmaster.direnv.rust

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.salatmaster.direnv.toolchain.ToolchainMachine
import org.rust.cargo.toolchain.RsToolchainProvider
import java.nio.file.Files
import java.nio.file.Path

/**
 * Holds the Rust plugin to what linking relies on: that a directory of links to cargo and rustc is
 * a toolchain to it. If a release of the plugin stops following links, this is where it shows.
 */
class DirenvRustToolchainAcceptanceTest : BasePlatformTestCase() {

    private fun tool(dir: Path, name: String): Path {
        val file = Files.createDirectories(dir).resolve(ToolchainMachine.Local.executable(name))
        Files.createFile(file).toFile().setExecutable(true)
        return file
    }

    fun `test linked tools make a toolchain the Rust plugin accepts`() {
        val temp = Files.createTempDirectory("rust-toolchain")
        val tools = listOf(tool(temp.resolve("cargo/bin"), "cargo"), tool(temp.resolve("rustc/bin"), "rustc"))
        val directory = DirenvRustToolchains.linkedDirectory(temp.resolve("links"), tools)

        DirenvRustToolchains.link(directory, tools)

        val toolchain = RsToolchainProvider.getToolchain(directory)
        assertNotNull(toolchain)
        assertTrue(toolchain!!.looksLikeValidToolchain())
    }

    fun `test the tools apart are not a toolchain to it`() {
        // The reason linking exists at all: neither directory direnv names would be accepted.
        val temp = Files.createTempDirectory("rust-toolchain")
        val cargo = tool(temp.resolve("cargo/bin"), "cargo")
        tool(temp.resolve("rustc/bin"), "rustc")

        assertFalse(RsToolchainProvider.getToolchain(cargo.parent)!!.looksLikeValidToolchain())
    }
}
