package io.github.salatmaster.direnv.ui

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import io.github.salatmaster.direnv.DirenvState
import io.github.salatmaster.direnv.DirenvStateListener

/**
 * Surfaces states the user has to act on.
 *
 * Only states that require a decision produce a notification: a blocked .envrc, a direnv failure,
 * or a missing executable. Successful loads stay silent and are visible in the status bar only —
 * a notification on every reload would be noise, and reloads happen automatically.
 */
class DirenvNotifier : ProjectActivity {

    override suspend fun execute(project: Project) {
        project.messageBus.connect().subscribe(
            DirenvStateListener.TOPIC,
            object : DirenvStateListener {
                private var lastNotified: DirenvState? = null

                override fun stateChanged(state: DirenvState) {
                    // Reloads repeat the same state often; notifying each time would be noise.
                    if (state == lastNotified) return
                    lastNotified = state

                    when (state) {
                        is DirenvState.Blocked -> DirenvNotifications.warn(
                            project,
                            "direnv: ${state.envrcPath} is blocked. Review it before allowing it.",
                            DirenvOpenEnvrcAction(),
                            DirenvAllowAction(),
                        )

                        is DirenvState.Failed -> DirenvNotifications.error(
                            project,
                            "direnv failed: ${state.message}",
                            DirenvReloadAction(),
                        )

                        is DirenvState.ExecutableMissing -> DirenvNotifications.warn(
                            project,
                            "direnv executable not found: ${state.executable}",
                            InstallDirenvAction(),
                        )

                        else -> Unit
                    }
                }
            },
        )
    }
}

private class InstallDirenvAction : AnAction("Installation Guide") {
    override fun actionPerformed(e: AnActionEvent) {
        BrowserUtil.browse("https://direnv.net/docs/installation.html")
    }
}
