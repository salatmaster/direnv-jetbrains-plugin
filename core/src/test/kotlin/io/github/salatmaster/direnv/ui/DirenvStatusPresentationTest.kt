package io.github.salatmaster.direnv.ui

import io.github.salatmaster.direnv.DirenvState
import io.github.salatmaster.direnv.direnv.DirenvDiff
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DirenvStatusPresentationTest {

    private fun diff(added: List<String> = emptyList(), changed: List<String> = emptyList(), removed: List<String> = emptyList()) =
        DirenvDiff(added, changed, removed)

    @Test
    fun `loaded state shows the change counts`() {
        val text = DirenvStatusPresentation.of(
            DirenvState.Loaded(diff(added = listOf("A", "B"), changed = listOf("C"))),
        ).text

        assertTrue(text.contains("+2"), text)
        assertTrue(text.contains("~1"), text)
        assertTrue(text.contains("-0"), text)
    }

    @Test
    fun `loaded state never shows variable names`() {
        val presentation = DirenvStatusPresentation.of(
            DirenvState.Loaded(diff(added = listOf("SECRET_TOKEN"))),
        )

        assertFalse(presentation.text.contains("SECRET_TOKEN"))
        assertFalse(presentation.tooltip.contains("SECRET_TOKEN"))
    }

    @Test
    fun `an empty environment reads as inactive rather than as an error`() {
        val presentation = DirenvStatusPresentation.of(DirenvState.Loaded(diff()))

        assertEquals(DirenvStatusPresentation.Kind.INACTIVE, presentation.kind)
    }

    @Test
    fun `blocked state names the file and offers approval`() {
        val presentation = DirenvStatusPresentation.of(DirenvState.Blocked("/p/.envrc"))

        assertEquals(DirenvStatusPresentation.Kind.BLOCKED, presentation.kind)
        assertTrue(presentation.tooltip.contains("/p/.envrc"))
    }

    @Test
    fun `missing executable is distinguished from a failure`() {
        val missing = DirenvStatusPresentation.of(DirenvState.ExecutableMissing("direnv"))
        val failed = DirenvStatusPresentation.of(DirenvState.Failed("syntax error"))

        assertEquals(DirenvStatusPresentation.Kind.NOT_INSTALLED, missing.kind)
        assertEquals(DirenvStatusPresentation.Kind.FAILED, failed.kind)
    }

    @Test
    fun `a revoked approval reads as blocked rather than as a loaded environment`() {
        // direnv exports nothing for a denied .envrc, so without its own state this would render
        // as "loaded, no variables changed" — telling the user everything is fine moments after
        // they revoked approval.
        val presentation = DirenvStatusPresentation.of(DirenvState.Denied("/p/.envrc"))

        assertEquals(DirenvStatusPresentation.Kind.BLOCKED, presentation.kind)
        assertEquals("direnv blocked", presentation.text)
        assertTrue(presentation.tooltip.contains("/p/.envrc"), presentation.tooltip)
        assertTrue(presentation.tooltip.contains("revoked"), presentation.tooltip)
    }

    @Test
    fun `blocked and denied are indistinguishable in the status bar text`() {
        // Both mean the same thing to the user: direnv will not run this until it is allowed.
        assertEquals(
            DirenvStatusPresentation.of(DirenvState.Blocked("/p/.envrc")).text,
            DirenvStatusPresentation.of(DirenvState.Denied("/p/.envrc")).text,
        )
    }

    @Test
    fun `failure tooltip carries the direnv message`() {
        val presentation = DirenvStatusPresentation.of(DirenvState.Failed("syntax error near line 3"))

        assertTrue(presentation.tooltip.contains("syntax error near line 3"))
    }

    @Test
    fun `loading and not-loaded states are represented`() {
        assertEquals(DirenvStatusPresentation.Kind.LOADING, DirenvStatusPresentation.of(DirenvState.Loading).kind)
        assertEquals(DirenvStatusPresentation.Kind.INACTIVE, DirenvStatusPresentation.of(DirenvState.NotLoaded).kind)
    }

    @Test
    fun `every state produces a non-blank text and tooltip`() {
        val states = listOf(
            DirenvState.NotLoaded,
            DirenvState.Loading,
            DirenvState.Loaded(diff(added = listOf("A"))),
            DirenvState.Blocked("/p/.envrc"),
            DirenvState.Denied("/p/.envrc"),
            DirenvState.Failed("boom"),
            DirenvState.ExecutableMissing("direnv"),
        )

        for (state in states) {
            val presentation = DirenvStatusPresentation.of(state)
            assertTrue(presentation.text.isNotBlank(), "empty text for $state")
            assertTrue(presentation.tooltip.isNotBlank(), "empty tooltip for $state")
        }
    }
}
