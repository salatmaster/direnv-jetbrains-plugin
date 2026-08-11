import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("org.jetbrains.intellij.platform")
    alias(libs.plugins.kotlin)
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

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
        create(IntelliJPlatformType.IntellijIdea, providers.gradleProperty("platformVersion")) {
            useInstaller = false
        }
        pluginComposedModule(implementation(project(":direnv-core")))
        pluginComposedModule(implementation(project(":direnv-products-terminal")))
        pluginVerifier()
        zipSigner()
        testFramework(TestFrameworkType.Platform)
    }
}

intellijPlatform {
    pluginConfiguration {
        version = providers.gradleProperty("pluginVersion")
        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            // No upper bound: the plugin must not stop loading when a new IDE ships.
            untilBuild.unset()
        }
    }
    pluginVerification {
        ides { recommended() }
    }
}
