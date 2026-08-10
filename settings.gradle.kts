rootProject.name = "jetpacker"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        // Analysis API standalone artifacts (analysis-api-standalone-for-ide)
        maven("https://packages.jetbrains.team/maven/p/ij/intellij-dependencies")
    }
}

include("core", "cli", "baselines", "eval")
