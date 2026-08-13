package io.github.salatmaster.direnv.project

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Paths

class DirenvProjectResolverTest {

    private fun roots(id: String, vararg paths: String) =
        ProjectRoots(id, paths.map { Paths.get(it) })

    @Test
    fun `resolves a project containing the working directory`() {
        val projects = listOf(roots("a", "/home/u/a"), roots("b", "/home/u/b"))

        assertThat(DirenvProjectResolver.resolve(Paths.get("/home/u/b/sub"), projects)).isEqualTo("b")
    }

    @Test
    fun `resolves the working directory equal to the root itself`() {
        val projects = listOf(roots("a", "/home/u/a"))

        assertThat(DirenvProjectResolver.resolve(Paths.get("/home/u/a"), projects)).isEqualTo("a")
    }

    @Test
    fun `prefers the longest matching root for nested projects`() {
        val projects = listOf(roots("outer", "/home/u/outer"), roots("inner", "/home/u/outer/inner"))

        assertThat(DirenvProjectResolver.resolve(Paths.get("/home/u/outer/inner/x"), projects))
            .isEqualTo("inner")
    }

    @Test
    fun `returns null when no project contains the working directory`() {
        val projects = listOf(roots("a", "/home/u/a"))

        assertThat(DirenvProjectResolver.resolve(Paths.get("/tmp/elsewhere"), projects)).isNull()
    }

    @Test
    fun `does not treat a sibling with a shared name prefix as a match`() {
        val projects = listOf(roots("a", "/home/u/app"))

        assertThat(DirenvProjectResolver.resolve(Paths.get("/home/u/app-backup/src"), projects)).isNull()
    }

    @Test
    fun `considers every content root of a project`() {
        val projects = listOf(roots("multi", "/home/u/main", "/home/u/extra"))

        assertThat(DirenvProjectResolver.resolve(Paths.get("/home/u/extra/deep"), projects))
            .isEqualTo("multi")
    }

    @Test
    fun `normalises relative segments before matching`() {
        val projects = listOf(roots("a", "/home/u/a"))

        assertThat(DirenvProjectResolver.resolve(Paths.get("/home/u/a/b/../c"), projects)).isEqualTo("a")
    }

    @Test
    fun `returns null for an empty candidate list`() {
        assertThat(DirenvProjectResolver.resolve(Paths.get("/home/u/a"), emptyList())).isNull()
    }
}
