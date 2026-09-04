package io.github.salatmaster.direnv.gradle

import io.github.salatmaster.direnv.direnv.DirenvPathMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.nio.file.Paths

class DirenvGradleWorkingDirectoryTest {

    private val root = Paths.get("/p").toAbsolutePath()

    /**
     * A project on another machine: over there paths start with /remote, here they live under
     * [here]. Enough to exercise the branch that cannot be run on the machine this is built on.
     */
    private class RemoteMapper(private val here: Path) : DirenvPathMapper {

        override fun toLocal(reported: String): Path? =
            if (!reported.startsWith("/remote/")) {
                null
            } else {
                reported.removePrefix("/remote/").split('/').fold(here) { path, part -> path.resolve(part) }
            }

        override fun toDirenv(path: Path): String? = null
    }

    @Test
    fun `prefers the linked Gradle project over the project root`() {
        // A Gradle module below the root can carry its own .envrc, and the environment cache walks
        // parents — so naming the module keeps the nearest one, while naming the root loses it.
        val module = root.resolve("service")

        val resolved = DirenvGradleWorkingDirectory.resolve(
            mapper = DirenvPathMapper.SameMachine,
            projectPath = module.toString(),
            projectRoot = root,
        )

        assertThat(resolved).isEqualTo(module)
    }

    @Test
    fun `reads the linked project path the way the project's machine writes it`() {
        // The regression this guards: the path used to be read with this JVM's rules for a project
        // on another machine, so on Windows /remote/service became a drive-relative C:\remote\...
        // that names nothing, and the whole Gradle module silently fell back to the project root.
        val here = Paths.get("/mapped").toAbsolutePath()

        val resolved = DirenvGradleWorkingDirectory.resolve(
            mapper = RemoteMapper(here),
            projectPath = "/remote/service",
            projectRoot = root,
        )

        assertThat(resolved).isEqualTo(here.resolve("service"))
    }

    @Test
    fun `falls back to the project root when the path cannot be expressed here`() {
        val resolved = DirenvGradleWorkingDirectory.resolve(
            mapper = RemoteMapper(Paths.get("/mapped").toAbsolutePath()),
            projectPath = "/elsewhere/service",
            projectRoot = root,
        )

        assertThat(resolved).isEqualTo(root)
    }

    @Test
    fun `reports nothing rather than guessing when neither is usable`() {
        val resolved = DirenvGradleWorkingDirectory.resolve(
            mapper = RemoteMapper(Paths.get("/mapped").toAbsolutePath()),
            projectPath = "/elsewhere/service",
            projectRoot = null,
        )

        assertThat(resolved).isNull()
    }
}
