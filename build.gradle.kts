// 顶层构建脚本：只声明插件版本，不做其他事（AGP 8.13.2 需 Gradle 8.13）
plugins {
    id("com.android.application") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "2.1.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.20" apply false
}
