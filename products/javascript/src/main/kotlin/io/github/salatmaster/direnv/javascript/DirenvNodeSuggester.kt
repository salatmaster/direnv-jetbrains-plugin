package io.github.salatmaster.direnv.javascript

import com.intellij.javascript.nodejs.interpreter.NodeJsInterpreterManager
import com.intellij.javascript.nodejs.interpreter.NodeJsInterpreterRef
import com.intellij.javascript.nodejs.interpreter.local.NodeJsLocalInterpreter
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.util.io.FileUtil
import io.github.salatmaster.direnv.DirenvMachine
import io.github.salatmaster.direnv.DirenvService
import io.github.salatmaster.direnv.DirenvState
import io.github.salatmaster.direnv.DirenvStateListener
import io.github.salatmaster.direnv.toolchain.ToolchainCandidateResolver
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * Offers the Node interpreter that direnv makes available, when it differs from the project's.
 *
 * Injecting `PATH` into launched processes is not enough for Node. The IDE resolves the interpreter
 * from its own settings rather than from `PATH`, so inspections, the package.json tooling and the
 * JavaScript Runtime page all keep reporting Node as missing while a terminal in the same project
 * finds it. That is the gap this closes, and it is the same one [io.github.salatmaster.direnv.java]
 * closes for the JDK.
 *
 * A suggestion, never an automatic change, for the same reason as the JDK: a Nix store path can
 * disappear after garbage collection, and rewriting project settings at that moment would leave the
 * project broken with no explanation of what changed it.
 */
class DirenvNodeSuggester : ProjectActivity {

    override suspend fun execute(project: Project) {
        project.messageBus.connect().subscribe(
            DirenvStateListener.TOPIC,
            object : DirenvStateListener {
                private var lastSuggested: Path? = null

                override fun stateChanged(state: DirenvState) {
                    if (state !is DirenvState.Loaded) return

                    // NodeJsLocalInterpreter names a binary on the machine the IDE runs on, and
                    // WebStorm has a separate interpreter type for one inside WSL. Handing it a
                    // \\wsl.localhost\... path would configure the project with an interpreter
                    // that cannot be started, so a project whose files live elsewhere is left alone
                    // until that type is wired up.
                    if (!DirenvMachine.isLocal(project)) return

                    val workingDir = DirenvMachine.projectDir(project) ?: return
                    // A Nix shell routinely provides node for tooling alone, so without this every
                    // Java project whose .envrc happens to pull in nodejs would be offered an
                    // interpreter it has no use for.
                    //
                    // Deliberately not "an interpreter is already configured" as the signal: the
                    // IDE auto-detects one from the system PATH, so that is non-null in projects
                    // with no JavaScript in them at all and would suppress nothing.
                    if (!Files.isRegularFile(workingDir.resolve("package.json"))) return

                    val environment = DirenvService.getInstance(project).cachedFor(workingDir) ?: return

                    // The executable itself, not a toolchain home: Node is configured by the path
                    // to the binary, and there is no NODE_HOME convention to look at first.
                    val machine = DirenvMachine.toolchainMachine(project)
                    val candidate = ToolchainCandidateResolver.resolveExecutable(
                        entries = environment.entries,
                        executable = machine.executable("node"),
                        machine = machine,
                    ) ?: return

                    if (isAlreadyConfigured(project, candidate)) return
                    // Do not nag on every reload about an interpreter the user already declined.
                    if (lastSuggested == candidate) return
                    lastSuggested = candidate

                    suggest(project, candidate)
                }
            },
        )
    }

    /**
     * True when the project already points at this interpreter.
     *
     * Only a local interpreter can be compared by path; a project configured against a remote or
     * WSL one is deliberately left alone rather than being offered a local replacement for it.
     */
    private fun isAlreadyConfigured(project: Project, candidate: Path): Boolean {
        val current = NodeJsInterpreterManager.getInstance(project).interpreter as? NodeJsLocalInterpreter
        val currentPath = current?.interpreterSystemDependentPath ?: return false
        return FileUtil.filesEqual(File(currentPath), candidate.toFile())
    }

    private fun suggest(project: Project, interpreter: Path) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("direnv")
            .createNotification(
                "direnv provides Node at $interpreter, which differs from this project's interpreter.",
                NotificationType.INFORMATION,
            )
            .addAction(ApplyNodeInterpreterAction(project, interpreter))
            .notify(project)
    }
}

private class ApplyNodeInterpreterAction(private val project: Project, private val interpreter: Path) :
    AnAction("Use This Node Interpreter") {

    private val log = Logger.getInstance(ApplyNodeInterpreterAction::class.java)

    override fun actionPerformed(e: AnActionEvent) {
        try {
            val local = NodeJsLocalInterpreter(interpreter.toString())
            NodeJsInterpreterManager.getInstance(project).setInterpreterRef(NodeJsInterpreterRef.create(local))
        } catch (e: Throwable) {
            log.warn("Failed to apply the Node interpreter provided by direnv", e)
        }
    }
}
