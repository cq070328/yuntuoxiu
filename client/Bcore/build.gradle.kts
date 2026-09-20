plugins {
    id("com.android.library")
}

android {
    namespace = "top.niunaijun.blackbox"
    compileSdk = 34

    defaultConfig {
        minSdk = 24
        targetSdk = 34
        consumerProguardFiles("consumer-rules.pro")
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    // native 构建（Dobby + DexDump）
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        aidl = true
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
        warningsAsErrors = false
        disable.addAll(listOf("UnusedResources", "RestrictedApi"))
        check.addAll(listOf("NewApi", "InlinedApi"))
    }
}

dependencies {
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar"))))
    implementation("androidx.appcompat:appcompat:1.7.0")
    // ⭐ free_reflection：原坐标 me.weishu:free_reflection:3.0.1 在 Maven Central 不存在，
    //    改用 JitPack 的 com.github.tiann:FreeReflection（已确认 3.2.2 可构建）
    implementation("com.github.tiann:FreeReflection:3.2.2")
    implementation(project(":Bcore:black-hook"))
    implementation(project(":Bcore:black-fake"))
}