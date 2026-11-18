pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Hammerhead karoo-ext SDK. Needs a GitHub token with read:packages,
        // either as gpr.user/gpr.key in ~/.gradle/gradle.properties or as USERNAME/TOKEN env vars.
        maven {
            url = uri("https://maven.pkg.github.com/hammerheadnav/karoo-ext")
            credentials {
                username = providers.gradleProperty("gpr.user").getOrElse(System.getenv("USERNAME") ?: "")
                password = providers.gradleProperty("gpr.key").getOrElse(System.getenv("TOKEN") ?: "")
            }
        }
    }
}

rootProject.name = "LightPilot"
include("core")
include("app")
