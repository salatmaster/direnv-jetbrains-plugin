package io.github.salatmaster.direnv.gradle

import io.github.salatmaster.direnv.direnv.DirenvPathMapper
import java.nio.file.Path

/**
 * Decides which directory a Gradle execution's environment should be looked up for.
 *
 * The linked Gradle project may sit below the IDE project root and carry its own `.envrc`, so its
 * path is preferred — the environment cache walks parents, and the nearest `.envrc` wins.
 *
 * That path is a string written in the syntax of the machine the project lives on, which is why it
 * goes through the mapper rather than through `Paths.get`: a WSL path handed to `Paths.get` on
 * Windows degrades to drive-relative nonsense instead of failing, which is the whole of issue #21.
 *
 * Kept separate from the extension point so this can be tested without constructing a
 * GradleExecutionContext, in the one case that cannot be exercised on the machine this is built on.
 */
object DirenvGradleWorkingDirectory {

    /**
     * [projectRoot] is the fallback for a path that cannot be expressed here at all — better the
     * project's own environment than none.
     */
    fun resolve(mapper: DirenvPathMapper, projectPath: String, projectRoot: Path?): Path? =
        mapper.toLocal(projectPath) ?: projectRoot
}
