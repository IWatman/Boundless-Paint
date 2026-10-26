import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    // Public API exposes JsonObject (Document.guides, UnknownAdjust).
    api(libs.kotlinx.serialization.json)
    implementation(libs.lz4)
    testImplementation(libs.junit)
    testImplementation(kotlin("test-junit"))
}

/** Regenerates the committed CPU↔GPU parity vectors in src/test/resources/vectors. */
tasks.register<JavaExec>("genVectors") {
    group = "verification"
    description = "Writes blend.json and adjust.json test vectors from BlendMath / AdjustMath."
    dependsOn("testClasses")
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("app.painter.core.vectors.VectorsKt")
    args(layout.projectDirectory.dir("src/test/resources/vectors").asFile.absolutePath)
}
