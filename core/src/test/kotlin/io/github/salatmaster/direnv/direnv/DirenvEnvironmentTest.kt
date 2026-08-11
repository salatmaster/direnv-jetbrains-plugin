package io.github.salatmaster.direnv.direnv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.nio.file.Paths
import java.time.Instant

class DirenvEnvironmentTest {

    private val secret = "s3cr3t-token-do-not-leak"

    private fun env(entries: Map<String, String?>) = DirenvEnvironment(
        entries = entries,
        watches = emptyList(),
        loadedRcPath = Paths.get("/p/.envrc"),
        workingDir = Paths.get("/p"),
        loadedAt = Instant.EPOCH,
    )

    @Test
    fun `toString never exposes values`() {
        val rendered = env(mapOf("API_TOKEN" to secret)).toString()

        assertFalse("value leaked into toString: $rendered", rendered.contains(secret))
    }

    @Test
    fun `toString does not expose variable names either`() {
        val rendered = env(mapOf("API_TOKEN" to secret)).toString()

        assertFalse(rendered.contains("API_TOKEN"))
    }

    @Test
    fun `applyTo sets values`() {
        val target = mutableMapOf("KEEP" to "1")

        env(mapOf("NEW" to "value")).applyTo(target)

        assertEquals(mapOf("KEEP" to "1", "NEW" to "value"), target)
    }

    @Test
    fun `applyTo removes keys whose value is null`() {
        val target = mutableMapOf("GONE" to "old", "KEEP" to "1")

        env(mapOf("GONE" to null)).applyTo(target)

        assertEquals(mapOf("KEEP" to "1"), target)
    }

    @Test
    fun `diff reports added changed and removed by name`() {
        val base = mapOf("CHANGED" to "before", "REMOVED" to "x", "UNTOUCHED" to "u")

        val diff = env(
            mapOf("ADDED" to "a", "CHANGED" to "after", "REMOVED" to null)
        ).diffAgainst(base)

        assertEquals(listOf("ADDED"), diff.added)
        assertEquals(listOf("CHANGED"), diff.changed)
        assertEquals(listOf("REMOVED"), diff.removed)
    }

    @Test
    fun `diff ignores entries whose value is unchanged`() {
        val diff = env(mapOf("SAME" to "v")).diffAgainst(mapOf("SAME" to "v"))

        assertEquals(emptyList<String>(), diff.added)
        assertEquals(emptyList<String>(), diff.changed)
        assertEquals(emptyList<String>(), diff.removed)
    }

    @Test
    fun `diff does not report removal of a variable that was not set`() {
        val diff = env(mapOf("NEVER_SET" to null)).diffAgainst(emptyMap())

        assertEquals(emptyList<String>(), diff.removed)
    }

    @Test
    fun `internal DIRENV_ variables are excluded from the diff`() {
        val diff = env(mapOf("DIRENV_DIFF" to "x", "REAL" to "y")).diffAgainst(emptyMap())

        assertEquals(listOf("REAL"), diff.added)
    }
}
