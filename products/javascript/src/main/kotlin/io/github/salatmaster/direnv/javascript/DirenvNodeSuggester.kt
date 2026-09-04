package io.github.salatmaster.direnv.javascript

import com.intellij.execution.wsl.WslPath
import com.intellij.javascript.nodejs.interpreter.NodeJsInterpreter
import com.intellij.javascript.nodejs.interpreter.NodeJsInterpreterManager
import com.intellij.javascript.nodejs.interpreter.NodeJsInterpreterRef
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import io.github.salatmaster.direnv.DirenvMachine
import io.github.salatmaster.direnv.DirenvService
import io.github.salatmaster.direnv.DirenvState
import io.github.salatmaster.direnv.DirenvStateListener
import io.github.salatmaster.direnv.toolchain.ToolchainCandidateResolver
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
                private var lastSuggested: String? = null

                override fun stateChanged(state: DirenvState) {
                    if (state !is DirenvState.Loaded) return

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
                    // to the binary, and there is no NODE_HOME convention to look at first. The
                    // environment was produced on the machine the project lives on, so its PATH
                    // follows that machine's conventions rather than this one's.
                    val machine = DirenvMachine.toolchainMachine(project)
                    val candidate = ToolchainCandidateResolver.resolveExecutable(
                        entries = environment.entries,
                        executable = machine.executable("node"),
                        machine = machine,
                    ) ?: return

                    val wsl = wslLocationOf(candidate)
                    val interpreter = DirenvNodeInterpreters.interpreterFor(
                        local = DirenvMachine.isLocal(project),
                        path = candidate,
                        wsl = wsl,
                    ) ?: return

                    if (isAlreadyConfigured(project, interpreter)) return
                    // Do not nag on every reload about an interpreter the user already declined.
                    if (lastSuggested == interpreter.referenceName) return
                    lastSuggested = interpreter.referenceName

                    suggest(project, interpreter, DirenvNodeInterpreters.describe(candidate, wsl))
                }
            },
        )
    }

    /**
     * Where [path] lives inside WSL, or null when it does not — which includes every operating
     * system that has no WSL, where the platform answers null without looking at the path.
     */
    private fun wslLocationOf(path: Path): DirenvWslLocation? =
        WslPath.parseWindowsUncPath(path.toString())
            ?.let { DirenvWslLocation(it.distributionId, it.linuxPath) }

    /**
     * True when the project already points at this exact interpreter.
     *
     * Compared by reference name, which is what the platform itself uses for interpreter identity
     * — and the only comparison that works now that the answer can be a WSL interpreter as well as
     * a local one. Anything else the project may be configured with is a difference worth
     * reporting, which is what this did before as well, whatever its comment claimed.
     */
    private fun isAlreadyConfigured(project: Project, candidate: NodeJsInterpreter): Boolean =
        NodeJsInterpreterManager.getInstance(project).interpreter?.referenceName == candidate.referenceName

    private fun suggest(project: Project, interpreter: NodeJsInterpreter, description: String) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("direnv")
            .createNotification(
                "direnv provides Node at $description, which differs from this project's interpreter.",
                NotificationType.INFORMATION,
            )
            .addAction(ApplyNodeInterpreterAction(project, interpreter))
            .notify(project)
    }
}

private class ApplyNodeInterpreterAction(
    private val project: Project,
    private val interpreter: NodeJsInterpreter,
) : AnAction("Use This Node Interpreter") {

    private val log = Logger.getInstance(ApplyNodeInterpreterAction::class.java)

    override fun actionPerformed(e: AnActionEvent) {
        try {
            // Nothing has to be registered anywhere first: a reference resolves by name, and the
            // WSL interpreter manager builds the interpreter back out of it on demand.
            NodeJsInterpreterManager.getInstance(project)
                .setInterpreterRef(NodeJsInterpreterRef.create(interpreter))
        } catch (e: Throwable) {
            log.warn("Failed to apply the Node interpreter provided by direnv", e)
        }
    }
}
