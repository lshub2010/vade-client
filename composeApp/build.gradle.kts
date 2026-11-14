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
            // U2 · miuix skin. Version + matrix come straight from spike/miuix/versions.lock.md:
            // 0.9.4 is the newest stable, compiled against Kotlin 2.4.20 / CMP 1.12.0 (both
            // already pinned in the root build), and its four Android AARs declare
            // minCompileSdk=37 (this module is already on 37). No AGP/Gradle/compileSdk change.
            implementation("top.yukonga.miuix.kmp:miuix-ui:0.9.4")
            // w2-bottombar · KernelSU-style floating pill. miuix-blur is a *separate*
            // artifact from miuix-ui (same version line, four variants: android /
            // desktop / js / wasmJs); it provides rememberLayerBackdrop /
            // Modifier.layerBackdrop / drawBackdrop + BackdropEffectScope (blur /
            // colorControls / runtimeShaderEffect) and the BloomStroke highlight.
            // Consumed ONLY via vade.ui.component.liquid (shell must stay toolkit-free).
            implementation("top.yukonga.miuix.kmp:miuix-blur:0.9.4")
            // v2 Wave 0 · real backdrop blur (docs/visual-language.md §5). 2.1.0 is the
            // probe-verified version (shots of tmp/haze-probe: three targets compile, desktop
            // really blurs); its `androidApiElements` / `jvmApiElements` / `wasmJsApiElements`
            // variants cover all three targets of this module. The tint MUST go through
            // `colorEffects(...)` — `backgroundColor` paints behind the opaque blurred layer
            // and is a no-op (probe RESULT.md §坑1).
            implementation("dev.chrisbanes.haze:haze:2.1.0")
            implementation("dev.chrisbanes.haze:haze-blur:2.1.0")
            // v2 Wave 0 · SessionSource transport seam. Ktor 3.6.1 = newest stable line on
            // Maven Central (metadata lastUpdated 2026-10-29); its Gradle module metadata
            // declares kotlin-stdlib 2.3.21, which the 2.4.20 compiler of this repo reads
            // (metadata is read one minor ahead), and the wasmJsApiElements variant exists
            // for every artifact below — verified against repo1.maven.org, not from memory.
            // Engines are per-target below; only the core/websockets/content-negotiation/
            // serialization contracts live in commonMain so `data/` stays engine-free.
            implementation("io.ktor:ktor-client-core:3.6.1")
            implementation("io.ktor:ktor-client-websockets:3.6.1")
            implementation("io.ktor:ktor-client-content-negotiation:3.6.1")
            implementation("io.ktor:ktor-serialization-kotlinx-json:3.6.1")
        }

        desktopMain.dependencies {
            implementation(compose.desktop.currentOs)
            // CIO engine: pure JVM, no extra native deps — the desktop default for Ktor 3.x.
            implementation("io.ktor:ktor-client-cio:3.6.1")
        }

        androidMain.dependencies {
            // Same version the spike used; no bump.
            implementation("androidx.activity:activity-compose:1.9.3")
            // OkHttp engine: the Android-recommended engine, backed by the platform's
            // connection pool and already on the classpath via activity-compose transitives.
            implementation("io.ktor:ktor-client-okhttp:3.6.1")
        }

        wasmJsMain.dependencies {
            // Browser engine: WebSocket + fetch against the JS runtime itself.
            implementation("io.ktor:ktor-client-js:3.6.1")
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
