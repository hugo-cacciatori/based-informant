plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.androidx.room3)
}

kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())

    jvm()

    android {
        namespace = "io.github.hugo_cacciatori.basedinformant.data"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":core"))
            // In the public API: the hosts make the database builder and the DataStore through them.
            api(libs.androidx.room3.runtime)
            implementation(libs.androidx.sqlite.bundled)
            api(libs.androidx.datastore.preferences.core)
            implementation(libs.kotlinx.serialization.json)
        }
        // The database tests run on the JVM only: the bundled SQLite's native library is there for the host.
        jvmTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.androidx.room3.testing)
        }
    }
}

room3 {
    // Committed: each schema version is the base of its migration test (04-platforms.md section 7).
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    add("kspAndroid", libs.androidx.room3.compiler)
    add("kspJvm", libs.androidx.room3.compiler)
}
