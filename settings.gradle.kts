pluginManagement {
    repositories {
        google()
        mavenCentral()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

include(":app")
include(":lib:platform_frameworks_ex:common")
include(":lib:platform_frameworks_opt_chips")
include(":lib:platform_frameworks_opt_photoviewer")
include(":lib:platform_frameworks_opt_vcard")
