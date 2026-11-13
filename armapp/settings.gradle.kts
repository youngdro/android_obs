// 机械臂操控软件（电脑版）。界面用 Compose，控制核心与安卓 App 共用 app/src/main/java/app/livecomposer/arm。
// 版本可用 -PkotlinVersion= / -PcomposeVersion= 覆盖（Gradle 9 上若 Compose 插件报不兼容，换 2.2.20 / 1.9.0）。
pluginManagement {
    repositories { gradlePluginPortal(); mavenCentral(); google() }
    val kotlinVersion = providers.gradleProperty("kotlinVersion").getOrElse("2.0.21")
    val composeVersion = providers.gradleProperty("composeVersion").getOrElse("1.7.0")
    plugins {
        kotlin("jvm") version kotlinVersion
        id("org.jetbrains.kotlin.plugin.compose") version kotlinVersion
        id("org.jetbrains.compose") version composeVersion
    }
}
rootProject.name = "armapp"
