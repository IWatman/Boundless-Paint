import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Single version source; CI passes -PappVersion=<tag without v>.
val appVersion = (findProperty("appVersion") as String?) ?: "0.5.10"
val appVersionCode = appVersion.split(".").map { it.toInt() }.let { (major, minor, patch) ->
    major * 10000 + minor * 100 + patch
}

// Release signing lives outside the repository: PAINTER_KEYSTORE_PROPERTIES, else ~/.painter/keystore.properties.
val keystorePropertiesFile = file(
    System.getenv("PAINTER_KEYSTORE_PROPERTIES") ?: "${System.getProperty("user.home")}/.painter/keystore.properties",
)
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
        versionCode = appVersionCode
        versionName = appVersion
        buildConfigField("String", "UPDATE_REPO", "\"IWatman/Boundless-Paint\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
        debug {
            // Same key as releases, so a dev build on a device can still take updates from GitHub.
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
        release {
            isMinifyEnabled = false
            // Falls back to an unsigned build when the keystore properties are absent.
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
    // GPU parity tests read the CPU-generated vectors (core/src/test/resources/vectors/*.json).
    sourceSets["androidTest"].assets.srcDir("../core/src/test/resources")

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    implementation(project(":core"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.input.motionprediction)
    implementation(libs.play.services.auth)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.kotlinx.coroutines.android)
    debugImplementation(libs.compose.ui.tooling)
    testImplementation(libs.junit)
}
