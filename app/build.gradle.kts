plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.zerotune"
    compileSdk = 34

    defaultConfig {
        applicationId = "app.zerotune"
        minSdk = 24
        targetSdk = 34
        // Схема X.Y.Z: X — большое обновление, Y — среднее, Z — мелкие фиксы.
        // versionCode = X*10000 + Y*100 + Z (1.7.3 -> 10703), он должен только расти.
        versionCode = 10703
        versionName = "1.7.3"
        ndk { abiFilters += listOf("arm64-v8a") } // почти все Huawei — arm64
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    // Ядро Xray: положи libv2ray.aar в app/libs/ (сборка AndroidLibXrayLite)
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar", "*.jar"))))
    implementation("androidx.core:core-ktx:1.13.1")
}
