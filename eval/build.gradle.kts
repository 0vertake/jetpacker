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

/** Let `-Pjetpacker.repo=...` reach a harness without a wrapper script. */
fun JavaExec.forwardJetpackerProperties() = listOf(
    "jetpacker.repo",
    "jetpacker.tasks",
    "jetpacker.budgets",
    "jetpacker.cache",
    "jetpacker.harbor",
    "jetpacker.harbor.repo",
    "jetpacker.embed",
    "jetpacker.l2",
).forEach {
    providers.gradleProperty(it).orNull?.let { value -> systemProperty(it, value) }
}

tasks.named<JavaExec>("run") {
    forwardJetpackerProperties()
}

tasks.register<JavaExec>("certify") {
    group = "verification"
    description = "Runs each Level-2 task's own verifier against the gold patch and against no patch."
    mainClass = "dev.jetpacker.eval.CertifyKt"
    classpath = sourceSets["main"].runtimeClasspath
    forwardJetpackerProperties()
}

tasks.test {
    useJUnitPlatform()
}
