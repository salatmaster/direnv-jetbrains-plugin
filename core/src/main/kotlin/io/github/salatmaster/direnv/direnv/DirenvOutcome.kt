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

    /**
     * The .envrc exists but has not been approved. The user must approve it explicitly.
     *
     * [watches] is still populated: direnv reports its watch list even when blocked, and that list
     * contains the allow stamp. Tracking it is what lets an external `direnv allow` be noticed.
     */
    data class Blocked(val envrcPath: String, val watches: List<DirenvWatch> = emptyList()) : DirenvOutcome

    /**
     * Approval was explicitly revoked with `direnv deny`.
     *
     * direnv does not report this as an error: `direnv export json` exits 0 and returns an empty
     * environment, which is indistinguishable from an .envrc that legitimately exports nothing.
     * The difference is in the watch list — direnv reports its deny stamp there, and the stamp
     * exists only while the file is denied.
     */
    data class Denied(val envrcPath: String, val watches: List<DirenvWatch> = emptyList()) : DirenvOutcome

    /** The configured direnv executable could not be started. */
    data class ExecutableNotFound(val executable: String) : DirenvOutcome

    /** direnv ran and failed, e.g. a syntax error in .envrc. [message] comes from stderr. */
    data class Failed(val message: String, val exitCode: Int) : DirenvOutcome
}
