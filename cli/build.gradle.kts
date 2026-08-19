plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass = "dev.jetpacker.cli.MainKt"
    applicationName = "packer"
    // The Analysis API boots the IntelliJ platform, which expects these outside an IDE.
    applicationDefaultJvmArgs = listOf(
        "-Xmx4g",
        "-Didea.is.unit.test=true",
        "-Djava.awt.headless=true",
    )
}

dependencies {
    implementation(project(":core"))
    implementation(project(":baselines"))
    // The IntelliJ platform pulls in slf4j-api, and with no binding every run opens with three
    // lines of SLF4J complaint. A CLI has stderr for its own errors; the platform's logging is
    // not the user's business.
    runtimeOnly(libs.slf4j.nop)
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
