package io.github.salatmaster.direnv

import io.github.salatmaster.direnv.direnv.DirenvCli
import io.github.salatmaster.direnv.direnv.DirenvProcessResult
import io.github.salatmaster.direnv.direnv.DirenvWatch
import io.github.salatmaster.direnv.direnv.DirenvWatchesCodec
import io.github.salatmaster.direnv.direnv.FakeDirenvProcessRunner
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import org.assertj.core.api.Assertions.assertThat

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

    fun `test loads and caches an environment`() = runBlocking<Unit> {
        runner.respondTo("export", DirenvProcessResult(0, """{"FOO":"bar"}""", ""))

        val state = service.load(workDir)

        assertThat(state).isInstanceOf(DirenvState.Loaded::class.java)
        assertThat(service.cachedFor(workDir)?.entries?.get("FOO")).isEqualTo("bar")
    }

    fun `test a second load reuses the cache without invoking direnv again`() = runBlocking<Unit> {
        runner.respondTo("export", DirenvProcessResult(0, """{"FOO":"bar"}""", ""))
        service.load(workDir)

        service.load(workDir)

        assertThat(runner.invocations).hasSize(1)
    }

    fun `test force reload bypasses the cache`() = runBlocking<Unit> {
        runner.respondTo("export", DirenvProcessResult(0, """{"FOO":"bar"}""", ""))
        service.load(workDir)

        service.load(workDir, force = true)

        assertThat(runner.invocations).hasSize(2)
    }

    fun `test a removed variable is reflected after reload`() = runBlocking<Unit> {
        runner.respondTo("export", DirenvProcessResult(0, """{"FOO":"bar"}""", ""))
        service.load(workDir)

        runner.respondTo("export", DirenvProcessResult(0, """{"FOO":null}""", ""))
        service.load(workDir, force = true)

        val cached = service.cachedFor(workDir)!!
        assertThat(cached.entries).containsKey("FOO")
        assertThat(cached.entries["FOO"]).isNull()
    }

    fun `test blocked envrc does not populate the cache`() = runBlocking<Unit> {
        runner.respondTo(
            "export",
            DirenvProcessResult(1, "", "direnv: error $workDir/.envrc is blocked."),
        )

        val state = service.load(workDir)

        assertThat(state).isInstanceOf(DirenvState.Blocked::class.java)
        assertThat(service.cachedFor(workDir)).isNull()
    }

    fun `test blocked envrc never triggers an allow invocation`() = runBlocking<Unit> {
        runner.respondTo(
            "export",
            DirenvProcessResult(1, "", "direnv: error $workDir/.envrc is blocked."),
        )

        service.load(workDir)

        assertThat(runner.invocations).noneMatch { it.args.firstOrNull() == "allow" }
    }

    fun `test a revoked approval is not cached and still names the file to allow`() = runBlocking<Unit> {
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

        assertThat(state).isInstanceOf(DirenvState.Denied::class.java)
        // direnv exports nothing once approval is revoked; caching that would leave the plugin
        // reporting a loaded environment that no longer exists.
        assertThat(service.cachedFor(workDir)).isNull()
        // Nothing is cached, so this is the only thing left that names the file — and without it
        // the Allow action loses its target and vanishes from the menu.
        assertThat(service.envrcPathFor(workDir)).isEqualTo(envrc)
    }

    fun `test missing executable is reported without throwing`() = runBlocking<Unit> {
        runner.executableMissing = true

        assertThat(service.load(workDir)).isInstanceOf(DirenvState.ExecutableMissing::class.java)
    }

    fun `test invalidate clears the cached environment`() = runBlocking<Unit> {
        runner.respondTo("export", DirenvProcessResult(0, """{"FOO":"bar"}""", ""))
        service.load(workDir)

        service.invalidate(null)

        assertThat(service.cachedFor(workDir)).isNull()
    }

    fun `test cachedFor returns null before anything is loaded`() {
        assertThat(service.cachedFor(workDir)).isNull()
    }

    fun `test a subdirectory reuses the environment loaded for its parent`() = runBlocking<Unit> {
        runner.respondTo("export", DirenvProcessResult(0, """{"FOO":"bar"}""", ""))
        service.load(workDir)

        val nested = workDir.resolve("sub").resolve("deeper")

        assertThat(service.cachedFor(nested)?.entries?.get("FOO")).isEqualTo("bar")
    }

    fun `test secret values never reach rendered state`() = runBlocking<Unit> {
        val secret = "leak-canary-8f2a1c"
        runner.respondTo("export", DirenvProcessResult(0, """{"TOKEN":"$secret"}""", ""))

        service.load(workDir)

        val rendered = service.cachedFor(workDir).toString() + service.state().toString()
        assertThat(rendered).withFailMessage("secret leaked: $rendered").doesNotContain(secret)
    }
}
