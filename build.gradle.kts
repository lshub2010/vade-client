// Plugin versions are pinned to the matrix verified in spike/miuix (C0c):
// Kotlin 2.4.20 + Compose Multiplatform 1.12.0 are the exact pair Miuix 0.9.4
// was published against, and AGP 9.5.1 is the first Android plugin that can
// consume compileSdk 37 (androidx compose 1.12 requires AGP >= 9.1).
//
// Do not bump one line of this block in isolation — see ui/README.md §矩阵约束
// and spike/miuix/versions.lock.md §四 (checklist before touching the matrix).
plugins {
    kotlin("multiplatform") version "2.4.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20" apply false
    id("org.jetbrains.compose") version "1.12.0" apply false
    id("com.android.application") version "9.5.1" apply false
}
