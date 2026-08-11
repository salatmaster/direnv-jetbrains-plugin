package io.github.salatmaster.direnv

import com.intellij.util.messages.Topic

/**
 * Notified when the direnv state of a project changes.
 *
 * Carries [DirenvState], which holds counts and paths but never variable names or values, so
 * listeners cannot accidentally surface secrets.
 */
interface DirenvStateListener {

    fun stateChanged(state: DirenvState)

    companion object {
        @Topic.ProjectLevel
        val TOPIC: Topic<DirenvStateListener> =
            Topic.create("direnv state changed", DirenvStateListener::class.java)
    }
}
