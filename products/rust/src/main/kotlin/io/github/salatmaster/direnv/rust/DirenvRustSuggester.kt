package io.github.salatmaster.direnv.rust

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import io.github.salatmaster.direnv.DirenvMachine
import io.github.salatmaster.direnv.DirenvService
import io.github.salatmaster.direnv.DirenvState
import io.github.salatmaster.direnv.DirenvStateListener
import org.rust.cargo.project.settings.RustProjectSettingsService
import org.rust.cargo.toolchain.RsToolchainProvider
import java.nio.file.Files
import java.nio.file.Path

/**
 * Offers the Rust toolchain that direnv makes available, when it differs from the project's.
 *
 * The same gap as the JDK and Node: cargo run from a terminal finds the toolchain on `PATH`, while
 * the Rust plugin takes it from its own settings and reports none. A suggestion, never an automatic
 * change, for the same reason as theirs — a Nix store path can vanish after garbage collection.
 *
 * The Rust plugin's `toolchainProvider` extension point is no alternative: it turns a path already
 * chosen into a toolchain, and the choosing is the part that has to be left to the user.
 */
class DirenvRustSuggester : ProjectActivity {

    override suspend fun execute(project: Project) {
        project.messageBus.connect().subscribe(
            DirenvStateListener.TOPIC,
            object : DirenvStateListener {
                private var lastSuggested: Path? = null

                override fun stateChanged(state: DirenvState) {
                    if (state !is DirenvState.Loaded) return

                    val workingDir = DirenvMachine.projectDir(project) ?: return
                    // A Nix shell often carries cargo for some tool's sake, and the Rust plugin is
                    // installed in IDEA Ultimate for projects that are not Rust at all.
                    if (!Files.isRegularFile(workingDir.resolve("Cargo.toml"))) return

                    val environment = DirenvService.getInstance(project).cachedFor(workingDir) ?: return
                    val tools = DirenvRustToolchains.locate(
                        environment.entries,
                        DirenvMachine.toolchainMachine(project),
                    ) ?: return

                    val shared = DirenvRustToolchains.sharedDirectory(tools)
                    // ponytail: links are only made on this machine. For a project in WSL they would
                    // have to be made inside the distribution, so a toolchain spread across
                    // directories there is not offered at all; make them there if anyone needs it.
                    if (shared == null && !DirenvMachine.isLocal(project)) return
                    val directory = shared ?: DirenvRustToolchains.linkedDirectory(linkRoot(), tools)

                    if (project.service<RustProjectSettingsService>().toolchain?.location == directory) return
                    // Do not nag on every reload about a toolchain the user already declined.
                    if (lastSuggested == directory) return
                    lastSuggested = directory

                    suggest(project, directory, tools, linked = shared == null)
                }
            },
        )
    }

    /** Under the IDE's own directory: nothing is written into the project for the IDE's sake. */
    private fun linkRoot(): Path = PathManager.getSystemDir().resolve("direnv").resolve("rust-toolchains")

    private fun suggest(project: Project, directory: Path, tools: List<Path>, linked: Boolean) {
        val text = if (linked) {
            "direnv provides a Rust toolchain whose tools are in separate directories, which differs " +
                "from this project's. Using it links them into $directory."
        } else {
            "direnv provides a Rust toolchain at $directory, which differs from this project's."
        }
        NotificationGroupManager.getInstance()
            .getNotificationGroup("direnv")
            .createNotification(text, NotificationType.INFORMATION)
            .addAction(ApplyRustToolchainAction(project, directory, tools))
            .notify(project)
    }
}

private class ApplyRustToolchainAction(
    private val project: Project,
    private val directory: Path,
    private val tools: List<Path>,
) : AnAction("Use This Toolchain") {

    private val log = Logger.getInstance(ApplyRustToolchainAction::class.java)

    override fun actionPerformed(e: AnActionEvent) {
        try {
            // Only now, once the user has agreed, and never when the toolchain is merely found.
            DirenvRustToolchains.link(directory, tools)
            // The platform's own dispatch, so a directory in WSL becomes a WSL toolchain.
            val toolchain = RsToolchainProvider.getToolchain(directory) ?: return
            // A new toolchain makes the Rust plugin reload the Cargo projects by itself.
            project.service<RustProjectSettingsService>().modify { it.toolchain = toolchain }
        } catch (e: Throwable) {
            log.warn("Failed to apply the Rust toolchain provided by direnv", e)
        }
    }
}
