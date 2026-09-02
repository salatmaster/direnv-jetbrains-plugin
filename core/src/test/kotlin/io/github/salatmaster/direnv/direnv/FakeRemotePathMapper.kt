package io.github.salatmaster.direnv.direnv

import java.nio.file.Path

/**
 * A project on another machine, without needing one.
 *
 * Over there every path starts with [REMOTE_ROOT]; here the same files sit under [here], which is a
 * directory this JVM can actually name. That is the entire shape of the WSL problem — one file,
 * two spellings, neither usable in the other's place — so translating between them is enough to
 * test the plugin's half of it on any operating system, the Windows CI runner included.
 */
class FakeRemotePathMapper(private val here: Path) : DirenvPathMapper {

    override fun toLocal(reported: String): Path? {
        if (!reported.startsWith("$REMOTE_ROOT/")) return null
        return reported.removePrefix("$REMOTE_ROOT/")
            .split('/')
            .fold(here) { path, part -> path.resolve(part) }
    }

    override fun toDirenv(path: Path): String? {
        if (!path.startsWith(here)) return null
        return here.relativize(path).joinToString("/", prefix = "$REMOTE_ROOT/")
    }

    companion object {
        const val REMOTE_ROOT: String = "/remote"
    }
}
