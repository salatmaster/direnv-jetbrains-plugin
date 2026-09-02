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
    /** direnv reports and receives paths in the syntax of the machine it runs on, not of this JVM. */
    private val pathMapper: DirenvPathMapper = DirenvPathMapper.SameMachine,
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
                DirenvExportParser.parseEntries(result.stdout)["DIRENV_WATCHES"].orEmpty(),
                pathMapper,
            )
            // The banner and the Allow action turn this back into a path, so it is stored the way
            // this JVM writes it. If it cannot be mapped the original is kept: a path the user
            // recognises is a better thing to show than nothing at all.
            val local = pathMapper.toLocal(blockedPath)?.toString() ?: blockedPath
            return DirenvOutcome.Blocked(local, watches)
        }

        if (result.exitCode != 0) {
            log.debug("direnv export failed with exit code ${result.exitCode}")
            return DirenvOutcome.Failed(
                DirenvExportParser.stripAnsi(result.stderr).trim(),
                result.exitCode,
            )
        }

        val entries = DirenvExportParser.parseEntries(result.stdout)
        val watches = DirenvWatchesCodec.decode(entries["DIRENV_WATCHES"].orEmpty(), pathMapper)
        val rcPath = entries["DIRENV_FILE"]?.let { pathMapper.toLocal(it) }

        // A denied .envrc exits 0 and exports nothing, so the exit code cannot tell it apart from a
        // directory whose .envrc legitimately produces no variables. direnv's own deny stamp can:
        // it is reported in the watch list, and it exists only while approval is revoked.
        //
        // Requiring the rc path keeps the outcome actionable — without a file to name, there is
        // nothing for the user to allow, and Loaded describes the result just as well.
        if (rcPath != null && watches.any { it.exists && isDenyStamp(it.path) }) {
            log.debug("direnv reports an existing deny stamp for $rcPath")
            return DirenvOutcome.Denied(rcPath.toString(), watches)
        }

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

    /**
     * Recognises direnv's deny stamp, `<data dir>/direnv/deny/<hash of the rc path>`.
     *
     * Anchored on both directory names rather than on "deny" alone: a project is free to watch a
     * file under some unrelated deny/ directory, and mistaking that for a revoked approval would
     * hide a perfectly good environment.
     */
    private fun isDenyStamp(path: Path): Boolean {
        val denyDir = path.parent ?: return false
        val direnvDir = denyDir.parent ?: return false
        return denyDir.fileName?.toString() == "deny" && direnvDir.fileName?.toString() == "direnv"
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
        // direnv reads its argument in its own machine's syntax, so the path the IDE holds cannot be
        // handed over as it stands: `direnv allow \\wsl.localhost\NixOS\p\.envrc` names nothing.
        val target = pathMapper.toDirenv(envrcPath)
            ?: return DirenvOutcome.Failed("Cannot express $envrcPath on the machine direnv runs on", -1)
        val result = execute(listOf(command, target), workingDir)
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
