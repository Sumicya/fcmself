// 根工程只负责钉住插件版本；依赖与仓库见 settings.gradle.kts 与 gradle/libs.versions.toml。
plugins {
    alias(libs.plugins.android.application) apply false
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
