package io.github.salatmaster.direnv.ui

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.DataContext
import io.github.salatmaster.direnv.DirenvGuard
import io.github.salatmaster.direnv.DirenvService
import io.github.salatmaster.direnv.DirenvState
import io.github.salatmaster.direnv.DirenvStateListener
import java.awt.event.MouseEvent

/**
 * Status bar entry showing whether a direnv environment is active, blocked, or failing.
 *
 * Shows counts only, never variable names — see [DirenvStatusPresentation].
 */
class DirenvStatusBarWidget(private val project: Project) : StatusBarWidget, StatusBarWidget.TextPresentation {

    private var statusBar: StatusBar? = null
    private var presentation: DirenvStatusPresentation =
        DirenvStatusPresentation.of(DirenvService.getInstance(project).state())

    override fun ID(): String = WIDGET_ID

    override fun getPresentation(): StatusBarWidget.WidgetPresentation = this

    override fun install(statusBar: StatusBar) {
        this.statusBar = statusBar
        project.messageBus.connect(this).subscribe(
            DirenvStateListener.TOPIC,
            object : DirenvStateListener {
                override fun stateChanged(state: DirenvState) {
                    presentation = DirenvStatusPresentation.of(state)
                    statusBar.updateWidget(WIDGET_ID)
                }
            },
        )
    }

    override fun dispose() {
        statusBar = null
    }

    override fun getText(): String = presentation.text

    override fun getTooltipText(): String = presentation.tooltip

    override fun getAlignment(): Float = java.awt.Component.CENTER_ALIGNMENT

    override fun getClickConsumer(): com.intellij.util.Consumer<MouseEvent>? =
        com.intellij.util.Consumer { event -> showActions(event) }

    private fun showActions(event: MouseEvent) {
        val group = ActionManager.getInstance().getAction(ACTION_GROUP_ID) as? DefaultActionGroup ?: return
        JBPopupFactory.getInstance()
            .createActionGroupPopup(
                "direnv",
                group,
                DataContext.EMPTY_CONTEXT,
                JBPopupFactory.ActionSelectionAid.SPEEDSEARCH,
                true,
                ActionPlaces.STATUS_BAR_PLACE,
            )
            .showInScreenCoordinates(event.component, event.locationOnScreen)
    }

    companion object {
        const val WIDGET_ID: String = "direnv.status"
        const val ACTION_GROUP_ID: String = "direnv.actions"
    }
}

/** Registers the widget; hidden entirely in projects where direnv may not run. */
class DirenvStatusBarWidgetFactory : StatusBarWidgetFactory {

    override fun getId(): String = DirenvStatusBarWidget.WIDGET_ID

    override fun getDisplayName(): String = "direnv"

    override fun isAvailable(project: Project): Boolean = DirenvGuard.mayRun(project)

    override fun createWidget(project: Project): StatusBarWidget = DirenvStatusBarWidget(project)

    override fun canBeEnabledOn(statusBar: StatusBar): Boolean = true
}
