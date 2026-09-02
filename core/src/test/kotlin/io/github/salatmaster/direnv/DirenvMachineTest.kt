package io.github.salatmaster.direnv

import org.assertj.core.api.Assertions.assertThat
import java.io.File
import java.nio.file.Paths

class DirenvMachineTest : DirenvLightTestCase() {

    fun `test a local project keeps naming its directory the direct way`() {
        // The WSL fix must not touch the ordinary case: for a project on this machine the answer is
        // still exactly project.basePath, with no mapping in between.
        assertThat(DirenvMachine.isLocal(project)).isTrue()
        assertThat(DirenvMachine.projectDir(project)).isEqualTo(Paths.get(project.basePath!!))
    }

    fun `test the directory is usable, not merely well-formed`() {
        // #21 arrived as a path that parsed cleanly and named nothing: Paths.get("/home/u/p") on
        // Windows yields a drive-relative C:\home\u\p without complaining. Existence is the check
        // that tells the two apart.
        val dir = DirenvMachine.projectDir(project)

        assertThat(dir).isNotNull()
        assertThat(dir!!.toFile()).exists()
    }

    fun `test a local project maps paths to itself`() {
        // The mapper is what stands between direnv's spelling of a path and this JVM's. For a
        // project on this machine there is nothing to translate, and translating anyway would be a
        // new way for the ordinary case to break.
        val mapper = DirenvMachine.pathMapper(project)
        val envrc = Paths.get(project.basePath!!).resolve(".envrc")

        assertThat(mapper.toLocal(envrc.toString())).isEqualTo(envrc)
        assertThat(mapper.toDirenv(envrc)).isEqualTo(envrc.toString())
    }

    fun `test a local project reads its environment with this machine's conventions`() {
        assertThat(DirenvMachine.toolchainMachine(project).splitPath("a${File.pathSeparatorChar}b"))
            .hasSize(2)
    }
}
