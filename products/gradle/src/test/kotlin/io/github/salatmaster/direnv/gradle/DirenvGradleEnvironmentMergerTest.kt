package io.github.salatmaster.direnv.gradle

import io.github.salatmaster.direnv.direnv.DirenvEnvironment
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Paths
import java.time.Instant

class DirenvGradleEnvironmentMergerTest {

    private fun env(entries: Map<String, String?>) = DirenvEnvironment(
        entries = entries,
        watches = emptyList(),
        loadedRcPath = null,
        workingDir = Paths.get("/p"),
        loadedAt = Instant.EPOCH,
    )

    @Test
    fun `includes each exported variable`() {
        val result = DirenvGradleEnvironmentMerger.environmentToInject(env(mapOf("FOO" to "bar")))

        assertThat(result).isEqualTo(mapOf("FOO" to "bar"))
    }

    @Test
    fun `skips variables direnv reported as unset rather than emptying them`() {
        val result = DirenvGradleEnvironmentMerger.environmentToInject(
            env(mapOf("GONE" to null, "KEPT" to "v")),
        )

        // GradleExecutionSettings only merges (withEnvironmentVariables is putAll) and the
        // parent-environment merge happens after these settings are applied, so an unset cannot
        // be expressed on this path at all. An empty string would be worse than the omission:
        // a defined-but-empty variable instead of an absent one.
        assertThat(result).isEqualTo(mapOf("KEPT" to "v"))
        assertThat(result).doesNotContainKey("GONE")
    }

    @Test
    fun `forwards direnv internal bookkeeping`() {
        val result = DirenvGradleEnvironmentMerger.environmentToInject(
            env(mapOf("DIRENV_DIFF" to "x", "REAL" to "y")),
        )

        // Internal variables are hidden from the UI, but processes forked from the build (and
        // direnv hooks inside them) still read DIRENV_* to know the environment is applied.
        assertThat(result).containsEntry("DIRENV_DIFF", "x")
        assertThat(result).hasSize(2)
    }

    @Test
    fun `produces an empty map for an empty environment`() {
        val result = DirenvGradleEnvironmentMerger.environmentToInject(env(emptyMap()))

        // The extension point skips withEnvironmentVariables entirely on an empty map.
        assertThat(result).isEmpty()
    }
}
