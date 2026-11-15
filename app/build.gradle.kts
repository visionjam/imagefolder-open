plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.imagefolder.open"
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "com.imagefolder.open"
        minSdk = 29
        targetSdk = 35
        versionCode = 10
        versionName = "0.9.0"
        vectorDrawables { useSupportLibrary = true }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        resourceConfigurations += listOf("zh", "en")
        // libarchive ships four ABIs; release devices are ARM. Add x86/x86_64 back
        // if you need an emulator build.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            // Not used without kotlin-reflect / commons-codec language APIs.
            excludes += "/kotlin/**"
            excludes += "/org/apache/commons/codec/language/**"
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.apache.commons:commons-compress:1.28.0")
    implementation("org.tukaani:xz:1.10")
    implementation("net.lingala.zip4j:zip4j:2.11.5")
    // Repackaged from me.zhanghai.android.libarchive:library:1.1.6 (compileSdk 35 compatible)
    // with the 1.1.7 native libraries (libarchive 3.8.8, includes the 2026 RAR5 fixes).
    implementation(files("libs/libarchive-android-1.1.6-libarchive-3.8.8.aar"))

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
