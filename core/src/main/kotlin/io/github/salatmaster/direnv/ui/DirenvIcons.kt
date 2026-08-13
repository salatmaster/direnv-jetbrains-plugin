package io.github.salatmaster.direnv.ui

import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

object DirenvIcons {

    /**
     * The `.envrc` file icon: 16x16, in the palette the bundled file type icons use.
     *
     * Only the light variant is named here. The platform appends `_dark` itself when the theme is
     * dark, so `icons/envrc_dark.svg` must keep exactly that name to be found. [IconLoader] resolves
     * the file lazily, so no image is read for a user who never opens a project with an `.envrc`.
     */
    val Envrc: Icon = IconLoader.getIcon("/icons/envrc.svg", DirenvIcons::class.java)
}
