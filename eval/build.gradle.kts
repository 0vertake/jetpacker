plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":core"))
    implementation(project(":baselines"))
    testImplementation(kotlin("test"))
}

application {
    mainClass = "dev.jetpacker.eval.BenchmarkKt"
    // The Analysis API boots the IntelliJ platform, which expects these outside an IDE.
    applicationDefaultJvmArgs = listOf("-Xmx6g", "-Didea.is.unit.test=true", "-Djava.awt.headless=true")
}

tasks.named<JavaExec>("run") {
    // Let `-Pjetpacker.repo=...` reach the harness without a wrapper script.
    listOf("jetpacker.repo", "jetpacker.tasks", "jetpacker.budgets", "jetpacker.cache").forEach {
        providers.gradleProperty(it).orNull?.let { value -> systemProperty(it, value) }
    }
}

tasks.test {
    useJUnitPlatform()
}
