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

    @Test
    fun `resolveExecutable returns the executable rather than a directory`() {
        val home = toolchainHome("node")

        val resolved = ToolchainCandidateResolver.resolveExecutable(
            entries = mapOf("PATH" to home.resolve("bin").toString()),
            executable = "node",
        )

        assertThat(resolved).isEqualTo(home.resolve("bin").resolve("node"))
    }

    @Test
    fun `resolveExecutable finds an executable that sits directly on a PATH entry`() {
        // Node on Windows is installed this way: node.exe on a PATH entry with no bin/ below it.
        // Reusing resolve() here would name the parent directory, which is not an interpreter.
        val dir = Files.createTempDirectory("node-flat")
        val file = Files.createFile(dir.resolve("node"))
        file.toFile().setExecutable(true)

        val resolved = ToolchainCandidateResolver.resolveExecutable(
            entries = mapOf("PATH" to dir.toString()),
            executable = "node",
        )

        assertThat(resolved).isEqualTo(file)
    }

    @Test
    fun `resolveExecutable takes the first PATH entry that has it, as a shell would`() {
        val first = toolchainHome("node")
        val second = toolchainHome("node")

        val resolved = ToolchainCandidateResolver.resolveExecutable(
            entries = mapOf(
                "PATH" to "${first.resolve("bin")}${File.pathSeparator}${second.resolve("bin")}",
            ),
            executable = "node",
        )

        assertThat(resolved).isEqualTo(first.resolve("bin").resolve("node"))
    }

    @Test
    fun `resolveExecutable reports nothing when the direnv environment has no PATH`() {
        assertThat(ToolchainCandidateResolver.resolveExecutable(emptyMap(), "node")).isNull()
    }

    /**
     * A toolchain on a machine that is not this one.
     *
     * Its paths are POSIX and start with /remote; the files they name really exist, under a
     * directory this JVM can reach. Returns the home as this JVM names it, and the machine that
     * knows how to read the environment it produced.
     */
    private fun remoteToolchain(executable: String): Pair<Path, ToolchainMachine> {
        val base = Files.createTempDirectory("remote-machine")
        val bin = Files.createDirectories(base.resolve("jdk").resolve("bin"))
        Files.createFile(bin.resolve(executable)).toFile().setExecutable(true)

        val machine = ToolchainMachine(isWindows = false) { value ->
            if (!value.startsWith("/remote/")) {
                null
            } else {
                value.removePrefix("/remote/").split('/').fold(base) { path, part -> path.resolve(part) }
            }
        }
        return base.resolve("jdk") to machine
    }

    @Test
    fun `reads a PATH written by a machine whose conventions differ from this one's`() {
        // A WSL project hands Windows a POSIX PATH. Split on ';' that is a single entry full of
        // colons, which is not a legal path there at all, so the search quietly found nothing and
        // the JDK was never offered. The separator belongs to whoever wrote the value.
        val (home, machine) = remoteToolchain("java")

        val resolved = ToolchainCandidateResolver.resolve(
            entries = mapOf("PATH" to "/remote/empty/bin:/remote/jdk/bin"),
            homeVariable = "JAVA_HOME",
            executable = machine.executable("java"),
            machine = machine,
        )

        assertThat(resolved).isEqualTo(home)
    }

    @Test
    fun `reads a home variable written by that machine too`() {
        val (home, machine) = remoteToolchain("java")

        val resolved = ToolchainCandidateResolver.resolve(
            entries = mapOf("JAVA_HOME" to "/remote/jdk"),
            homeVariable = "JAVA_HOME",
            executable = machine.executable("java"),
            machine = machine,
        )

        assertThat(resolved).isEqualTo(home)
    }

    @Test
    fun `skips a PATH entry that cannot be expressed here`() {
        val (home, machine) = remoteToolchain("java")

        val resolved = ToolchainCandidateResolver.resolve(
            entries = mapOf("PATH" to "/outside/bin:/remote/jdk/bin"),
            homeVariable = "JAVA_HOME",
            executable = "java",
            machine = machine,
        )

        assertThat(resolved).isEqualTo(home)
    }

    @Test
    fun `names the executable the way the machine that produced the environment names it`() {
        assertThat(ToolchainMachine(isWindows = false) { null }.executable("node")).isEqualTo("node")
        assertThat(ToolchainMachine(isWindows = true) { null }.executable("node")).isEqualTo("node.exe")
    }
}
