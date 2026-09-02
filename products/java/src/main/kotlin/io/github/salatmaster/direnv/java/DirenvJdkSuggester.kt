package io.github.salatmaster.direnv.java

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.JavaSdk
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.util.io.FileUtil
import io.github.salatmaster.direnv.DirenvMachine
import io.github.salatmaster.direnv.DirenvService
import io.github.salatmaster.direnv.DirenvState
import io.github.salatmaster.direnv.DirenvStateListener
import io.github.salatmaster.direnv.toolchain.ToolchainCandidateResolver
import java.io.File
import java.nio.file.Path

/**
 * Offers the JDK that direnv makes available, when it differs from the project SDK.
 *
 * Deliberately a suggestion rather than an automatic change. In Nix and Devbox setups the path can
 * disappear after garbage collection, and silently rewriting project configuration at that moment
 * would leave the project broken with no explanation. This addresses the requests in IJPL-11588
 * from users whose JDK is only reachable through direnv.
 */
class DirenvJdkSuggester : ProjectActivity {

    override suspend fun execute(project: Project) {
        project.messageBus.connect().subscribe(
            DirenvStateListener.TOPIC,
            object : DirenvStateListener {
                private var lastSuggested: Path? = null

                override fun stateChanged(state: DirenvState) {
                    if (state !is DirenvState.Loaded) return

                    val workingDir = DirenvMachine.projectDir(project) ?: return
                    val environment = DirenvService.getInstance(project).cachedFor(workingDir) ?: return

                    // The environment was produced on the machine the project lives on, so the
                    // conventions its PATH follows are that machine's, not this one's.
                    val machine = DirenvMachine.toolchainMachine(project)
                    val candidate = ToolchainCandidateResolver.resolve(
                        entries = environment.entries,
                        homeVariable = "JAVA_HOME",
                        executable = machine.executable("java"),
                        machine = machine,
                    ) ?: return

                    val current = ProjectRootManager.getInstance(project).projectSdk?.homePath
                    if (current != null && FileUtil.filesEqual(File(current), candidate.toFile())) return
                    // Do not nag on every reload about a JDK the user already declined.
                    if (lastSuggested == candidate) return
                    lastSuggested = candidate

                    suggest(project, candidate)
                }
            },
        )
    }

    private fun suggest(project: Project, home: Path) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("direnv")
            .createNotification(
                "direnv provides a JDK at $home, which differs from this project's SDK.",
                NotificationType.INFORMATION,
            )
            .addAction(ApplyJdkAction(project, home))
            .notify(project)
    }
}

private class ApplyJdkAction(private val project: Project, private val home: Path) :
    AnAction("Use This JDK") {

    private val log = Logger.getInstance(ApplyJdkAction::class.java)

    override fun actionPerformed(e: AnActionEvent) {
        try {
            WriteAction.runAndWait<Throwable> {
                val javaSdk = JavaSdk.getInstance()
                val name = javaSdk.suggestSdkName("direnv", home.toString())
                val table = ProjectJdkTable.getInstance()

                val sdk = javaSdk.createJdk(name, home.toString(), false)
                table.findJdk(name)?.let { table.removeJdk(it) }
                table.addJdk(sdk)

                ProjectRootManager.getInstance(project).projectSdk = sdk
            }
        } catch (e: Throwable) {
            log.warn("Failed to apply the JDK provided by direnv", e)
        }
    }
}
