package io.github.salatmaster.direnv.direnv

import java.nio.file.Path
import java.time.Instant

/** Names only — values must never appear in a diff, because diffs end up in logs and UI. */
data class DirenvDiff(
    val added: List<String>,
    val changed: List<String>,
    val removed: List<String>,
) {
    val isEmpty: Boolean get() = added.isEmpty() && changed.isEmpty() && removed.isEmpty()
    val total: Int get() = added.size + changed.size + removed.size
}

/**
 * An environment produced by `direnv export json` for [workingDir].
 *
 * A `null` value in [entries] means "unset this variable", which is how direnv reports removals.
 *
 * [toString] deliberately renders neither values nor names: instances reach log statements and
 * exception messages, and direnv output frequently contains secrets. Enforcing this in the type
 * is more reliable than remembering it at every call site.
 */
class DirenvEnvironment(
    val entries: Map<String, String?>,
    val watches: List<DirenvWatch>,
    val loadedRcPath: Path?,
    val workingDir: Path,
    val loadedAt: Instant,
) {

    /** Applies this environment to [target], removing keys direnv reported as unset. */
    fun applyTo(target: MutableMap<String, String>) {
        for ((key, value) in entries) {
            if (value == null) target.remove(key) else target[key] = value
        }
    }

    /** Compares against [base], reporting variable names only. Internal DIRENV_* keys are ignored. */
    fun diffAgainst(base: Map<String, String>): DirenvDiff {
        val added = mutableListOf<String>()
        val changed = mutableListOf<String>()
        val removed = mutableListOf<String>()

        for ((key, value) in entries) {
            if (isInternal(key)) continue
            val previous = base[key]
            when {
                value == null -> if (previous != null) removed += key
                previous == null -> added += key
                previous != value -> changed += key
            }
        }
        return DirenvDiff(added, changed, removed)
    }

    override fun toString(): String =
        "DirenvEnvironment(vars=${entries.size}, watches=${watches.size}, rc=$loadedRcPath, at=$loadedAt)"

    companion object {
        /** direnv's own bookkeeping variables. They are applied, but never surfaced to the user. */
        fun isInternal(key: String): Boolean = key.startsWith("DIRENV_")

        fun empty(workingDir: Path): DirenvEnvironment = DirenvEnvironment(
            entries = emptyMap(),
            watches = emptyList(),
            loadedRcPath = null,
            workingDir = workingDir,
            loadedAt = Instant.EPOCH,
        )
    }
}
