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

rootProject.name = "orion"

include(":app")
include(":core:common")
include(":core:model")
include(":core:commands")
include(":core:queries")
include(":core:events")
include(":core:ipc")
include(":core:orchestrator")
include(":core:policy")
include(":core:actions")
include(":core:resources")
include(":core:time")
include(":core:recovery")
include(":core:health")
include(":core:security")
include(":core:observability")
include(":core:ingress")
include(":core:exitinfo")
include(":core:skills:metadata")
include(":data:database")
include(":data:datastore")
include(":data:repository")
include(":data:tasks")
include(":data:reminders")
include(":data:actions")
include(":data:events")
include(":data:memory")
include(":ai:api")
include(":voice:api")
include(":voice:ipc")
include(":skills:api")
include(":scheduler")
include(":feature:home")
include(":feature:diagnostics")
include(":feature:settings")
