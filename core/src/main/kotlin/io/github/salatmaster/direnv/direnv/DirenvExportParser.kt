package io.github.salatmaster.direnv.direnv

import com.intellij.openapi.diagnostic.Logger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Parses the output of `direnv export json`. */
object DirenvExportParser {

    private val LOG = Logger.getInstance(DirenvExportParser::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Matches direnv's blocked-file diagnostic. The path capture is greedy up to " is blocked"
     * because .envrc paths may contain spaces.
     */
    private val BLOCKED = Regex("""direnv: error (?<path>.+) is blocked""")

    /**
     * direnv colourises stderr regardless of TERM — verified against direnv 2.37.1, where
     * `TERM=dumb direnv export json` still emits \u001B[31m around its diagnostics. Escape codes
     * must be removed before a message is shown to the user or matched against.
     */
    private val ANSI = Regex("""\u001B\[[0-9;]*[a-zA-Z]""")

    /**
     * Parses exported variables. A `null` JSON value maps to a `null` entry, meaning "unset".
     *
     * Malformed output yields an empty map: direnv reports failures through its exit code and
     * stderr, so throwing here would turn a diagnosable state into a stack trace.
     */
    fun parseEntries(stdout: String): Map<String, String?> {
        val trimmed = stdout.trim()
        if (trimmed.isEmpty()) return emptyMap()

        val obj = try {
            json.parseToJsonElement(trimmed) as? JsonObject ?: return emptyMap()
        } catch (e: Exception) {
            LOG.debug("Failed to parse direnv export output", e)
            return emptyMap()
        }

        return buildMap {
            for ((key, element) in obj) {
                when (element) {
                    is JsonNull -> put(key, null)
                    is JsonPrimitive -> put(key, element.content)
                    else -> LOG.debug("Ignoring non-scalar direnv entry for key $key")
                }
            }
        }
    }

    /** Removes ANSI colour escapes so text is safe to display and to match against. */
    fun stripAnsi(text: String): String = ANSI.replace(text, "")

    /** Returns the blocked `.envrc` path if stderr reports one, otherwise null. */
    fun findBlockedPath(stderr: String): String? =
        BLOCKED.find(stripAnsi(stderr))?.groups?.get("path")?.value?.trim()
}
