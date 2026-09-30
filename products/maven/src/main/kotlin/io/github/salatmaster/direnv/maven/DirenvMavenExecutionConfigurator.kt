package io.github.salatmaster.direnv.maven

import com.intellij.execution.configurations.ParametersList
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.diagnostic.Logger
import io.github.salatmaster.direnv.DirenvGuard
import io.github.salatmaster.direnv.DirenvMachine
import io.github.salatmaster.direnv.DirenvService
import org.jetbrains.idea.maven.execution.MavenRunConfiguration
import org.jetbrains.idea.maven.execution.run.MavenExecutionConfigurator
import org.jetbrains.idea.maven.execution.run.MavenExecutionConfiguratorProvider
import java.nio.file.Paths

/**
 * Injects the direnv environment into Maven goals: run configurations, and the build itself when
 * the IDE delegates build and run actions to Maven.
 *
 * Required in addition to the command line customizer, for the same reason the terminal is: with
 * the `maven.use.scripts` registry key on — the default since at least 2026.1 — the IDE runs `mvn`
 * through `MavenShCommandLineState`, which starts it with the Eel API and never builds a
 * GeneralCommandLine, so the customizer is not called at all. This extension point is handed the
 * very map that becomes the process environment, after the login shell's variables and the run
 * configuration's own are in it, so direnv goes on top of both.
 *
 * Unsets are applied to the map, but the process also inherits the IDE's environment underneath
 * it, so a variable direnv unsets can survive — the same asymmetry Gradle has.
 */
class DirenvMavenExecutionConfigurator : MavenExecutionConfiguratorProvider {

    private val log = Logger.getInstance(DirenvMavenExecutionConfigurator::class.java)

    override fun createConfigurator(
        environment: ExecutionEnvironment,
        configuration: MavenRunConfiguration,
    ): MavenExecutionConfigurator = object : MavenExecutionConfigurator {
        override fun configureParameters(env: MutableMap<String, String>, parametersList: ParametersList) =
            inject(configuration, env)
    }

    private fun inject(configuration: MavenRunConfiguration, env: MutableMap<String, String>) {
        try {
            val project = configuration.project
            if (!DirenvGuard.mayRun(project)) {
                if (log.isDebugEnabled) {
                    log.debug("Not injecting into a Maven execution: direnv is off or the project is untrusted")
                }
                return
            }

            // The Maven module's own directory, so a nested .envrc wins over the root's. It is a path
            // as this JVM names it — the IDE maps it to the target machine itself — so Paths.get is
            // the right reading here, unlike for anything direnv reports.
            val workingDir = configuration.runnerParameters.workingDirPath
                ?.let { runCatching { Paths.get(it) }.getOrNull() }
                ?: DirenvMachine.projectDir(project)
            if (workingDir == null) {
                if (log.isDebugEnabled) log.debug("Not injecting into a Maven execution: no usable project directory")
                return
            }

            // ponytail: cache only, like the command line customizer; the build is user-started long
            // after the startup activity has warmed it. Load on demand if that ever proves false.
            val loaded = DirenvService.getInstance(project).cachedFor(workingDir)
            if (loaded == null) {
                if (log.isDebugEnabled) {
                    log.debug("Not injecting into a Maven execution in $workingDir: no environment is loaded for it")
                }
                return
            }

            loaded.applyTo(env)
            // Count only. Names and values both stay out of the log; see the command line customizer.
            if (log.isDebugEnabled) {
                log.debug("Injected ${loaded.entries.size} direnv variables into a Maven execution in $workingDir")
            }
        } catch (e: Exception) {
            // Throwing here would fail the Maven run outright; a missing environment must not.
            log.warn("Failed to inject the direnv environment into a Maven execution", e)
        }
    }
}
