package io.github.salatmaster.direnv.watch

import io.github.salatmaster.direnv.direnv.DirenvWatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
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

        assertTrue(registry.reloadTargetFor(Paths.get("/p/.envrc")) != null)
    }

    @Test
    fun `reload target is the directory the environment was loaded for`() {
        val registry = DirenvWatchRegistry()
        registry.replace(Paths.get("/p"), listOf(watch("/p/flake.lock")))

        assertEquals(abs("/p"), registry.reloadTargetFor(Paths.get("/p/flake.lock")))
    }

    @Test
    fun `a change to an unrelated file does not require reload`() {
        val registry = DirenvWatchRegistry()
        registry.replace(Paths.get("/p"), listOf(watch("/p/.envrc")))

        assertEquals(null, registry.reloadTargetFor(Paths.get("/p/src/Main.kt")))
    }

    @Test
    fun `files outside the project still count when direnv watches them`() {
        // direnv watches ~/.config/direnv/direnvrc and its allow/deny stamps, which live far
        // outside the project. Missing those is what makes an external `direnv allow` go unnoticed.
        val registry = DirenvWatchRegistry()
        val stamp = "/home/u/.local/share/direnv/allow/8be319f2"
        registry.replace(Paths.get("/p"), listOf(watch(stamp)))

        assertEquals(abs("/p"), registry.reloadTargetFor(Paths.get(stamp)))
    }

    @Test
    fun `replacing watches drops the previous set`() {
        val registry = DirenvWatchRegistry()
        registry.replace(Paths.get("/p"), listOf(watch("/p/old.txt")))

        registry.replace(Paths.get("/p"), listOf(watch("/p/new.txt")))

        assertEquals(null, registry.reloadTargetFor(Paths.get("/p/old.txt")))
        assertEquals(abs("/p"), registry.reloadTargetFor(Paths.get("/p/new.txt")))
    }

    @Test
    fun `watches from different directories are tracked independently`() {
        val registry = DirenvWatchRegistry()
        registry.replace(Paths.get("/p"), listOf(watch("/p/.envrc")))
        registry.replace(Paths.get("/p/nested"), listOf(watch("/p/nested/.envrc")))

        assertEquals(abs("/p"), registry.reloadTargetFor(Paths.get("/p/.envrc")))
        assertEquals(abs("/p/nested"), registry.reloadTargetFor(Paths.get("/p/nested/.envrc")))
    }

    @Test
    fun `watched paths are exposed for filesystem registration`() {
        val registry = DirenvWatchRegistry()
        registry.replace(Paths.get("/p"), listOf(watch("/p/.envrc"), watch("/p/flake.lock")))

        assertEquals(
            setOf(abs("/p/.envrc"), abs("/p/flake.lock")),
            registry.allWatchedPaths(),
        )
    }

    @Test
    fun `clearing removes every registration`() {
        val registry = DirenvWatchRegistry()
        registry.replace(Paths.get("/p"), listOf(watch("/p/.envrc")))

        registry.clear()

        assertTrue(registry.allWatchedPaths().isEmpty())
        assertEquals(null, registry.reloadTargetFor(Paths.get("/p/.envrc")))
    }

    @Test
    fun `paths are compared after normalisation`() {
        val registry = DirenvWatchRegistry()
        registry.replace(Paths.get("/p"), listOf(watch("/p/sub/../.envrc")))

        assertEquals(abs("/p"), registry.reloadTargetFor(Paths.get("/p/.envrc")))
    }

    @Test
    fun `a file appearing where direnv expected none is reported as stale`() {
        // This is exactly what an external `direnv allow` looks like: the stamp direnv listed as
        // non-existent suddenly exists.
        val dir = Files.createTempDirectory("stamp")
        val stamp = dir.resolve("allow-stamp")
        val registry = DirenvWatchRegistry()
        registry.replace(Paths.get("/p"), listOf(DirenvWatch(stamp, 0L, false)))

        assertTrue(registry.staleTargets().isEmpty())

        Files.writeString(stamp, "approved")

        assertEquals(setOf(abs("/p")), registry.staleTargets())
    }

    @Test
    fun `a modified file is reported as stale`() {
        val file = Files.createTempFile("watched", ".lock")
        Files.writeString(file, "one")
        val recordedTime = Files.getLastModifiedTime(file).toInstant().epochSecond
        val registry = DirenvWatchRegistry()
        registry.replace(Paths.get("/p"), listOf(DirenvWatch(file, recordedTime, true)))

        assertTrue(registry.staleTargets().isEmpty())

        Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.fromMillis(
            (recordedTime + 60) * 1000))

        assertEquals(setOf(abs("/p")), registry.staleTargets())
    }

    @Test
    fun `rebaseline stops the same change being reported twice`() {
        val dir = Files.createTempDirectory("stamp2")
        val stamp = dir.resolve("allow-stamp")
        val registry = DirenvWatchRegistry()
        registry.replace(Paths.get("/p"), listOf(DirenvWatch(stamp, 0L, false)))
        Files.writeString(stamp, "approved")
        assertEquals(setOf(abs("/p")), registry.staleTargets())

        registry.rebaseline()

        assertTrue(registry.staleTargets().isEmpty())
    }

    @Test
    fun `an unknown file yields no reload target when nothing is registered`() {
        assertFalse(DirenvWatchRegistry().reloadTargetFor(Paths.get("/p/.envrc")) != null)
    }
}
