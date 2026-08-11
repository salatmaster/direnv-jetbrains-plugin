package io.github.salatmaster.direnv.ui

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.EditorNotificationPanel
import com.intellij.ui.EditorNotificationProvider
import io.github.salatmaster.direnv.DirenvGuard
import io.github.salatmaster.direnv.DirenvService
import io.github.salatmaster.direnv.DirenvState
import java.nio.file.Paths
import java.util.function.Function
import javax.swing.JComponent

/**
 * Shows a banner over a blocked `.envrc`.
 *
 * This is the moment approval actually makes sense: the user is looking at the code they are about
 * to authorise. The plugin never approves an `.envrc` anywhere else without an explicit action.
 */
class DirenvEditorBanner : EditorNotificationProvider {

    override fun collectNotificationData(
        project: Project,
        file: VirtualFile,
    ): Function<in FileEditor, out JComponent?>? {
        if (file.name != ENVRC) return null
        if (!DirenvGuard.mayRun(project)) return null

        val state = DirenvService.getInstance(project).state()
        val (envrcPath, message) = when (state) {
            is DirenvState.Blocked ->
                state.envrcPath to "This .envrc is blocked. direnv will not run it until you allow it."

            is DirenvState.Denied ->
                state.envrcPath to
                    "Approval for this .envrc was revoked. direnv will not run it until you allow it again."

            else -> return null
        }

        // Only warn about the file the user is actually looking at.
        val blockedPath = runCatching { Paths.get(envrcPath).toAbsolutePath().normalize() }.getOrNull()
        val openedPath = runCatching { file.toNioPath().toAbsolutePath().normalize() }.getOrNull()
        if (blockedPath != null && openedPath != null && blockedPath != openedPath) return null

        return Function { _ ->
            EditorNotificationPanel(EditorNotificationPanel.Status.Warning).apply {
                text = message
                createActionLabel("Allow") {
                    val workingDir = projectWorkingDir(project) ?: return@createActionLabel
                    val envrc = blockedPath ?: return@createActionLabel
                    DirenvService.getInstance(project).scheduleAllow(envrc, workingDir)
                }
            }
        }
    }

    private companion object {
        const val ENVRC = ".envrc"
    }
}
