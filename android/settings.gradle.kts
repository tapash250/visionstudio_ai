pluginManagement {
    includeBuild("build-logic")
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
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

rootProject.name = "VisionStudioAI"

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

include(":app")
include(":baselineprofile")

// Core layer
include(":core:common")
include(":core:model")
include(":core:designsystem")
include(":core:security")
include(":core:mcp")
include(":core:network")
include(":core:database")
include(":core:data")
include(":core:player")

// Feature layer
include(":feature:settings")
include(":feature:t2i")
include(":feature:imageedit")
include(":feature:video")
include(":feature:gallery")

