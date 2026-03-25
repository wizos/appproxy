import java.util.Properties

plugins {
    id("com.android.application")
    id("kotlin-android")
    // The Flutter Gradle Plugin must be applied after the Android and Kotlin Gradle plugins.
    id("dev.flutter.flutter-gradle-plugin")
}

// 读取 local.properties
val localProperties = Properties()
val localPropertiesFile = rootProject.file("local.properties")
if (localPropertiesFile.exists()) {
    localPropertiesFile.inputStream().use { localProperties.load(it) }
}

// 获取 keystore 路径
val keystorePath = localProperties.getProperty("flutter.keystore")
    ?: throw GradleException("flutter.keystore is not set in local.properties")

android {
    namespace = "cn.ys1231.appproxy"
    compileSdk = 36  // Flutter 插件要求至少 36
    ndkVersion = flutter.ndkVersion

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    defaultConfig {
        applicationId = "cn.ys1231.appproxy"
        // You can update the following values to match your application needs.
        // For more information, see: https://flutter.dev/to/review-gradle-config.
        minSdk = 28  // 跟随 tun2socks.aar api
        targetSdk = 36
        versionCode = flutter.versionCode
        versionName = flutter.versionName
        
        // 只保留最常用的架构（可选优化）
        // 移除 x86_64 可以减少包大小（大多数设备是 ARM）
        // ndk {
        //     abiFilters += listOf("armeabi-v7a", "arm64-v8a")
        // }
    }

    signingConfigs {
        getByName("debug") {
            // Debug signing config
        }
        create("release") {
            keyAlias = System.getenv("KEY_ALIAS")
            keyPassword = System.getenv("KEY_PASSWORD")
            storeFile = file(keystorePath)
            storePassword = System.getenv("KEYSTORE_PASSWORD")
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    packaging {
        dex {
            useLegacyPackaging = true // 启用 Dex 压缩
        }
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            // 排除导致冲突的 META-INF 文件
            excludes += "META-INF/INDEX.LIST"
            excludes += "META-INF/io.netty.versions.properties"
            excludes += "META-INF/DEPENDENCIES"
            excludes += "META-INF/LICENSE"
            excludes += "META-INF/LICENSE.txt"
            excludes += "META-INF/NOTICE"
            excludes += "META-INF/NOTICE.txt"
        }
    }
}

flutter {
    source = "../.."
}

dependencies {
    implementation(files("libs/tun2socks.aar"))
    implementation("com.google.code.gson:gson:2.13.2")
    implementation("io.ktor:ktor-server-cors:3.4.1")
    implementation("io.ktor:ktor-server-netty:3.4.1")
    implementation("io.ktor:ktor-server-auth:3.4.1")
    implementation("io.ktor:ktor-server-sse:3.4.1")
    implementation("io.modelcontextprotocol:kotlin-sdk-server:0.9.0")
    implementation("io.ktor:ktor-server-content-negotiation:3.4.1")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.4.1")
}
