package io.github.salatmaster.direnv.project

import java.nio.file.Path

/** Identified content roots of one open project. */
data class ProjectRoots(val id: String, val roots: List<Path>)

/**
 * Maps a process working directory to the project that owns it.
 *
 * Deliberately does not fall back to "the only open project". The IDE starts plenty of processes
 * outside any project directory, and guessing there would inject an unrelated project's
 * environment — the exact isolation failure this plugin exists to avoid.
 */
object DirenvProjectResolver {

    /** Returns the id of the owning project, or null when the directory belongs to none. */
    fun resolve(workingDir: Path, candidates: List<ProjectRoots>): String? {
        if (candidates.isEmpty()) return null
        val target = workingDir.normalise()

        var bestId: String? = null
        var bestDepth = -1

        for (candidate in candidates) {
            for (root in candidate.roots) {
                val normalisedRoot = root.normalise()
                if (!target.startsWith(normalisedRoot)) continue
                // Longest match wins, so a nested project beats the outer one that also contains it.
                val depth = normalisedRoot.nameCount
                if (depth > bestDepth) {
                    bestDepth = depth
                    bestId = candidate.id
                }
            }
        }
        return bestId
    }

    /**
     * Path.startsWith compares path elements rather than characters, so "/home/u/app-backup"
     * is correctly rejected against root "/home/u/app". Normalising first resolves ".." segments.
     */
    private fun Path.normalise(): Path = toAbsolutePath().normalize()
}
