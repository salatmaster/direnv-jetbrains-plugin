package io.github.salatmaster.direnv

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.salatmaster.direnv.settings.DirenvSettings
import io.github.salatmaster.direnv.watch.DirenvWatchService
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Base for tests that run against the light project fixture.
 *
 * `BasePlatformTestCase` hands every test in every class the same light project, so whatever a
 * project-level service holds outlives the test that put it there:
 *
 * - `DirenvService` keeps a cached environment, and a later load short-circuits on it;
 * - `DirenvWatchService` keeps a two-second poll running over the files a test registered. By the
 *   time it ticks those files are usually gone, since each `tearDown` deletes the project
 *   directory, so the poll correctly reports a change and forces a reload — into whichever test is
 *   running by then, through whatever CLI that test installed, or through real direnv once the
 *   override has been cleared. The test that fails is never the one at fault, and the one at fault
 *   has already passed;
 * - `DirenvSettings` keeps whatever a test assigned to it.
 *
 * Resetting all three in one place is what stops the next test class from rediscovering this.
 *
 * One inherited constraint is worth knowing before writing a test here. JUnit 3 recognises a test
 * only if the method returns void, and an expression-bodied test ends in the type of its last
 * statement — which for an AssertJ assertion is the assertion object, not Unit. Such a method is
 * not a test at all, and the class reports "No tests found" instead of failing anything, so the
 * subclasses write `= runBlocking<Unit> { ... }` rather than `= runBlocking { ... }`.
 */
abstract class DirenvLightTestCase : BasePlatformTestCase() {

    protected val workDir: Path get() = Paths.get(project.basePath!!)

    override fun setUp() {
        super.setUp()
        // Each tearDown deletes the directory behind basePath, so a test that starts a real
        // process, or writes a fixture file, needs it back.
        Files.createDirectories(workDir)
        resetProjectServices()
    }

    override fun tearDown() {
        try {
            resetProjectServices()
        } finally {
            super.tearDown()
        }
    }

    private fun resetProjectServices() {
        // The watch service goes first: cancelling the poll before the CLI override is taken away
        // is what keeps a reload from reaching real direnv. dispose() is the service's own
        // cleanup — cancel the jobs, drop the roots, empty the registry — and the platform calling
        // it again when the project closes repeats exactly that.
        DirenvWatchService.getInstance(project).dispose()
        DirenvService.getInstance(project).let {
            it.invalidate(null)
            it.cliOverride = null
        }
        DirenvSettings.getInstance(project).loadState(DirenvSettings.State())
    }
}
