import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("org.jetbrains.intellij.platform.module")
    alias(libs.plugins.kotlin)
}

kotlin { jvmToolchain(21) }

dependencies {
    // compileOnly on purpose: the platform already ships kotlinx-serialization-json
    // (platform/util .../xmlb/JsonHelper.kt). Bundling a second copy risks a classloader conflict.
    compileOnly(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testCompileOnly(libs.kotlinx.serialization.json)

    intellijPlatform {
        create(IntelliJPlatformType.IntellijIdeaCommunity, providers.gradleProperty("platformVersion")) {
            useInstaller = false
        }
        testFramework(TestFrameworkType.Platform)
    }
}
