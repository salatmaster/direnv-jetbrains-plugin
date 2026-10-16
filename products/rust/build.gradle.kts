import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("org.jetbrains.intellij.platform.module")
    alias(libs.plugins.kotlin)
}

kotlin { jvmToolchain(21) }

testing {
    suites {
        named<JvmTestSuite>("test") {
            useJUnitJupiter(libs.versions.junitJupiter)
        }
    }
}

dependencies {
    implementation(project(":core"))

    testImplementation(libs.assertj)
    testImplementation(libs.junit)
    testRuntimeOnly(libs.junit.vintage.engine)

    intellijPlatform {
        // RustRover, because it is the IDE that bundles the Rust plugin: compiling against the one
        // the platform ships means no Marketplace version to pin and keep in step with the platform.
        create(IntelliJPlatformType.RustRover, providers.gradleProperty("platformVersion")) {
            useInstaller = false
        }
        bundledPlugin("com.jetbrains.rust")
        testFramework(TestFrameworkType.Platform)
    }
}
