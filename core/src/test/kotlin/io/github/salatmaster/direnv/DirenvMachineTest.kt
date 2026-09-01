package io.github.salatmaster.direnv

import org.assertj.core.api.Assertions.assertThat
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
}
