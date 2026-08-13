package io.github.salatmaster.direnv.inject

import com.intellij.execution.configurations.GeneralCommandLine
import io.github.salatmaster.direnv.DirenvLightTestCase
import io.github.salatmaster.direnv.DirenvService
import io.github.salatmaster.direnv.direnv.DirenvCli
import io.github.salatmaster.direnv.direnv.DirenvInternalMarker
import io.github.salatmaster.direnv.direnv.DirenvProcessResult
import io.github.salatmaster.direnv.direnv.FakeDirenvProcessRunner
import kotlinx.coroutines.runBlocking
import java.nio.file.Paths
import org.assertj.core.api.Assertions.assertThat

class DirenvCommandLineEnvCustomizerTest : DirenvLightTestCase() {

    private lateinit var runner: FakeDirenvProcessRunner
    private lateinit var service: DirenvService
    private lateinit var customizer: DirenvCommandLineEnvCustomizer

    override fun setUp() {
        super.setUp()
        runner = FakeDirenvProcessRunner()
        customizer = DirenvCommandLineEnvCustomizer()
        service = DirenvService.getInstance(project)
        service.cliOverride = DirenvCli(
            runner = runner,
            executableProvider = { "direnv" },
            extraEnvProvider = { emptyMap() },
            timeoutMsProvider = { 5_000 },
        )
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

        assertThat(environment["FOO"]).isEqualTo("bar")
    }

    fun `test removes variables direnv reported as unset`() {
        loadEnvironment("""{"OBSOLETE":null}""")
        val environment = mutableMapOf("OBSOLETE" to "old")

        customizer.customizeEnv(commandLineInProject(), environment)

        assertThat(environment).doesNotContainKey("OBSOLETE")
    }

    fun `test leaves unrelated variables untouched`() {
        loadEnvironment("""{"FOO":"bar"}""")
        val environment = mutableMapOf("HOME" to "/home/u")

        customizer.customizeEnv(commandLineInProject(), environment)

        assertThat(environment["HOME"]).isEqualTo("/home/u")
    }

    fun `test skips command lines the plugin created itself`() {
        loadEnvironment("""{"FOO":"bar"}""")
        val commandLine = commandLineInProject().also { DirenvInternalMarker.mark(it) }
        val environment = mutableMapOf<String, String>()

        customizer.customizeEnv(commandLine, environment)

        assertThat(environment).isEmpty()
    }

    fun `test skips command lines without a working directory`() {
        loadEnvironment("""{"FOO":"bar"}""")
        val environment = mutableMapOf<String, String>()

        customizer.customizeEnv(GeneralCommandLine("echo"), environment)

        assertThat(environment).isEmpty()
    }

    fun `test skips working directories outside any open project`() {
        loadEnvironment("""{"FOO":"bar"}""")
        val outside = GeneralCommandLine("echo")
            .withWorkingDirectory(Paths.get(System.getProperty("java.io.tmpdir")))
        val environment = mutableMapOf<String, String>()

        customizer.customizeEnv(outside, environment)

        assertThat(environment).doesNotContainKey("FOO")
    }

    fun `test does nothing when no environment has been loaded`() {
        val environment = mutableMapOf<String, String>()

        customizer.customizeEnv(commandLineInProject(), environment)

        assertThat(environment).isEmpty()
    }

    fun `test never invokes direnv itself`() {
        val environment = mutableMapOf<String, String>()

        customizer.customizeEnv(commandLineInProject(), environment)

        assertThat(runner.invocations).withFailMessage("customizer must serve cache only").isEmpty()
    }
}
