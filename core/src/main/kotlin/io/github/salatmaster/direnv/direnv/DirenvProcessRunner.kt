package io.github.salatmaster.direnv.direnv

import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.util.ExecUtil
import com.intellij.openapi.util.Key
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.file.Path

data class DirenvProcessResult(val exitCode: Int, val stdout: String, val stderr: String)

class DirenvExecutableNotFoundException(val executable: String, cause: Throwable? = null) :
    RuntimeException("direnv executable not found: $executable", cause)

/**
 * The executable exists but could not be run to completion, e.g. it is not executable, the
 * timeout elapsed, or the process was killed.
 *
 * Distinct from [DirenvExecutableNotFoundException] on purpose: reporting every launch failure as
 * "direnv not found" would send users to the installation guide for a problem that has nothing to
 * do with installation.
 */
class DirenvProcessFailedException(message: String, cause: Throwable?) : RuntimeException(message, cause)

/**
 * Seam over process execution.
 *
 * Exists so the CLI and service layers can be tested without direnv installed and without
 * spawning processes — which is also what makes those tests work on Windows CI.
 */
interface DirenvProcessRunner {
    fun run(
        executable: String,
        args: List<String>,
        workingDir: Path,
        extraEnv: Map<String, String>,
        timeoutMs: Int,
    ): DirenvProcessResult
}

/**
 * Marks command lines the plugin itself creates.
 *
 * Without this marker DirenvCommandLineEnvCustomizer would customise our own direnv invocation,
 * which would call direnv again, forever. GeneralCommandLine implements UserDataHolder, so the
 * marker lives exactly as long as the command line object and needs no cleanup.
 */
object DirenvInternalMarker {

    val KEY: Key<Boolean> = Key.create("direnv.internal.command")

    fun mark(commandLine: GeneralCommandLine) = commandLine.putUserData(KEY, true)

    fun isMarked(commandLine: GeneralCommandLine): Boolean = commandLine.getUserData(KEY) == true
}

/** Runs direnv through the platform's process API. */
class GeneralCommandLineRunner : DirenvProcessRunner {

    override fun run(
        executable: String,
        args: List<String>,
        workingDir: Path,
        extraEnv: Map<String, String>,
        timeoutMs: Int,
    ): DirenvProcessResult {
        val commandLine = GeneralCommandLine(executable)
            .withParameters(args)
            .withWorkingDirectory(workingDir)
            .withEnvironment(extraEnv)
        DirenvInternalMarker.mark(commandLine)

        val output = try {
            ExecUtil.execAndGetOutput(commandLine, timeoutMs)
        } catch (e: IOException) {
            throw DirenvExecutableNotFoundException(executable, e)
        } catch (e: ExecutionException) {
            // ExecutionException covers both "cannot start" and "started but failed", so the
            // distinction has to come from the cause rather than from the exception type.
            if (isMissingExecutable(e)) {
                throw DirenvExecutableNotFoundException(executable, e)
            }
            throw DirenvProcessFailedException("Failed to run $executable: ${e.message}", e)
        }

        return DirenvProcessResult(output.exitCode, output.stdout, output.stderr)
    }

}

/** True, when the failure chain indicates, the binary itself could not be located. */
internal fun isMissingExecutable(e: Throwable): Boolean {
    var cause: Throwable? = e
    while (cause != null) {
        if (cause is FileNotFoundException) return true
        val message = cause.message.orEmpty()
        if (message.contains("No such file or directory") ||
            message.contains("CreateProcess error=2") ||
            message.contains("cannot run program", ignoreCase = true)
        ) {
            return true
        }
        cause = cause.cause
    }
    return false
}
