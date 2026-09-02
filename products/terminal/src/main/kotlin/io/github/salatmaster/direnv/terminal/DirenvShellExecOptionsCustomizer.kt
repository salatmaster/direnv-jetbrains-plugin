package io.github.salatmaster.direnv.terminal

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.platform.eel.provider.asNioPath
import io.github.salatmaster.direnv.DirenvGuard
import io.github.salatmaster.direnv.DirenvMachine
import io.github.salatmaster.direnv.DirenvService
import kotlinx.coroutines.runBlocking
import org.jetbrains.plugins.terminal.startup.MutableShellExecOptions
import org.jetbrains.plugins.terminal.startup.ShellExecOptionsCustomizer
import java.nio.file.Path

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
     * The terminal reports its working directory as an EelPath, written in the syntax of the
     * machine the session runs on.
     *
     * Rendering it through `Paths.get` looked like it degraded gracefully and did not: for a WSL
     * session `/home/u/p` becomes a drive-relative `C:\home\u\p` instead of failing, so the
     * fallback below was unreachable and the lookup missed the cached environment every time. The
     * platform's own mapping is the only thing that can answer this.
     */
    private fun resolveWorkingDirectory(project: Project, options: MutableShellExecOptions): Path? =
        runCatching { options.workingDirectory.asNioPath() }.getOrNull()
            ?: DirenvMachine.projectDir(project)
}
