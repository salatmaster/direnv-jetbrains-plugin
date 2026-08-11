package io.github.salatmaster.direnv.watch

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import io.github.salatmaster.direnv.DirenvService
import io.github.salatmaster.direnv.direnv.DirenvWatch
import io.github.salatmaster.direnv.settings.DirenvSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.nio.file.Path

/**
 * Keeps track of the files the loaded environments depend on and reloads when they change.
 *
 * Two things make this more than an `.envrc` watcher:
 *
 * - the watch set comes from `DIRENV_WATCHES`, so `flake.lock`, `.env` and `devbox.json` are
 *   covered without knowing anything about those tools;
 * - direnv's allow/deny stamps live under the user's data directory, outside the project, so they
 *   are registered as filesystem roots explicitly. That is what makes a `direnv allow` typed in an
 *   external terminal take effect in the IDE.
 */
@Service(Service.Level.PROJECT)
class DirenvWatchService(
    private val project: Project,
    private val scope: CoroutineScope,
) : Disposable {

    private val log = Logger.getInstance(DirenvWatchService::class.java)
    private val registry = DirenvWatchRegistry()

    private var watchRequests: Collection<LocalFileSystem.WatchRequest> = emptyList()
    private var debounceJob: Job? = null

    /** Records the files [loadedFor]'s environment depends on and registers them with the VFS. */
    fun updateWatches(loadedFor: Path, watches: List<DirenvWatch>) {
        registry.replace(loadedFor, watches)
        registerFilesystemRoots()
    }

    /**
     * Reacts to changed paths. Irrelevant paths are ignored cheaply, which matters because this is
     * called for every VFS change in the IDE.
     */
    fun handleChangedPaths(changed: Collection<Path>) {
        if (!DirenvSettings.getInstance(project).state.watchFiles) return

        val targets = changed.mapNotNullTo(mutableSetOf()) { registry.reloadTargetFor(it) }
        if (targets.isEmpty()) return

        scheduleReload(targets)
    }

    /** Exposed for tests and for the reload action. */
    fun watchedPaths(): Set<Path> = registry.allWatchedPaths()

    private fun scheduleReload(targets: Set<Path>) {
        // Editors and build tools rewrite files in bursts; without debouncing, saving a flake.lock
        // would trigger several concurrent direnv runs.
        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(DEBOUNCE_MS)
            val service = DirenvService.getInstance(project)
            for (target in targets) {
                log.info("Reloading direnv environment for $target after a watched file changed")
                service.load(target, force = true)
            }
        }
    }

    private fun registerFilesystemRoots() {
        val fileSystem = LocalFileSystem.getInstance()
        val roots = registry.allWatchedPaths().map { it.toString() }

        // Watching the files themselves, not their directories: the set is small and precise.
        val newRequests = fileSystem.addRootsToWatch(roots, false)
        val previous = watchRequests
        watchRequests = newRequests
        if (previous.isNotEmpty()) {
            fileSystem.removeWatchedRoots(previous)
        }
    }

    override fun dispose() {
        debounceJob?.cancel()
        if (watchRequests.isNotEmpty()) {
            LocalFileSystem.getInstance().removeWatchedRoots(watchRequests)
            watchRequests = emptyList()
        }
        registry.clear()
    }

    companion object {
        private const val DEBOUNCE_MS = 500L

        fun getInstance(project: Project): DirenvWatchService = project.service()
    }
}
