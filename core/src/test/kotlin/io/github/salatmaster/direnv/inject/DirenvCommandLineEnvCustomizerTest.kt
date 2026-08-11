package io.github.salatmaster.direnv.inject

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.salatmaster.direnv.DirenvService
import io.github.salatmaster.direnv.direnv.DirenvCli
import io.github.salatmaster.direnv.direnv.DirenvInternalMarker
import io.github.salatmaster.direnv.direnv.DirenvProcessResult
import io.github.salatmaster.direnv.direnv.FakeDirenvProcessRunner
import kotlinx.coroutines.runBlocking
import java.nio.file.Paths

class DirenvCommandLineEnvCustomizerTest : BasePlatformTestCase() {

    private lateinit var runner: FakeDirenvProcessRunner
    private lateinit var service: DirenvService
    private lateinit var customizer: DirenvCommandLineEnvCustomizer

    override fun setUp() {
        super.setUp()
        runner = FakeDirenvProcessRunner()
        customizer = DirenvCommandLineEnvCustomizer()
        service = DirenvService.getInstance(project)
        // The light project is shared across tests, so the service must be reset explicitly.
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

    private fun commandLineInProject(): GeneralCommandLine =
        GeneralCommandLine("echo").withWorkingDirectory(Paths.get(project.basePath!!))

    private fun loadEnvironment(json: String) = runBlocking {
        runner.respondTo("export", DirenvProcessResult(0, json, ""))
        service.load(Paths.get(project.basePath!!), force = true)
    }

    fun `test injects loaded variables into the environment`() {
        loadEnvironment("""{"FOO":"bar"}""")
        val environment = mutableMapOf<String, String>()

        customizer.customizeEnv(commandLineInProject(), environment)

        assertEquals("bar", environment["FOO"])
    }

    fun `test removes variables direnv reported as unset`() {
        loadEnvironment("""{"OBSOLETE":null}""")
        val environment = mutableMapOf("OBSOLETE" to "old")

        customizer.customizeEnv(commandLineInProject(), environment)

        assertFalse(environment.containsKey("OBSOLETE"))
    }

    fun `test leaves unrelated variables untouched`() {
        loadEnvironment("""{"FOO":"bar"}""")
        val environment = mutableMapOf("HOME" to "/home/u")

        customizer.customizeEnv(commandLineInProject(), environment)

        assertEquals("/home/u", environment["HOME"])
    }

    fun `test skips command lines the plugin created itself`() {
        loadEnvironment("""{"FOO":"bar"}""")
        val commandLine = commandLineInProject().also { DirenvInternalMarker.mark(it) }
        val environment = mutableMapOf<String, String>()

        customizer.customizeEnv(commandLine, environment)

        assertTrue(environment.isEmpty())
    }

    fun `test skips command lines without a working directory`() {
        loadEnvironment("""{"FOO":"bar"}""")
        val environment = mutableMapOf<String, String>()

        customizer.customizeEnv(GeneralCommandLine("echo"), environment)

        assertTrue(environment.isEmpty())
    }

    fun `test skips working directories outside any open project`() {
        loadEnvironment("""{"FOO":"bar"}""")
        val outside = GeneralCommandLine("echo")
            .withWorkingDirectory(Paths.get(System.getProperty("java.io.tmpdir")))
        val environment = mutableMapOf<String, String>()

        customizer.customizeEnv(outside, environment)

        assertFalse(environment.containsKey("FOO"))
    }

    fun `test does nothing when no environment has been loaded`() {
        val environment = mutableMapOf<String, String>()

        customizer.customizeEnv(commandLineInProject(), environment)

        assertTrue(environment.isEmpty())
    }

    fun `test never invokes direnv itself`() {
        val environment = mutableMapOf<String, String>()

        customizer.customizeEnv(commandLineInProject(), environment)

        assertTrue("customizer must serve cache only", runner.invocations.isEmpty())
    }
}
