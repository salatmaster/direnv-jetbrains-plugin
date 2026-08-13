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

    /** Records what the applier would hand to MutableShellExecOptions.setEnvironmentVariable. */
    private class RecordingSetter {
        val calls = mutableListOf<Pair<String, String?>>()
        fun set(name: String, value: String?) {
            calls += name to value
        }
    }

    @Test
    fun `sets each exported variable`() {
        val setter = RecordingSetter()

        DirenvTerminalEnvironmentApplier.apply(env(mapOf("FOO" to "bar")), setter::set)

        assertThat(setter.calls).isEqualTo(listOf("FOO" to "bar"))
    }

    @Test
    fun `passes null for variables direnv reported as unset`() {
        val setter = RecordingSetter()

        DirenvTerminalEnvironmentApplier.apply(env(mapOf("GONE" to null)), setter::set)

        // The platform's setEnvironmentVariable removes the entry when the value is null,
        // so a null must be forwarded rather than replaced with an empty string.
        assertThat(setter.calls).isEqualTo(listOf<Pair<String, String?>>("GONE" to null))
    }

    @Test
    fun `forwards every entry including direnv internal bookkeeping`() {
        val setter = RecordingSetter()

        DirenvTerminalEnvironmentApplier.apply(
            env(mapOf("DIRENV_DIFF" to "x", "REAL" to "y")),
            setter::set,
        )

        // Internal variables are hidden from the UI, but the shell still needs them: direnv's
        // own shell hook reads DIRENV_* to decide whether the environment is already applied.
        assertThat(setter.calls).hasSize(2)
        assertThat(setter.calls).contains("DIRENV_DIFF" to "x")
    }

    @Test
    fun `does nothing for an empty environment`() {
        val setter = RecordingSetter()

        DirenvTerminalEnvironmentApplier.apply(env(emptyMap()), setter::set)

        assertThat(setter.calls).isEmpty()
    }
}
