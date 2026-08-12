package io.github.salatmaster.direnv.settings

import com.intellij.openapi.util.JDOMUtil
import com.intellij.util.xmlb.XmlSerializer
import io.github.salatmaster.direnv.DirenvLightTestCase

class DirenvSettingsTest : DirenvLightTestCase() {

    fun `test defaults are enabled and generous with timeout`() {
        val state = DirenvSettings.State()

        assertTrue(state.enabled)
        assertTrue(state.autoLoad)
        assertTrue(state.watchFiles)
        assertEquals("direnv", state.executablePath)
        // Generous on purpose: a first nix or devbox build routinely takes minutes.
        assertEquals(120, state.timeoutSeconds)
    }

    fun `test settings round-trip through serialization`() {
        val settings = DirenvSettings.getInstance(project)
        settings.state.executablePath = "/opt/bin/direnv"
        settings.state.timeoutSeconds = 300

        val reloaded = DirenvSettings.State()
        XmlSerializer.deserializeInto(reloaded, XmlSerializer.serialize(settings.state))

        assertEquals("/opt/bin/direnv", reloaded.executablePath)
        assertEquals(300, reloaded.timeoutSeconds)
    }

    fun `test serialized state has no field capable of holding direnv output`() {
        val settings = DirenvSettings.getInstance(project)

        val serialized = JDOMUtil.write(XmlSerializer.serialize(settings.state))

        // extraEnv is user-authored configuration and may persist; a loaded environment must not.
        assertFalse(serialized.contains("entries"))
        assertFalse(serialized.contains("loadedEnvironment"))
        assertFalse(serialized.contains("watches"))
    }

    fun `test timeout is exposed in milliseconds for the CLI`() {
        val settings = DirenvSettings.getInstance(project)
        settings.state.timeoutSeconds = 7

        assertEquals(7_000, settings.timeoutMs())
    }
}
