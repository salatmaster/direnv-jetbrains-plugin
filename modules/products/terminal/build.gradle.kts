import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("org.jetbrains.intellij.platform.module")
    alias(libs.plugins.kotlin)
}

kotlin { jvmToolchain(21) }

dependencies {
    implementation(project(":direnv-core"))
    testImplementation(libs.junit)

    intellijPlatform {
        create(IntelliJPlatformType.IntellijIdea, providers.gradleProperty("platformVersion")) {
            useInstaller = false
        }
        bundledPlugin("org.jetbrains.plugins.terminal")
        testFramework(TestFrameworkType.Platform)
    }
}
