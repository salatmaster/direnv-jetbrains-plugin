import org.jetbrains.changelog.Changelog
import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("org.jetbrains.intellij.platform")
    alias(libs.plugins.kotlin)
    alias(libs.plugins.changelog)
}

group = providers.gradleProperty("pluginGroup").get()

// The release workflow passes the version it is cutting, so the number in the artifact,
// the tag and the changelog cannot drift apart. Local builds fall back to gradle.properties.
val pluginVersion = providers.environmentVariable("PLUGIN_VERSION")
    .orElse(providers.gradleProperty("pluginVersion"))

version = pluginVersion.get()

kotlin { jvmToolchain(21) }

listOf(configurations.runtimeClasspath, configurations.testRuntimeClasspath).forEach { cfg ->
    cfg.configure {
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-jdk7")
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-jdk8")
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-common")
    }
}

dependencies {
    intellijPlatform {
        create(IntelliJPlatformType.IntellijIdeaCommunity, providers.gradleProperty("platformVersion")) {
            useInstaller = false
        }
        pluginComposedModule(implementation(project(":core")))
        pluginComposedModule(implementation(project(":products:terminal")))
        pluginComposedModule(implementation(project(":products:java")))
        pluginVerifier()
        zipSigner()
        testFramework(TestFrameworkType.Platform)
    }
}

// Developer convenience: ./gradlew runIde -PsampleProject=/path/to/project opens that project
// directly, so the plugin can be exercised without clicking through the welcome screen.
tasks.runIde {
    providers.gradleProperty("sampleProject").orNull?.let { path ->
        args(path)
        // The plugin refuses to run direnv in an untrusted project, which is the point; this
        // skips the trust dialog for a throwaway sandbox only.
        systemProperty("idea.trust.all.projects", "true")
        // A fresh sandbox otherwise blocks on the end-user agreement dialog before any plugin
        // code runs, which makes the sandbox useless for exercising the plugin.
        systemProperty("jb.consents.confirmation.enabled", "false")
        systemProperty("jb.privacy.policy.text", "<!--999.999-->")
        systemProperty("idea.initially.ask.config", "never")
        systemProperty("idea.log.debug.categories", "io.github.salatmaster.direnv")
    }
}

// Read-only use of the changelog: the file is cut by .github/scripts/cut-changelog.sh before the
// release builds, so patchChangelog is deliberately never run and cannot disagree with it.
changelog {
    version = pluginVersion
}

intellijPlatform {
    pluginConfiguration {
        version = pluginVersion

        // <change-notes> is what the Marketplace shows as "What's new" for a version, and what the
        // Plugins dialog shows when an update is offered. Leaving it unset is why 0.1.0 through
        // 0.1.3 published a blank one.
        //
        // The section of the version being released is used rather than [Unreleased], because the
        // workflow cuts the file before building and by then the entry has already moved. That is
        // also why the plugin's own convention, which reads [Unreleased], is not relied on.
        changeNotes = pluginVersion.map { version ->
            with(changelog) {
                val item = getOrNull(version) ?: getUnreleased()
                renderItem(item.withHeader(false).withEmptySections(false), Changelog.OutputType.HTML)
            }
        }

        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            // No upper bound: the plugin must not stop loading when a new IDE ships.
            untilBuild.unset()
        }
    }
    pluginVerification {
        ides {
            // Community is the compile target and the lowest common denominator.
            create(IntelliJPlatformType.IntellijIdeaCommunity, providers.gradleProperty("platformVersion"))
            // PyCharm Community has no Java plugin, so this proves the optional product modules
            // really are optional and the plugin loads in IDEs beyond IDEA.
            create(IntelliJPlatformType.PyCharmCommunity, providers.gradleProperty("platformVersion"))
        }
    }

    // Credentials come from the environment so nothing sensitive lives in the repository.
    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }

    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
    }
}
