rootProject.name = "mcpluginlab"

dependencyResolutionManagement {
    repositories { mavenCentral() }
}

include("api", "core", "daemon", "cli")
