pluginManagement {
    repositories {
        // AGP 只发布在 Google 仓库，缺了它 IDE/命令行都解析不到 com.android.library
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "pacc-android-probe"

include(":app")
