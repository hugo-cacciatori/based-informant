plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())
}

dependencies {
    implementation(project(":core"))
    implementation(libs.ktor.client.okhttp)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(kotlin("test"))
}

application {
    mainClass.set("io.github.hugo_cacciatori.basedinformant.lab.MainKt")
}

// Run from the repo root so lab-out/ (git-ignored) lands there.
tasks.named<JavaExec>("run") {
    workingDir = rootDir
}
