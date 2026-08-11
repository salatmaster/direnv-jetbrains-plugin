package io.github.salatmaster.direnv.watch

import io.github.salatmaster.direnv.direnv.DirenvWatch
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

    /** Replaces the watch set belonging to [loadedFor], leaving other directories untouched. */
    fun replace(loadedFor: Path, watches: List<DirenvWatch>) {
        val target = loadedFor.normalise()
        targetByWatchedPath.entries.removeIf { it.value == target }
        for (watch in watches) {
            targetByWatchedPath[watch.path.normalise()] = target
        }
    }

    /** Returns the directory to reload when [changedPath] changes, or null if it is irrelevant. */
    fun reloadTargetFor(changedPath: Path): Path? = targetByWatchedPath[changedPath.normalise()]

    /** Every path currently watched, for registering filesystem roots outside the project. */
    fun allWatchedPaths(): Set<Path> = targetByWatchedPath.keys.toSet()

    fun clear() = targetByWatchedPath.clear()

    private fun Path.normalise(): Path = toAbsolutePath().normalize()
}
