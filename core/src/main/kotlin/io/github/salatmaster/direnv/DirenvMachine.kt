package io.github.salatmaster.direnv

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.platform.eel.EelOsFamily
import com.intellij.platform.eel.path.EelPath
import com.intellij.platform.eel.provider.asEelPath
import com.intellij.platform.eel.provider.asNioPath
import com.intellij.platform.eel.provider.LocalEelDescriptor
import com.intellij.platform.eel.provider.getEelDescriptor
import io.github.salatmaster.direnv.direnv.DirenvPathMapper
import io.github.salatmaster.direnv.direnv.EelDirenvPathMapper
import io.github.salatmaster.direnv.toolchain.ToolchainMachine
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Where a project's files actually live, and how to name its directory in this JVM.
 *
 * A project can sit on a machine that is not the one running the IDE — WSL above all, but also a
 * remote host. The plugin has to know, because both the path syntax and the direnv binary belong to
 * that other machine.
 */
object DirenvMachine {

    private val log = Logger.getInstance(DirenvMachine::class.java)

    /**
     * True when the project's files are on the machine the IDE itself runs on.
     *
     * Compared against the local descriptor rather than asked of `EelPathUtils.isProjectLocal`,
     * which reads better and is marked `@ApiStatus.Internal` — the Plugin Verifier fails the build
     * over it. Any failure answers "local", so a project that behaves as it always did cannot be
     * broken by this.
     */
    fun isLocal(project: Project): Boolean =
        runCatching { project.getEelDescriptor() === LocalEelDescriptor }.getOrDefault(true)

    /** Translates the paths direnv reports for [project], and the ones it is given as arguments. */
    fun pathMapper(project: Project): DirenvPathMapper =
        if (isLocal(project)) DirenvPathMapper.SameMachine else EelDirenvPathMapper(project)

    /** How to read the paths inside an environment direnv produced for [project]. */
    fun toolchainMachine(project: Project): ToolchainMachine {
        if (isLocal(project)) return ToolchainMachine.Local
        val descriptor = runCatching { project.getEelDescriptor() }.getOrNull()
            ?: return ToolchainMachine.Local
        val mapper = pathMapper(project)
        return ToolchainMachine(
            isWindows = descriptor.osFamily == EelOsFamily.Windows,
            toPath = mapper::toLocal,
        )
    }

    /** How to name the machine [project] lives on in a log line or a message shown to the user. */
    fun name(project: Project): String = when {
        isLocal(project) -> "this machine"
        else -> runCatching { project.getEelDescriptor().name }
            .getOrDefault("the machine this project lives on")
    }

    /**
     * The project directory as a path this JVM can actually use.
     *
     * `project.basePath` is a string in the project's *own* path syntax. For a project in WSL that
     * is a POSIX path, and `Paths.get("/home/u/p")` on Windows quietly yields a drive-relative
     * `C:\home\u\p` rather than failing — which is the nonsense working directory reported in #21.
     * Only the platform's own mapping can translate one into the other, so for a non-local project
     * that is what is used; a local project keeps taking the direct route it always did.
     */
    fun projectDir(project: Project): Path? {
        val basePath = project.basePath ?: return null
        if (isLocal(project)) return runCatching { Paths.get(basePath) }.getOrNull()

        val descriptor = runCatching { project.getEelDescriptor() }.getOrNull() ?: return null

        // basePath may already be written the way this JVM names files over there — a
        // \\wsl.localhost\... path, which is what the IDE records for a project opened by that
        // name. Reading it as a POSIX path would not fail, it would silently produce
        // /wsl.localhost/NixOS/home/u/p, so the local spelling is tried first and accepted only
        // when the platform agrees it names the machine this project lives on. On Windows a POSIX
        // path cannot be mistaken for one: Paths.get("/home/u/p") there is not absolute.
        runCatching { Paths.get(basePath) }.getOrNull()
            ?.takeIf { runCatching { it.asEelPath().descriptor }.getOrNull() == descriptor }
            ?.let { return it }

        val mapped = runCatching { EelPath.parse(basePath, descriptor).asNioPath() }.getOrNull()

        if (mapped == null) {
            // Deliberately not falling back to Paths.get: that is what produced C:\home\... and an
            // error naming a directory that never existed. Reporting nothing loaded is honest.
            log.warn("Cannot map the project directory of a non-local project into a usable path")
        }
        return mapped
    }
}
