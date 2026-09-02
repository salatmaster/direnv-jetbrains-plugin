package io.github.salatmaster.direnv.ui

import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import io.github.salatmaster.direnv.DirenvMachine
import io.github.salatmaster.direnv.DirenvService
import io.github.salatmaster.direnv.settings.DirenvSettings

/** Settings page under Tools → direnv. */
class DirenvConfigurable(private val project: Project) : BoundConfigurable("direnv") {

    /**
     * Applies the settings and runs direnv again with them.
     *
     * Every setting here is read at the moment direnv is invoked, and nothing invokes it, so
     * changing one used to leave the status bar showing the result of the previous one. #24 is a
     * report of six executable paths tried in a row against a status that never moved.
     */
    override fun apply() {
        val changed = isModified()
        super.apply()
        if (!changed) return

        val service = DirenvService.getInstance(project)
        service.invalidate(null)
        DirenvMachine.projectDir(project)?.let { service.scheduleReload(it) }
    }

    override fun createPanel(): DialogPanel {
        val state = DirenvSettings.getInstance(project).state

        return panel {
            row {
                checkBox("Enable direnv for this project")
                    .bindSelected(state::enabled)
                    .comment(
                        "When disabled, direnv is never executed and no environment is applied. " +
                            "direnv is also skipped in projects you have not trusted."
                    )
            }
            row("direnv executable:") {
                textField()
                    .bindText(state::executablePath)
                    .comment("Path to the direnv binary, or just \"direnv\" to look it up on PATH.")
            }
            row {
                checkBox("Load the environment when the project opens")
                    .bindSelected(state::autoLoad)
            }
            row {
                checkBox("Reload when files the environment depends on change")
                    .bindSelected(state::watchFiles)
                    .comment(
                        "Uses direnv's own watch list, so changes to flake.lock, .env or an " +
                            "external <code>direnv allow</code> are picked up too."
                    )
            }
            row("Timeout (seconds):") {
                intTextField(range = 1..3600)
                    .bindIntText(state::timeoutSeconds)
                    .comment(
                        "A first Nix or Devbox build can take minutes, which is why the default " +
                            "is deliberately generous."
                    )
            }
        }
    }
}
