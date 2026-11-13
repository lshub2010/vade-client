// Vade UI — Kotlin Multiplatform skeleton (U1-a).
//
// No gradle wrapper on purpose: this repo has none and the host image ships Gradle
// 9.6.1 on PATH (`D:/Hermes/tools/gradle-9.6.1/bin/gradle`). Adding a wrapper would
// need a distribution download that this machine cannot fetch; see ui/README.md.
//
// Repositories mirror spike/miuix/settings.gradle.kts (which is the verified C0c
// setup): Aliyun mirrors first (repo1 is slow from here), then the upstreams.
// `google()` is NOT removable: androidx.navigationevent & the compose AARs only
// exist there.
pluginManagement {
    repositories {
        maven("https://maven.aliyun.com/repository/public")
        maven("https://maven.aliyun.com/repository/google")
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        maven("https://maven.aliyun.com/repository/public")
        maven("https://maven.aliyun.com/repository/google")
        google()
        mavenCentral()
    }
}

rootProject.name = "vade-ui"

// U1-a ships a single KMP module. `shared/` (state machine + ProtocolClient, C-10)
// lands with U1-b; it is created there so this slice never has to move files.
include(":composeApp")
