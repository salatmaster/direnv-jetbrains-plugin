package io.github.salatmaster.direnv.direnv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun `failure messages carry no ANSI escapes into the UI`() {
        runner.respondTo(
            "export",
            DirenvProcessResult(2, "", "\u001B[31mdirenv: error syntax error\u001B[0m"),
        )

        val failed = cli.export(workDir) as DirenvOutcome.Failed

        assertFalse("escape code leaked into the message: ${failed.message}",
            failed.message.contains("\u001B"))
    }

    @Test
    fun `blocked outcome still reports the files to watch`() {
        // direnv emits DIRENV_WATCHES even when the .envrc is blocked, and those watches include
        // the allow stamp. Without them an external `direnv allow` would go unnoticed — exactly
        // the case where noticing matters most.
        val stamp = Paths.get("/home/u/.local/share/direnv/allow/abc")
        val encoded = DirenvWatchesCodec.encode(listOf(DirenvWatch(stamp, 0L, true)))
        runner.respondTo(
            "export",
            DirenvProcessResult(1, """{"DIRENV_WATCHES":"$encoded"}""", "direnv: error /p/.envrc is blocked."),
        )

        val blocked = cli.export(workDir) as DirenvOutcome.Blocked

        assertEquals(listOf(DirenvWatch(stamp, 0L, true)), blocked.watches)
    }

    @Test
    fun `export reports a missing executable distinctly`() {
        runner.executableMissing = true

        assertTrue(cli.export(workDir) is DirenvOutcome.ExecutableNotFound)
    }

    @Test
    fun `export distinguishes an unrunnable executable from a missing one`() {
        runner.processFails = true

        val outcome = cli.export(workDir)

        // Reporting this as ExecutableNotFound would send the user to the installation guide for
        // a problem that has nothing to do with installation.
        assertTrue(outcome is DirenvOutcome.Failed)
        assertTrue((outcome as DirenvOutcome.Failed).message.contains("cannot start process"))
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
        val envrc = Paths.get("/p/.envrc")
        cli.allow(envrc)

        assertEquals(listOf("allow", envrc.toString()), runner.invocations.single().args)
    }

    @Test
    fun `deny invokes direnv deny with the envrc path`() {
        val envrc = Paths.get("/p/.envrc")
        cli.deny(envrc)

        assertEquals(listOf("deny", envrc.toString()), runner.invocations.single().args)
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
