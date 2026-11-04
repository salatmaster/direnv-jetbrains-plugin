package io.github.salatmaster.direnv.go

import io.github.salatmaster.direnv.toolchain.ToolchainMachine
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class DirenvGoRootsTest {

    @TempDir
    lateinit var temp: Path

    private val go = ToolchainMachine.Local.executable("go")

    private fun goroot(dir: Path): Path {
        Files.createDirectories(dir.resolve("bin"))
        Files.createFile(dir.resolve("bin").resolve(go))
        return dir
    }

    private fun locate(vararg entries: Pair<String, String>): Path? =
        DirenvGoRoots.locate(mapOf(*entries), ToolchainMachine.Local)

    @Test
    fun `a GOROOT laid out as an archive is the parent of the bin directory on PATH`() {
        val root = goroot(temp.resolve("go"))

        assertThat(locate("PATH" to root.resolve("bin").toString())).isEqualTo(root.toRealPath())
    }

    @Test
    fun `a GOROOT nixpkgs links into bin is found through the link`() {
        // $out/bin/go -> $out/share/go/bin/go: the parent of $out/bin is $out, which is no GOROOT.
        val out = temp.resolve("go-1.27")
        val root = goroot(out.resolve("share/go"))
        val bin = Files.createDirectories(out.resolve("bin"))
        Files.createSymbolicLink(bin.resolve(go), root.resolve("bin").resolve(go))

        assertThat(locate("PATH" to bin.toString())).isEqualTo(root.toRealPath())
    }

    @Test
    fun `GOROOT from the envrc wins over the go on PATH`() {
        // devenv exports both, and the go command takes the variable over its own location.
        val onPath = goroot(temp.resolve("path-go"))
        val exported = goroot(temp.resolve("devenv-go"))

        val found = locate("GOROOT" to "$exported/", "PATH" to onPath.resolve("bin").toString())

        assertThat(found).isEqualTo(exported)
    }

    @Test
    fun `no go on PATH and no GOROOT is no GOROOT`() {
        assertThat(locate("PATH" to Files.createDirectories(temp.resolve("bin")).toString())).isNull()
    }
}
