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
        maven {
            url = uri("https://maven.pkg.github.com/everysight-maverick-AI/mav-ai-android-maven")
            credentials {
                username = providers.gradleProperty("gpr.user").orNull
                password = providers.gradleProperty("gpr.key").orNull
            }
        }
    }
}

rootProject.name = "MyMav"

include(":composeApp")
include(":glasses-api")
include(":glasses-stub")
include(":glasses-mav2")
include(":miniapps:notify")
include(":miniapps:spotify")
include(":miniapps:conversate")
include(":miniapps:food")
include(":miniapps:shop")
include(":miniapps:identify")
include(":miniapps:football")
include(":miniapps:fitness")
include(":miniapps:mirror")
include(":miniapps:gallery")
include(":miniapps:tasks")
include(":miniapps:workouts")
include(":miniapps:memorysight")
include(":miniapps:money")
include(":miniapps:mymind")

include(":miniapps:saved")
