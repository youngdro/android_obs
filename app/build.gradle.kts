plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.livecomposer"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.livecomposer"
        minSdk = 29          // Android 10 及以上
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-demo"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // 正式发布时替换为自己的签名配置；当前 release 用 debug 签名，便于直接安装验证
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    lint {
        abortOnError = false
        htmlReport = true
    }
}

// 不依赖任何第三方库：Camera2 / MediaCodec / WebView / OpenGL ES / RTMP 均为系统 API 或项目内实现
dependencies {}
