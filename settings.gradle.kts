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
    }
}

rootProject.name = "Reyaak"

// Four modules, three of them platform-agnostic. The split is the architecture
// made structural rather than conventional: :ui cannot reach a provider because
// :router is two modules below it, and :router cannot reach the UI or the OS
// because its commonMain has neither on its classpath.
//
//   :router  KMP  provider routing        jvm (tests) + android + ios
//   :core    KMP  agent runtime, Room     android + ios
//   :ui      KMP  Compose screens         android + ios      (no platform code)
//   :app     Android application host     the OS-facing shell
//
// An :iosApp module joins later; it needs a macOS host with Xcode, so the iOS
// targets here are declared and metadata-checked but not linked on Windows.
include(":router", ":core", ":ui", ":app")
