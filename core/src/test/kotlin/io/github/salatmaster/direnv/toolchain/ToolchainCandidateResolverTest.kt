package io.github.salatmaster.direnv.toolchain

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

class ToolchainCandidateResolverTest {

    /** Creates a directory tree containing bin/<name>, mimicking a toolchain layout. */
    private fun toolchainHome(executable: String): Path {
        val home = Files.createTempDirectory("toolchain")
        val bin = Files.createDirectories(home.resolve("bin"))
        val file = Files.createFile(bin.resolve(executable))
        file.toFile().setExecutable(true)
        return home
    }

    @Test
    fun `prefers an explicit home variable`() {
        val home = toolchainHome("java")

        val resolved = ToolchainCandidateResolver.resolve(
            entries = mapOf("JAVA_HOME" to home.toString()),
            homeVariable = "JAVA_HOME",
            executable = "java",
        )

        assertThat(resolved).isEqualTo(home)
    }

    @Test
    fun `falls back to locating the executable on the direnv PATH`() {
        val home = toolchainHome("java")

        val resolved = ToolchainCandidateResolver.resolve(
            entries = mapOf("PATH" to "/nonexistent${File.pathSeparator}${home.resolve("bin")}"),
            homeVariable = "JAVA_HOME",
            executable = "java",
        )

        assertThat(resolved).isEqualTo(home)
    }

    @Test
    fun `ignores a home variable pointing at a directory that does not exist`() {
        // Nix garbage collection can delete a store path while the variable still names it.
        val resolved = ToolchainCandidateResolver.resolve(
            entries = mapOf("JAVA_HOME" to "/nix/store/deleted-jdk"),
            homeVariable = "JAVA_HOME",
            executable = "java",
        )

        assertThat(resolved).isNull()
    }

    @Test
    fun `returns null when the environment provides nothing`() {
        assertThat(ToolchainCandidateResolver.resolve(
                entries = emptyMap(),
                homeVariable = "JAVA_HOME",
                executable = "java",
            )).isNull()
    }

    @Test
    fun `ignores an unset home variable and still searches PATH`() {
        val home = toolchainHome("java")

        val resolved = ToolchainCandidateResolver.resolve(
            entries = mapOf("JAVA_HOME" to null, "PATH" to home.resolve("bin").toString()),
            homeVariable = "JAVA_HOME",
            executable = "java",
        )

        assertThat(resolved).isEqualTo(home)
    }

    @Test
    fun `returns the first PATH entry that actually contains the executable`() {
        val first = Files.createTempDirectory("empty")
        val real = toolchainHome("go")

        val resolved = ToolchainCandidateResolver.resolve(
            entries = mapOf("PATH" to "$first${File.pathSeparator}${real.resolve("bin")}"),
            homeVariable = "GOROOT",
            executable = "go",
        )

        assertThat(resolved).isEqualTo(real)
    }

    @Test
    fun `does not treat a bare bin directory without a parent as a toolchain home`() {
        val resolved = ToolchainCandidateResolver.resolve(
            entries = mapOf("PATH" to ""),
            homeVariable = "JAVA_HOME",
            executable = "java",
        )

        assertThat(resolved).isNull()
    }
}
