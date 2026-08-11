package io.github.salatmaster.direnv

import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import io.github.salatmaster.direnv.settings.DirenvSettings

/**
 * The single authority on whether direnv may run for a project.
 *
 * An .envrc is arbitrary shell code, so merely opening an untrusted project must never execute it.
 * Centralising the check here means it cannot be forgotten at one of the several call sites that
 * can trigger a load.
 */
object DirenvGuard {

    private val LOG = Logger.getInstance(DirenvGuard::class.java)

    fun mayRun(project: Project): Boolean {
        if (project.isDisposed) return false
        if (!DirenvSettings.getInstance(project).state.enabled) return false

        if (!TrustedProjects.isProjectTrusted(project)) {
            LOG.info("Skipping direnv: project is not trusted")
            return false
        }
        return true
    }
}
