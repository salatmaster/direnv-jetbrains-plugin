package io.github.salatmaster.direnv.rust

import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.salatmaster.direnv.toolchain.ToolchainMachine
import org.rust.cargo.CargoConfig
import org.rust.cargo.project.workspace.StandardLibrary
import org.rust.cargo.toolchain.RsToolchainProvider
import org.rust.cargo.toolchain.tools.rustup
import java.nio.file.Files
import java.nio.file.Path

/**
 * Holds the Rust plugin to what the suggestions rely on: that a directory of links to cargo and
 * rustc is a toolchain to it, and that it reads standard library sources in the shapes offered. If
 * a release of the plugin changes either, this is where it shows.
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

    fun `test rustup in the toolchain directory is what makes the plugin fetch sources itself`() {
        // Sources are not offered then, because the plugin would never read them.
        val bin = Files.createTempDirectory("rust-toolchain").resolve("bin")
        tool(bin, "cargo")
        tool(bin, "rustc")
        assertNull(RsToolchainProvider.getToolchain(bin)!!.rustup(bin))

        tool(bin, "rustup")
        assertNotNull(RsToolchainProvider.getToolchain(bin)!!.rustup(bin))
    }

    fun `test the standard library sources offered are ones the Rust plugin accepts`() {
        val temp = Files.createTempDirectory("rust-sources").toRealPath()
        VfsRootAccess.allowRootAccess(testRootDisposable, temp.toString())
        val library = temp.resolve("rust-lib-src")
        val checkout = temp.resolve("rust")
        for (crate in listOf("core", "alloc", "std")) {
            for (dir in listOf(library, checkout.resolve("library"))) {
                Files.createDirectories(dir.resolve(crate).resolve("src")).resolve("lib.rs").let(Files::createFile)
            }
        }

        for (sources in listOf(library, checkout)) {
            assertTrue(DirenvRustToolchains.isStandardLibrary(sources))
            assertNotNull(StandardLibrary.fromPath(project, sources.toString(), null, CargoConfig.DEFAULT))
        }
    }
}
