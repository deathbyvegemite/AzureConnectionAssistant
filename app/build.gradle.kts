import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

/*
 * Optional release signing. Drop a `keystore.properties` next to this file with
 * storeFile / storePassword / keyAlias / keyPassword and release builds get signed
 * with your own key. Without it, use `assembleDebug` — a debug-signed APK installs
 * fine when sideloaded, it just cannot be upgraded in place by a differently signed one.
 */
val keystoreProperties = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.deathbyvegemite.platewatch"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.deathbyvegemite.platewatch"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        resourceConfigurations += listOf("en")
    }

    signingConfigs {
        if (keystoreProperties.getProperty("storeFile") != null) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // Left off deliberately: the bundled ML Kit model dominates the APK size,
            // so shrinking buys little, and an untested R8 config is a good way to
            // ship a build that crashes only in release. Turn it on once you can test.
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
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

    sourceSets["main"].java.srcDirs("src/main/kotlin")

    // The vehicle-detector model (assets/efficientdet_lite0.tflite) is loaded by
    // mmap-ing the asset directly; AAPT compressing it in the APK breaks that.
    androidResources {
        noCompress += "tflite"
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.incremental", "true")
}

dependencies {
    implementation(project(":core"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.navigation.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    // Bundled (not Play-Services-backed) so the app works on a sideloaded phone
    // with no Google Play, and needs no model download on first run.
    implementation(libs.mlkit.text.recognition)

    // Confirms a vehicle is actually in frame before any text near it is trusted as
    // a plate. Model is bundled in assets/, so this needs no network access either.
    implementation(libs.tensorflow.lite.task.vision)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime)

    testImplementation(libs.junit)
}
