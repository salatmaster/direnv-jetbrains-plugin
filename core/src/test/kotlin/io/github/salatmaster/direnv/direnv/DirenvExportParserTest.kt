package io.github.salatmaster.direnv.direnv

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class DirenvExportParserTest {

    @Test
    fun `parses string values`() {
        val parsed = DirenvExportParser.parseEntries("""{"FOO":"bar","BAZ":"qux"}""")

        assertThat(parsed).isEqualTo(mapOf<String, String?>("FOO" to "bar", "BAZ" to "qux"))
    }

    @Test
    fun `parses null as an unset request`() {
        val parsed = DirenvExportParser.parseEntries("""{"GONE":null}""")

        assertThat(parsed).containsKey("GONE")
        assertThat(parsed["GONE"]).isNull()
    }

    @Test
    fun `preserves values containing newlines and quotes`() {
        val parsed = DirenvExportParser.parseEntries("""{"MULTI":"a\nb\"c"}""")

        assertThat(parsed["MULTI"]).isEqualTo("a\nb\"c")
    }

    @Test
    fun `returns empty map for empty or blank stdout`() {
        assertThat(DirenvExportParser.parseEntries("")).isEmpty()
        assertThat(DirenvExportParser.parseEntries("  \n ")).isEmpty()
    }

    @Test
    fun `returns empty map for malformed json instead of throwing`() {
        assertThat(DirenvExportParser.parseEntries("{not json")).isEmpty()
        assertThat(DirenvExportParser.parseEntries("[1,2,3]")).isEmpty()
    }

    @Test
    fun `extracts the blocked path from stderr`() {
        val stderr = "direnv: error /home/u/project/.envrc is blocked. " +
            "Run `direnv allow` to approve its content"

        assertThat(DirenvExportParser.findBlockedPath(stderr)).isEqualTo("/home/u/project/.envrc")
    }

    @Test
    fun `extracts the blocked path when other direnv output precedes it`() {
        val stderr = "direnv: loading something\ndirenv: error /a b/c/.envrc is blocked.\n"

        assertThat(DirenvExportParser.findBlockedPath(stderr)).isEqualTo("/a b/c/.envrc")
    }

    @Test
    fun `extracts the blocked path from colourised stderr`() {
        // direnv colourises stderr even with TERM=dumb, verified against direnv 2.37.1.
        val stderr = "\u001B[31mdirenv: error /home/u/project/.envrc is blocked. " +
            "Run `direnv allow` to approve its content\u001B[0m"

        assertThat(DirenvExportParser.findBlockedPath(stderr)).isEqualTo("/home/u/project/.envrc")
    }

    @Test
    fun `strips ANSI escapes so messages shown to the user are readable`() {
        val coloured = "\u001B[31mdirenv: error syntax error\u001B[0m"

        assertThat(DirenvExportParser.stripAnsi(coloured).trim()).isEqualTo("direnv: error syntax error")
    }

    @Test
    fun `stripAnsi leaves plain text untouched`() {
        assertThat(DirenvExportParser.stripAnsi("plain message")).isEqualTo("plain message")
    }

    @Test
    fun `returns null when stderr describes a different problem`() {
        assertThat(DirenvExportParser.findBlockedPath("direnv: error some other failure")).isNull()
        assertThat(DirenvExportParser.findBlockedPath("")).isNull()
    }
}
