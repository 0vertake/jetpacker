plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass = "dev.jetpacker.cli.MainKt"
    // The Analysis API boots the IntelliJ platform, which expects these outside an IDE.
    applicationDefaultJvmArgs = listOf(
        "-Xmx4g",
        "-Didea.is.unit.test=true",
        "-Djava.awt.headless=true",
    )
}

dependencies {
    implementation(project(":core"))
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
