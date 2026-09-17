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
        versionCode = 5
        versionName = "1.5.4"

        ndk {
            // 客户端 ABI 固定 arm64-v8a（PRD 定稿）
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
}