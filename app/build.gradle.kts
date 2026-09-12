plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "sumicya.fcmself"
    compileSdk = 36

    defaultConfig {
        applicationId = "sumicya.fcmself"
        minSdk = 29
        targetSdk = 36
        versionCode = 56
        // CI 通过 -Pfcmself.versionName 注入「日期_短SHA」版本；本地构建回落到语义版本
        versionName = providers.gradleProperty("fcmself.versionName").getOrElse("0.9.0")
    }

    buildTypes {
        debug {
            // debug 也走 R8：把没用到的那 90%+ kotlin-stdlib 裁掉，
            // 同时保持默认 debug 签名（可直接安装），不再需要手动 apksigner。
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        release {
            // 正式发布用：R8 裁剪 + 未签名。
            // 入口类由 proguard-rules.pro 保留（LSPosed 按类名字符串加载）。
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        checkReleaseBuilds = false
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

// AGP 9 起 Kotlin 由 AGP 内置（built-in Kotlin），不再单独 apply kotlin-android。
// jvmTarget 与 compileOptions 的 17 保持一致；javaParameters 保留
// （MethodArgs 按参数名兜底定位依赖 -java-parameters）。
kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
        javaParameters = true
    }
}

dependencies {
    compileOnly(libs.libxposed.api)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("-parameters")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
