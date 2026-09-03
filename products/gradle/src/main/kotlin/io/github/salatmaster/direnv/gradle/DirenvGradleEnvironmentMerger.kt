package io.github.salatmaster.direnv.gradle

import io.github.salatmaster.direnv.direnv.DirenvEnvironment

/**
 * Projects a direnv environment into the entries that can be handed to
 * GradleExecutionSettings.withEnvironmentVariables.
 *
 * Null values (direnv's "unset") are skipped, not emptied: ExternalSystemExecutionSettings only
 * merges (withEnvironmentVariables is putAll) and the parent-environment merge happens after
 * these settings are applied, so an unset cannot be expressed on this path at all. An empty
 * string would be a defined-but-empty variable instead of an absent one — worse than the
 * omission.
 *
 * Kept separate from the extension point so the semantics are testable without constructing
 * GradleExecutionSettings and GradleExecutionContext, which drag in platform state.
 */
object DirenvGradleEnvironmentMerger {

    fun environmentToInject(environment: DirenvEnvironment): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        for ((key, value) in environment.entries) {
            if (value != null) result[key] = value
        }
        return result
    }
}
