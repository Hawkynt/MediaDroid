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

rootProject.name = "MediaDroid"

include(":app")
include(":native")
include(":core:imaging")
include(":core:nfo")
include(":core:library")
include(":core:player")
include(":core:sources")
include(":engine:api")
include(":engine:android")
include(":stage")
include(":engine:desktop")
include(":desktop")
