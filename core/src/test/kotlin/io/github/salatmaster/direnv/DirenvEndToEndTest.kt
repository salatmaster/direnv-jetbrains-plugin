package io.github.salatmaster.direnv

import com.intellij.openapi.util.SystemInfo
import io.github.salatmaster.direnv.direnv.DirenvCli
import io.github.salatmaster.direnv.direnv.GeneralCommandLineRunner
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path

/**
 * Drives the real process runner against an executable that speaks direnv's protocol.
 *
 * Exercises process launch, the anti-recursion marker and parsing together — the parts the
 * fake-runner tests deliberately stub out. Skipped on Windows, where the shell-script form does
 * not apply; the fake-runner tests already cover the logic cross-platform.
 */
class DirenvEndToEndTest : DirenvLightTestCase() {

    private lateinit var service: DirenvService

    override fun setUp() {
        super.setUp()
        service = DirenvService.getInstance(project)
    }

    private fun writeFakeDirenv(exportJson: String): Path {
        val script = Files.createTempFile("fake-direnv", ".sh")
        // \$1 is escaped so Kotlin emits a literal shell positional parameter, not a template.
        Files.writeString(
            script,
            "#!/bin/sh\n" +
                "case \"\$1\" in\n" +
                "  export) printf '%s' '$exportJson' ;;\n" +
                "  version) echo 2.37.1 ;;\n" +
                "  *) exit 0 ;;\n" +
                "esac\n",
        )
        script.toFile().setExecutable(true)
        return script
    }

    private fun useFakeDirenv(exportJson: String) {
        val script = writeFakeDirenv(exportJson)
        service.cliOverride = DirenvCli(
            runner = GeneralCommandLineRunner(),
            executableProvider = { script.toString() },
            extraEnvProvider = { emptyMap() },
            timeoutMsProvider = { 30_000 },
        )
    }

    fun `test loads an environment through a real process`() {
        if (SystemInfo.isWindows) return

        useFakeDirenv("""{"E2E_VARIABLE":"e2e-value"}""")
        val base = workDir

        runBlocking { service.load(base, force = true) }

        assertEquals("e2e-value", service.cachedFor(base)?.entries?.get("E2E_VARIABLE"))
    }

    fun `test running direnv does not recurse through the customizer`() {
        if (SystemInfo.isWindows) return

        useFakeDirenv("""{"E2E_VARIABLE":"e2e-value"}""")
        val base = workDir

        // Completing at all proves there is no infinite recursion: the registered customizer runs
        // inside this call, on the very command line that launches direnv.
        val state = runBlocking { service.load(base, force = true) }

        assertNotNull("state=$state", service.cachedFor(base))
    }

    fun `test a directory without envrc yields an empty environment rather than an error`() {
        if (SystemInfo.isWindows) return

        useFakeDirenv("{}")
        val base = workDir

        val state = runBlocking { service.load(base, force = true) }

        assertTrue("state=$state", state is DirenvState.Loaded)
        assertTrue(service.cachedFor(base)!!.entries.isEmpty())
    }
}
