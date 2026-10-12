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

// Upcoming modules are registered here as their tickets land:
//   :core:mcp          (Ticket 2.1)   :core:network      (Ticket 2.2)
//   :feature:settings  (Ticket 2.3)   :feature:t2i       (Ticket 3.1)
//   :feature:imageedit (Ticket 3.2)   :feature:video     (Ticket 3.3)
//   :feature:gallery   (Ticket 4.1)
