package io.github.salatmaster.direnv.ui

import com.intellij.ide.FileIconProvider
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import io.github.salatmaster.direnv.ENVRC_FILE_NAME
import javax.swing.Icon

/**
 * Gives `.envrc` an icon of its own.
 *
 * The file stays a Shell Script — that is what gives it highlighting, and what the Marketplace reads
 * to offer this plugin to someone who opens an `.envrc` without it. Only the icon is taken over, so
 * an `.envrc` is no longer indistinguishable from every other shell script in the project tree.
 *
 * The platform asks every provider for an icon whenever it draws a file, in the project tree, in
 * editor tabs, in Search Everywhere and in Recent Files. This one therefore does nothing but compare
 * a name, and deliberately does not consult project trust or plugin settings: drawing an icon runs
 * no `.envrc`, and an icon that came and went with a setting would be a puzzle rather than a hint.
 */
class DirenvFileIconProvider : FileIconProvider {

    override fun getIcon(file: VirtualFile, flags: Int, project: Project?): Icon? =
        if (file.name == ENVRC_FILE_NAME) DirenvIcons.Envrc else null
}
