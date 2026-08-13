package io.github.salatmaster.direnv.project

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.nio.file.Paths

class DirenvProjectResolverTest {

    private fun roots(id: String, vararg paths: String) =
        ProjectRoots(id, paths.map { Paths.get(it) })

    @Test
    fun `resolves a project containing the working directory`() {
        val projects = listOf(roots("a", "/home/u/a"), roots("b", "/home/u/b"))

        assertEquals("b", DirenvProjectResolver.resolve(Paths.get("/home/u/b/sub"), projects))
    }

    @Test
    fun `resolves the working directory equal to the root itself`() {
        val projects = listOf(roots("a", "/home/u/a"))

        assertEquals("a", DirenvProjectResolver.resolve(Paths.get("/home/u/a"), projects))
    }

    @Test
    fun `prefers the longest matching root for nested projects`() {
        val projects = listOf(roots("outer", "/home/u/outer"), roots("inner", "/home/u/outer/inner"))

        assertEquals("inner", DirenvProjectResolver.resolve(Paths.get("/home/u/outer/inner/x"), projects))
    }

    @Test
    fun `returns null when no project contains the working directory`() {
        val projects = listOf(roots("a", "/home/u/a"))

        assertNull(DirenvProjectResolver.resolve(Paths.get("/tmp/elsewhere"), projects))
    }

    @Test
    fun `does not treat a sibling with a shared name prefix as a match`() {
        val projects = listOf(roots("a", "/home/u/app"))

        assertNull(DirenvProjectResolver.resolve(Paths.get("/home/u/app-backup/src"), projects))
    }

    @Test
    fun `considers every content root of a project`() {
        val projects = listOf(roots("multi", "/home/u/main", "/home/u/extra"))

        assertEquals("multi", DirenvProjectResolver.resolve(Paths.get("/home/u/extra/deep"), projects))
    }

    @Test
    fun `normalises relative segments before matching`() {
        val projects = listOf(roots("a", "/home/u/a"))

        assertEquals("a", DirenvProjectResolver.resolve(Paths.get("/home/u/a/b/../c"), projects))
    }

    @Test
    fun `returns null for an empty candidate list`() {
        assertNull(DirenvProjectResolver.resolve(Paths.get("/home/u/a"), emptyList()))
    }
}
