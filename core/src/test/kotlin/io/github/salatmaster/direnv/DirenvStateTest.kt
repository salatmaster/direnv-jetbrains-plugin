package io.github.salatmaster.direnv

import io.github.salatmaster.direnv.direnv.DirenvDiff
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class DirenvStateTest {

    @Test
    fun `both states awaiting approval report that they need it`() {
        assertThat(DirenvState.Blocked("/p/.envrc").needsApproval).isTrue()
        assertThat(DirenvState.Denied("/p/.envrc").needsApproval).isTrue()
    }

    @Test
    fun `no other state asks for approval`() {
        // Failed matters most here: an .envrc with a syntax error is approved and broken, not
        // unapproved. Offering Allow for it would suggest a fix that does nothing.
        val approved = listOf(
            DirenvState.NotLoaded,
            DirenvState.Loading,
            DirenvState.Loaded(DirenvDiff(emptyList(), emptyList(), emptyList())),
            DirenvState.Failed("syntax error near line 3"),
            DirenvState.ExecutableMissing("direnv"),
        )

        for (state in approved) {
            assertThat(state.needsApproval).withFailMessage("$state should not ask for approval").isFalse()
        }
    }
}
