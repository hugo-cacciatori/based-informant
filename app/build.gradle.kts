import java.util.Properties

plugins {
    // AGP 9 compiles Kotlin itself (built-in Kotlin): no org.jetbrains.kotlin.android plugin.
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

// The release keystore stays outside the repo (security.md section 3). Until keystore.properties exists, both
// builds use this PC's debug key: internal builds for the owner's phone only, never published (platforms.md section 3).
val keystoreFile = rootProject.file("keystore.properties")
val keystore = Properties().apply { if (keystoreFile.exists()) keystoreFile.inputStream().use(::load) }

kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())
}

android {
    namespace = "io.github.hugo_cacciatori.basedinformant"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        // Permanent: updates, the signing key and the Google OAuth client depend on it (platforms.md section 3).
        applicationId = "io.github.hugo_cacciatori.basedinformant"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionName = providers.gradleProperty("app.versionName").get()
        versionCode = providers.gradleProperty("app.versionCode").get().toInt()
    }

    signingConfigs {
        if (keystoreFile.exists()) {
            create("owner") {
                storeFile = rootProject.file(keystore.getProperty("storeFile"))
                storePassword = keystore.getProperty("storePassword")
                keyAlias = keystore.getProperty("keyAlias")
                keyPassword = keystore.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("owner") ?: signingConfigs.getByName("debug")
        }
        debug {
            // One install identity: debug builds use the release key when it exists.
            signingConfigs.findByName("owner")?.let { signingConfig = it }
        }
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation(project(":ui"))
    implementation(project(":data"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.ktor.client.okhttp)
    testImplementation(kotlin("test-junit"))
}
