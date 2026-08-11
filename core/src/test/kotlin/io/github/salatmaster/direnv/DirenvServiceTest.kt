package io.github.salatmaster.direnv

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.salatmaster.direnv.direnv.DirenvCli
import io.github.salatmaster.direnv.direnv.DirenvProcessResult
import io.github.salatmaster.direnv.direnv.FakeDirenvProcessRunner
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import java.nio.file.Paths

class DirenvServiceTest : BasePlatformTestCase() {

    private lateinit var runner: FakeDirenvProcessRunner
    private lateinit var service: DirenvService

    private val workDir: Path get() = Paths.get(project.basePath!!)

    override fun setUp() {
        super.setUp()
        runner = FakeDirenvProcessRunner()
        service = DirenvService.getInstance(project)
        // BasePlatformTestCase reuses a single light project across tests, so this project-level
        // service outlives each test. Without clearing it, a previous test's cached environment
        // leaks in and later loads short-circuit on the stale cache.
        service.invalidate(null)
        service.cliOverride = DirenvCli(
            runner = runner,
            executableProvider = { "direnv" },
            extraEnvProvider = { emptyMap() },
            timeoutMsProvider = { 5_000 },
        )
    }

    override fun tearDown() {
        try {
            service.invalidate(null)
            service.cliOverride = null
        } finally {
            super.tearDown()
        }
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
