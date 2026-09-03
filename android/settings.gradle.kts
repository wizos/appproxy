pluginManagement {
    val flutterSdkPath =
        run {
            val properties = java.util.Properties()
            file("local.properties").inputStream().use { properties.load(it) }
            val flutterSdkPath = properties.getProperty("flutter.sdk")
            require(flutterSdkPath != null) { "flutter.sdk not set in local.properties" }
            flutterSdkPath
        }

    includeBuild("$flutterSdkPath/packages/flutter_tools/gradle")

    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("dev.flutter.flutter-plugin-loader") version "1.0.0"
    id("com.android.application") version "9.3.2" apply false
//    id("com.android.application") version "8.13.2" apply false
    // KGP classpath 声明仅用于 Flutter 版本校验；AGP 9 下编译走 Built-in Kotlin，插件不会被实际应用
    id("org.jetbrains.kotlin.android") version "2.4.10" apply false
}

include(":app")
