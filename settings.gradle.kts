// 插件与依赖仓库统一在 settings 里管理（旧版 buildscript classpath / allprojects 已废弃）。
// 注：libxposed api 发布在 Maven Central；api.xposed.info 仓库仅为兼容保留。
pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        google()
        mavenCentral()
        maven("https://api.xposed.info/")
    }
}

rootProject.name = "fcmself"
include(":app")
