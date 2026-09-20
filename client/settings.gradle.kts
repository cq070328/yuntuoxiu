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
// ⭐ v2.0：移植 newBlackDex 脱壳引擎（BlackBox 框架）
include(":Bcore:black-hook")
include(":Bcore:black-fake")
include(":Bcore")