package io.github.salatmaster.direnv.terminal

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import io.github.salatmaster.direnv.DirenvGuard
import io.github.salatmaster.direnv.DirenvService
import kotlinx.coroutines.runBlocking
import org.jetbrains.plugins.terminal.startup.MutableShellExecOptions
import org.jetbrains.plugins.terminal.startup.ShellExecOptionsCustomizer
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Applies the direnv environment to terminal sessions.
 *
 * Required in addition to the command line customizer: LocalTerminalDirectRunner starts the shell
 * through the EEL API or PtyProcessBuilder, never through GeneralCommandLine, so the catch-all
 * extension point does not see terminal sessions at all. This is also why the plugin requires
 * 2026.1 — shellExecOptionsCustomizer is the first terminal hook that works across EEL boundaries.
 *
 * The platform calls this on a background thread without a read action, so loading on demand is
 * safe here, unlike in the command line customizer.
 */
@Suppress("UnstableApiUsage")
class DirenvShellExecOptionsCustomizer : ShellExecOptionsCustomizer {

    private val log = Logger.getInstance(DirenvShellExecOptionsCustomizer::class.java)

    override fun customizeExecOptions(project: Project, shellExecOptions: MutableShellExecOptions) {
        try {
            if (!DirenvGuard.mayRun(project)) return

            val workingDir = resolveWorkingDirectory(project, shellExecOptions) ?: return
            val service = DirenvService.getInstance(project)

            val environment = service.cachedFor(workingDir)
                ?: runBlocking {
                    service.load(workingDir)
                    service.cachedFor(workingDir)
                }
                ?: return

            DirenvTerminalEnvironmentApplier.apply(environment) { name, value ->
                // A null value removes the variable; see DirenvTerminalEnvironmentApplier.
                shellExecOptions.setEnvironmentVariable(name, value)
            }
        } catch (e: Exception) {
            // A failure here must not stop the terminal from opening.
            log.warn("Failed to customize terminal environment", e)
        }
    }

    /**
     * The terminal reports its working directory as an EelPath, which is expressed in the target
     * environment's format. Rendering it back to a local path works for local sessions; for remote
     * or WSL sessions it may not resolve on the host, so the project base path is used as fallback.
     */
    private fun resolveWorkingDirectory(project: Project, options: MutableShellExecOptions): Path? {
        val fromOptions = runCatching { Paths.get(options.workingDirectory.toString()) }.getOrNull()
        if (fromOptions != null) return fromOptions
        return project.basePath?.let { runCatching { Paths.get(it) }.getOrNull() }
    }
}
