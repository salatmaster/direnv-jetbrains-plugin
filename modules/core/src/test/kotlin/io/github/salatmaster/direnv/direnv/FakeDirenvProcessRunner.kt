package io.github.salatmaster.direnv.direnv

import java.nio.file.Path

/**
 * Scripted [DirenvProcessRunner] for tests.
 *
 * Lets the whole CLI and service layer be tested without direnv installed and without spawning
 * processes, which also makes the tests behave identically on the Windows CI runner.
 */
class FakeDirenvProcessRunner : DirenvProcessRunner {

    data class Invocation(
        val executable: String,
        val args: List<String>,
        val workingDir: Path,
        val extraEnv: Map<String, String>,
    )

    val invocations = mutableListOf<Invocation>()
    private val responses = mutableMapOf<String, DirenvProcessResult>()
    private var fallback = DirenvProcessResult(0, "", "")

    /** When true, every invocation behaves as if the executable were missing. */
    var executableMissing = false

    /** [firstArg] is matched against args[0], e.g. "export", "allow", "version". */
    fun respondTo(firstArg: String, result: DirenvProcessResult) {
        responses[firstArg] = result
    }

    fun respondByDefault(result: DirenvProcessResult) {
        fallback = result
    }

    override fun run(
        executable: String,
        args: List<String>,
        workingDir: Path,
        extraEnv: Map<String, String>,
        timeoutMs: Int,
    ): DirenvProcessResult {
        invocations += Invocation(executable, args, workingDir, extraEnv)
        if (executableMissing) throw DirenvExecutableNotFoundException(executable)
        return responses[args.firstOrNull()] ?: fallback
    }
}
