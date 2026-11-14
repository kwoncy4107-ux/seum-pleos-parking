pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Pleos Playground SDK. TTS/STT pull ai.pleos.playground.security:universal-access, so allow subgroups.
        maven {
            url = uri("https://nexus-playground.pleos.ai/repository/maven-releases/")
            content { includeGroupByRegex("ai\\.pleos\\.playground(\\..*)?") }
        }
    }
}

rootProject.name = "seum"
include(":engine")
// The Android app module needs the Android SDK; skip it on machines without one (engine tests still run).
if (file("local.properties").exists() || System.getenv("ANDROID_HOME") != null) {
    include(":app")
}
