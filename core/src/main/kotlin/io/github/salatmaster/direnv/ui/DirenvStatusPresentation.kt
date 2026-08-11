package io.github.salatmaster.direnv.ui

import io.github.salatmaster.direnv.DirenvState

/**
 * Turns [DirenvState] into what the status bar shows.
 *
 * Separated from the widget so the rendering rules — in particular the guarantee that no variable
 * name or value is ever displayed — can be tested without a UI.
 */
data class DirenvStatusPresentation(
    val text: String,
    val tooltip: String,
    val kind: Kind,
) {

    enum class Kind {
        /** No environment applies here: either nothing loaded, or direnv produced nothing. */
        INACTIVE,
        LOADING,
        ACTIVE,
        BLOCKED,
        FAILED,
        NOT_INSTALLED,
    }

    companion object {

        fun of(state: DirenvState): DirenvStatusPresentation = when (state) {
            is DirenvState.NotLoaded -> DirenvStatusPresentation(
                text = "direnv",
                tooltip = "direnv: no environment loaded",
                kind = Kind.INACTIVE,
            )

            is DirenvState.Loading -> DirenvStatusPresentation(
                text = "direnv…",
                tooltip = "direnv: loading the environment",
                kind = Kind.LOADING,
            )

            is DirenvState.Loaded -> {
                val diff = state.diff
                // Counts only. Names would leak which secrets a project defines, and the values
                // are secrets themselves.
                val counts = "+${diff.added.size}/~${diff.changed.size}/-${diff.removed.size}"
                if (diff.isEmpty) {
                    DirenvStatusPresentation(
                        text = "direnv",
                        tooltip = "direnv: loaded, no variables changed",
                        kind = Kind.INACTIVE,
                    )
                } else {
                    DirenvStatusPresentation(
                        text = "direnv $counts",
                        tooltip = "direnv: ${diff.added.size} added, " +
                            "${diff.changed.size} changed, ${diff.removed.size} removed",
                        kind = Kind.ACTIVE,
                    )
                }
            }

            is DirenvState.Blocked -> DirenvStatusPresentation(
                text = "direnv blocked",
                tooltip = "direnv: ${state.envrcPath} is blocked.\nReview it, then allow it.",
                kind = Kind.BLOCKED,
            )

            // Deliberately the same wording and kind as Blocked: to the user both mean "direnv
            // will not run this until you allow it", and the plugin's own action is called Block.
            // Only the tooltip distinguishes how the file got there.
            is DirenvState.Denied -> DirenvStatusPresentation(
                text = "direnv blocked",
                tooltip = "direnv: approval for ${state.envrcPath} was revoked.\n" +
                    "Allow it again to load the environment.",
                kind = Kind.BLOCKED,
            )

            is DirenvState.Failed -> DirenvStatusPresentation(
                text = "direnv failed",
                tooltip = "direnv failed: ${state.message}",
                kind = Kind.FAILED,
            )

            is DirenvState.ExecutableMissing -> DirenvStatusPresentation(
                text = "direnv missing",
                tooltip = "direnv executable not found: ${state.executable}",
                kind = Kind.NOT_INSTALLED,
            )
        }
    }
}
