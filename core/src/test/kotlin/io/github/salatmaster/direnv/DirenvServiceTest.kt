package io.github.salatmaster.direnv

import io.github.salatmaster.direnv.direnv.DirenvCli
import io.github.salatmaster.direnv.direnv.DirenvProcessResult
import io.github.salatmaster.direnv.direnv.DirenvWatch
import io.github.salatmaster.direnv.direnv.DirenvWatchesCodec
import io.github.salatmaster.direnv.direnv.FakeDirenvProcessRunner
import kotlinx.coroutines.runBlocking
import java.nio.file.Files

class DirenvServiceTest : DirenvLightTestCase() {

    private lateinit var runner: FakeDirenvProcessRunner
    private lateinit var service: DirenvService

    override fun setUp() {
        super.setUp()
        runner = FakeDirenvProcessRunner()
        service = DirenvService.getInstance(project)
        service.cliOverride = DirenvCli(
            runner = runner,
            executableProvider = { "direnv" },
            extraEnvProvider = { emptyMap() },
            timeoutMsProvider = { 5_000 },
        )
    }

    fun `test loads and caches an environment`() = runBlocking {
        runner.respondTo("export", DirenvProcessResult(0, """{"FOO":"bar"}""", ""))

        val state = service.load(workDir)

        assertTrue(state is DirenvState.Loaded)
        assertEquals("bar", service.cachedFor(workDir)?.entries?.get("FOO"))
    }

    fun `test a second load reuses the cache without invoking direnv again`() = runBlocking {
        runner.respondTo("export", DirenvProcessResult(0, """{"FOO":"bar"}""", ""))
        service.load(workDir)

        service.load(workDir)

        assertEquals(1, runner.invocations.size)
    }

    fun `test force reload bypasses the cache`() = runBlocking {
        runner.respondTo("export", DirenvProcessResult(0, """{"FOO":"bar"}""", ""))
        service.load(workDir)

        service.load(workDir, force = true)

        assertEquals(2, runner.invocations.size)
    }

    fun `test a removed variable is reflected after reload`() = runBlocking {
        runner.respondTo("export", DirenvProcessResult(0, """{"FOO":"bar"}""", ""))
        service.load(workDir)

        runner.respondTo("export", DirenvProcessResult(0, """{"FOO":null}""", ""))
        service.load(workDir, force = true)

        val cached = service.cachedFor(workDir)!!
        assertTrue(cached.entries.containsKey("FOO"))
        assertNull(cached.entries["FOO"])
    }

    fun `test blocked envrc does not populate the cache`() = runBlocking {
        runner.respondTo(
            "export",
            DirenvProcessResult(1, "", "direnv: error $workDir/.envrc is blocked."),
        )

        val state = service.load(workDir)

        assertTrue(state is DirenvState.Blocked)
        assertNull(service.cachedFor(workDir))
    }

    fun `test blocked envrc never triggers an allow invocation`() = runBlocking {
        runner.respondTo(
            "export",
            DirenvProcessResult(1, "", "direnv: error $workDir/.envrc is blocked."),
        )

        service.load(workDir)

        assertFalse(runner.invocations.any { it.args.firstOrNull() == "allow" })
    }

    fun `test a revoked approval is not cached and still names the file to allow`() = runBlocking {
        val envrc = workDir.resolve(".envrc")
        // A real file, recorded with its real modification time. direnv reports the stamp as
        // existing, and a path that exists only in the fixture would not: the watch service polls
        // it, correctly calls that a change, and forces a reload seconds later. The directory
        // names matter — the stamp is recognised by living under direnv/deny.
        val stamp = Files.writeString(
            Files.createDirectories(workDir.resolve("direnv").resolve("deny")).resolve("abc"),
            "",
        )
        val modtime = Files.getLastModifiedTime(stamp).toInstant().epochSecond
        val watches = DirenvWatchesCodec.encode(listOf(DirenvWatch(stamp, modtime, true)))
        // Backslashes in a Windows path would otherwise be read as JSON escapes.
        val envrcJson = envrc.toString().replace("\\", "\\\\")
        runner.respondTo(
            "export",
            DirenvProcessResult(0, """{"DIRENV_FILE":"$envrcJson","DIRENV_WATCHES":"$watches"}""", ""),
        )

        val state = service.load(workDir)

        assertTrue(state is DirenvState.Denied)
        // direnv exports nothing once approval is revoked; caching that would leave the plugin
        // reporting a loaded environment that no longer exists.
        assertNull(service.cachedFor(workDir))
        // Nothing is cached, so this is the only thing left that names the file — and without it
        // the Allow action loses its target and vanishes from the menu.
        assertEquals(envrc, service.envrcPathFor(workDir))
    }

    fun `test missing executable is reported without throwing`() = runBlocking {
        runner.executableMissing = true

        assertTrue(service.load(workDir) is DirenvState.ExecutableMissing)
    }

    fun `test invalidate clears the cached environment`() = runBlocking {
        runner.respondTo("export", DirenvProcessResult(0, """{"FOO":"bar"}""", ""))
        service.load(workDir)

        service.invalidate(null)

        assertNull(service.cachedFor(workDir))
    }

    fun `test cachedFor returns null before anything is loaded`() {
        assertNull(service.cachedFor(workDir))
    }

    fun `test a subdirectory reuses the environment loaded for its parent`() = runBlocking {
        runner.respondTo("export", DirenvProcessResult(0, """{"FOO":"bar"}""", ""))
        service.load(workDir)

        val nested = workDir.resolve("sub").resolve("deeper")

        assertEquals("bar", service.cachedFor(nested)?.entries?.get("FOO"))
    }

    fun `test secret values never reach rendered state`() = runBlocking {
        val secret = "leak-canary-8f2a1c"
        runner.respondTo("export", DirenvProcessResult(0, """{"TOKEN":"$secret"}""", ""))

        service.load(workDir)

        val rendered = service.cachedFor(workDir).toString() + service.state().toString()
        assertFalse("secret leaked: $rendered", rendered.contains(secret))
    }
}
