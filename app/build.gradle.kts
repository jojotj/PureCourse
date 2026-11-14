import java.util.Properties
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// ★ 版本号单一真源：发版时只改这两行，defaultConfig 与 dist 归档命名自动跟着变。
val appVersionCode = 37
val appVersionName = "2.0.9"
// dist 归档前缀，与 dist/ 里历史包保持一致（PureCourse-<versionName><-clean>-<abi>.apk）。
val distApkPrefix = "PureCourse"

/**
 * 两个 productFlavor，靠**源集隔离**区分预置数据，而不是运行期 if。
 *
 *   · `local` —— 日常自用包，带本机真实 SMB 配置与快捷位置（源码在 src/local）；
 *   · `clean` —— 对外发布包，src/clean 下那些 BuiltinPresets 全是空表 / 空表。
 *              真实 IP、账号、共享名**根本不参与编译**，dex 里搜不到，反编译也拿不到。
 *
 * 两者共用包名、签名、versionCode，只在 dist 文件名上用 `-clean` 后缀区分。
 * 可以互相覆盖安装（数据都在 App 私有目录，覆盖安装不会清数据）。
 */
// ★ 只归档纯净版：用户电视端已装并配好数据，后续发版一律只出 clean 包。
// local flavor 仍保留（仅用于模拟器 / 本机调试），不进 dist。
val distFlavors = listOf(
    "clean" to "-clean",  // PureCourse-<ver>-clean-<abi>.apk
)

android {
    namespace = "com.chunting.purecourse"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.chunting.purecourse"
        // 移除 libmpv 后不再受它的 minSdk 26 约束。定 21 是为了对齐同一台电视上
        // 实测能装的第三方应用（小黄人 minSdk 21、奇异果 minSdk 21）。
        minSdk = 21
        // targetSdk 定 34 而不是 36：本机电视是 TCL MT5879 / Android 11(API 30)。
        // 实测同一台电视上能装的第三方应用（含 VLC、小黄人）targetSdk 都在 32~34。
        // 36 在 API 30 设备上没有任何收益，反而可能被厂商安装校验判定为可疑。
        targetSdk = 34
        versionCode = appVersionCode
        // 2.0 相对 1.0：数据层改成「点哪层读哪层」，不再开机全树扫描。
        // 版本名除 20xx 日期之外只留语义版本，便于 GitHub Release 打标签（v2.0.1）。
        versionName = appVersionName
        resourceConfigurations += listOf("zh", "en")
    }

    // ★ 预置数据开关：只在 flavor 里注入，并由 flavor 各自的源集提供具体常量。
    flavorDimensions += "preset"
    productFlavors {
        // 自用：带本机配置
        create("local") {
            dimension = "preset"
            buildConfigField("boolean", "CLEAN_BUILD", "false")
        }
        // 发布：不带任何预置数据。
        create("clean") {
            dimension = "preset"
            buildConfigField("boolean", "CLEAN_BUILD", "true")
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86_64")
            // 用户明确只要单 ABI 包：不再额外产出 universal（两台电视都是 v7a）。
            isUniversalApk = false
        }
    }

    signingConfigs {
        create("release") {
            val ks = rootProject.file("keystore/courseplayer.jks")
            // ★ 口令不外置到仓库：从 local.properties（已 gitignore）或环境变量读。
            //   缺省空串时 Gradle 只会在签名阶段报错，不会把口令写进仓库。
            val props = Properties().apply {
                val f = rootProject.file("local.properties")
                if (f.exists()) f.inputStream().use { load(it) }
            }
            fun secret(key: String, env: String) =
                props.getProperty(key) ?: System.getenv(env) ?: ""
            if (ks.exists()) {
                storeFile = ks
                storePassword = secret("signing.storePassword", "PURECOURSE_STORE_PASSWORD")
                keyAlias = props.getProperty("signing.keyAlias") ?: "courseplayer"
                keyPassword = secret("signing.keyPassword", "PURECOURSE_KEY_PASSWORD")
            }
            // ★ 必须显式打开 v1（JAR）签名。
            // AGP 8 在 minSdk >= 24 时默认**只出 v2**，而 TCL 电视（MT5879 / Android 11）
            // 的安装校验器只认 v1：缺 v1 时 adb install 直接报
            // INSTALL_FAILED_VERIFICATION_FAILURE，与包内容无关。
            // 实测对照：同一台电视上能装的三个第三方应用（含 VLC）全是 v1=true。
            enableV1Signing = true
            enableV2Signing = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (rootProject.file("keystore/courseplayer.jks").exists()) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
        debug {
            // ★ release 包共存安装时，桌面应用列表要靠名字区分，否则两个「课程库」分不清
            applicationIdSuffix = ".dbg"
            resValue("string", "app_name", "纯看课·调试")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/AL2.0",
                "META-INF/LGPL2.1",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
            )
        }
    }

    lint {
        abortOnError = false
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.4")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    implementation("androidx.compose.ui:ui:1.7.6")
    implementation("androidx.compose.ui:ui-graphics:1.7.6")
    implementation("androidx.compose.foundation:foundation:1.7.6")
    implementation("androidx.compose.material3:material3:1.3.1")
    implementation("androidx.compose.ui:ui-tooling-preview:1.7.6")
    debugImplementation("androidx.compose.ui:ui-tooling:1.7.6")

    // 只用 TV 主题基座；控件 100% 自研（不用 tv-material 的 Card/Carousel）
    implementation("androidx.tv:tv-material:1.0.0")

    // ---- 内核 A：Media3(ExoPlayer)，SMB 走自研 DataSource ----
    implementation("androidx.media3:media3-common:1.5.1")
    implementation("androidx.media3:media3-datasource:1.5.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.5.1")
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")

    // ---- WebDAV：HTTP 客户端（PROPFIND 列目录 + 带 Basic 认证/自签证书直通的播放数据源）----
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // libmpv 已移除（2026-11-05）：PoC 结论是 Media3 足够（实测 FLV/H.264/AAC 全通）。
    // 而它带来的代价是——强制 minSdk 26、并把包从 5MB 撑到 34MB（ffmpeg + mpv 共约 30MB）。
    // 目标设备是 TCL MT5879（Android 11 / armeabi-v7a），参考同机能装的第三方应用
    // 均为 minSdk 21 / 包体 3MB 级，这里对齐它们能显著降低安装与运行风险。

    // ---- SMB2/3 ----
    implementation("com.hierynomus:smbj:0.13.0")
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")
    implementation("org.slf4j:slf4j-nop:1.7.36")
}

// ==================== 发版产物自动归档到工程根 dist/ ====================
// 约定：每次 assembleRelease 结束后，自动把两种在产 ABI 的单包按
// <前缀>-<versionName>-<abi>.apk 拷进工程的 dist/，同名直接覆盖。
// · x86_64 只用于模拟器调试，不进 dist。
// · 只覆盖同版本同名文件，dist 里的历史版本包（如 2.0.0）不受影响。
val distDir = rootProject.layout.projectDirectory.dir("dist")
val distAbis = listOf("armeabi-v7a", "arm64-v8a")

val copyReleaseApksToDist = tasks.register<Copy>("copyReleaseApksToDist") {
    group = "distribution"
    description = "Archive release APKs into dist/ as PureCourse-<ver>[-clean]-<abi>.apk"
    distFlavors.forEach { (flavor, suffix) ->
        from(layout.buildDirectory.dir("outputs/apk/$flavor/release")) {
            // ★ flavor 后产物名是 app-<flavor>-<abi>-release.apk。
            // ★ ABI 白名单过滤，x86_64 只用于模拟器，不进 dist
            include(distAbis.map { "app-$flavor-$it-release.apk" })
            rename { srcName ->
                val abi = srcName.removePrefix("app-$flavor-").removeSuffix("-release.apk")
                "$distApkPrefix-$appVersionName$suffix-$abi.apk"
            }
        }
    }
    into(distDir)
    doLast {
        distFlavors.forEach { (_, suffix) ->
            distAbis.forEach { abi ->
                val f = distDir.file("$distApkPrefix-$appVersionName$suffix-$abi.apk").asFile
                logger.lifecycle("[dist] ${if (f.exists()) "OK  ${f.absolutePath}" else "MISS $abi$suffix"}")
            }
        }
    }
}

// 聚合任务：所有 flavor 级任务都是 finalizer，跑哪一个都会自动归档（不用 dependsOn，避免环路）。
tasks.matching {
    it.name.equals("assembleRelease", true) ||
        it.name.equals("assembleLocalRelease", true) ||
        it.name.equals("assembleCleanRelease", true)
}.configureEach {
    finalizedBy(copyReleaseApksToDist)
}
