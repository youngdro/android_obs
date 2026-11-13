plugins {
    kotlin("jvm")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
}

repositories { mavenCentral(); google() }

sourceSets {
    main {
        // 控制核心（纯 Kotlin，与安卓 App 同一份源码）
        kotlin.srcDir("../app/src/main/java/app/livecomposer/arm")
    }
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation("com.fazecast:jSerialComm:2.11.0")
    implementation("org.json:json:20240303")
}

compose.desktop {
    application {
        mainClass = "app.livecomposer.armapp.MainKt"
        nativeDistributions {
            packageName = "ArmControl"
            packageVersion = "1.0.0"
        }
    }
}
