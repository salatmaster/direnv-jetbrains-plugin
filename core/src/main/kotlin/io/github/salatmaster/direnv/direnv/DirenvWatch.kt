package io.github.salatmaster.direnv.direnv

import java.nio.file.Path

/** One file direnv reported as an input of the current environment. */
data class DirenvWatch(
    val path: Path,
    val modtime: Long,
    val exists: Boolean,
)
