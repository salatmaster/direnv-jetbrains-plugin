package io.github.salatmaster.direnv.ui

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import io.github.salatmaster.direnv.DirenvGuard
import io.github.salatmaster.direnv.DirenvService
import io.github.salatmaster.direnv.direnv.DirenvEnvironment
import javax.swing.JComponent
import javax.swing.table.DefaultTableModel

/**
 * Shows which variables direnv applied, by name.
 *
 * Answers the request in IJPL-11588 to see the applied environment, without the leak that
 * materialising values into a run configuration would cause: values are never displayed and never
 * written anywhere. Seeing that PGPASSWORD was set is the useful part; seeing its value is not.
 */
class DirenvShowEnvironmentAction : AnAction("Show direnv Environment") {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project?.let { DirenvGuard.mayRun(it) } == true
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val workingDir = projectWorkingDir(project) ?: return
        val environment = DirenvService.getInstance(project).cachedFor(workingDir)

        if (environment == null) {
            DirenvNotifications.warn(project, "No direnv environment is loaded for this project.")
            return
        }
        EnvironmentDialog(project, environment).show()
    }

    private class EnvironmentDialog(
        project: Project,
        private val environment: DirenvEnvironment,
    ) : DialogWrapper(project) {

        init {
            title = "direnv Environment"
            init()
        }

        override fun createCenterPanel(): JComponent {
            val model = object : DefaultTableModel(arrayOf("Variable", "Status"), 0) {
                override fun isCellEditable(row: Int, column: Int) = false
            }

            val base = System.getenv()
            environment.entries.keys
                .filterNot { DirenvEnvironment.isInternal(it) }
                .sorted()
                .forEach { name ->
                    val value = environment.entries[name]
                    val status = when {
                        value == null -> "removed"
                        base[name] == null -> "added"
                        base[name] != value -> "changed"
                        else -> "unchanged"
                    }
                    model.addRow(arrayOf(name, status))
                }

            return JBScrollPane(JBTable(model))
        }

        override fun createActions() = arrayOf(okAction)
    }
}
