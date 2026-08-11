package io.github.salatmaster.direnv

import io.github.salatmaster.direnv.direnv.DirenvDiff

/**
 * Observable state of direnv for a project.
 *
 * No variant carries variable values: these instances are rendered in the status bar and written
 * to logs, so carrying a value here would defeat the secrecy guarantee enforced elsewhere.
 */
sealed interface DirenvState {

    /**
     * True while direnv refuses to run the file until the user approves it.
     *
     * The two states that satisfy this differ only in how they were reached — never approved, or
     * approved and then revoked — and every part of the UI treats them the same.
     */
    val needsApproval: Boolean
        get() = this is Blocked || this is Denied

    object NotLoaded : DirenvState

    object Loading : DirenvState

    /** Environment loaded. [diff] holds variable names only. */
    data class Loaded(val diff: DirenvDiff) : DirenvState

    /** An .envrc awaits explicit approval. */
    data class Blocked(val envrcPath: String) : DirenvState

    /**
     * Approval was revoked. Kept distinct from [Loaded] with an empty diff, which is what direnv
     * reports for a denied .envrc and would otherwise claim that everything is fine.
     */
    data class Denied(val envrcPath: String) : DirenvState

    data class Failed(val message: String) : DirenvState

    data class ExecutableMissing(val executable: String) : DirenvState
}
