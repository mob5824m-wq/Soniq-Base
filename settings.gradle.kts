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

rootProject.name = "soniq-base"

// The reusable framework library.
include(":soniq-base")

// Reference app: everything YouTube-specific lives here, behind the
// MusicProvider SPI. Copy this module to start your own music app.
include(":examples:youtube-music")
