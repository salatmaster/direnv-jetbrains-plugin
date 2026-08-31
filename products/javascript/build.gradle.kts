import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("org.jetbrains.intellij.platform.module")
    alias(libs.plugins.kotlin)
}

kotlin { jvmToolchain(21) }

dependencies {
    implementation(project(":core"))

    testImplementation(libs.assertj)
    testImplementation(libs.junit)
    testRuntimeOnly(libs.junit.vintage.engine)

    intellijPlatform {
        // Ultimate, not Community: the JavaScript plugin is not bundled with IDEA Community, so
        // there is nothing to compile against there. This is the one module that needs it, and it
        // is optional at runtime — the plugin still loads in IDEs without JavaScript support, which
        // the Plugin Verifier run against PyCharm Community proves.
        create(IntelliJPlatformType.IntellijIdea, providers.gradleProperty("platformVersion")) {
            useInstaller = false
        }
        bundledPlugin("JavaScript")
        testFramework(TestFrameworkType.Platform)
    }
}
