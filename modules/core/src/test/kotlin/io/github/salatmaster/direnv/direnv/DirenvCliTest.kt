package io.github.salatmaster.direnv.direnv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Paths

class DirenvCliTest {

    private val workDir = Paths.get(System.getProperty("java.io.tmpdir"), "direnv-cli-test")
    private val runner = FakeDirenvProcessRunner()
    private val cli = DirenvCli(
        runner = runner,
        executableProvider = { "direnv" },
        extraEnvProvider = { emptyMap() },
        timeoutMsProvider = { 5_000 },
    )

    @Test
    fun `export returns a loaded environment with decoded watches`() {
        val watches = listOf(DirenvWatch(Paths.get("/p/.envrc"), 42L, true))
        val encoded = DirenvWatchesCodec.encode(watches)
        runner.respondTo(
            "export",
            DirenvProcessResult(0, """{"FOO":"bar","DIRENV_WATCHES":"$encoded"}""", ""),
        )

        val outcome = cli.export(workDir)

        val loaded = outcome as DirenvOutcome.Loaded
        assertEquals("bar", loaded.environment.entries["FOO"])
        assertEquals(watches, loaded.environment.watches)
    }

    @Test
    fun `export passes TERM=dumb so direnv does not colourise output`() {
        runner.respondTo("export", DirenvProcessResult(0, "{}", ""))

        cli.export(workDir)

        assertEquals("dumb", runner.invocations.single().extraEnv["TERM"])
    }

    @Test
    fun `export requests json format from the working directory`() {
        runner.respondTo("export", DirenvProcessResult(0, "{}", ""))

        cli.export(workDir)

        val invocation = runner.invocations.single()
        assertEquals(listOf("export", "json"), invocation.args)
        assertEquals(workDir, invocation.workingDir)
    }

    @Test
    fun `export reports blocked state instead of failure`() {
        runner.respondTo(
            "export",
            DirenvProcessResult(1, """{"DIRENV_DIFF":"x"}""", "direnv: error /p/.envrc is blocked."),
        )

        val outcome = cli.export(workDir)

        assertEquals("/p/.envrc", (outcome as DirenvOutcome.Blocked).envrcPath)
    }

    @Test
    fun `export reports failure with stderr for a non-blocked non-zero exit`() {
        runner.respondTo("export", DirenvProcessResult(2, "", "direnv: error syntax error near line 3"))

        val failed = cli.export(workDir) as DirenvOutcome.Failed

        assertTrue(failed.message.contains("syntax error"))
        assertEquals(2, failed.exitCode)
    }

    @Test
    fun `export reports a missing executable distinctly`() {
        runner.executableMissing = true

        assertTrue(cli.export(workDir) is DirenvOutcome.ExecutableNotFound)
    }

    @Test
    fun `export of a directory without envrc yields an empty environment`() {
        runner.respondTo("export", DirenvProcessResult(0, "", ""))

        val loaded = cli.export(workDir) as DirenvOutcome.Loaded

        assertTrue(loaded.environment.entries.isEmpty())
    }

    @Test
    fun `export records the loaded rc path from DIRENV_FILE`() {
        runner.respondTo("export", DirenvProcessResult(0, """{"DIRENV_FILE":"/p/.envrc"}""", ""))

        val loaded = cli.export(workDir) as DirenvOutcome.Loaded

        assertEquals(Paths.get("/p/.envrc"), loaded.environment.loadedRcPath)
    }

    @Test
    fun `export leaves the rc path null when direnv reports none`() {
        runner.respondTo("export", DirenvProcessResult(0, "{}", ""))

        val loaded = cli.export(workDir) as DirenvOutcome.Loaded

        assertNull(loaded.environment.loadedRcPath)
    }

    @Test
    fun `allow invokes direnv allow with the envrc path`() {
        cli.allow(Paths.get("/p/.envrc"))

        assertEquals(listOf("allow", "/p/.envrc"), runner.invocations.single().args)
    }

    @Test
    fun `deny invokes direnv deny with the envrc path`() {
        cli.deny(Paths.get("/p/.envrc"))

        assertEquals(listOf("deny", "/p/.envrc"), runner.invocations.single().args)
    }

    @Test
    fun `version returns the reported version string`() {
        runner.respondTo("version", DirenvProcessResult(0, "2.37.1\n", ""))

        assertEquals("2.37.1", cli.version())
    }

    @Test
    fun `version returns null when direnv is absent`() {
        runner.executableMissing = true

        assertNull(cli.version())
    }
}
