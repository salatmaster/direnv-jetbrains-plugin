package io.github.salatmaster.direnv.terminal

import io.github.salatmaster.direnv.direnv.DirenvEnvironment

/**
 * Applies a direnv environment through a setter with the platform's contract.
 *
 * Kept separate from the extension point so the behaviour can be tested without constructing
 * MutableShellExecOptions and EelPath instances, which are Experimental platform types with no
 * public constructors.
 */
object DirenvTerminalEnvironmentApplier {

    /**
     * Feeds every entry to [setVariable].
     *
     * A null value is forwarded as null on purpose: MutableShellExecOptionsImpl removes the entry
     * when the value is null, which is exactly how direnv's "unset" is meant to be honoured.
     * Substituting an empty string would leave a defined-but-empty variable instead.
     */
    fun apply(environment: DirenvEnvironment, setVariable: (String, String?) -> Unit) {
        for ((key, value) in environment.entries) {
            setVariable(key, value)
        }
    }
}
