package io.github.salatmaster.direnv.direnv

import com.intellij.openapi.project.Project
import com.intellij.platform.eel.path.EelPath
import com.intellij.platform.eel.provider.asEelPath
import com.intellij.platform.eel.provider.asNioPath
import com.intellij.platform.eel.provider.getEelDescriptor
import java.nio.file.Path

/**
 * Translates paths for a project whose files live on another machine.
 *
 * Both directions go through the platform's own mapping rather than through string surgery: only it
 * knows that `/home/u` on this WSL distribution is `\\wsl.localhost\NixOS\home\u` here, and that
 * the next distribution answers differently.
 */
class EelDirenvPathMapper(private val project: Project) : DirenvPathMapper {

    override fun toLocal(reported: String): Path? = runCatching {
        EelPath.parse(reported, project.getEelDescriptor()).asNioPath()
    }.getOrNull()

    override fun toDirenv(path: Path): String? = runCatching {
        path.asEelPath().toString()
    }.getOrNull()
}
