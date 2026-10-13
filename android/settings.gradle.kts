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

// Core layer
include(":core:common")
include(":core:model")
include(":core:designsystem")
include(":core:security")
include(":core:mcp")
include(":core:network")
include(":core:database")
include(":core:data")

// Feature layer
include(":feature:settings")
include(":feature:t2i")
include(":feature:imageedit")

// Upcoming modules are registered here as their tickets land:
//   :feature:video (3.3)   :feature:gallery (4.1)
