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

    private const val FORCE_SET = "_INTELLIJ_FORCE_SET_"

    /**
     * What fish-integration.fish assigns without `set -l` while it walks the environment to apply
     * `_INTELLIJ_FORCE_SET_*`. fish writes an unscoped `set` to a global of the same name when one
     * exists, so the script overwrites a direnv variable with any of these names on every step of the
     * loop. Every Nix dev shell exports `name` (`nix-shell-env`); since 2026.2 the script also runs
     * `set -e -g $name` after setting it, and every new terminal printed
     * `set: nix-shell-env: invalid variable name` (#40).
     */
    private val FISH_INTEGRATION_VARIABLES = setOf("name", "value", "new_name", "name_and_value")

    /**
     * Feeds every entry to [setVariable].
     *
     * A null value is forwarded as null on purpose: MutableShellExecOptionsImpl removes the entry
     * when the value is null, which is exactly how direnv's "unset" is meant to be honoured.
     * Substituting an empty string would leave a defined-but-empty variable instead.
     *
     * In fish, a variable the integration script would overwrite is passed only as its forced copy,
     * which the script turns into a global with `set -gx` once there is no global for its loop to
     * collide with. Removing the plain variable takes the forced copy with it, and setting the forced
     * name puts back that one key, because the platform does not prefix a name twice. Where the
     * platform writes no forced copy (2026.1, whose script sets forced variables with function scope
     * and would lose it), the variable is left as it was.
     */
    fun apply(
        environment: DirenvEnvironment,
        fish: Boolean,
        envs: Map<String, String>,
        setVariable: (String, String?) -> Unit,
    ) {
        for ((key, value) in environment.entries) {
            setVariable(key, value)
            if (fish && value != null && key in FISH_INTEGRATION_VARIABLES && FORCE_SET + key in envs) {
                setVariable(key, null)
                setVariable(FORCE_SET + key, value)
            }
        }
    }
}
