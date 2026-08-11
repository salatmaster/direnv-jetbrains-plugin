package io.github.salatmaster.direnv.direnv

import com.intellij.openapi.diagnostic.Logger
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Instant

/**
 * Typed direnv operations.
 *
 * Configuration is supplied as lambdas rather than values so that changing settings takes effect
 * without rebuilding the CLI.
 */
class DirenvCli(
    private val runner: DirenvProcessRunner,
    private val executableProvider: () -> String,
    private val extraEnvProvider: () -> Map<String, String>,
    private val timeoutMsProvider: () -> Int,
) {

    private val log = Logger.getInstance(DirenvCli::class.java)

    /** Loads the environment for [workingDir]. Never throws; failures come back as outcomes. */
    fun export(workingDir: Path): DirenvOutcome {
        val result = try {
            execute(listOf("export", "json"), workingDir)
        } catch (e: DirenvExecutableNotFoundException) {
            return DirenvOutcome.ExecutableNotFound(e.executable)
        } catch (e: DirenvProcessFailedException) {
            return DirenvOutcome.Failed(e.message.orEmpty(), -1)
        }

        DirenvExportParser.findBlockedPath(result.stderr)?.let { blockedPath ->
            // The exported variables are dropped: no usable environment was produced. The watch
            // list is kept, because it contains the allow stamp and lets us notice approval
            // granted outside the IDE.
            val watches = DirenvWatchesCodec.decode(
                DirenvExportParser.parseEntries(result.stdout)["DIRENV_WATCHES"].orEmpty()
            )
            return DirenvOutcome.Blocked(blockedPath, watches)
        }

        if (result.exitCode != 0) {
            log.debug("direnv export failed with exit code ${result.exitCode}")
            return DirenvOutcome.Failed(
                DirenvExportParser.stripAnsi(result.stderr).trim(),
                result.exitCode,
            )
        }

        val entries = DirenvExportParser.parseEntries(result.stdout)
        val watches = DirenvWatchesCodec.decode(entries["DIRENV_WATCHES"].orEmpty())
        val rcPath = entries["DIRENV_FILE"]?.let { runCatching { Paths.get(it) }.getOrNull() }

        return DirenvOutcome.Loaded(
            DirenvEnvironment(
                entries = entries,
                watches = watches,
                loadedRcPath = rcPath,
                workingDir = workingDir,
                loadedAt = Instant.now(),
            )
        )
    }

    /** Approves an .envrc. Only ever called in response to an explicit user action. */
    fun allow(envrcPath: Path): DirenvOutcome = mutateApproval("allow", envrcPath)

    /** Revokes approval of an .envrc. */
    fun deny(envrcPath: Path): DirenvOutcome = mutateApproval("deny", envrcPath)

    /** Returns the direnv version, or null if direnv cannot be started. */
    fun version(): String? = try {
        execute(listOf("version"), Paths.get(System.getProperty("user.home")))
            .stdout.trim().ifEmpty { null }
    } catch (e: DirenvExecutableNotFoundException) {
        null
    } catch (e: DirenvProcessFailedException) {
        null
    }

    private fun mutateApproval(command: String, envrcPath: Path): DirenvOutcome = try {
        val workingDir = envrcPath.parent ?: envrcPath
        val result = execute(listOf(command, envrcPath.toString()), workingDir)
        if (result.exitCode == 0) {
            DirenvOutcome.Loaded(DirenvEnvironment.empty(workingDir))
        } else {
            DirenvOutcome.Failed(DirenvExportParser.stripAnsi(result.stderr).trim(), result.exitCode)
        }
    } catch (e: DirenvExecutableNotFoundException) {
        DirenvOutcome.ExecutableNotFound(e.executable)
    } catch (e: DirenvProcessFailedException) {
        DirenvOutcome.Failed(e.message.orEmpty(), -1)
    }

    private fun execute(args: List<String>, workingDir: Path): DirenvProcessResult =
        runner.run(
            executable = executableProvider(),
            args = args,
            workingDir = workingDir,
            // TERM=dumb keeps direnv from assuming an interactive terminal. It does NOT suppress
            // colour: direnv 2.37.1 still emits escape codes, which is why stderr is stripped
            // before use rather than trusted to be plain.
            extraEnv = mapOf("TERM" to "dumb") + extraEnvProvider(),
            timeoutMs = timeoutMsProvider(),
        )
}
