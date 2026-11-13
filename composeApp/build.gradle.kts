// U1-a: three targets (android / desktop JVM / wasmJs), one common UI tree.
// The dependency set is the spike's verified set — nothing new is introduced
// here, so U2/U3 only write code and never touch this file.
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("multiplatform")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
    id("com.android.application")
}

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    // JVM 17, not "whatever JDK Gradle runs on": the daemon here is JDK 26, and without
    // an explicit target the desktop classes come out as class-file 70, which the JDK 21
    // used to launch/screenshot the app cannot load. Task book T1 asks for Desktop JVM 17.
    jvm("desktop") {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs {
        // `moduleName` was renamed to `outputModuleName` in KGP 1.9; 2.4.20 has no
        // `moduleName` accessor at all (the build script would not compile).
        outputModuleName = "composeApp"
        browser {
            commonWebpackConfig {
                outputFileName = "composeApp.js"
            }
        }
        binaries.executable()
    }

    sourceSets {
        val desktopMain by getting
        val wasmJsMain by getting

        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.ui)
            implementation(compose.material3)
        }

        desktopMain.dependencies {
            implementation(compose.desktop.currentOs)
        }

        androidMain.dependencies {
            // Same version the spike used; no bump.
            implementation("androidx.activity:activity-compose:1.9.3")
        }

        wasmJsMain.dependencies {
            // Nothing extra: ComposeViewport is the whole surface.
        }

        // Guards the C-09 rule "business pages only talk to Skin": fails the build
        // if any file under core/ or destinations/ imports material3 directly.
        val desktopTest by getting
        desktopTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

android {
    namespace = "vade.ui"
    // 37, not 36: the compose/material3 AARs pulled by CMP 1.12 declare
    // minCompileSdk=37, and AGP < 9.1 cannot resolve platforms;android-37.0.
    compileSdk = 37

    defaultConfig {
        applicationId = "vade.ui"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0-u1a"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
    }
}

compose.desktop {
    application {
        mainClass = "vade.ui.MainKt"
        nativeDistributions {
            // jpackage on Windows can only emit Msi/Exe. U1-a never packages; this
            // only has to configure cleanly so the task graph stays green.
            targetFormats(TargetFormat.Msi)
            packageName = "vade-ui"
            packageVersion = "0.1.0"
        }
    }
}

// --- helpers for the real-window screenshot loop (ui/tools/capture-window.ps1) ---
val desktopMainCompilation = kotlin.targets.getByName("desktop").compilations.getByName("main")

// Prints the runtime classpath so the PowerShell capture script can `java -cp` the
// app without paying Gradle configuration for every shot.
tasks.register("printRuntimeClasspath") {
    doLast {
        println(
            files(desktopMainCompilation.output.allOutputs, desktopMainCompilation.runtimeDependencyFiles).asPath,
        )
    }
}
