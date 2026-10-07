plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.dictate.widget"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.dictate.widget"
        minSdk = 29   // Bubble API требует Android 10+ (29), стабильно с 11 (30)
        targetSdk = 34
        // В CI versionCode растёт с каждой сборкой — это позволяет ставить
        // новую сборку поверх установленной (нужна ещё одна и та же подпись).
        versionCode = System.getenv("VERSION_CODE")?.toIntOrNull() ?: 2
        versionName = System.getenv("VERSION_NAME") ?: "2.0"

        buildConfigField("String", "API_BASE_URL", "\"https://voicebot-iwdm.onrender.com\"")
        buildConfigField("String", "APP_SECRET_TOKEN", "\"my_super_secret_123\"")
    }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        create("release") {
            val ks = System.getenv("KEYSTORE_FILE")
            if (ks != null) {
                storeFile = file(ks)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            // Та же подпись, что у release — чтобы debug и release ставились друг поверх друга
            if (System.getenv("KEYSTORE_FILE") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        release {
            if (System.getenv("KEYSTORE_FILE") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    // Для ShortcutInfoCompat (требование Bubble API)
    implementation("androidx.core:core:1.12.0")
}
