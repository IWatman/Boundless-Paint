import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Release signing lives outside the repository sources: .tools/signing/keystore.properties.
val keystorePropertiesFile = rootProject.file(".tools/signing/keystore.properties")
val keystoreProperties: Properties? = if (keystorePropertiesFile.isFile) {
    Properties().apply { keystorePropertiesFile.inputStream().use { load(it) } }
} else {
    null
}

android {
    namespace = "app.painter"
    compileSdk = 34

    defaultConfig {
        applicationId = "app.painter"
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField("String", "UPDATE_REPO", "\"IWatman/Painter\"")
    }

    signingConfigs {
        if (keystoreProperties != null) {
            create("release") {
                storeFile = keystorePropertiesFile.parentFile.resolve(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Falls back to an unsigned build when .tools/signing/keystore.properties is absent.
            signingConfig = signingConfigs.findByName("release")
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

dependencies {
    implementation(project(":core"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.input.motionprediction)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.kotlinx.coroutines.android)
    debugImplementation(libs.compose.ui.tooling)
    testImplementation(libs.junit)
}
