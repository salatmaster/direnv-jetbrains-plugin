package io.github.salatmaster.direnv.direnv

import com.intellij.openapi.progress.runBlockingCancellable
import com.intellij.openapi.project.Project
import com.intellij.platform.eel.ExecuteProcessException
import com.intellij.platform.eel.provider.asEelPath
import com.intellij.platform.eel.provider.getEelDescriptor
import com.intellij.platform.eel.provider.toEelApiBlocking
import com.intellij.platform.eel.provider.utils.awaitProcessResult
import com.intellij.platform.eel.provider.utils.stderrString
import com.intellij.platform.eel.provider.utils.stdoutString
import com.intellij.platform.eel.spawnProcess
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.file.Path
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

        return runBlockingCancellable {
            val result = withTimeoutOrNull(timeoutMs.milliseconds) {
                val process = try {
                    eel.exec.spawnProcess(executable)
                        .args(args)
                        .env(extraEnv)
                        .workingDirectory(cwd)
                        .eelIt()
                } catch (e: ExecuteProcessException) {
                    // Not every launch failure is a missing binary, and here that distinction is
                    // load-bearing: ENOENT from a spawn covers the working directory as much as the
                    // executable, and a working directory that does not exist is exactly what #21
                    // was. Answering "direnv is not installed" would send that user to the
                    // installation guide for a problem that has nothing to do with installation.
                    if (isMissingExecutable(e)) throw DirenvExecutableNotFoundException(executable, e)
                    throw DirenvProcessFailedException(
                        "Failed to run $executable in $workingDir on ${eel.descriptor.name}: " +
                            "${e.message} (errno ${e.errno})",
                        e,
                    )
                }
                process.awaitProcessResult()
            } ?: throw DirenvProcessFailedException("$executable timed out after ${timeoutMs}ms", null)

            DirenvProcessResult(result.exitCode, result.stdoutString, result.stderrString)
        }
    }
}
