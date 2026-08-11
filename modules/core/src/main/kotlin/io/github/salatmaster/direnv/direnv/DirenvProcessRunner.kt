package io.github.salatmaster.direnv.direnv

import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.util.ExecUtil
import com.intellij.openapi.util.Key
import java.io.IOException
import java.nio.file.Path

data class DirenvProcessResult(val exitCode: Int, val stdout: String, val stderr: String)

class DirenvExecutableNotFoundException(val executable: String) :
    RuntimeException("direnv executable not found: $executable")

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
            throw DirenvExecutableNotFoundException(executable)
        } catch (e: ExecutionException) {
            throw DirenvExecutableNotFoundException(executable)
        }

        return DirenvProcessResult(output.exitCode, output.stdout, output.stderr)
    }
}
