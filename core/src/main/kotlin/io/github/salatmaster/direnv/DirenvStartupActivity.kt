package io.github.salatmaster.direnv

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import io.github.salatmaster.direnv.settings.DirenvSettings
import java.nio.file.Paths

/**
 * Warms the environment cache when a project opens.
 *
 * The command line customizer serves cache only and never loads on demand, because it runs
 * synchronously at process start and may be on the EDT. Without this warm-up, the first process
 * a user launches after opening a project would run without the direnv environment.
 */
class DirenvStartupActivity : ProjectActivity {

    override suspend fun execute(project: Project) {
        if (!DirenvSettings.getInstance(project).state.autoLoad) return
        if (!DirenvGuard.mayRun(project)) return

        val basePath = project.basePath ?: return
        val workingDir = runCatching { Paths.get(basePath) }.getOrNull() ?: return

        DirenvService.getInstance(project).load(workingDir)
    }
}
