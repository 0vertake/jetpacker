plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":core"))
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
