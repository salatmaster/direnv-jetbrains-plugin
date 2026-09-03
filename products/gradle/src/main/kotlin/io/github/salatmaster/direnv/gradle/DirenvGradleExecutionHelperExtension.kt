package io.github.salatmaster.direnv.gradle

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import io.github.salatmaster.direnv.DirenvGuard
import io.github.salatmaster.direnv.DirenvMachine
import io.github.salatmaster.direnv.DirenvService
import io.github.salatmaster.direnv.direnv.DirenvEnvironment
import kotlinx.coroutines.runBlocking
import org.jetbrains.plugins.gradle.service.execution.GradleExecutionContext
import org.jetbrains.plugins.gradle.service.project.GradleExecutionHelperExtension
import org.jetbrains.plugins.gradle.settings.GradleExecutionSettings
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Injects the direnv environment into every Gradle execution: sync, Gradle task configurations,
 * and run configurations delegated to Gradle ("Build and run using: Gradle" — the default).
 *
 * Required in addition to the command line customizer: the IDE runs Gradle through the Tooling
 * API inside its own JVM, and the build environment the daemon uses comes from
 * GradleExecutionHelper.setupEnvironment(), whose throwaway GeneralCommandLine has no working
 * directory — so the catch-all extension point sees the launch but can never place it. Entries
 * put into settings.env here are applied on top of the IDE's parent environment there, so direnv
 * overrides the parent — the same precedence GeneralCommandLine gives the customizer, which runs
 * after the run configuration's own variables.
 *
 * configureSettings runs before setupEnvironment for every execution, possibly more than once
 * per sync; the merge is idempotent, so that is fine. configureOperation is deliberately not
 * implemented: it is the replacement API the platform documents as unsafe, and it would clobber
 * what other extensions contributed.
 *
 * The interface is not a stable API — a signature change in a future major would stop this class
 * from loading. The plugin verifier in the release workflow is what catches that.
 */
class DirenvGradleExecutionHelperExtension : GradleExecutionHelperExtension {

    private val log = Logger.getInstance(DirenvGradleExecutionHelperExtension::class.java)

    override fun configureSettings(settings: GradleExecutionSettings, context: GradleExecutionContext) {
        try {
            val project = context.project
            if (!DirenvGuard.mayRun(project)) {
                if (log.isDebugEnabled) {
                    log.debug(
                        "Not injecting into a Gradle execution: " +
                            "direnv is off or the project is untrusted"
                    )
                }
                return
            }

            val workingDir = resolveWorkingDirectory(context)
            if (workingDir == null) {
                if (log.isDebugEnabled) log.debug("Not injecting into a Gradle execution: no usable project directory")
                return
            }

            val loaded = cachedOrLoaded(project, workingDir)
            if (loaded == null) {
                if (log.isDebugEnabled) {
                    log.debug("Not injecting into a Gradle execution in $workingDir: no environment is loaded for it")
                }
                return
            }

            val entries = DirenvGradleEnvironmentMerger.environmentToInject(loaded)
            if (entries.isEmpty()) return
            settings.withEnvironmentVariables(entries)
            // Count only. Names and values both stay out of the log; see the command line customizer.
            if (log.isDebugEnabled) {
                log.debug("Injected ${entries.size} direnv variables into a Gradle execution in $workingDir")
            }
        } catch (e: Exception) {
            // Throwing here would fail the Gradle execution outright; a missing environment must not.
            log.warn("Failed to inject the direnv environment into a Gradle execution", e)
        }
    }

    /**
     * The platform calls configureSettings on the external-system task's background thread, so
     * loading on demand is safe here, like the terminal hook — and it matters: the first Gradle
     * sync of a freshly opened project usually beats the startup activity's cache warm. The
     * dispatch-thread check is a belt against that calling convention ever changing.
     */
    private fun cachedOrLoaded(project: Project, workingDir: Path): DirenvEnvironment? {
        val service = DirenvService.getInstance(project)
        service.cachedFor(workingDir)?.let { return it }
        if (ApplicationManager.getApplication().isDispatchThread) return null
        return runBlocking {
            service.load(workingDir)
            service.cachedFor(workingDir)
        }
    }

    /**
     * context.projectPath points at the linked Gradle project, which may sit below the IDE
     * project root with its own .envrc — cachedFor walks parents, so the nearest one wins.
     * The string is written in the project machine's syntax, so for a non-local project it must
     * not reach Paths.get: a WSL path degrades to drive-relative garbage instead of failing, and
     * DirenvMachine's platform mapping is the only safe reading there.
     */
    private fun resolveWorkingDirectory(context: GradleExecutionContext): Path? {
        val project = context.project
        if (DirenvMachine.isLocal(project)) {
            runCatching { Paths.get(context.projectPath) }.getOrNull()?.let { return it }
        }
        return DirenvMachine.projectDir(project)
    }
}
