plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(21)
}

val aaVersion = libs.versions.analysisApi.get()
val ijVersion = libs.versions.intellijPlatform.get()

dependencies {
    // Analysis API + compiler frontend artifacts. Non-transitive on purpose: their poms
    // point at unpublished internal coordinates. Dependency set mirrors google/ksp
    // (kotlin-analysis-api/build.gradle.kts), the canonical production consumer.
    listOf(
        "org.jetbrains.kotlin:analysis-api-k2-for-ide",
        "org.jetbrains.kotlin:analysis-api-for-ide",
        "org.jetbrains.kotlin:low-level-api-fir-for-ide",
        "org.jetbrains.kotlin:analysis-api-platform-interface-for-ide",
        "org.jetbrains.kotlin:symbol-light-classes-for-ide",
        "org.jetbrains.kotlin:analysis-api-standalone-for-ide",
        "org.jetbrains.kotlin:analysis-api-impl-base-for-ide",
        "org.jetbrains.kotlin:kotlin-compiler-common-for-ide",
        "org.jetbrains.kotlin:kotlin-compiler-fir-for-ide",
        "org.jetbrains.kotlin:kotlin-compiler-fe10-for-ide",
        "org.jetbrains.kotlin:kotlin-compiler-ir-for-ide",
    ).forEach {
        implementation("$it:$aaVersion") { isTransitive = false }
    }

    // IntelliJ platform jars the Analysis API runs on (PSI, VFS, service container).
    listOf(
        "com.jetbrains.intellij.platform:util-rt",
        "com.jetbrains.intellij.platform:util-class-loader",
        "com.jetbrains.intellij.platform:util-text-matching",
        "com.jetbrains.intellij.platform:util",
        "com.jetbrains.intellij.platform:util-base",
        "com.jetbrains.intellij.platform:util-coroutines",
        "com.jetbrains.intellij.platform:util-xml-dom",
        "com.jetbrains.intellij.platform:core",
        "com.jetbrains.intellij.platform:core-impl",
        "com.jetbrains.intellij.platform:extensions",
        "com.jetbrains.intellij.platform:diagnostic",
        "com.jetbrains.intellij.platform:diagnostic-telemetry",
        "com.jetbrains.intellij.java:java-frontback-psi",
        "com.jetbrains.intellij.java:java-frontback-psi-impl",
        "com.jetbrains.intellij.java:java-psi",
        "com.jetbrains.intellij.java:java-psi-impl",
    ).forEach {
        implementation("$it:$ijVersion") { isTransitive = false }
    }

    // Third-party runtime deps of the above (versions from ksp's libs.versions.toml).
    implementation("org.jetbrains.kotlinx:kotlinx-collections-immutable-jvm:0.3.4")
    // JetBrains' patched coroutines: the IntelliJ platform jars reference
    // kotlinx.coroutines.internal.intellij.IntellijCoroutines, which vanilla coroutines lacks.
    implementation("org.jetbrains.intellij.deps.kotlinx:kotlinx-coroutines-core-jvm:1.10.2-intellij-1")
    // Needed by IntelliJ's XML DOM at runtime, and by the index cache the benchmark relies on.
    api(libs.kotlinx.serialization.json)
    implementation("com.google.guava:guava:33.2.0-jre")
    implementation("one.util:streamex:0.7.2")
    implementation("org.jetbrains.intellij.deps:asm-all:9.0")
    implementation("org.codehaus.woodstox:stax2-api:4.2.1") { isTransitive = false }
    implementation("com.fasterxml:aalto-xml:1.3.0") { isTransitive = false }
    implementation("com.github.ben-manes.caffeine:caffeine:2.9.3")
    implementation("org.jetbrains.intellij.deps.jna:jna:5.9.0.26") { isTransitive = false }
    implementation("org.jetbrains.intellij.deps.jna:jna-platform:5.9.0.26") { isTransitive = false }
    implementation("org.jetbrains.intellij.deps:trove4j:1.0.20200330") { isTransitive = false }
    implementation("org.jetbrains.intellij.deps:log4j:1.2.17.2") { isTransitive = false }
    implementation("org.jetbrains.intellij.deps:jdom:2.0.6") { isTransitive = false }
    implementation("org.jetbrains.intellij.deps.fastutil:intellij-deps-fastutil:8.5.14-jb1") { isTransitive = false }
    implementation("javax.inject:javax.inject:1")
    implementation("org.lz4:lz4-java:1.7.1") { isTransitive = false }
    implementation("org.jetbrains:annotations:24.1.0")
    implementation("io.opentelemetry:opentelemetry-api:1.34.1") { isTransitive = false }

    // Reads the source roots and resolved classpath out of a target project's Gradle build.
    implementation("org.gradle:gradle-tooling-api:${libs.versions.gradleToolingApi.get()}")

    // Token budgets have to be counted with the eval model's tokenizer, not estimated.
    implementation(libs.jtokkit)

    testImplementation(kotlin("test"))
    // The Tooling API logs through slf4j; a binding keeps its "no providers" warning out of test output.
    testRuntimeOnly("org.slf4j:slf4j-simple:2.0.16")
}

tasks.test {
    useJUnitPlatform()
    maxHeapSize = "2g"
    // Required by the IntelliJ platform when running headless outside an IDE.
    systemProperty("idea.is.unit.test", "true")
    systemProperty("java.awt.headless", "true")
    systemProperty("idea.home.path", layout.buildDirectory.dir("ideaHome").get().asFile.absolutePath)
    environment("NO_FS_ROOTS_ACCESS_CHECK", "true")
    // Opt-in target for RealRepositoryIndexTest.
    System.getProperty("jetpacker.repo")?.let { systemProperty("jetpacker.repo", it) }
}
