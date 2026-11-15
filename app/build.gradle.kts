plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.example.pdfreader"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.pdfreader"
        minSdk = 24
        targetSdk = 35
        versionCode = 2
        versionName = "1.1"
        // Только языки, на которые переведён интерфейс (переводы библиотек на другие языки отбрасываются).
        resourceConfigurations += listOf(
            "en", "ru", "es", "pt", "fr", "de", "it", "pl", "uk", "tr",
            "ar", "hi", "in", "ja", "ko", "zh-rCN", "zh-rTW",
        )
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Для простоты release подписывается debug-ключом, чтобы APK сразу ставился.
            signingConfig = signingConfigs.getByName("debug")
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
    androidResources {
        // Список языков для выбора языка приложения в настройках Android 13+.
        generateLocaleConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
}
