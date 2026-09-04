package io.github.salatmaster.direnv.javascript

import com.intellij.javascript.nodejs.interpreter.local.NodeJsLocalInterpreter
import com.intellij.javascript.nodejs.interpreter.wsl.WslNodeInterpreter
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Paths

class DirenvNodeInterpretersTest {

    private val unc = Paths.get("//wsl.localhost/NixOS/nix/store/abc/bin/node")
    private val inWsl = DirenvWslLocation("NixOS", "/nix/store/abc/bin/node")

    @Test
    fun `a project on this machine gets a local interpreter`() {
        val path = Paths.get("/nix/store/abc/bin/node")

        val interpreter = DirenvNodeInterpreters.interpreterFor(local = true, path = path, wsl = null)

        assertThat(interpreter).isInstanceOf(NodeJsLocalInterpreter::class.java)
    }

    @Test
    fun `a project in WSL gets an interpreter named the way WSL names it`() {
        // The point of the whole thing: the resolved path is \\wsl.localhost\..., and handing that
        // to a local interpreter would have the IDE start an ELF binary as a Windows process.
        val interpreter = DirenvNodeInterpreters.interpreterFor(local = false, path = unc, wsl = inWsl)

        val wslInterpreter = interpreter as WslNodeInterpreter
        assertThat(wslInterpreter.wslDistributionId).isEqualTo("NixOS")
        assertThat(wslInterpreter.wslInterpreterPath).isEqualTo("/nix/store/abc/bin/node")
    }

    @Test
    fun `a WSL interpreter is referenced by a name the IDE can resolve back`() {
        // WslNodeInterpreterManager.findByReferenceName rebuilds the interpreter out of this
        // string, which is why nothing has to be registered before applying the suggestion.
        val interpreter = DirenvNodeInterpreters.interpreterFor(local = false, path = unc, wsl = inWsl)!!

        assertThat(interpreter.referenceName).isEqualTo("wsl://NixOS@/nix/store/abc/bin/node")
    }

    @Test
    fun `a project on a machine with no interpreter type of its own is left alone`() {
        // A remote host over SSH, say. Offering the path as a local interpreter would configure
        // the project with one that cannot start, which is worse than offering nothing.
        val interpreter = DirenvNodeInterpreters.interpreterFor(
            local = false,
            path = Paths.get("/remote/bin/node"),
            wsl = null,
        )

        assertThat(interpreter).isNull()
    }

    @Test
    fun `a WSL interpreter is described by its own machine's path, not by the UNC one`() {
        assertThat(DirenvNodeInterpreters.describe(unc, inWsl))
            .isEqualTo("/nix/store/abc/bin/node in NixOS")
    }

    @Test
    fun `a local interpreter is described by its path`() {
        val path = Paths.get("/nix/store/abc/bin/node")

        assertThat(DirenvNodeInterpreters.describe(path, null)).isEqualTo(path.toString())
    }
}
