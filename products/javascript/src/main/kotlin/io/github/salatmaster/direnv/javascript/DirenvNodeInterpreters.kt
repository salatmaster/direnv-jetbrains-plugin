package io.github.salatmaster.direnv.javascript

import com.intellij.javascript.nodejs.interpreter.NodeJsInterpreter
import com.intellij.javascript.nodejs.interpreter.local.NodeJsLocalInterpreter
import com.intellij.javascript.nodejs.interpreter.wsl.WslNodeInterpreter
import java.nio.file.Path

/** A path inside WSL, split the way [WslNodeInterpreter] wants it. */
data class DirenvWslLocation(val distributionId: String, val linuxPath: String)

/**
 * Builds the kind of Node interpreter that names a binary on the machine it actually lives on.
 *
 * [NodeJsLocalInterpreter] is a path on the machine the IDE runs on, and nothing about it survives
 * pointing it at `\\wsl.localhost\...`: the IDE would start an ELF binary as a Windows process. The
 * IDE has [WslNodeInterpreter] for that, which wants the distribution and the POSIX path rather
 * than the UNC path the plugin resolves — the same file, named the way its own machine names it.
 *
 * Kept apart from the suggester because `WslPath.parseWindowsUncPath` answers null on any operating
 * system without WSL, so the branch that matters cannot be reached on the machine this is built on.
 * Splitting the decision from the parsing is what makes it testable at all.
 */
object DirenvNodeInterpreters {

    /**
     * @param local whether the project's files are on the machine the IDE runs on.
     * @param path the interpreter, named the way this JVM names it.
     * @param wsl where [path] lives inside WSL, or null when it does not live inside WSL.
     * @return null for a machine the IDE has no Node interpreter type for — a remote host, say.
     *   Offering a local interpreter there would configure the project with one that cannot start,
     *   which is worse than offering nothing.
     */
    fun interpreterFor(local: Boolean, path: Path, wsl: DirenvWslLocation?): NodeJsInterpreter? = when {
        local -> NodeJsLocalInterpreter(path.toString())
        wsl != null -> WslNodeInterpreter(wsl.distributionId, wsl.linuxPath)
        else -> null
    }

    /** How to name the interpreter in a message, without asking WSL anything about itself. */
    fun describe(path: Path, wsl: DirenvWslLocation?): String =
        if (wsl == null) path.toString() else "${wsl.linuxPath} in ${wsl.distributionId}"
}
