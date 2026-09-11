plugins {
    // AGP 9 開始內建 Kotlin 支援，不需要（也不能）再套用 org.jetbrains.kotlin.android，
    // 套了會直接 "Failed to apply plugin" 建置失敗
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.cornming.lenstag"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.cornming.lenstag"
        minSdk = 26 // ML Kit GenAI Prompt API 要求 API 26+
        targetSdk = 36
        // CI（.github/workflows/release.yml）會帶 VERSION_CODE / VERSION_NAME 進來
        // 做自動版號遞增；本機建置沒有這兩個環境變數時就用下面的預設值。
        versionCode = System.getenv("VERSION_CODE")?.toIntOrNull() ?: 1
        versionName = System.getenv("VERSION_NAME") ?: "0.1.0"
    }

    signingConfigs {
        getByName("debug") {
            // 固定用 repo 裡存好的 debug keystore（keystore/debug.keystore），
            // 不要用 CI runner 每次自動產生的那把——runner 是全新機器，沒有這個
            // 設定的話每次建置的 debug 簽章都不一樣，裝置上會因為簽章對不起來
            // 裝不上去，只能每次先解除安裝舊版再裝新版。
            storeFile = rootProject.file("keystore/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        compose = true
        buildConfig = true // UpdateChecker 要讀 BuildConfig.VERSION_CODE
    }
}

dependencies {
    implementation(libs.core.ktx)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.activity.compose)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.compose.material3)

    implementation(libs.camera.core)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)

    implementation(libs.mlkit.objectdetection)
    implementation(libs.mlkit.genai.prompt)

    testImplementation(libs.junit)
    testImplementation(libs.org.json)
}
