package io.github.salatmaster.direnv.watch

import io.github.salatmaster.direnv.DirenvLightTestCase
import io.github.salatmaster.direnv.DirenvService
import io.github.salatmaster.direnv.direnv.DirenvCli
import io.github.salatmaster.direnv.direnv.DirenvProcessResult
import io.github.salatmaster.direnv.direnv.DirenvWatch
import io.github.salatmaster.direnv.direnv.DirenvWatchesCodec
import io.github.salatmaster.direnv.direnv.FakeDirenvProcessRunner
import io.github.salatmaster.direnv.settings.DirenvSettings
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Paths

class DirenvWatchServiceTest : DirenvLightTestCase() {

    private lateinit var runner: FakeDirenvProcessRunner
    private lateinit var service: DirenvService
    private lateinit var watchService: DirenvWatchService

    override fun setUp() {
        super.setUp()
        runner = FakeDirenvProcessRunner()
        service = DirenvService.getInstance(project)
        watchService = DirenvWatchService.getInstance(project)
        service.cliOverride = DirenvCli(
            runner = runner,
            executableProvider = { "direnv" },
            extraEnvProvider = { emptyMap() },
            timeoutMsProvider = { 5_000 },
        )
    }

    /**
     * Builds an export payload whose watches point at files that actually exist, recording their
     * real modification time. The service polls watched files, so fictional paths would look like
     * a change on the first tick and trigger reloads unrelated to what a test is asserting.
     */
    private fun exportWith(vararg watched: String): String {
        val entries = watched.map { raw ->
            val path = Paths.get(raw)
            path.parent?.let { Files.createDirectories(it) }
            if (!Files.exists(path)) Files.writeString(path, "fixture")
            DirenvWatch(path, Files.getLastModifiedTime(path).toInstant().epochSecond, true)
        }
        val encoded = DirenvWatchesCodec.encode(entries)
        return """{"FOO":"bar","DIRENV_WATCHES":"$encoded"}"""
    }

    fun `test loading an environment registers the files it depends on`() = runBlocking {
        val flake = workDir.resolve("flake.lock").toString()
        runner.respondTo("export", DirenvProcessResult(0, exportWith(flake), ""))

        service.load(workDir, force = true)

        assertTrue(watchService.watchedPaths().contains(Paths.get(flake)))
    }

    fun `test files outside the project are registered too`() = runBlocking {
        // direnv's allow stamps live under the user's data directory. Missing them is what makes
        // an external `direnv allow` go unnoticed by the IDE.
        val stamp = Files.createTempFile("direnv-allow-stamp-outside", ".test").toString()
        runner.respondTo("export", DirenvProcessResult(0, exportWith(stamp), ""))

        service.load(workDir, force = true)

        assertTrue(watchService.watchedPaths().contains(Paths.get(stamp)))
    }

    fun `test reloading replaces the previous watch set`() = runBlocking {
        val first = workDir.resolve("first.lock").toString()
        runner.respondTo("export", DirenvProcessResult(0, exportWith(first), ""))
        service.load(workDir, force = true)

        val second = workDir.resolve("second.lock").toString()
        runner.respondTo("export", DirenvProcessResult(0, exportWith(second), ""))
        service.load(workDir, force = true)

        assertFalse(watchService.watchedPaths().contains(Paths.get(first)))
        assertTrue(watchService.watchedPaths().contains(Paths.get(second)))
    }

    fun `test a change to an unwatched file does not trigger direnv`() = runBlocking {
        runner.respondTo("export", DirenvProcessResult(0, exportWith(workDir.resolve("a.lock").toString()), ""))
        service.load(workDir, force = true)
        val before = runner.invocations.size

        watchService.handleChangedPaths(listOf(workDir.resolve("unrelated.txt")))
        delay(DEBOUNCE_PLUS_MARGIN_MS)

        assertEquals(before, runner.invocations.size)
    }

    fun `test a change to a watched file triggers a reload`() = runBlocking {
        val lock = workDir.resolve("watched.lock")
        runner.respondTo("export", DirenvProcessResult(0, exportWith(lock.toString()), ""))
        service.load(workDir, force = true)
        val before = runner.invocations.size

        watchService.handleChangedPaths(listOf(lock))
        delay(DEBOUNCE_PLUS_MARGIN_MS)

        assertTrue(
            "expected a reload after a watched file changed",
            runner.invocations.size > before,
        )
    }

    fun `test a blocked envrc still registers its allow stamp for watching`() = runBlocking {
        // Without this, approving the .envrc in an external terminal would never reach the IDE,
        // because a blocked project has no environment and therefore had no watches at all.
        val stamp = Files.createTempFile("direnv-allow-stamp", ".test").toString()
        val encoded = DirenvWatchesCodec.encode(
            listOf(
                DirenvWatch(
                    Paths.get(stamp),
                    Files.getLastModifiedTime(Paths.get(stamp)).toInstant().epochSecond,
                    true,
                )
            )
        )
        runner.respondTo(
            "export",
            DirenvProcessResult(1, """{"DIRENV_WATCHES":"$encoded"}""", "direnv: error $workDir/.envrc is blocked."),
        )

        service.load(workDir, force = true)

        assertTrue(watchService.watchedPaths().contains(Paths.get(stamp)))
    }

    fun `test watching can be disabled in settings`() = runBlocking {
        val lock = workDir.resolve("watched.lock")
        runner.respondTo("export", DirenvProcessResult(0, exportWith(lock.toString()), ""))
        service.load(workDir, force = true)
        // Not restored here on purpose: a failing assertion would skip the restore and leak the
        // setting into the next test. DirenvLightTestCase puts the settings back either way.
        DirenvSettings.getInstance(project).state.watchFiles = false
        val before = runner.invocations.size

        watchService.handleChangedPaths(listOf(lock))
        delay(DEBOUNCE_PLUS_MARGIN_MS)

        assertEquals(before, runner.invocations.size)
    }

    fun `test a registered watch set stays quiet while its files are unchanged`() = runBlocking {
        // The poll is what makes a terminal `direnv allow` reach the IDE, and it runs for as long
        // as the project lives — across every later test, since the light project is shared. A
        // watch set that reports a change when nothing changed therefore forces a reload into an
        // unrelated test, which is how this suite produced failures that could not be reproduced.
        runner.respondTo("export", DirenvProcessResult(0, exportWith(workDir.resolve("quiet.lock").toString()), ""))
        service.load(workDir, force = true)
        val before = runner.invocations.size

        delay(POLL_PLUS_MARGIN_MS)

        assertEquals("the poll reloaded although nothing changed", before, runner.invocations.size)
    }

    private companion object {
        /** The service debounces by 500 ms; wait past that plus scheduling slack. */
        const val DEBOUNCE_PLUS_MARGIN_MS = 1_500L

        /** The service polls every 2 s; wait past one full tick plus scheduling slack. */
        const val POLL_PLUS_MARGIN_MS = 3_000L
    }
}
