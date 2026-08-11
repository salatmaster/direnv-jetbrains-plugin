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

            val workingDir = commandLine.workingDirectory ?: return
            val project = resolveProject(workingDir) ?: return
            if (!DirenvGuard.mayRun(project)) return

            DirenvService.getInstance(project).cachedFor(workingDir)?.applyTo(environment)
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
