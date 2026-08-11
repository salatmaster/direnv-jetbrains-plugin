package io.github.salatmaster.direnv.direnv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DirenvExportParserTest {

    @Test
    fun `parses string values`() {
        val parsed = DirenvExportParser.parseEntries("""{"FOO":"bar","BAZ":"qux"}""")

        assertEquals(mapOf<String, String?>("FOO" to "bar", "BAZ" to "qux"), parsed)
    }

    @Test
    fun `parses null as an unset request`() {
        val parsed = DirenvExportParser.parseEntries("""{"GONE":null}""")

        assertTrue(parsed.containsKey("GONE"))
        assertNull(parsed["GONE"])
    }

    @Test
    fun `preserves values containing newlines and quotes`() {
        val parsed = DirenvExportParser.parseEntries("""{"MULTI":"a\nb\"c"}""")

        assertEquals("a\nb\"c", parsed["MULTI"])
    }

    @Test
    fun `returns empty map for empty or blank stdout`() {
        assertTrue(DirenvExportParser.parseEntries("").isEmpty())
        assertTrue(DirenvExportParser.parseEntries("  \n ").isEmpty())
    }

    @Test
    fun `returns empty map for malformed json instead of throwing`() {
        assertTrue(DirenvExportParser.parseEntries("{not json").isEmpty())
        assertTrue(DirenvExportParser.parseEntries("[1,2,3]").isEmpty())
    }

    @Test
    fun `extracts the blocked path from stderr`() {
        val stderr = "direnv: error /home/u/project/.envrc is blocked. " +
            "Run `direnv allow` to approve its content"

        assertEquals("/home/u/project/.envrc", DirenvExportParser.findBlockedPath(stderr))
    }

    @Test
    fun `extracts the blocked path when other direnv output precedes it`() {
        val stderr = "direnv: loading something\ndirenv: error /a b/c/.envrc is blocked.\n"

        assertEquals("/a b/c/.envrc", DirenvExportParser.findBlockedPath(stderr))
    }

    @Test
    fun `extracts the blocked path from colourised stderr`() {
        // direnv colourises stderr even with TERM=dumb, verified against direnv 2.37.1.
        val stderr = "\u001B[31mdirenv: error /home/u/project/.envrc is blocked. " +
            "Run `direnv allow` to approve its content\u001B[0m"

        assertEquals("/home/u/project/.envrc", DirenvExportParser.findBlockedPath(stderr))
    }

    @Test
    fun `strips ANSI escapes so messages shown to the user are readable`() {
        val coloured = "\u001B[31mdirenv: error syntax error\u001B[0m"

        assertEquals("direnv: error syntax error", DirenvExportParser.stripAnsi(coloured).trim())
    }

    @Test
    fun `stripAnsi leaves plain text untouched`() {
        assertEquals("plain message", DirenvExportParser.stripAnsi("plain message"))
    }

    @Test
    fun `returns null when stderr describes a different problem`() {
        assertNull(DirenvExportParser.findBlockedPath("direnv: error some other failure"))
        assertNull(DirenvExportParser.findBlockedPath(""))
    }
}
