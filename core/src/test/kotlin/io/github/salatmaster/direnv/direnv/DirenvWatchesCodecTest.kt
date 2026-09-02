package io.github.salatmaster.direnv.direnv

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Paths

class DirenvWatchesCodecTest {

    @Test
    fun `decodes watches round-tripped through the gzenv format`() {
        val original = listOf(
            DirenvWatch(Paths.get("/home/u/project/.envrc"), 1_700_000_000L, true),
            DirenvWatch(Paths.get("/home/u/project/flake.lock"), 1_700_000_001L, true),
        )

        val decoded = DirenvWatchesCodec.decode(DirenvWatchesCodec.encode(original))

        assertThat(decoded).isEqualTo(original)
    }

    @Test
    fun `accepts capitalised Path key emitted by older direnv versions`() {
        val json = """[{"Path":"/home/u/.direnvrc","Modtime":0,"Exists":false}]"""
        val encoded = DirenvWatchesCodec.encodeRawJson(json)

        val decoded = DirenvWatchesCodec.decode(encoded)

        assertThat(decoded).hasSize(1)
        assertThat(decoded[0].path).isEqualTo(Paths.get("/home/u/.direnvrc"))
        assertThat(decoded[0].exists).isFalse()
    }

    @Test
    fun `returns empty list for blank input`() {
        assertThat(DirenvWatchesCodec.decode("")).isEmpty()
        assertThat(DirenvWatchesCodec.decode("   ")).isEmpty()
    }

    @Test
    fun `returns empty list instead of throwing on malformed input`() {
        assertThat(DirenvWatchesCodec.decode("not-valid-base64!!!")).isEmpty()
        assertThat(DirenvWatchesCodec.decode("aGVsbG8gd29ybGQ=")).isEmpty()
    }

    @Test
    fun `skips entries without a usable path`() {
        val json = """[{"modtime":1,"exists":true},{"path":"/tmp/ok","modtime":2,"exists":true}]"""

        val decoded = DirenvWatchesCodec.decode(DirenvWatchesCodec.encodeRawJson(json))

        assertThat(decoded).hasSize(1)
        assertThat(decoded[0].path).isEqualTo(Paths.get("/tmp/ok"))
    }

    @Test
    fun `watch paths are read through the mapper of the machine direnv ran on`() {
        val here = Files.createTempDirectory("direnv-remote-watches")
        val json = """[{"path":"/remote/p/.envrc","modtime":5,"exists":true}]"""

        val decoded = DirenvWatchesCodec.decode(
            DirenvWatchesCodec.encodeRawJson(json),
            FakeRemotePathMapper(here),
        )

        assertThat(decoded.single().path).isEqualTo(here.resolve("p").resolve(".envrc"))
    }

    @Test
    fun `a watch that cannot be expressed here is dropped rather than guessed at`() {
        // Keeping it would put a path nothing can stat into the poll, which reports it as changed
        // on every tick.
        val json = """[{"path":"/elsewhere/.envrc","modtime":5,"exists":true}]"""

        val decoded = DirenvWatchesCodec.decode(
            DirenvWatchesCodec.encodeRawJson(json),
            FakeRemotePathMapper(Files.createTempDirectory("direnv-remote-watches")),
        )

        assertThat(decoded).isEmpty()
    }
}
