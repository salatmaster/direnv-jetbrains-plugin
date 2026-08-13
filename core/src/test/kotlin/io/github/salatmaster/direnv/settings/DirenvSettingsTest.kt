package io.github.salatmaster.direnv.settings

import com.intellij.openapi.util.JDOMUtil
import com.intellij.util.xmlb.XmlSerializer
import io.github.salatmaster.direnv.DirenvLightTestCase
import org.assertj.core.api.Assertions.assertThat

class DirenvSettingsTest : DirenvLightTestCase() {

    fun `test defaults are enabled and generous with timeout`() {
        val state = DirenvSettings.State()

        assertThat(state.enabled).isTrue()
        assertThat(state.autoLoad).isTrue()
        assertThat(state.watchFiles).isTrue()
        assertThat(state.executablePath).isEqualTo("direnv")
        // Generous on purpose: a first nix or devbox build routinely takes minutes.
        assertThat(state.timeoutSeconds).isEqualTo(120)
    }

    fun `test settings round-trip through serialization`() {
        val settings = DirenvSettings.getInstance(project)
        settings.state.executablePath = "/opt/bin/direnv"
        settings.state.timeoutSeconds = 300

        val reloaded = DirenvSettings.State()
        XmlSerializer.deserializeInto(reloaded, XmlSerializer.serialize(settings.state))

        assertThat(reloaded.executablePath).isEqualTo("/opt/bin/direnv")
        assertThat(reloaded.timeoutSeconds).isEqualTo(300)
    }

    fun `test serialized state has no field capable of holding direnv output`() {
        val settings = DirenvSettings.getInstance(project)

        val serialized = JDOMUtil.write(XmlSerializer.serialize(settings.state))

        // extraEnv is user-authored configuration and may persist; a loaded environment must not.
        assertThat(serialized).doesNotContain("entries")
        assertThat(serialized).doesNotContain("loadedEnvironment")
        assertThat(serialized).doesNotContain("watches")
    }

    fun `test timeout is exposed in milliseconds for the CLI`() {
        val settings = DirenvSettings.getInstance(project)
        settings.state.timeoutSeconds = 7

        assertThat(settings.timeoutMs()).isEqualTo(7_000)
    }
}
