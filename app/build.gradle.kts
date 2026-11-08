plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.gms.google-services")
}

val apiBaseUrl = providers.gradleProperty("TAP_API_BASE_URL")
    .orElse(providers.environmentVariable("TAP_API_BASE_URL"))
    .orElse("https://tap-api.example.com")
    .get()

android {
    namespace = "com.tap.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.tap.app"
        minSdk = 26
        targetSdk = 35
        versionCode = providers.gradleProperty("VERSION_CODE").orElse("1").get().toInt()
        versionName = providers.gradleProperty("VERSION_NAME").orElse("1.0.0").get()
        buildConfigField("String", "API_BASE_URL", "\"${apiBaseUrl.replace("\\", "\\\\").replace("\"", "\\\"")}\"")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    signingConfigs {
        val keystorePath = providers.environmentVariable("CM_KEYSTORE_PATH")
        val keystorePassword = providers.environmentVariable("CM_KEYSTORE_PASSWORD")
        val keyAlias = providers.environmentVariable("CM_KEY_ALIAS")
        val keyPassword = providers.environmentVariable("CM_KEY_PASSWORD")
        if (keystorePath.isPresent && keystorePassword.isPresent && keyAlias.isPresent && keyPassword.isPresent) {
            create("release") {
                storeFile = file(keystorePath.get())
                storePassword = keystorePassword.get()
                this.keyAlias = keyAlias.get()
                this.keyPassword = keyPassword.get()
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (signingConfigs.findByName("release") != null) signingConfig = signingConfigs.getByName("release")
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.02.00")
    implementation(platform("com.google.firebase:firebase-bom:34.0.0"))
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("com.google.firebase:firebase-auth")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.10.1")
    debugImplementation("androidx.compose.ui:ui-tooling")
}