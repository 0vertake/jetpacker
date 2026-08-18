plugins {
    `java-library`
    idea
}

sourceSets {
    // dataframe: a second copy of the API registered as a source set, then hidden from the IDE.
    create("generatedDocs") {
        java.srcDir("generated-sources/src/main/java")
    }
}

idea {
    module {
        excludeDirs.add(file("generated-sources"))
    }
}

dependencies {
    implementation(project(":lib"))
}
