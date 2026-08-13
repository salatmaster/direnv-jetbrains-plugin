package io.github.salatmaster.direnv.watch

import io.github.salatmaster.direnv.direnv.DirenvWatch
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Paths

class DirenvWatchRegistryTest {

    private fun watch(path: String) = DirenvWatch(Paths.get(path), 0L, true)

    /** The registry normalises paths, so expectations must be normalised the same way. */
    private fun abs(path: String) = Paths.get(path).toAbsolutePath().normalize()

    @Test
    fun `a change to a watched file requires reload`() {
        val registry = DirenvWatchRegistry()
        registry.replace(Paths.get("/p"), listOf(watch("/p/.envrc")))

        assertThat(registry.reloadTargetFor(Paths.get("/p/.envrc"))).isNotNull()
    }

    @Test
    fun `reload target is the directory the environment was loaded for`() {
        val registry = DirenvWatchRegistry()
        registry.replace(Paths.get("/p"), listOf(watch("/p/flake.lock")))

        assertThat(registry.reloadTargetFor(Paths.get("/p/flake.lock"))).isEqualTo(abs("/p"))
    }

    @Test
    fun `a change to an unrelated file does not require reload`() {
        val registry = DirenvWatchRegistry()
        registry.replace(Paths.get("/p"), listOf(watch("/p/.envrc")))

        assertThat(registry.reloadTargetFor(Paths.get("/p/src/Main.kt"))).isEqualTo(null)
    }

    @Test
    fun `files outside the project still count when direnv watches them`() {
        // direnv watches ~/.config/direnv/direnvrc and its allow/deny stamps, which live far
        // outside the project. Missing those is what makes an external `direnv allow` go unnoticed.
        val registry = DirenvWatchRegistry()
        val stamp = "/home/u/.local/share/direnv/allow/8be319f2"
        registry.replace(Paths.get("/p"), listOf(watch(stamp)))

        assertThat(registry.reloadTargetFor(Paths.get(stamp))).isEqualTo(abs("/p"))
    }

    @Test
    fun `replacing watches drops the previous set`() {
        val registry = DirenvWatchRegistry()
        registry.replace(Paths.get("/p"), listOf(watch("/p/old.txt")))

        registry.replace(Paths.get("/p"), listOf(watch("/p/new.txt")))

        assertThat(registry.reloadTargetFor(Paths.get("/p/old.txt"))).isEqualTo(null)
        assertThat(registry.reloadTargetFor(Paths.get("/p/new.txt"))).isEqualTo(abs("/p"))
    }

    @Test
    fun `watches from different directories are tracked independently`() {
        val registry = DirenvWatchRegistry()
        registry.replace(Paths.get("/p"), listOf(watch("/p/.envrc")))
        registry.replace(Paths.get("/p/nested"), listOf(watch("/p/nested/.envrc")))

        assertThat(registry.reloadTargetFor(Paths.get("/p/.envrc"))).isEqualTo(abs("/p"))
        assertThat(registry.reloadTargetFor(Paths.get("/p/nested/.envrc"))).isEqualTo(abs("/p/nested"))
    }

    @Test
    fun `watched paths are exposed for filesystem registration`() {
        val registry = DirenvWatchRegistry()
        registry.replace(Paths.get("/p"), listOf(watch("/p/.envrc"), watch("/p/flake.lock")))

        assertThat(registry.allWatchedPaths()).isEqualTo(setOf(abs("/p/.envrc"), abs("/p/flake.lock")))
    }

    @Test
    fun `clearing removes every registration`() {
        val registry = DirenvWatchRegistry()
        registry.replace(Paths.get("/p"), listOf(watch("/p/.envrc")))

        registry.clear()

        assertThat(registry.allWatchedPaths()).isEmpty()
        assertThat(registry.reloadTargetFor(Paths.get("/p/.envrc"))).isEqualTo(null)
    }

    @Test
    fun `paths are compared after normalisation`() {
        val registry = DirenvWatchRegistry()
        registry.replace(Paths.get("/p"), listOf(watch("/p/sub/../.envrc")))

        assertThat(registry.reloadTargetFor(Paths.get("/p/.envrc"))).isEqualTo(abs("/p"))
    }

    @Test
    fun `a file appearing where direnv expected none is reported as stale`() {
        // This is exactly what an external `direnv allow` looks like: the stamp direnv listed as
        // non-existent suddenly exists.
        val dir = Files.createTempDirectory("stamp")
        val stamp = dir.resolve("allow-stamp")
        val registry = DirenvWatchRegistry()
        registry.replace(Paths.get("/p"), listOf(DirenvWatch(stamp, 0L, false)))

        assertThat(registry.staleTargets()).isEmpty()

        Files.writeString(stamp, "approved")

        assertThat(registry.staleTargets()).isEqualTo(setOf(abs("/p")))
    }

    @Test
    fun `a modified file is reported as stale`() {
        val file = Files.createTempFile("watched", ".lock")
        Files.writeString(file, "one")
        val recordedTime = Files.getLastModifiedTime(file).toInstant().epochSecond
        val registry = DirenvWatchRegistry()
        registry.replace(Paths.get("/p"), listOf(DirenvWatch(file, recordedTime, true)))

        assertThat(registry.staleTargets()).isEmpty()

        Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.fromMillis(
            (recordedTime + 60) * 1000))

        assertThat(registry.staleTargets()).isEqualTo(setOf(abs("/p")))
    }

    @Test
    fun `rebaseline stops the same change being reported twice`() {
        val dir = Files.createTempDirectory("stamp2")
        val stamp = dir.resolve("allow-stamp")
        val registry = DirenvWatchRegistry()
        registry.replace(Paths.get("/p"), listOf(DirenvWatch(stamp, 0L, false)))
        Files.writeString(stamp, "approved")
        assertThat(registry.staleTargets()).isEqualTo(setOf(abs("/p")))

        registry.rebaseline()

        assertThat(registry.staleTargets()).isEmpty()
    }

    @Test
    fun `an unknown file yields no reload target when nothing is registered`() {
        assertThat(DirenvWatchRegistry().reloadTargetFor(Paths.get("/p/.envrc"))).isNull()
    }
}
