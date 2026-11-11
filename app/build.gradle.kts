plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "app.livecomposer"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.livecomposer"
        minSdk = 29          // Android 10 及以上
        targetSdk = 35
        versionCode = 14
        versionName = "0.8.3"
    }

    // 固定的开发签名（仓库内提交，非正式发布密钥）：CI 每次打包签名一致，新版本可以直接覆盖安装、保留配置
    signingConfigs {
        create("dev") {
            storeFile = file("livecomposer-dev.keystore")
            storePassword = "android"
            keyAlias = "livecomposer"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("dev")
        }
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            // 正式发布时替换为自己的签名配置；当前用仓库内的开发签名，便于直接安装验证
            signingConfig = signingConfigs.getByName("dev")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }

    lint {
        abortOnError = false
        htmlReport = true
    }
}

// 媒体链路（Camera2 / MediaCodec / WebView / OpenGL ES / RTMP）只用系统 API 与项目内实现；
// 界面使用 Jetpack Compose（Apache-2.0）。
dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    // 媒体图层：拉流（HLS / RTSP / RTMP / HTTP-FLV / MP4）与本地视频
    val media3 = "1.4.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-exoplayer-rtsp:$media3")
    implementation("androidx.media3:media3-datasource-rtmp:$media3")
    // 网页音频桥：在页面脚本之前注入（DOCUMENT_START_SCRIPT）
    implementation("androidx.webkit:webkit:1.12.1")
    // 助播截图通道：HTTPS 发现接口 + WebSocket
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
