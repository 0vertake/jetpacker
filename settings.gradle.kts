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
        // Analysis API artifacts (org.jetbrains.kotlin:*-for-ide)
        maven("https://packages.jetbrains.team/maven/p/ij/intellij-dependencies")
        // IntelliJ platform artifacts (com.jetbrains.intellij.platform:*, .java:*)
        maven("https://cache-redirector.jetbrains.com/intellij-repository/releases")
    }
}

include("core", "cli", "baselines", "eval")
