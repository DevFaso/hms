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

rootProject.name = "PatientMobileApp"

include(":app")
include(":core")
include(":feature-auth")
include(":feature-home")
include(":feature-appointments")
include(":feature-records")
include(":feature-billing")
include(":feature-medications")
include(":feature-notifications")
include(":feature-profile")
include(":feature-settings")
include(":feature-more")
include(":feature-lab-results")
include(":feature-vitals")
include(":feature-care-team")
include(":feature-chat")
include(":feature-consents")
include(":feature-access-logs")
include(":feature-immunizations")
include(":feature-treatment-plans")
include(":feature-referrals")
include(":feature-consultations")
include(":feature-documents")
