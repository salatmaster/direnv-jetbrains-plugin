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
    private var pollJob: Job? = null

    /** Records the files [loadedFor]'s environment depends on and registers them with the VFS. */
    fun updateWatches(loadedFor: Path, watches: List<DirenvWatch>) {
        registry.replace(loadedFor, watches)
        registerFilesystemRoots()
        startPollingIfNeeded()
        log.debug("Watching ${registry.allWatchedPaths().size} paths for $loadedFor")
    }

    /**
     * Polls the watched files for changes.
     *
     * The virtual file system is the fast path for files inside the project, but it delivers
     * nothing for direnv's allow and deny stamps under the user's data directory — confirmed
     * against a running IDE, even with a watch root registered and refreshed. Since noticing an
     * approval granted in a terminal is a headline feature, correctness cannot depend on those
     * events. direnv resolves the same problem the same way: by comparing modification times.
     */
    private fun startPollingIfNeeded() {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch {
            while (true) {
                delay(POLL_INTERVAL_MS)
                if (!DirenvSettings.getInstance(project).state.watchFiles) continue

                val stale = registry.staleTargets()
                if (stale.isEmpty()) continue

                // Rebaseline first: reloading replaces the watch set anyway, and this stops one
                // change from being reported repeatedly if the reload fails.
                registry.rebaseline()
                val service = DirenvService.getInstance(project)
                for (target in stale) {
                    log.info("Reloading direnv environment for $target after a watched file changed")
                    service.load(target, force = true)
                }
            }
        }
    }

    /**
     * Reacts to changed paths. Irrelevant paths are ignored cheaply, which matters because this is
     * called for every VFS change in the IDE.
     */
    fun handleChangedPaths(changed: Collection<Path>) {
        if (!DirenvSettings.getInstance(project).state.watchFiles) return

        val targets = changed.mapNotNullTo(mutableSetOf()) { registry.reloadTargetFor(it) }
        if (targets.isEmpty()) {
            if (log.isDebugEnabled) {
                log.debug(
                    "No watched path among ${changed.size} changed: ${changed.take(4)} " +
                        "| watching: ${registry.allWatchedPaths().take(4)}"
                )
            }
            return
        }

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

        // Register the containing directories rather than the files themselves. Native watchers
        // (FSEvents, inotify, ReadDirectoryChangesW) observe directories, and registering
        // individual files produced no events at all when verified against a running IDE.
        // Precision is not lost: DirenvWatchRegistry still matches on the exact file path.
        val roots = registry.allWatchedPaths()
            .mapNotNull { it.parent?.toString() }
            .distinct()

        val newRequests = fileSystem.addRootsToWatch(roots, false)
        val previous = watchRequests
        watchRequests = newRequests
        if (previous.isNotEmpty()) {
            fileSystem.removeWatchedRoots(previous)
        }

        // Registering a watch root is not enough: the platform only delivers events for files
        // already present in the VFS, and directories such as ~/.local/share/direnv/allow have
        // never been read by the IDE. Without this refresh an external `direnv allow` produces no
        // event at all — verified against a running IDE.
        for (root in roots) {
            fileSystem.refreshAndFindFileByPath(root)
        }
    }

    override fun dispose() {
        pollJob?.cancel()
        debounceJob?.cancel()
        if (watchRequests.isNotEmpty()) {
            LocalFileSystem.getInstance().removeWatchedRoots(watchRequests)
            watchRequests = emptyList()
        }
        registry.clear()
    }

    companion object {
        private const val DEBOUNCE_MS = 500L

        /** Cheap: a handful of stat calls. Fast enough that a terminal `direnv allow` feels live. */
        private const val POLL_INTERVAL_MS = 2_000L

        fun getInstance(project: Project): DirenvWatchService = project.service()
    }
}
