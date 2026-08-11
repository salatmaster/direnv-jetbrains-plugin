package io.github.salatmaster.direnv.direnv

/**
 * Result of asking direnv for an environment.
 *
 * Blocked is modelled as a first-class outcome rather than an error: an unapproved .envrc is the
 * expected state on first encounter, and the UI must offer approval instead of reporting a fault.
 */
sealed interface DirenvOutcome {

    /** direnv produced an environment. It may be empty when the directory has no .envrc. */
    data class Loaded(val environment: DirenvEnvironment) : DirenvOutcome

    /** The .envrc exists but has not been approved. The user must approve it explicitly. */
    data class Blocked(val envrcPath: String) : DirenvOutcome

    /** The configured direnv executable could not be started. */
    data class ExecutableNotFound(val executable: String) : DirenvOutcome

    /** direnv ran and failed, e.g. a syntax error in .envrc. [message] comes from stderr. */
    data class Failed(val message: String, val exitCode: Int) : DirenvOutcome
}
