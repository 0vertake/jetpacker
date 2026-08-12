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
    // The embedding baseline runs its model in-process, on the CPU.
    implementation(libs.djl.api)
    implementation(libs.djl.tokenizers)
    runtimeOnly(libs.djl.onnxruntime)
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
    // Opt-in target for EmbeddingChunkRetrieverTest, which downloads a model.
    System.getProperty("jetpacker.embed")?.let { systemProperty("jetpacker.embed", it) }
}
