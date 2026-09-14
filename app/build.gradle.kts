plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.guber.hermesandroid"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.guber.hermesandroid"
        minSdk = 26
        targetSdk = 35
        versionCode = 9
        versionName = "0.1.8"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
    }

    val releaseKeystore = providers.environmentVariable("HERMES_KEYSTORE").orNull
    val releaseStorePassword = providers.environmentVariable("HERMES_KEYSTORE_PASSWORD").orNull
    val releaseKeyPassword = providers.environmentVariable("HERMES_KEY_PASSWORD").orNull
    if (!releaseKeystore.isNullOrBlank() && !releaseStorePassword.isNullOrBlank() && !releaseKeyPassword.isNullOrBlank()) {
        signingConfigs {
            create("hermesRelease") {
                storeFile = file(releaseKeystore)
                storePassword = releaseStorePassword
                keyAlias = "hermes"
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (!releaseKeystore.isNullOrBlank() && !releaseStorePassword.isNullOrBlank() && !releaseKeyPassword.isNullOrBlank()) {
                signingConfig = signingConfigs.getByName("hermesRelease")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.03")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
