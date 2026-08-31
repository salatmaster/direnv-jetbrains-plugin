package io.github.salatmaster.direnv

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import io.github.salatmaster.direnv.direnv.DirenvCli
import io.github.salatmaster.direnv.direnv.DirenvEnvironment
import io.github.salatmaster.direnv.direnv.DirenvOutcome
import io.github.salatmaster.direnv.direnv.GeneralCommandLineRunner
import io.github.salatmaster.direnv.settings.DirenvSettings
import io.github.salatmaster.direnv.watch.DirenvWatchService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * Owns the loaded direnv environments for one project.
 *
 * Environments are cached by the directory holding the resolved .envrc, so subdirectories sharing
 * an .envrc share one entry while a nested .envrc gets its own — that is what makes nested
 * environments work without special casing.
 *
 * Nothing here reaches disk: the values are frequently secrets.
 */
@Service(Service.Level.PROJECT)
class DirenvService(private val project: Project, private val scope: CoroutineScope) {

    private val log = Logger.getInstance(DirenvService::class.java)

    /** Cache key: directory of the resolved .envrc, or the queried directory when there is none. */
    private val cache = ConcurrentHashMap<Path, DirenvEnvironment>()

    /** Maps an already-queried working directory to its cache key. */
    private val keyByQueriedDir = ConcurrentHashMap<Path, Path>()

    private val currentState = AtomicReference<DirenvState>(DirenvState.NotLoaded)
    private val loadMutex = Mutex()

    /** Test seam: lets tests supply a CLI backed by a fake process runner. */
    var cliOverride: DirenvCli? = null

    private val defaultCli: DirenvCli by lazy {
        val settings = DirenvSettings.getInstance(project)
        DirenvCli(
            runner = GeneralCommandLineRunner(),
            executableProvider = { settings.state.executablePath },
            extraEnvProvider = { settings.state.extraEnv.toMap() },
            timeoutMsProvider = { settings.timeoutMs() },
        )
    }

    private fun cli(): DirenvCli = cliOverride ?: defaultCli

    fun state(): DirenvState = currentState.get()

    /** Returns a cached environment covering [workingDir], or null when nothing is loaded for it. */
    fun cachedFor(workingDir: Path): DirenvEnvironment? {
        val normalised = workingDir.toAbsolutePath().normalize()
        keyByQueriedDir[normalised]?.let { key -> cache[key]?.let { return it } }

        // The directory may itself be the key. That happens whenever the first load was triggered
        // from a subdirectory — a process started by a build tool, say — because the environment is
        // then filed under the directory holding the .envrc rather than under the one asked about.
        cache[normalised]?.let { environment ->
            keyByQueriedDir[normalised] = normalised
            return environment
        }

        // A parent directory may already hold the environment covering this one.
        var parent: Path? = normalised.parent
        while (parent != null) {
            cache[parent]?.let { environment ->
                keyByQueriedDir[normalised] = parent
                return environment
            }
            parent = parent.parent
        }
        return null
    }

    /**
     * Loads the environment for [workingDir].
     *
     * Serialised through a mutex so that concurrent triggers — startup activity, a watched file
     * changing, an explicit reload — collapse into one direnv invocation instead of competing.
     */
    suspend fun load(workingDir: Path, force: Boolean = false): DirenvState {
        if (!DirenvGuard.mayRun(project)) return currentState.get()

        val normalised = workingDir.toAbsolutePath().normalize()
        if (!force && cachedFor(normalised) != null) return currentState.get()

        return loadMutex.withLock {
            if (!force && cachedFor(normalised) != null) return@withLock currentState.get()

            publish(DirenvState.Loading)
            val outcome = withContext(Dispatchers.IO) { cli().export(normalised) }
            val newState = applyOutcome(normalised, outcome)
            publish(newState)
            newState
        }
    }

    private fun applyOutcome(workingDir: Path, outcome: DirenvOutcome): DirenvState = when (outcome) {
        is DirenvOutcome.Loaded -> {
            val environment = outcome.environment
            val key = environment.loadedRcPath?.parent?.toAbsolutePath()?.normalize() ?: workingDir
            cache[key] = environment
            keyByQueriedDir[workingDir] = key
            // Register the files this environment depends on, so a change to flake.lock or an
            // external `direnv allow` triggers a reload.
            DirenvWatchService.getInstance(project).updateWatches(key, environment.watches)
            val diff = environment.diffAgainst(System.getenv())
            log.info(
                "direnv loaded for $key: " +
                    "+${diff.added.size} ~${diff.changed.size} -${diff.removed.size}"
            )
            DirenvState.Loaded(diff)
        }

        is DirenvOutcome.Blocked -> {
            // Never cache a blocked result: no environment was produced, and caching one would
            // silently keep a stale environment alive after the user revoked approval.
            //
            // The watches are still registered. direnv reports them even when blocked, and they
            // include the allow stamp, so approving the file in an external terminal reaches the
            // IDE — the case where a blocked project most needs to notice a change.
            DirenvWatchService.getInstance(project).updateWatches(workingDir, outcome.watches)
            log.info("direnv blocked: ${outcome.envrcPath}")
            DirenvState.Blocked(outcome.envrcPath)
        }

        is DirenvOutcome.Denied -> {
            // Nothing is cached, for the same reason as Blocked: no environment was produced, and
            // a cached one would outlive the approval it came from. The watches are kept so that
            // allowing the file again — here or in a terminal — is noticed.
            DirenvWatchService.getInstance(project).updateWatches(workingDir, outcome.watches)
            log.info("direnv denied: ${outcome.envrcPath}")
            DirenvState.Denied(outcome.envrcPath)
        }

        is DirenvOutcome.ExecutableNotFound -> DirenvState.ExecutableMissing(outcome.executable)

        is DirenvOutcome.Failed -> {
            log.warn("direnv failed with exit code ${outcome.exitCode}")
            DirenvState.Failed(outcome.message)
        }
    }

    /** Reloads the environment for [workingDir] from a non-suspending caller, e.g. an action. */
    fun scheduleReload(workingDir: Path) {
        scope.launch { load(workingDir, force = true) }
    }

    /**
     * Approves an .envrc and reloads.
     *
     * Only ever reached from an explicit user action: nothing in the plugin calls this on its own.
     */
    fun scheduleAllow(envrcPath: Path, workingDir: Path) {
        scope.launch {
            withContext(Dispatchers.IO) { cli().allow(envrcPath) }
            load(workingDir, force = true)
        }
    }

    /** Revokes approval of an .envrc and drops the environment it produced. */
    fun scheduleBlock(envrcPath: Path, workingDir: Path) {
        scope.launch {
            withContext(Dispatchers.IO) { cli().deny(envrcPath) }
            invalidate(workingDir)
            load(workingDir, force = true)
        }
    }

    /** The .envrc backing the environment for [workingDir], if one is known. */
    fun envrcPathFor(workingDir: Path): Path? = cachedFor(workingDir)?.loadedRcPath
        ?: unapprovedRcPath()

    /**
     * The .envrc named by a state that produced no environment.
     *
     * Neither Blocked nor Denied caches an environment, so without this the UI would lose the one
     * file it needs to act on precisely when approval is the only thing left to do.
     */
    private fun unapprovedRcPath(): Path? = when (val state = currentState.get()) {
        is DirenvState.Blocked -> runCatching { Path.of(state.envrcPath) }.getOrNull()
        is DirenvState.Denied -> runCatching { Path.of(state.envrcPath) }.getOrNull()
        else -> null
    }

    private fun publish(state: DirenvState) {
        currentState.set(state)
        if (project.isDisposed) return
        project.messageBus.syncPublisher(DirenvStateListener.TOPIC).stateChanged(state)
    }

    /** Drops cached environments. Passing null clears everything. */
    fun invalidate(workingDir: Path?) {
        if (workingDir == null) {
            cache.clear()
            keyByQueriedDir.clear()
            publish(DirenvState.NotLoaded)
            return
        }
        val normalised = workingDir.toAbsolutePath().normalize()
        val key = keyByQueriedDir.remove(normalised) ?: normalised
        cache.remove(key)
        keyByQueriedDir.entries.removeIf { it.value == key }
    }

    companion object {
        fun getInstance(project: Project): DirenvService = project.service()
    }
}
