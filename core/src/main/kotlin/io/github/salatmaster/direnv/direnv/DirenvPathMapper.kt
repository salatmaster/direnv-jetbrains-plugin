package io.github.salatmaster.direnv.direnv

import java.nio.file.Path
import java.nio.file.Paths

/**
 * Translates paths between direnv's view of the filesystem and this JVM's.
 *
 * Everything direnv reports — `DIRENV_FILE`, the watch list, the diagnostic naming a blocked file —
 * is written in the syntax of the machine direnv ran on, and its arguments are read the same way.
 * When that is not the machine running the IDE the two are not interchangeable, and the difference
 * does not announce itself: `Paths.get("/home/u/p")` on Windows yields a drive-relative
 * `C:\home\u\p` rather than failing. That is how #21 shipped, and it is why every file in the watch
 * list of a WSL project afterwards looked to the poll like a file that had just been deleted.
 */
interface DirenvPathMapper {

    /** A path as direnv wrote it, expressed so this JVM can use it. Null when it cannot be mapped. */
    fun toLocal(reported: String): Path?

    /** A path this JVM uses, expressed the way direnv must be told about it. Null when it cannot be. */
    fun toDirenv(path: Path): String?

    companion object {

        /** direnv runs on the machine running the IDE, so the two spellings already agree. */
        val SameMachine: DirenvPathMapper = object : DirenvPathMapper {

            override fun toLocal(reported: String): Path? =
                runCatching { Paths.get(reported) }.getOrNull()

            override fun toDirenv(path: Path): String = path.toString()
        }
    }
}
