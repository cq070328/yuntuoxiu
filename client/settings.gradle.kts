pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        // LSPosed 仓库（LSPatch 依赖）
        maven(url = "https://maven.aliyun.com/repository/public")
        maven(url = "https://jitpack.io")
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven(url = "https://jitpack.io")
        maven(url = "https://maven.aliyun.com/repository/public")
    }
}
rootProject.name = "YunTuoXiu"
include(":app")