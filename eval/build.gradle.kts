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
    "jetpacker.python",
).forEach {
    providers.gradleProperty(it).orNull?.let { value -> systemProperty(it, value) }
}

tasks.named<JavaExec>("run") {
    forwardJetpackerProperties()
}

tasks.register<JavaExec>("level2") {
    group = "verification"
    description = "Scores each pack arm by whether a model's patch passes the task's own tests."
    mainClass = "dev.jetpacker.eval.Level2Kt"
    classpath = sourceSets["main"].runtimeClasspath
    // The daemon's environment is not this shell's, so the key is forwarded rather than inherited.
    providers.environmentVariable("CURSOR_API_KEY").orNull?.let { environment("CURSOR_API_KEY", it) }
    providers.gradleProperty("jetpacker.model").orNull?.let { environment("JETPACKER_MODEL", it) }
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
