package io.github.salatmaster.direnv.go

import com.goide.sdk.GoSdk
import com.goide.sdk.GoSdkService
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.vfs.VfsUtilCore
import io.github.salatmaster.direnv.DirenvMachine
import io.github.salatmaster.direnv.DirenvService
import io.github.salatmaster.direnv.DirenvState
import io.github.salatmaster.direnv.DirenvStateListener
import java.nio.file.Files

/**
 * Offers the GOROOT direnv provides, when it differs from the project's.
 *
 * GoLand starts every go command — module sync, run, test, gofmt — from the GOROOT in its own
 * settings and ignores `PATH`, so a terminal and the IDE build the same project with different Go.
 * With devenv it is worse than different: devenv exports GOROOT, the environment injected into those
 * commands carries it, and GoLand's go binary then runs against another release's GOROOT.
 *
 * A suggestion, never an automatic change, for the same reason as the JDK, Node and Rust: a Nix
 * store path can vanish after garbage collection.
 */
class DirenvGoSuggester : ProjectActivity {

    override suspend fun execute(project: Project) {
        project.messageBus.connect().subscribe(
            DirenvStateListener.TOPIC,
            object : DirenvStateListener {
                // Do not nag on every reload about a GOROOT the user already declined.
                private var lastSuggested: String? = null

                override fun stateChanged(state: DirenvState) {
                    if (state !is DirenvState.Loaded) return

                    val workingDir = DirenvMachine.projectDir(project) ?: return
                    // A Nix shell often carries go for some tool's sake, and the Go plugin is
                    // installed in IDEA Ultimate for projects that are not Go at all.
                    if (GO_PROJECT_FILES.none { Files.isRegularFile(workingDir.resolve(it)) }) return

                    val environment = DirenvService.getInstance(project).cachedFor(workingDir) ?: return
                    val root = DirenvGoRoots.locate(environment.entries, DirenvMachine.toolchainMachine(project))
                        ?: return

                    // GoLand's own test of a GOROOT, which also turns down a store path that garbage
                    // collection has removed.
                    val sdk = GoSdk.fromHomePath(root.toString())
                    if (!sdk.isValid) return

                    if (GoSdkService.getInstance(project).getSdk(null).homeUrl == sdk.homeUrl) return
                    if (lastSuggested == sdk.homeUrl) return
                    lastSuggested = sdk.homeUrl

                    NotificationGroupManager.getInstance()
                        .getNotificationGroup("direnv")
                        .createNotification(
                            "direnv provides Go ${sdk.version} at ${VfsUtilCore.urlToPath(sdk.homeUrl)}, which " +
                                "differs from this project's GOROOT.",
                            NotificationType.INFORMATION,
                        )
                        .addAction(ApplyGoRootAction(project, sdk))
                        .notify(project)
                }
            },
        )
    }

    private companion object {
        val GO_PROJECT_FILES = listOf("go.mod", "go.work")
    }
}

private class ApplyGoRootAction(private val project: Project, private val sdk: GoSdk) : AnAction("Use This GOROOT") {

    override fun actionPerformed(e: AnActionEvent) {
        // On the EDT, which setSdk insists on; it re-indexes the SDK and updates the libraries itself.
        GoSdkService.getInstance(project).setSdk(sdk)
    }
}
