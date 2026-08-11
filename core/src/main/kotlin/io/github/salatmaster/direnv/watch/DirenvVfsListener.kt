package io.github.salatmaster.direnv.watch

import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.vfs.AsyncFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import io.github.salatmaster.direnv.DirenvGuard
import java.nio.file.Paths

/**
 * Translates virtual file system events into reload requests.
 *
 * Application-level, so it sees events for every open project and has to attribute them. The work
 * done here is deliberately trivial — collect paths, hand them to each project's watch service —
 * because [prepareChange] runs for every change in the IDE.
 */
class DirenvVfsListener : AsyncFileListener {

    private val log = com.intellij.openapi.diagnostic.Logger.getInstance(DirenvVfsListener::class.java)

    override fun prepareChange(events: MutableList<out VFileEvent>): AsyncFileListener.ChangeApplier? {
        val paths = events.mapNotNullTo(mutableSetOf()) { event ->
            runCatching { Paths.get(event.path) }.getOrNull()
        }
        if (paths.isEmpty()) return null

        val projects = openProjects()
        if (projects.isEmpty()) return null

        return object : AsyncFileListener.ChangeApplier {
            override fun afterVfsChange() {
                log.debug("VFS change: ${paths.size} paths, ${projects.size} projects")
                // Only schedules work: this runs inside a write action, so it must return fast.
                for (project in projects) {
                    if (project.isDisposed) continue
                    DirenvWatchService.getInstance(project).handleChangedPaths(paths)
                }
            }
        }
    }

    private fun openProjects(): List<Project> {
        val manager = ProjectManager.getInstanceIfCreated() ?: return emptyList()
        return manager.openProjects.filter { !it.isDisposed && DirenvGuard.mayRun(it) }
    }
}
