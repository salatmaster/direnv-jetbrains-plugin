package io.github.salatmaster.direnv.direnv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Paths

class DirenvWatchesCodecTest {

    @Test
    fun `decodes watches round-tripped through the gzenv format`() {
        val original = listOf(
            DirenvWatch(Paths.get("/home/u/project/.envrc"), 1_700_000_000L, true),
            DirenvWatch(Paths.get("/home/u/project/flake.lock"), 1_700_000_001L, true),
        )

        val decoded = DirenvWatchesCodec.decode(DirenvWatchesCodec.encode(original))

        assertEquals(original, decoded)
    }

    @Test
    fun `accepts capitalised Path key emitted by older direnv versions`() {
        val json = """[{"Path":"/home/u/.direnvrc","Modtime":0,"Exists":false}]"""
        val encoded = DirenvWatchesCodec.encodeRawJson(json)

        val decoded = DirenvWatchesCodec.decode(encoded)

        assertEquals(1, decoded.size)
        assertEquals(Paths.get("/home/u/.direnvrc"), decoded[0].path)
        assertEquals(false, decoded[0].exists)
    }

    @Test
    fun `returns empty list for blank input`() {
        assertTrue(DirenvWatchesCodec.decode("").isEmpty())
        assertTrue(DirenvWatchesCodec.decode("   ").isEmpty())
    }

    @Test
    fun `returns empty list instead of throwing on malformed input`() {
        assertTrue(DirenvWatchesCodec.decode("not-valid-base64!!!").isEmpty())
        assertTrue(DirenvWatchesCodec.decode("aGVsbG8gd29ybGQ=").isEmpty())
    }

    @Test
    fun `skips entries without a usable path`() {
        val json = """[{"modtime":1,"exists":true},{"path":"/tmp/ok","modtime":2,"exists":true}]"""

        val decoded = DirenvWatchesCodec.decode(DirenvWatchesCodec.encodeRawJson(json))

        assertEquals(1, decoded.size)
        assertEquals(Paths.get("/tmp/ok"), decoded[0].path)
    }
}
