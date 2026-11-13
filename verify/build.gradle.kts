plugins {
    kotlin("jvm") version "2.0.21"
}

repositories { mavenCentral() }


sourceSets {
    // 1) 推流模块（与 App 共用同一份源码）+ 端到端推流测试程序
    main {
        kotlin.srcDir("../app/src/main/java/app/livecomposer/rtmp")
        kotlin.srcDir("../app/src/main/java/app/livecomposer/mix")
        // 助播截图通道的协议客户端（纯 JVM 部分），截图实现依赖 Android，排除
        kotlin.srcDir("../app/src/main/java/app/livecomposer/vision")
        kotlin.srcDir("../app/src/main/java/app/livecomposer/core")
        // 机械臂控制核心（纯 Kotlin）
        kotlin.srcDir("../app/src/main/java/app/livecomposer/arm")
        kotlin.exclude("**/AppConfig.kt", "**/Scenes.kt")
        kotlin.exclude("**/CameraFrameGrabber.kt")
        kotlin.srcDir("src/e2e/kotlin")
    }
    // 2) 整个 App 的编译检查（Android 框架 API 来自 Robolectric 发布在 Maven Central 的 android-all）
    // 3) 机械臂有线调试控制台（电脑 USB 串口）
    create("armcli") {
        kotlin.srcDir("../app/src/main/java/app/livecomposer/arm")
        kotlin.srcDir("src/armcli/kotlin")
    }
    create("androidCheck") {
        kotlin.srcDir("../app/src/main/java")
        // 界面层使用 Jetpack Compose（AndroidX），由 CI 的 Android 构建负责编译；这里检查引擎层
        kotlin.exclude("app/livecomposer/ui/**")
        // 媒体播放用 Media3（AndroidX），同理由 CI 编译；这里用公开 API 相同的替身
        kotlin.exclude("app/livecomposer/source/PlayerSource.kt")
        kotlin.exclude("app/livecomposer/source/WebCompat.kt")
        kotlin.srcDir("src/stubs/kotlin")
    }
}

val androidAll = "org.robolectric:android-all:14-robolectric-10818077"
val okhttp = "com.squareup.okhttp3:okhttp:4.12.0"
dependencies {
    "armcliImplementation"("com.fazecast:jSerialComm:2.11.0")
    "armcliImplementation"("org.json:json:20240303")
    "androidCheckCompileOnly"(androidAll)
    "androidCheckCompileOnly"(okhttp)
    implementation(okhttp)
    implementation("org.json:json:20240303")
    implementation("com.squareup.okhttp3:mockwebserver:4.12.0")
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

tasks.register<JavaExec>("denoiseChecks") {
    group = "verification"
    description = "麦克风降噪：底噪压低、人声保留、关闭时无损、固定延迟"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("DenoiseChecksKt")
    jvmArgs("-Dstdout.encoding=UTF-8", "-Dfile.encoding=UTF-8")
}

tasks.register<JavaExec>("visionE2E") {
    group = "verification"
    description = "助播截图通道：对接模拟服务端（发现、鉴权、心跳、截图请求与回传、错误与重连）"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("VisionE2EKt")
    jvmArgs("-Dstdout.encoding=UTF-8", "-Dfile.encoding=UTF-8")
    args = (project.findProperty("e2eArgs") as String? ?: "").split(" ").filter { it.isNotBlank() }
}

tasks.register<JavaExec>("configChecks") {
    group = "verification"
    description = "整体配置导出 / 加载格式与云端获取"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("ConfigChecksKt")
    jvmArgs("-Dstdout.encoding=UTF-8", "-Dfile.encoding=UTF-8")
}

tasks.register<JavaExec>("armChecks") {
    group = "verification"
    description = "机械臂控制核心：舵机协议、标定换算、正运动学、同步轨迹、限位、遥控超时、负载/卡住保护（模拟总线）"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("ArmChecksKt")
    jvmArgs("-Dstdout.encoding=UTF-8", "-Dfile.encoding=UTF-8")
}

tasks.register<JavaExec>("armCli") {
    group = "application"
    description = "机械臂有线调试控制台：-Pport=/dev/cu.usbmodemXXXX [-Pcal=标定.json] [-Ppresets=预设.json]"
    classpath = sourceSets["armcli"].runtimeClasspath
    mainClass.set("ArmCliKt")
    standardInput = System.`in`
    jvmArgs("-Dstdout.encoding=UTF-8", "-Dfile.encoding=UTF-8")
    args = listOfNotNull(project.findProperty("port") as String?, project.findProperty("cal") as String?, project.findProperty("presets") as String?)
}
