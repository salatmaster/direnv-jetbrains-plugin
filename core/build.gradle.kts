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
            // Declared here rather than as tasks.test { useJUnitPlatform() } so that the engine and
            // the launcher are resolved as a matched set. Bringing junit-jupiter in by hand leaves
            // Gradle to supply its own launcher, and the two disagree: discovery then dies with
            // "OutputDirectoryCreator not available", which names neither the cause nor the fix.
            useJUnitJupiter(libs.versions.junitJupiter)
        }
    }
}

dependencies {
    // compileOnly on purpose: the platform already ships kotlinx-serialization-json
    // (platform/util .../xmlb/JsonHelper.kt). Bundling a second copy risks a classloader conflict.
    compileOnly(libs.kotlinx.serialization.json)

    // Neither of these is a leftover. BasePlatformTestCase extends UsefulTestCase extends
    // junit.framework.TestCase, so every test that needs a project is a JUnit 3 test: junit:junit
    // is where that base class lives, and only the vintage engine can run it — Jupiter cannot, at
    // any version. The platform test framework happens to pull both in transitively today, so
    // dropping these would appear to work; it would also make five test classes depend on an
    // undeclared detail of someone else's dependency graph, and they would leave the run silently
    // rather than fail the build if it ever changed. Pinned to the same version the platform
    // resolves, so the classpath carries one copy of each engine rather than two.
    testImplementation(libs.junit)
    testRuntimeOnly(libs.junit.vintage.engine)
    testCompileOnly(libs.kotlinx.serialization.json)

    intellijPlatform {
        create(IntelliJPlatformType.IntellijIdeaCommunity, providers.gradleProperty("platformVersion")) {
            useInstaller = false
        }
        testFramework(TestFrameworkType.Platform)
    }
}
