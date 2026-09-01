package io.github.salatmaster.direnv

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.platform.eel.path.EelPath
import com.intellij.platform.eel.provider.asNioPath
import com.intellij.platform.eel.provider.LocalEelDescriptor
import com.intellij.platform.eel.provider.getEelDescriptor
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

        val mapped = runCatching {
            EelPath.parse(basePath, project.getEelDescriptor()).asNioPath()
        }.getOrNull()

        if (mapped == null) {
            // Deliberately not falling back to Paths.get: that is what produced C:\home\... and an
            // error naming a directory that never existed. Reporting nothing loaded is honest.
            log.warn("Cannot map the project directory of a non-local project into a usable path")
        }
        return mapped
    }
}
