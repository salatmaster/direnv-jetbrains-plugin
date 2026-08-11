package io.github.salatmaster.direnv

import io.github.salatmaster.direnv.direnv.DirenvDiff

/**
 * Observable state of direnv for a project.
 *
 * No variant carries variable values: these instances are rendered in the status bar and written
 * to logs, so carrying a value here would defeat the secrecy guarantee enforced elsewhere.
 */
sealed interface DirenvState {

    object NotLoaded : DirenvState

    object Loading : DirenvState

    /** Environment loaded. [diff] holds variable names only. */
    data class Loaded(val diff: DirenvDiff) : DirenvState

    /** An .envrc awaits explicit approval. */
    data class Blocked(val envrcPath: String) : DirenvState

    data class Failed(val message: String) : DirenvState

    data class ExecutableMissing(val executable: String) : DirenvState
}
