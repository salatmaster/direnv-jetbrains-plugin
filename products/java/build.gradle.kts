import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("org.jetbrains.intellij.platform.module")
    alias(libs.plugins.kotlin)
}

kotlin { jvmToolchain(21) }

dependencies {
    implementation(project(":core"))
    testImplementation(libs.junit)

    intellijPlatform {
        create(IntelliJPlatformType.IntellijIdeaCommunity, providers.gradleProperty("platformVersion")) {
            useInstaller = false
        }
        bundledPlugin("com.intellij.java")
        testFramework(TestFrameworkType.Platform)
    }
}
