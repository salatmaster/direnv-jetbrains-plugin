package io.github.salatmaster.direnv.direnv

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.runBlockingCancellable
import com.intellij.openapi.project.Project
import com.intellij.platform.eel.EelApi
import com.intellij.platform.eel.EelDescriptor
import com.intellij.platform.eel.EelOsFamily
import com.intellij.platform.eel.ExecuteProcessException
import com.intellij.platform.eel.environmentVariables
import com.intellij.platform.eel.expandPathEnvVar
import com.intellij.platform.eel.path.EelPath
import com.intellij.platform.eel.provider.asEelPath
import com.intellij.platform.eel.provider.asNioPath
import com.intellij.platform.eel.provider.getEelDescriptor
import com.intellij.platform.eel.provider.toEelApiBlocking
import com.intellij.platform.eel.provider.utils.awaitProcessResult
import com.intellij.platform.eel.provider.utils.stderrString
import com.intellij.platform.eel.provider.utils.stdoutString
import com.intellij.platform.eel.spawnProcess
import com.intellij.platform.eel.where
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.time.Duration.Companion.milliseconds

/**
 * Runs direnv on the machine the project lives on.
 *
 * [GeneralCommandLineRunner] starts a process on the machine running the IDE, which is the wrong
 * one whenever the project is in WSL or on a remote host: the working directory is expressed in
 * that machine's path syntax, and the direnv binary is installed there rather than here. Both were
 * reported in #21, where a POSIX project directory reached a Windows process launcher and came back
 * as `C:\home\...`.
 *
 * Only used when the project is not local, so the ordinary case keeps the well-worn code path.
 */
class EelDirenvProcessRunner(private val project: Project) : DirenvProcessRunner {

    private val log = Logger.getInstance(EelDirenvProcessRunner::class.java)

    override fun run(
        executable: String,
        args: List<String>,
        workingDir: Path,
        extraEnv: Map<String, String>,
        timeoutMs: Int,
    ): DirenvProcessResult {
        val eel = try {
            project.getEelDescriptor().toEelApiBlocking()
        } catch (e: Exception) {
            throw DirenvProcessFailedException("Cannot reach the machine this project lives on", e)
        }

        val cwd = try {
            workingDir.asEelPath()
        } catch (e: Exception) {
            throw DirenvProcessFailedException("Cannot express $workingDir on that machine", e)
        }

        val machine = eel.descriptor.name

        return runBlockingCancellable {
            // The timeout covers the whole exchange, not just the direnv process: reading the shell
            // environment starts a shell over there, and a login script that blocks would otherwise
            // hang the load with nothing to cancel it.
            val result = withTimeoutOrNull(timeoutMs.milliseconds) {
                val shellEnv = shellEnvironment(eel, machine)
                val exe = resolveExecutable(eel, executable, shellEnv, machine)

                val process = try {
                    eel.exec.spawnProcess(exe.toString())
                        .args(args)
                        // extraEnv last: TERM=dumb and the user's own additions win over the shell.
                        .env(shellEnv + extraEnv)
                        .workingDirectory(cwd)
                        .eelIt()
                } catch (e: ExecuteProcessException) {
                    // Not reported as a missing executable, however much errno 2 looks like one:
                    // ENOENT from a spawn covers the working directory just as well, that is
                    // exactly what #21 turned out to be, and the executable has already been
                    // resolved above. Naming both is what tells the two apart.
                    throw DirenvProcessFailedException(
                        "Failed to run $exe in $cwd on $machine: ${e.message} (errno ${e.errno})",
                        e,
                    )
                }
                process.awaitProcessResult()
            } ?: throw DirenvProcessFailedException("$executable timed out after ${timeoutMs}ms", null)

            DirenvProcessResult(result.exitCode, result.stdoutString, result.stderrString)
        }
    }

    /**
     * The environment a shell on that machine would start in.
     *
     * direnv is a shell tool: it evaluates `.envrc` with bash, and that file reaches for nix,
     * devbox or devenv, none of which has to be present in the environment the IDE's own connection
     * to the machine happens to have inherited. The platform documents this map as what to pass
     * when a process should run "in an environment like in a terminal", and caches it, so this does
     * not start a shell per direnv invocation.
     *
     * `DIRENV_*` is dropped. A direnv user has direnv hooked into their login shell, and handing
     * those variables back to direnv would have it export a diff against a state belonging to some
     * other directory — including, when it decides the environment is already loaded, no diff at
     * all.
     */
    private suspend fun shellEnvironment(eel: EelApi, machine: String): Map<String, String> {
        val variables = try {
            eel.exec.environmentVariables().eelIt().await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Could not read the shell environment of $machine; direnv keeps the inherited one", e)
            return emptyMap()
        }
        return variables.filterKeys { !it.startsWith("DIRENV_") }
    }

    /**
     * Finds the direnv binary on the machine the project lives on.
     *
     * The Eel API documents the executable it is given as an absolute path on that machine, and
     * nothing resolves a bare name against `PATH` the way starting a local process does — so the
     * default setting, plain `direnv`, could not work there at all. That is #24. The three forms
     * below are the three the settings field can plausibly hold.
     */
    private suspend fun resolveExecutable(
        eel: EelApi,
        executable: String,
        shellEnv: Map<String, String>,
        machine: String,
    ): EelPath {
        val descriptor = eel.descriptor

        // Written the way that machine writes paths: /run/current-system/sw/bin/direnv.
        runCatching { EelPath.parse(executable, descriptor) }.getOrNull()?.let { return it }

        // Written the way this JVM names files over there: \\wsl.localhost\NixOS\...\direnv, which
        // is what browsing for the binary on Windows produces. Accepted only when the platform
        // agrees the path names this project's machine, so that /usr/bin/direnv on a Linux host is
        // not mistaken for a path on the remote one.
        runCatching { Paths.get(executable).asEelPath() }.getOrNull()
            ?.takeIf { it.descriptor == descriptor }
            ?.let { return it }

        // A bare name. PATH is consulted twice, because the two answers differ: once as the
        // machine's own connection sees it, and once as a login shell sees it. On NixOS that
        // difference is the whole answer — direnv lives under /run/current-system/sw/bin, which a
        // non-login environment has no reason to contain.
        val found = try {
            eel.exec.where(executable)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.debug("Could not search PATH on $machine for $executable", e)
            null
        }

        (found ?: findOnShellPath(descriptor, executable, shellEnv))?.let {
            log.info("Resolved $executable to $it on $machine")
            return it
        }

        log.warn("$executable is not on the PATH of $machine")
        throw DirenvExecutableNotFoundException(executable)
    }

    /**
     * Looks for [name] on the `PATH` a login shell on that machine reports.
     *
     * Existence is checked through the file system the IDE already uses for that machine's files —
     * the same one the watch poll reads — rather than through the Eel file system API, whose stat
     * helper is marked internal and would fail the Plugin Verifier.
     */
    private fun findOnShellPath(
        descriptor: EelDescriptor,
        name: String,
        shellEnv: Map<String, String>,
    ): EelPath? {
        val path = descriptor.osFamily.expandPathEnvVar(shellEnv)?.takeIf { it.isNotBlank() } ?: return null
        val separator = if (descriptor.osFamily == EelOsFamily.Windows) ';' else ':'

        for (entry in path.split(separator)) {
            if (entry.isBlank()) continue
            val candidate = runCatching { EelPath.parse(entry, descriptor).getChild(name) }.getOrNull()
                ?: continue
            val here = runCatching { candidate.asNioPath() }.getOrNull() ?: continue
            if (runCatching { Files.isRegularFile(here) }.getOrDefault(false)) return candidate
        }
        return null
    }
}
