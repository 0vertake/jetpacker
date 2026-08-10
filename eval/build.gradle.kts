plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":core"))
    implementation(project(":baselines"))
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
