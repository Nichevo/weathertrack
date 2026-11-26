plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// Every CI build gets a higher versionCode, so a new APK always installs as an upgrade.
val ciBuildNumber = System.getenv("BUILD_NUMBER")?.toIntOrNull() ?: 1
// The release line. Bump it with each release in app/src/main/assets/CHANGELOG.md ("## 1.0 · ...").
val releaseLine = "1.8"

// Release signing key comes from CI secrets (never committed). See docs/SIGNING.md.
val keystorePath: String? = System.getenv("WT_KEYSTORE_PATH")
val hasReleaseKey = keystorePath != null && file(keystorePath).exists()

android {
    namespace = "app.weathertrack"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.weathertrack"
        minSdk = 26
        targetSdk = 35
        versionCode = ciBuildNumber
        versionName = "$releaseLine.$ciBuildNumber"
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = file(keystorePath!!)
                // Trim whitespace that can sneak in when secrets are pasted.
                val storePass = System.getenv("WT_KEYSTORE_PASSWORD")?.trim()
                storePassword = storePass
                keyAlias = System.getenv("WT_KEY_ALIAS")?.trim()?.ifEmpty { null } ?: "weathertrack"
                keyPassword = System.getenv("WT_KEY_PASSWORD")?.trim()?.ifEmpty { null } ?: storePass
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (hasReleaseKey) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(project(":core"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.work.runtime.ktx)
    implementation(libs.glance.appwidget)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
}
