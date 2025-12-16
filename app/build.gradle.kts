plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization") version "1.9.22"
    id("com.chaquo.python")
}

android {
    namespace = "com.resonanz.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.resonanz.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        
        // Chaquopy configuration
        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
        }
    }
    
    // Chaquopy Python configuration
    flavorDimensions += "pyVersion"
    productFlavors {
        create("py311") {
            dimension = "pyVersion"
        }
    }
    
    // Signing configuration for release builds
    signingConfigs {
        create("release") {
            // Use debug keystore for easy sharing (for personal use)
            storeFile = file(System.getProperty("user.home") + "/.android/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
    }
}

chaquopy {
    defaultConfig {
        version = "3.11"
        
        pip {
            // yt-dlp for YouTube downloading
            install("yt-dlp")
            // mutagen for ID3 tag editing
            install("mutagen")
        }
    }
}

dependencies {
    // Core
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.appcompat:appcompat:1.6.1")
    
    // Compose BOM
    implementation(platform("androidx.compose:compose-bom:2024.02.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.foundation:foundation")
    debugImplementation("androidx.compose.ui:ui-tooling")
    
    // Navigation Compose
    implementation("androidx.navigation:navigation-compose:2.7.7")
    
    // NanoHTTPD - HTTP Server
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    
    // ZXing QR Scanner
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    
    // Media3 for music playback
    implementation("androidx.media3:media3-exoplayer:1.2.1")
    implementation("androidx.media3:media3-session:1.2.1")
    implementation("androidx.media3:media3-ui:1.2.1")
    
    // Coil for image loading (Compose)
    implementation("io.coil-kt:coil-compose:2.5.0")
    
    // Palette for color extraction from album art
    implementation("androidx.palette:palette-ktx:1.0.0")
    
    // Retrofit for Lyrics API
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")
    
    // Guava for ListenableFuture (required by MediaSession)
    implementation("com.google.guava:guava:32.1.3-android")
    
    // Kotlinx Serialization for Lyrics model
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2")
    
    // Reorderable for Drag & Drop in LazyColumn
    implementation("sh.calvin.reorderable:reorderable:2.4.3")
}
