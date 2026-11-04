package io.github.salatmaster.direnv.go

import com.goide.sdk.GoSdk
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.salatmaster.direnv.toolchain.ToolchainMachine
import java.nio.file.Files
import java.nio.file.Path

/**
 * Holds GoLand to what the suggestion relies on: that the directory found for a Go from nixpkgs is a
 * GOROOT to it, and that the one a naive search would find is not. If a release of the Go plugin
 * learns to unwrap `share/go` itself, the second half fails and the link-following can go.
 */
class DirenvGoRootAcceptanceTest : BasePlatformTestCase() {

    fun `test the GOROOT found for a Go from nixpkgs is one GoLand accepts`() {
        val out = Files.createTempDirectory("go-1.27").toRealPath()
        VfsRootAccess.allowRootAccess(testRootDisposable, out.toString())
        val go = ToolchainMachine.Local.executable("go")

        // The files GoLand reads a GOROOT's version from, as `go tool dist` writes them.
        val root = out.resolve("share/go")
        write(root.resolve("VERSION"), "go1.27.0\ntime 2026-08-12T00:00:00Z\n")
        write(root.resolve("src/internal/buildcfg/zbootstrap.go"), "package buildcfg\n\nconst version = `go1.27.0`\n")
        write(root.resolve("bin").resolve(go), "")
        val bin = Files.createDirectories(out.resolve("bin"))
        Files.createSymbolicLink(bin.resolve(go), root.resolve("bin").resolve(go))

        val found = DirenvGoRoots.locate(mapOf("PATH" to bin.toString()), ToolchainMachine.Local)

        val sdk = GoSdk.fromHomePath(found.toString())
        assertTrue(sdk.isValid)
        assertEquals("1.27.0", sdk.version)
        assertFalse(GoSdk.fromHomePath(out.toString()).isValid)
    }

    private fun write(file: Path, text: String) {
        Files.createDirectories(file.parent)
        Files.writeString(file, text)
    }
}
