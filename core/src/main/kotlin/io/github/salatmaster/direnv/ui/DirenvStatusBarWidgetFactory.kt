package io.github.salatmaster.direnv.ui

import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.openapi.wm.TextWidgetPresentation
import com.intellij.openapi.wm.WidgetPresentation
import com.intellij.openapi.wm.WidgetPresentationDataContext
import com.intellij.openapi.wm.WidgetPresentationFactory
import io.github.salatmaster.direnv.DirenvGuard
import io.github.salatmaster.direnv.DirenvService
import io.github.salatmaster.direnv.DirenvState
import io.github.salatmaster.direnv.DirenvStateListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.map
import java.awt.Component
import java.awt.event.MouseEvent

/**
 * Registers the status bar entry; hidden entirely in projects where direnv may not run.
 *
 * Implementing WidgetPresentationFactory is what selects the presentation-based API:
 * StatusBarWidgetsManager checks for it and never calls createWidget on such a factory. The older
 * route — implementing StatusBarWidget directly — drags in a deprecated getPresentation(PlatformType)
 * that the Kotlin compiler materialises into every implementing class, whether or not it is written.
 */
class DirenvStatusBarWidgetFactory : StatusBarWidgetFactory, WidgetPresentationFactory {

    override fun getId(): String = WIDGET_ID

    override fun getDisplayName(): String = "direnv"

    override fun isAvailable(project: Project): Boolean = DirenvGuard.mayRun(project)

    override fun createPresentation(
        context: WidgetPresentationDataContext,
        scope: CoroutineScope,
    ): WidgetPresentation = DirenvStatusWidgetPresentation(context.project)

    companion object {
        const val WIDGET_ID: String = "direnv.status"
        const val ACTION_GROUP_ID: String = "direnv.actions"
    }
}

/**
 * Status bar entry showing whether a direnv environment is active, blocked, or failing.
 *
 * Shows counts only, never variable names — see [DirenvStatusPresentation].
 *
 * One instance per status bar: the platform creates a presentation per component, including the
 * child status bars of detached windows, so this holds no shared state and unsubscribes itself.
 */
private class DirenvStatusWidgetPresentation(private val project: Project) : TextWidgetPresentation {

    override val alignment: Float
        get() = Component.CENTER_ALIGNMENT

    override fun text(): Flow<String?> = states().map { DirenvStatusPresentation.of(it).text }

    /**
     * Read on demand rather than cached alongside the text, so the tooltip cannot show a state the
     * widget has already moved on from.
     */
    override suspend fun getTooltipText(): String =
        DirenvStatusPresentation.of(DirenvService.getInstance(project).state()).tooltip

    override fun getClickConsumer(): (MouseEvent) -> Unit = ::showActions

    /**
     * The state as it stands, followed by every later one.
     *
     * Subscribing before reading the current state is deliberate: the other order drops a change
     * that lands in between and leaves the widget stale until something else happens to publish.
     * A duplicate emission is harmless — the platform applies distinctUntilChanged itself.
     */
    private fun states(): Flow<DirenvState> = callbackFlow {
        val connection = project.messageBus.connect()
        connection.subscribe(
            DirenvStateListener.TOPIC,
            object : DirenvStateListener {
                // Published synchronously from whichever thread loaded the environment, so this
                // must not block: trySend never does.
                override fun stateChanged(state: DirenvState) {
                    trySend(state)
                }
            },
        )
        trySend(DirenvService.getInstance(project).state())
        awaitClose { connection.disconnect() }
    }

    private fun showActions(event: MouseEvent) {
        val group = ActionManager.getInstance()
            .getAction(DirenvStatusBarWidgetFactory.ACTION_GROUP_ID) as? DefaultActionGroup ?: return
        // Every direnv action resolves its target through e.project, so the popup has to carry one.
        // An empty context disables all of them, and Allow — which hides rather than greys out —
        // disappears from the menu altogether. The component context is kept as the parent so the
        // actions still see the frame they were invoked from.
        val dataContext = SimpleDataContext.getSimpleContext(
            CommonDataKeys.PROJECT,
            project,
            DataManager.getInstance().getDataContext(event.component),
        )
        JBPopupFactory.getInstance()
            .createActionGroupPopup(
                "direnv",
                group,
                dataContext,
                JBPopupFactory.ActionSelectionAid.SPEEDSEARCH,
                true,
                ActionPlaces.STATUS_BAR_PLACE,
            )
            .showInScreenCoordinates(event.component, event.locationOnScreen)
    }
}
