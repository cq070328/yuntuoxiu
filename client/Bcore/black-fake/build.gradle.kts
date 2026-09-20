plugins {
    id("com.android.library")
}

android {
    namespace = "top.niunaijun.black_fake"
    compileSdk = 34

    defaultConfig {
        minSdk = 24
        targetSdk = 34
        consumerProguardFiles("consumer-rules.pro")
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

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
        warningsAsErrors = false
        disable += setOf("UnusedResources", "RestrictedApi")
        check += setOf("NewApi", "InlinedApi")
    }
}

tasks.withType<Javadoc> {
    options.addStringOption("Xdoclint:none", "-quiet")
    options.addStringOption("encoding", "UTF-8")
    options.addStringOption("charSet", "UTF-8")
}

dependencies {
    implementation(project(":Bcore:black-hook"))
}