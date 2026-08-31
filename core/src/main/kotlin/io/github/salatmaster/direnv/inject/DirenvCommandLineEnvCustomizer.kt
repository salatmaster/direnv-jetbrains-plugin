package io.github.salatmaster.direnv.inject

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CommandLineEnvCustomizer
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.roots.ProjectRootManager
import io.github.salatmaster.direnv.DirenvGuard
import io.github.salatmaster.direnv.DirenvService
import io.github.salatmaster.direnv.direnv.DirenvInternalMarker
import io.github.salatmaster.direnv.project.DirenvProjectResolver
import io.github.salatmaster.direnv.project.ProjectRoots
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Injects the direnv environment into every process started through [GeneralCommandLine].
 *
 * `GeneralCommandLine.setupEnvironment()` invokes this, so one registration covers run/debug
 * configurations of every language, the JPS build process, Gradle sync and Maven (which reach
 * GeneralCommandLine through LocalTargetEnvironment), External Tools, and processes started by
 * third-party plugins.
 *
 * The terminal is NOT covered here: it starts its shell through the EEL API or PtyProcessBuilder,
 * bypassing GeneralCommandLine entirely. See DirenvShellExecOptionsCustomizer.
 *
 * Called synchronously at process start, possibly on the EDT and possibly under a read lock, so it
 * serves an already-populated cache and never triggers a load. Warming the cache is the startup
 * activity's job.
 */
@Suppress("UnstableApiUsage")
class DirenvCommandLineEnvCustomizer : CommandLineEnvCustomizer {

    private val log = Logger.getInstance(DirenvCommandLineEnvCustomizer::class.java)

    override fun customizeEnv(commandLine: GeneralCommandLine, environment: MutableMap<String, String>) {
        try {
            // Must be first: this is what stops us recursing into our own direnv invocation.
            if (DirenvInternalMarker.isMarked(commandLine)) return

            // Every skip below is reported. Injection failing is invisible by nature — the process
            // simply starts without the variables — and there are four separate reasons for it, so
            // without this a report of "my run configuration sees nothing" cannot be told apart
            // from any of the others. The lines are debug-level because this runs for every process
            // the IDE starts; see the README for turning the category on.
            val workingDir = commandLine.workingDirectory
            if (workingDir == null) {
                if (log.isDebugEnabled) log.debug("Not injecting: the command line has no working directory")
                return
            }

            val project = resolveProject(workingDir)
            if (project == null) {
                if (log.isDebugEnabled) {
                    log.debug("Not injecting into a process in $workingDir: no open project contains it")
                }
                return
            }

            if (!DirenvGuard.mayRun(project)) {
                if (log.isDebugEnabled) {
                    log.debug(
                        "Not injecting into a process in $workingDir: " +
                            "direnv is off or the project is untrusted"
                    )
                }
                return
            }

            val loaded = DirenvService.getInstance(project).cachedFor(workingDir)
            if (loaded == null) {
                if (log.isDebugEnabled) {
                    log.debug("Not injecting into a process in $workingDir: no environment is loaded for it")
                }
                return
            }

            loaded.applyTo(environment)
            // Count only. Names and values both stay out of the log: direnv output is routinely
            // secret, and a name alone can disclose which service a project talks to.
            if (log.isDebugEnabled) {
                log.debug("Injected ${loaded.entries.size} direnv variables into a process in $workingDir")
            }
        } catch (e: Exception) {
            // Throwing here would break process launch for the entire IDE, so failures are contained.
            log.warn("Failed to customize environment", e)
        }
    }

    private fun resolveProject(workingDir: Path): Project? {
        val projectManager = ProjectManager.getInstanceIfCreated() ?: return null
        val openProjects = projectManager.openProjects.filterNot { it.isDisposed }
        if (openProjects.isEmpty()) return null

        val candidates = openProjects.mapNotNull { project ->
            val roots = contentRootsOf(project)
            if (roots.isEmpty()) null else ProjectRoots(project.locationHash, roots)
        }

        val id = DirenvProjectResolver.resolve(workingDir, candidates) ?: return null
        return openProjects.firstOrNull { it.locationHash == id }
    }

    private fun contentRootsOf(project: Project): List<Path> {
        val roots = mutableListOf<Path>()

        // basePath covers the common case and requires no read action.
        project.basePath?.let { base ->
            runCatching { Paths.get(base) }.getOrNull()?.let { roots.add(it) }
        }

        runCatching {
            ProjectRootManager.getInstance(project).contentRoots.forEach { root ->
                runCatching { Paths.get(root.path) }.getOrNull()?.let { roots.add(it) }
            }
        }
        return roots.distinct()
    }
}
