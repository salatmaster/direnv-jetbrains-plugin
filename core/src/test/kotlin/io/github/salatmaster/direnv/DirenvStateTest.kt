package io.github.salatmaster.direnv

import io.github.salatmaster.direnv.direnv.DirenvDiff
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DirenvStateTest {

    @Test
    fun `both states awaiting approval report that they need it`() {
        assertTrue(DirenvState.Blocked("/p/.envrc").needsApproval)
        assertTrue(DirenvState.Denied("/p/.envrc").needsApproval)
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
            assertFalse("$state should not ask for approval", state.needsApproval)
        }
    }
}
