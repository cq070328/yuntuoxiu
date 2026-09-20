plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.yuntuoxiu.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.yuntuoxiu.app"
        minSdk = 24
        targetSdk = 34
        versionCode = 8
        versionName = "1.6.0"
        ndk {
            // 客户端 ABI 固定 arm64-v8a（PRD 定稿）
            abiFilters += listOf("arm64-v8a")
        }
    }

    // ⭐ v1.6 修复：固定签名（否则每次 CI 编译用随机 debug.keystore
    //   → 签名变化 → 覆盖安装失败 + Shizuku 授权丢失）
    //
    // keystore 位置：client/ytx-release.jks
    //   （本地 src/ytx-release.jks；push.sh 会把它复制为 client/）
    signingConfigs {
        create("ytx") {
            val ks = rootProject.file("ytx-release.jks")
            if (ks.exists()) {
                storeFile = ks
                storePassword = "ytx12345"
                keyAlias = "ytx"
                keyPassword = "ytx12345"
            }
        }
    }

    buildTypes {
        debug {
            // ★ 关键：debug 也用固定签名（CI 编的是 assembleDebug）
            if (signingConfigs.findByName("ytx")?.storeFile != null) {
                signingConfig = signingConfigs.getByName("ytx")
            }
        }
        release {
            isMinifyEnabled = false
            if (signingConfigs.findByName("ytx")?.storeFile != null) {
                signingConfig = signingConfigs.getByName("ytx")
            }
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
        viewBinding = true
        // ⚠️ 必须显式开启 AIDL！AGP 8.x 默认关闭。
        // 否则 src/main/aidl 下的 .aidl 不会被编译成 Java 接口，
        // 导致 IYunTuoXiuService / IYunTuoXiuCallback 找不到。
        aidl = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.cardview:cardview:1.0.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Shizuku SDK（官方，已核实坐标存在）
    // 官方 AAR: dev.rikka.shizuku:api:13.1.5 / provider:13.1.5
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")

    // ⚠️ 关于 LSPatch：**没有 Maven 依赖！**
    // 官方（LSPatch 仓库 README）说明：
    //   - 通过 jar：下载 lspatch.jar，`java -jar lspatch.jar`（在 PC/容器侧执行）
    //   - 通过 manager：在设备安装 manager.apk（用户手动操作）
    // 因此本项目**不在 App 内调用 LSPatch API**，而是：
    //   → 由 Operit 容器侧用 lspatch.jar 打补丁（见 backend/Skills/lspatch_runner.py）
    //   → 产物 APK 推给设备，App 只负责 `pm install`
    // 原先误写的 "org.lsposed.lspatch:core" 依赖已移除（该坐标不存在）。

    // JSON（分片契约 / action_payload 解析）
    implementation("com.google.code.gson:gson:2.10.1")

    // ⭐ v2.0：本地脱壳引擎（移植 newBlackDex / BlackBox 框架）
    implementation(project(":Bcore"))

    // ⭐ v2.0：本地修复/打包/签名（脱离终端）
    //   · apksig —— Google 官方 APK 签名库（v1/v2/v3）
    //   注：DEX 修复用纯字节/ZipFile 实现（无需 dexlib2，减少依赖风险）
    implementation("com.android.tools.build:apksig:8.5.2")
}