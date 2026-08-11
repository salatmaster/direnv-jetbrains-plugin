import org.jetbrains.intellij.platform.gradle.extensions.intellijPlatform

plugins {
    // Lets Gradle provision the JVM 21 toolchain automatically, so a clean checkout builds
    // even when the contributor has a different JDK installed.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
    id("org.jetbrains.intellij.platform.settings") version "2.18.1"
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        intellijPlatform { defaultRepositories() }
    }
}

rootProject.name = "direnv"

include("modules/core")

// Turns "modules/core" into the project name ":direnv-core".
rootProject.children.forEach {
    it.name = it.name.replaceFirst("modules/", "direnv-").replace("/", "-")
}
