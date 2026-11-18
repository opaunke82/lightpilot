// Based on the KarooFirefly build (https://github.com/derstrassi/karoofirefly), MIT License.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.jetbrains.kotlin.android)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
}

fun git(vararg args: String): String? = try {
    val process = ProcessBuilder("git", *args).directory(rootDir).redirectErrorStream(true).start()
    process.inputStream.bufferedReader().readText().trim().ifEmpty { null }
} catch (e: Exception) {
    null
}

android {
    namespace = "io.github.opaunke82.lightpilot"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.opaunke82.lightpilot"
        minSdk = 23
        targetSdk = 34
        // Needs the full history (CI checks out with fetch-depth: 0).
        versionCode = git("rev-list", "--count", "HEAD")?.toIntOrNull() ?: 1
        versionName = System.getenv("VERSION_NAME") ?: "0.1.0-dev"
        buildConfigField("String", "GIT_SHA", "\"${git("rev-parse", "--short", "HEAD") ?: "unknown"}\"")
    }

    signingConfigs {
        create("release") {
            val storeFilePath = System.getenv("KEYSTORE_PATH")
            if (storeFilePath != null) {
                storeFile = file(storeFilePath)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (System.getenv("KEYSTORE_PATH") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.hammerhead.karoo.ext)
    implementation(libs.androidx.core.ktx)
    implementation(libs.bundles.androidx.lifecycle)
    implementation(libs.androidx.activity.compose)
    implementation(libs.bundles.compose.ui)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.sunrise.sunset)
    implementation(libs.timber)
    implementation(libs.kotlinx.serialization.json)
    implementation("androidx.compose.material:material-icons-extended")
    implementation(libs.nordic.ble.client)
}
