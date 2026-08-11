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
import kotlinx.coroutines.Dispatchers
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
class DirenvService(private val project: Project) {

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

            currentState.set(DirenvState.Loading)
            val outcome = withContext(Dispatchers.IO) { cli().export(normalised) }
            val newState = applyOutcome(normalised, outcome)
            currentState.set(newState)
            newState
        }
    }

    private fun applyOutcome(workingDir: Path, outcome: DirenvOutcome): DirenvState = when (outcome) {
        is DirenvOutcome.Loaded -> {
            val environment = outcome.environment
            val key = environment.loadedRcPath?.parent?.toAbsolutePath()?.normalize() ?: workingDir
            cache[key] = environment
            keyByQueriedDir[workingDir] = key
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
            log.info("direnv blocked: ${outcome.envrcPath}")
            DirenvState.Blocked(outcome.envrcPath)
        }

        is DirenvOutcome.ExecutableNotFound -> DirenvState.ExecutableMissing(outcome.executable)

        is DirenvOutcome.Failed -> {
            log.warn("direnv failed with exit code ${outcome.exitCode}")
            DirenvState.Failed(outcome.message)
        }
    }

    /** Drops cached environments. Passing null clears everything. */
    fun invalidate(workingDir: Path?) {
        if (workingDir == null) {
            cache.clear()
            keyByQueriedDir.clear()
            currentState.set(DirenvState.NotLoaded)
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
