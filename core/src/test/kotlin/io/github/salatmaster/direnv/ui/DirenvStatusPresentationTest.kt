package io.github.salatmaster.direnv.ui

import io.github.salatmaster.direnv.DirenvState
import io.github.salatmaster.direnv.direnv.DirenvDiff
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class DirenvStatusPresentationTest {

    private fun diff(added: List<String> = emptyList(), changed: List<String> = emptyList(), removed: List<String> = emptyList()) =
        DirenvDiff(added, changed, removed)

    @Test
    fun `loaded state shows the change counts`() {
        val text = DirenvStatusPresentation.of(
            DirenvState.Loaded(diff(added = listOf("A", "B"), changed = listOf("C"))),
        ).text

        assertThat(text).contains("+2")
        assertThat(text).contains("~1")
        assertThat(text).contains("-0")
    }

    @Test
    fun `loaded state never shows variable names`() {
        val presentation = DirenvStatusPresentation.of(
            DirenvState.Loaded(diff(added = listOf("SECRET_TOKEN"))),
        )

        assertThat(presentation.text).doesNotContain("SECRET_TOKEN")
        assertThat(presentation.tooltip).doesNotContain("SECRET_TOKEN")
    }

    @Test
    fun `an empty environment reads as inactive rather than as an error`() {
        val presentation = DirenvStatusPresentation.of(DirenvState.Loaded(diff()))

        assertThat(presentation.kind).isEqualTo(DirenvStatusPresentation.Kind.INACTIVE)
    }

    @Test
    fun `blocked state names the file and offers approval`() {
        val presentation = DirenvStatusPresentation.of(DirenvState.Blocked("/p/.envrc"))

        assertThat(presentation.kind).isEqualTo(DirenvStatusPresentation.Kind.BLOCKED)
        assertThat(presentation.tooltip).contains("/p/.envrc")
    }

    @Test
    fun `missing executable is distinguished from a failure`() {
        val missing = DirenvStatusPresentation.of(DirenvState.ExecutableMissing("direnv"))
        val failed = DirenvStatusPresentation.of(DirenvState.Failed("syntax error"))

        assertThat(missing.kind).isEqualTo(DirenvStatusPresentation.Kind.NOT_INSTALLED)
        assertThat(failed.kind).isEqualTo(DirenvStatusPresentation.Kind.FAILED)
    }

    @Test
    fun `a revoked approval reads as blocked rather than as a loaded environment`() {
        // direnv exports nothing for a denied .envrc, so without its own state this would render
        // as "loaded, no variables changed" — telling the user everything is fine moments after
        // they revoked approval.
        val presentation = DirenvStatusPresentation.of(DirenvState.Denied("/p/.envrc"))

        assertThat(presentation.kind).isEqualTo(DirenvStatusPresentation.Kind.BLOCKED)
        assertThat(presentation.text).isEqualTo("direnv blocked")
        assertThat(presentation.tooltip).contains("/p/.envrc")
        assertThat(presentation.tooltip).contains("revoked")
    }

    @Test
    fun `blocked and denied are indistinguishable in the status bar text`() {
        // Both mean the same thing to the user: direnv will not run this until it is allowed.
        assertThat(DirenvStatusPresentation.of(DirenvState.Denied("/p/.envrc")).text)
            .isEqualTo(DirenvStatusPresentation.of(DirenvState.Blocked("/p/.envrc")).text)
    }

    @Test
    fun `failure tooltip carries the direnv message`() {
        val presentation = DirenvStatusPresentation.of(DirenvState.Failed("syntax error near line 3"))

        assertThat(presentation.tooltip).contains("syntax error near line 3")
    }

    @Test
    fun `loading and not-loaded states are represented`() {
        assertThat(DirenvStatusPresentation.of(DirenvState.Loading).kind)
            .isEqualTo(DirenvStatusPresentation.Kind.LOADING)
        assertThat(DirenvStatusPresentation.of(DirenvState.NotLoaded).kind)
            .isEqualTo(DirenvStatusPresentation.Kind.INACTIVE)
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
            assertThat(presentation.text).withFailMessage("empty text for $state").isNotBlank()
            assertThat(presentation.tooltip).withFailMessage("empty tooltip for $state").isNotBlank()
        }
    }
}
