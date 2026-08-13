package io.github.salatmaster.direnv.ui

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import io.github.salatmaster.direnv.DirenvGuard
import io.github.salatmaster.direnv.DirenvService
import io.github.salatmaster.direnv.DirenvState
import io.github.salatmaster.direnv.ENVRC_FILE_NAME
import java.nio.file.Path
import java.nio.file.Paths

internal object DirenvNotifications {

    private const val GROUP_ID = "direnv"

    fun warn(project: Project, content: String, vararg actions: AnAction) =
        notify(project, content, NotificationType.WARNING, *actions)

    fun error(project: Project, content: String, vararg actions: AnAction) =
        notify(project, content, NotificationType.ERROR, *actions)

    private fun notify(project: Project, content: String, type: NotificationType, vararg actions: AnAction) {
        val notification = NotificationGroupManager.getInstance()
            .getNotificationGroup(GROUP_ID)
            .createNotification(content, type)
        actions.forEach { notification.addAction(it) }
        notification.notify(project)
    }
}

/** Shared helper: the directory whose environment the UI is talking about. */
internal fun projectWorkingDir(project: Project): Path? =
    project.basePath?.let { runCatching { Paths.get(it) }.getOrNull() }

/** The .envrc the UI would act on, or null when direnv has not named one. */
internal fun envrcFor(project: Project): Path? {
    val workingDir = projectWorkingDir(project) ?: return null
    return DirenvService.getInstance(project).envrcPathFor(workingDir)
}

class DirenvReloadAction : AnAction("Reload direnv Environment") {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project?.let { DirenvGuard.mayRun(it) } == true
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val workingDir = projectWorkingDir(project) ?: return
        DirenvService.getInstance(project).scheduleReload(workingDir)
    }
}

/**
 * Approves the current .envrc.
 *
 * This is the only path to `direnv allow` in the entire plugin, and it exists solely as an explicit
 * user action.
 */
class DirenvAllowAction : AnAction("Allow This .envrc") {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        if (project == null || !DirenvGuard.mayRun(project) || envrcFor(project) == null) {
            e.presentation.isEnabledAndVisible = false
            return
        }
        // Visible but inert once the file is approved. Approving it again would only re-run
        // direnv, which is what Reload is for, and an entry that comes and goes shifts every row
        // below it — in this menu that would put Block where the user aimed at Show.
        e.presentation.isVisible = true
        e.presentation.isEnabled = DirenvService.getInstance(project).state().needsApproval
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val envrc = envrcFor(project) ?: return
        val workingDir = projectWorkingDir(project) ?: return
        DirenvService.getInstance(project).scheduleAllow(envrc, workingDir)
    }
}

class DirenvBlockAction : AnAction("Block This .envrc") {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        if (project == null || !DirenvGuard.mayRun(project)) {
            e.presentation.isEnabled = false
            return
        }
        // Requires a file to revoke: without that check the entry offered itself in projects with
        // no .envrc at all, and clicking it did nothing and said nothing. Already-revoked
        // approval is left inert rather than hidden, matching Allow.
        e.presentation.isEnabled =
            envrcFor(project) != null && DirenvService.getInstance(project).state() !is DirenvState.Denied
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val workingDir = projectWorkingDir(project) ?: return
        val envrc = envrcFor(project) ?: return
        DirenvService.getInstance(project).scheduleBlock(envrc, workingDir)
    }
}

/** Opens the .envrc so the user can read it before deciding to approve. */
class DirenvOpenEnvrcAction : AnAction("Open .envrc") {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val workingDir = projectWorkingDir(project) ?: return
        val service = DirenvService.getInstance(project)

        val envrc = service.envrcPathFor(workingDir)
            ?: (service.state() as? DirenvState.Blocked)?.let { Paths.get(it.envrcPath) }
            ?: workingDir.resolve(ENVRC_FILE_NAME)

        val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(envrc)
        if (file == null) {
            DirenvNotifications.warn(project, "No .envrc found at $envrc")
            return
        }
        FileEditorManager.getInstance(project).openFile(file, true)
    }
}
