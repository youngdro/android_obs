plugins {
    kotlin("jvm") version "2.0.21"
}

repositories { mavenCentral() }


sourceSets {
    // 1) 推流模块（与 App 共用同一份源码）+ 端到端推流测试程序
    main {
        kotlin.srcDir("../app/src/main/java/app/livecomposer/rtmp")
        kotlin.srcDir("../app/src/main/java/app/livecomposer/mix")
        kotlin.srcDir("src/e2e/kotlin")
    }
    // 2) 整个 App 的编译检查（Android 框架 API 来自 Robolectric 发布在 Maven Central 的 android-all）
    create("androidCheck") {
        kotlin.srcDir("../app/src/main/java")
        // 界面层使用 Jetpack Compose（AndroidX），由 CI 的 Android 构建负责编译；这里检查引擎层
        kotlin.exclude("app/livecomposer/ui/**")
        // 媒体播放用 Media3（AndroidX），同理由 CI 编译；这里用公开 API 相同的替身
        kotlin.exclude("app/livecomposer/source/PlayerSource.kt")
        kotlin.srcDir("src/stubs/kotlin")
    }
}

val androidAll = "org.robolectric:android-all:14-robolectric-10818077"
dependencies {
    "androidCheckCompileOnly"(androidAll)
}

tasks.register<JavaExec>("rtmpE2E") {
    group = "verification"
    description = "把测试 H.264/AAC 推到 RTMP 服务器"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("RtmpE2EKt")
    jvmArgs("-Dstdout.encoding=UTF-8", "-Dfile.encoding=UTF-8")
    args = (project.findProperty("e2eArgs") as String? ?: "").split(" ").filter { it.isNotBlank() }
}

tasks.register<JavaExec>("urlChecks") {
    group = "verification"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("UrlChecksKt")
    jvmArgs("-Dstdout.encoding=UTF-8", "-Dfile.encoding=UTF-8")
}

tasks.register<JavaExec>("mixerChecks") {
    group = "verification"
    description = "混音器：重采样、音量、限幅、积压控制"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("MixerChecksKt")
    jvmArgs("-Dstdout.encoding=UTF-8", "-Dfile.encoding=UTF-8")
}
