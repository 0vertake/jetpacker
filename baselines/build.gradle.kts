plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":core"))
    // A baseline that retrieves windows rather than declarations counts its own tokens, with the
    // same tokenizer the packer uses — an unequal budget would settle the comparison by itself.
    implementation(libs.jtokkit)
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
