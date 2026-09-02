package io.github.salatmaster.direnv.direnv

import com.intellij.openapi.diagnostic.Logger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import java.util.Base64
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream

/**
 * Codec for direnv's "gzenv" format: base64url(zlib(JSON)).
 *
 * Despite the upstream package being named gzenv, the compression is zlib, not gzip.
 * See direnv/gzenv/gzenv.go.
 *
 * This is what makes change detection correct: DIRENV_WATCHES lists every file the environment
 * depends on — flake.nix, flake.lock, .env, allow/deny stamps — not just .envrc.
 */
object DirenvWatchesCodec {

    private val LOG = Logger.getInstance(DirenvWatchesCodec::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Decodes DIRENV_WATCHES. Returns an empty list for any malformed input rather than throwing.
     *
     * [pathMapper] matters more here than anywhere else: these paths are polled for changes, and a
     * path that cannot be reached reads as a file that has just been deleted. Left unmapped, every
     * watch of a WSL project would report a change on every poll, reloading the environment every
     * two seconds for as long as the project stayed open.
     */
    fun decode(
        encoded: String,
        pathMapper: DirenvPathMapper = DirenvPathMapper.SameMachine,
    ): List<DirenvWatch> {
        val trimmed = encoded.trim()
        if (trimmed.isEmpty()) return emptyList()

        val text = try {
            val compressed = Base64.getUrlDecoder().decode(trimmed)
            InflaterInputStream(compressed.inputStream()).use { it.readBytes() }.toString(Charsets.UTF_8)
        } catch (e: Exception) {
            LOG.debug("Failed to decode DIRENV_WATCHES", e)
            return emptyList()
        }

        val array = try {
            json.parseToJsonElement(text) as? JsonArray ?: return emptyList()
        } catch (e: Exception) {
            LOG.debug("Failed to parse DIRENV_WATCHES payload", e)
            return emptyList()
        }

        return array.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            // Current direnv emits lowercase keys; older releases capitalised them.
            val rawPath = obj.stringOf("path") ?: obj.stringOf("Path") ?: return@mapNotNull null
            val path: Path = pathMapper.toLocal(rawPath) ?: run {
                LOG.debug("Skipping a watch path that cannot be expressed here")
                return@mapNotNull null
            }
            DirenvWatch(
                path = path,
                modtime = obj.longOf("modtime") ?: obj.longOf("Modtime") ?: 0L,
                exists = obj.boolOf("exists") ?: obj.boolOf("Exists") ?: true,
            )
        }
    }

    /** Encodes watches back into the gzenv format. Used by tests and fixtures. */
    fun encode(watches: List<DirenvWatch>): String {
        val body = watches.joinToString(",") { watch ->
            """{"path":${quote(watch.path.toString())},"modtime":${watch.modtime},"exists":${watch.exists}}"""
        }
        return encodeRawJson("[$body]")
    }

    /** Encodes an arbitrary JSON string into the gzenv format. Used to build fixtures. */
    fun encodeRawJson(rawJson: String): String {
        val buffer = ByteArrayOutputStream()
        DeflaterOutputStream(buffer).use { it.write(rawJson.toByteArray(Charsets.UTF_8)) }
        return Base64.getUrlEncoder().encodeToString(buffer.toByteArray())
    }

    private fun quote(value: String): String = JsonPrimitive(value).toString()

    private fun JsonObject.stringOf(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.longOf(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull

    private fun JsonObject.boolOf(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
}
