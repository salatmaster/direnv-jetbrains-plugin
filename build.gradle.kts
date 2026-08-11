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
