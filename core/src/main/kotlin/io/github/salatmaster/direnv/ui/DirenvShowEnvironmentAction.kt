package io.github.salatmaster.direnv.ui

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
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

            val table = JBTable(model).apply {
                setShowGrid(false)
                // "added", "changed", "removed", "unchanged" — a short, fixed vocabulary. Pinning
                // it narrow leaves the rest of the width to the name, which is the column with
                // something to say: PGPASSWORD truncated to PGPASSW… defeats the point of the list.
                columnModel.getColumn(1).apply {
                    preferredWidth = JBUI.scale(STATUS_COLUMN_WIDTH)
                    maxWidth = JBUI.scale(STATUS_COLUMN_WIDTH)
                }
            }

            return JBScrollPane(table).apply {
                // Without a preferred size the dialog shrinks to the table's minimum and every name
                // arrives clipped.
                preferredSize = JBUI.size(WIDTH, HEIGHT)
            }
        }

        /** Remembers whatever size the user drags the dialog to. */
        override fun getDimensionServiceKey(): String = "direnv.environment"

        override fun createActions() = arrayOf(okAction)

        private companion object {
            const val WIDTH = 520
            const val HEIGHT = 340
            const val STATUS_COLUMN_WIDTH = 110
        }
    }
}
