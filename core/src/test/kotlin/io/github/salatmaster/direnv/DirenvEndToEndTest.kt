package io.github.salatmaster.direnv

import com.intellij.openapi.util.SystemInfo
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.salatmaster.direnv.direnv.DirenvCli
import io.github.salatmaster.direnv.direnv.GeneralCommandLineRunner
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Drives the real process runner against an executable that speaks direnv's protocol.
 *
 * Exercises process launch, the anti-recursion marker and parsing together — the parts the
 * fake-runner tests deliberately stub out. Skipped on Windows, where the shell-script form does
 * not apply; the fake-runner tests already cover the logic cross-platform.
 */
class DirenvEndToEndTest : BasePlatformTestCase() {

    private lateinit var service: DirenvService

    override fun setUp() {
        super.setUp()
        service = DirenvService.getInstance(project)
        service.invalidate(null)
        // The light project reuses one basePath across tests, but each tearDown deletes the
        // directory behind it. Recreate it, or the process cannot be started at all.
        Files.createDirectories(Paths.get(project.basePath!!))
    }

    override fun tearDown() {
        try {
            service.invalidate(null)
            service.cliOverride = null
        } finally {
            super.tearDown()
        }
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
        val base = Paths.get(project.basePath!!)

        runBlocking { service.load(base, force = true) }

        assertEquals("e2e-value", service.cachedFor(base)?.entries?.get("E2E_VARIABLE"))
    }

    fun `test running direnv does not recurse through the customizer`() {
        if (SystemInfo.isWindows) return

        useFakeDirenv("""{"E2E_VARIABLE":"e2e-value"}""")
        val base = Paths.get(project.basePath!!)

        // Completing at all proves there is no infinite recursion: the registered customizer runs
        // inside this call, on the very command line that launches direnv.
        val state = runBlocking { service.load(base, force = true) }

        assertNotNull("state=$state", service.cachedFor(base))
    }

    fun `test a directory without envrc yields an empty environment rather than an error`() {
        if (SystemInfo.isWindows) return

        useFakeDirenv("{}")
        val base = Paths.get(project.basePath!!)

        val state = runBlocking { service.load(base, force = true) }

        assertTrue("state=$state", state is DirenvState.Loaded)
        assertTrue(service.cachedFor(base)!!.entries.isEmpty())
    }
}
