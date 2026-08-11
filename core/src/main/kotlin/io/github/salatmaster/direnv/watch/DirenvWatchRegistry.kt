package io.github.salatmaster.direnv.watch

import io.github.salatmaster.direnv.direnv.DirenvWatch
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * Maps every file direnv reported as an environment input back to the directory whose environment
 * depends on it.
 *
 * Kept free of platform types so the matching logic can be tested directly. The service that
 * subscribes to the virtual file system only translates events into calls on this registry.
 *
 * Watching `.envrc` alone would be insufficient: direnv also reports `flake.nix`, `flake.lock`,
 * `.env`, `~/.config/direnv/direnvrc` and its own allow/deny stamps. The stamps are what let an
 * external `direnv allow` be noticed by the IDE.
 */
class DirenvWatchRegistry {

    /** Watched file → directory whose environment must be reloaded when that file changes. */
    private val targetByWatchedPath = ConcurrentHashMap<Path, Path>()

    /** The state direnv reported for each watched file, used to detect changes by polling. */
    private val stateByWatchedPath = ConcurrentHashMap<Path, DirenvWatch>()

    /** Replaces the watch set belonging to [loadedFor], leaving other directories untouched. */
    fun replace(loadedFor: Path, watches: List<DirenvWatch>) {
        val target = loadedFor.normalise()
        val obsolete = targetByWatchedPath.filterValues { it == target }.keys
        obsolete.forEach { stateByWatchedPath.remove(it) }
        targetByWatchedPath.entries.removeIf { it.value == target }

        for (watch in watches) {
            val path = watch.path.normalise()
            targetByWatchedPath[path] = target
            stateByWatchedPath[path] = watch.copy(path = path)
        }
    }

    /**
     * Returns the directories whose watched files no longer match what direnv reported.
     *
     * Polling exists because file system notifications are not dependable for the paths that
     * matter most: direnv's allow and deny stamps live under the user's data directory, far
     * outside any project, and the IDE delivered no events for them even with a watch root
     * registered — verified against a running IDE. direnv itself detects changes the same way,
     * by comparing modification times.
     */
    fun staleTargets(): Set<Path> {
        val stale = mutableSetOf<Path>()
        for ((path, recorded) in stateByWatchedPath) {
            val target = targetByWatchedPath[path] ?: continue
            if (currentStateOf(path) != recorded.let { it.exists to it.modtime }) {
                stale.add(target)
            }
        }
        return stale
    }

    /** Records the current on-disk state as the new baseline, so one change is reported once. */
    fun rebaseline() {
        for ((path, recorded) in stateByWatchedPath) {
            val (exists, modtime) = currentStateOf(path)
            stateByWatchedPath[path] = recorded.copy(exists = exists, modtime = modtime)
        }
    }

    private fun currentStateOf(path: Path): Pair<Boolean, Long> = try {
        if (Files.exists(path)) {
            true to Files.getLastModifiedTime(path).toInstant().epochSecond
        } else {
            false to 0L
        }
    } catch (e: Exception) {
        false to 0L
    }

    /** Returns the directory to reload when [changedPath] changes, or null if it is irrelevant. */
    fun reloadTargetFor(changedPath: Path): Path? = targetByWatchedPath[changedPath.normalise()]

    /** Every path currently watched, for registering filesystem roots outside the project. */
    fun allWatchedPaths(): Set<Path> = targetByWatchedPath.keys.toSet()

    fun clear() {
        targetByWatchedPath.clear()
        stateByWatchedPath.clear()
    }

    private fun Path.normalise(): Path = toAbsolutePath().normalize()
}
