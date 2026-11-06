package io.github.salatmaster.direnv.terminal

import io.github.salatmaster.direnv.direnv.DirenvEnvironment
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Paths
import java.time.Instant

class DirenvTerminalEnvironmentApplierTest {

    private fun env(entries: Map<String, String?>) = DirenvEnvironment(
        entries = entries,
        watches = emptyList(),
        loadedRcPath = null,
        workingDir = Paths.get("/p"),
        loadedAt = Instant.EPOCH,
    )

    /**
     * Records what the applier hands to MutableShellExecOptions.setEnvironmentVariable, and keeps the
     * resulting environment the way the platform does: since 2026.2 each variable also gets a forced
     * copy, which a null removes along with it, and a name that already carries the prefix is not
     * prefixed again. 2026.1 writes the plain variable only.
     */
    private class FakeOptions(private val forcedCopies: Boolean) {
        val calls = mutableListOf<Pair<String, String?>>()
        val envs = linkedMapOf<String, String>()

        fun set(name: String, value: String?) {
            calls += name to value
            val keys = if (forcedCopies) setOf(name, forced(name)) else setOf(name)
            if (value == null) keys.forEach(envs::remove) else keys.forEach { envs[it] = value }
        }

        private fun forced(name: String) = if (name.startsWith(FORCE_SET)) name else FORCE_SET + name
    }

    private fun apply(entries: Map<String, String?>, fish: Boolean = false, forcedCopies: Boolean = false) =
        FakeOptions(forcedCopies).also { DirenvTerminalEnvironmentApplier.apply(env(entries), fish, it.envs, it::set) }

    @Test
    fun `sets each exported variable`() {
        assertThat(apply(mapOf("FOO" to "bar")).calls).isEqualTo(listOf("FOO" to "bar"))
    }

    @Test
    fun `passes null for variables direnv reported as unset`() {
        // The platform's setEnvironmentVariable removes the entry when the value is null,
        // so a null must be forwarded rather than replaced with an empty string.
        assertThat(apply(mapOf("GONE" to null)).calls).isEqualTo(listOf<Pair<String, String?>>("GONE" to null))
    }

    @Test
    fun `forwards every entry including direnv internal bookkeeping`() {
        val calls = apply(mapOf("DIRENV_DIFF" to "x", "REAL" to "y")).calls

        // Internal variables are hidden from the UI, but the shell still needs them: direnv's
        // own shell hook reads DIRENV_* to decide whether the environment is already applied.
        assertThat(calls).hasSize(2)
        assertThat(calls).contains("DIRENV_DIFF" to "x")
    }

    @Test
    fun `does nothing for an empty environment`() {
        assertThat(apply(emptyMap()).calls).isEmpty()
    }

    @Test
    fun `passes a variable the fish integration would overwrite only as its forced copy`() {
        val options = apply(mapOf("name" to "nix-shell-env", "FOO" to "bar"), fish = true, forcedCopies = true)

        // No global `name` for the script's loop to write into; it creates one from the forced copy.
        assertThat(options.envs).isEqualTo(
            mapOf(
                "_INTELLIJ_FORCE_SET_name" to "nix-shell-env",
                "FOO" to "bar",
                "_INTELLIJ_FORCE_SET_FOO" to "bar",
            ),
        )
    }

    @Test
    fun `leaves the variable alone in other shells`() {
        val options = apply(mapOf("name" to "nix-shell-env"), fish = false, forcedCopies = true)

        assertThat(options.envs).containsEntry("name", "nix-shell-env")
    }

    @Test
    fun `leaves the variable alone where the platform writes no forced copy`() {
        // 2026.1's script sets forced variables with function scope, so a forced copy alone would
        // be lost when the script returns.
        val options = apply(mapOf("name" to "nix-shell-env"), fish = true, forcedCopies = false)

        assertThat(options.envs).isEqualTo(mapOf("name" to "nix-shell-env"))
    }

    @Test
    fun `still unsets such a variable when direnv unsets it`() {
        val options = FakeOptions(forcedCopies = true)
        options.set("name", "inherited")

        DirenvTerminalEnvironmentApplier.apply(env(mapOf("name" to null)), true, options.envs, options::set)

        assertThat(options.envs).isEmpty()
    }

    private companion object {
        const val FORCE_SET = "_INTELLIJ_FORCE_SET_"
    }
}
