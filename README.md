# Vade Client

**English** | [简体中文](README.zh-CN.md)

![Release build](https://github.com/lshub2010/vade-client/actions/workflows/release.yml/badge.svg)
![CI](https://github.com/lshub2010/vade-client/actions/workflows/ci.yml/badge.svg)

**Vade** (Chinese name 「偕行」) is an agent runtime built around a deliberately thin core: the core owns connection, routing, the turn loop, keys and audit, while models, tools, memory and clients attach as separate processes.

This repository is the **client**: one Compose Multiplatform UI shared by Android, desktop (JVM) and Web (wasmJs). The protocol core, contracts and design docs live in **[lshub2010/Vade](https://github.com/lshub2010/Vade)**. The client contains no model of its own — **it must connect to a core**.

> [!WARNING]
> **Alpha software.** Features are incomplete, and the protocol, data formats and UI behaviour may change between releases. Do not use it in production, and do not store important data that cannot be recovered. Back up before upgrading.

> [!WARNING]
> **AI-assisted development.** The code is built on AI-generated work and has gone through automated tests and human review, but consistent style, readability, complete comments and coverage of every edge case are not guaranteed. Audit, harden and extend the tests before using it in critical settings.

## Release status

- **Upcoming release: `0.0.2-alpha`** — [download page](https://github.com/lshub2010/vade-client/releases/tag/v0.0.2-alpha). The assets below are attached by CI when the release is published.
- The single version source (`vadeVersion` in [`gradle.properties`](gradle.properties)) is `0.0.2-alpha`; the Android `versionName` and the MSI `packageVersion` (numeric `0.0.2`) derive from it.
- The Android APK is built with `:composeApp:assembleDebug` and is **debug-signed** (see [Debug-signed APK](#debug-signed-apk)).

## Platform status

| Platform | Status | Notes |
|---|---|---|
| Android | ✅ Available | minSdk 24, targetSdk 36, compileSdk 37. Tested on an API 30 device and an API 37 emulator. |
| Desktop · Windows | ✅ Available | MSI installer or portable zip (bundled runtime). `gradle :composeApp:run` also works. |
| Desktop · macOS / Linux | ⚠️ Not verified | Same source and build path, but no prebuilt artifacts and no recorded test pass. Build from source with JDK 21. |
| Web · wasmJs | 🟡 Compiles; CI packages a static site | The browser cannot complete first-time pairing (see [Known limitations](#known-limitations)); running the built site in a browser has not been verified here. |

## Download and install (0.0.2-alpha)

Release page: **<https://github.com/lshub2010/vade-client/releases/tag/v0.0.2-alpha>**

| Asset | Platform | How to install |
|---|---|---|
| `vade-ui-0.0.2-alpha-android.apk` | Android | Sideload the APK (allow installation from unknown sources). |
| `vade-ui-0.0.2-alpha-windows-x64.msi` | Windows | Run the installer. If a release has no MSI (the WiX step is allowed to fail), use the zip. |
| `vade-ui-0.0.2-alpha-windows-x64.zip` | Windows | Unzip anywhere and run `vade-ui.exe`. The bundled jlink runtime means no JDK is needed. |
| `vade-ui-0.0.2-alpha-web.zip` | Web | Unzip and serve the directory over HTTP; it will not run from `file://`. |

### Debug-signed APK

The APK comes from the `debug` build variant, and this repository has no release signing configuration, so it carries the Android **debug key**:

- it is intended for testing and sideloading, not for app stores or production distribution;
- a debug key is not a stable release key: APKs built on different machines or CI runs may not share the same key. If Android reports a signature mismatch when updating, uninstall the previous build first.

### Windows MSI and alpha versions

`jpackage` derives the MSI `ProductVersion` by dropping the `-alpha` suffix. Two alphas with the same numeric `x.y.z` (for example `0.0.2-alpha.1` and `0.0.2-alpha.2`) are therefore indistinguishable to Windows Installer: uninstall the older build first, or use the portable zip. Moving from `0.0.1` to `0.0.2` is a normal version change.

## Connect to a core

The client starts in **fixture mode** by default: it shows local sample data and makes no network requests. This is useful for previewing the UI offline. To use real sessions, models, tools and approvals, start a core and point the client at it.

### 1. Start a core

The core is not in this repository; run it from the root of [lshub2010/Vade](https://github.com/lshub2010/Vade).

```bash
# Linux / macOS — both entries are required in PYTHONPATH
export PYTHONPATH="core:protocol/core/python"
.venv/bin/python -m vade --config core.toml
```

```powershell
# Windows — the separator is a semicolon
$env:PYTHONPATH = "core;protocol/core/python"
.venv\Scripts\python.exe -m vade --config core.toml
```

`PYTHONPATH` must contain both `core` (the `vade` package) and `protocol/core/python` (the native `vade_protocol` binding). Without the first you get `No module named vade`; without the second, `protocol core python binding is not importable`.

On startup the core prints a pairing code like:

```text
pair_code=茶源-45JV2U purpose=browser expires_in=600s
```

The code is one-time and valid for **600 seconds (10 minutes)**. If it expires, restart the core to get a new one.

### 2. Point the client at the core

All three targets accept the same four keys: **`source`**, **`host`**, **`port`**, **`pair_code`**. Each platform only differs in how the values are passed.

| Key | Required | Meaning |
|---|---|---|
| `source` | No | The exact value `real` enables the live core. Missing or any other value keeps fixture mode. |
| `host` | No | Core host. Defaults to `127.0.0.1`. |
| `port` | No | Core port. Defaults to `8443`; a non-empty invalid value is a hard error, never a silent fallback. |
| `pair_code` | Practically required | The one-time code. Without it the WebSocket upgrade is treated as a device peer and the client cannot attach. |

**Android** — ADB intent extras. The keys are **bare** (`source`, not `vade.source`):

```bash
adb shell am start -n io.github.lshub2010.vade/vade.ui.MainActivity \
  --es source real --es host 192.168.1.10 --es port 8443 --es pair_code ABCDEFGH
```

**Desktop** — environment variables (example uses the portable app-image; on Windows the executable is `vade-ui.exe`):

```bash
VADE_SOURCE=real VADE_HOST=192.168.1.10 VADE_PORT=8443 VADE_PAIR_CODE=ABCDEFGH ./bin/vade-ui
```

```powershell
$env:VADE_SOURCE="real"; $env:VADE_HOST="192.168.1.10"; $env:VADE_PORT="8443"; $env:VADE_PAIR_CODE="ABCDEFGH"; .\vade-ui.exe
```

**Web** — URL query parameters:

```text
http://localhost:8080/?source=real&host=192.168.1.10&port=8443&pair_code=ABCDEFGH
```

> The Web build parses `pair_code`, but a browser cannot read the `Set-Cookie` returned by `POST /ui/session`, so it cannot bootstrap pairing and deliberately does not try. Complete the first pairing in the desktop or Android client.

You can also pass no parameters: on first launch the session list shows a pairing card where you can enter the host, port and one-time code. After a successful pairing the client rebuilds its source against that core. The input is available on desktop and Android; the browser does not attempt the exchange and only tells you to pair on another device.

### 3. First steps

1. Tap **✎** (phone) or press **⌘N / Ctrl+N** (desktop) to create a session. The session is created on the core, not locally.
2. Type in the floating composer and press the round send button.
3. To change the model, use the **model chip** at the bottom-right of the composer.
4. When a model wants to run a risky action, an **inline approval card** appears: ✓ allows that one call, ✕ denies it. If nobody answers before the timeout, the call is denied (fail-closed).

## What you can do

- **Sessions** — create, open, rename, pin, archive, group and delete; failures roll the row back and show an error instead of leaving the UI out of sync with the core.
- **Chat** — token-by-token streaming replies; the ■ button interrupts generation and keeps what was already produced.
- **Approvals** — inline cards with a tool/argument summary; decisions apply to one call only. Browser sessions cannot approve by design; approve on the device.
- **Models and usage** — per-session model selection, with the session's cumulative token usage in the header.
- **Attachments** — add images or files with **+**; image-only messages are allowed.
- **Search** — 3-character minimum, 200 ms debounce, highlighted matches.
- **Groups and appearance** — group colour/icon management (deleting a group does not delete its sessions); `material` and `miuix` skins; system/light/dark theme.

## Build from source

Requirements:

- **JDK 21**
- **Gradle 9.6.1** on `PATH` — this project does not generate a wrapper
- **Android SDK** with `platforms;android-37.0` and `build-tools;37.0.0` (only for the APK)
- `local.properties` with `sdk.dir` pointing at your SDK, using forward slashes (the file is git-ignored)

```properties
sdk.dir=D:/Android/Sdk
```

Common commands, from the repository root:

```bash
gradle :composeApp:run                          # desktop: run from source
gradle :composeApp:createDistributable          # desktop: portable app-image with bundled runtime
gradle :composeApp:packageMsi                   # desktop: MSI installer
gradle :composeApp:assembleDebug                # Android APK
gradle :composeApp:compileKotlinWasmJs          # Web: compile check
gradle :composeApp:wasmJsBrowserDistribution    # Web: static site
gradle :composeApp:desktopTest --rerun          # full desktop test suite
```

Before merging, all three gates must pass:

```bash
gradle :composeApp:desktopTest --rerun
gradle :composeApp:assembleDebug
gradle :composeApp:compileKotlinWasmJs
```

Test totals must be read from `composeApp/build/test-results/desktopTest/TEST-*.xml`; a bare `BUILD SUCCESSFUL` is not enough. The Gradle daemon caches environment variables, so run `gradle --stop` after changing `JAVA_HOME` or `ANDROID_HOME`. See [`docs/DEVELOPING.md`](docs/DEVELOPING.md) for the build matrix and anti-regression constraints, and [`docs/ENVIRONMENT.md`](docs/ENVIRONMENT.md) for why each version is pinned.

## Data and privacy

- The pairing code stays **in memory** and is not written to disk or logs; guard tests enforce this. The Web launch form is the exception for transport: it accepts `pair_code` in the URL query, so avoid that form on shared machines.
- The auth token is issued by the core; the client does not print or report it.
- Fixture mode sends **no network requests**.

## Known limitations

1. **Web cannot perform first-time pairing.** A browser cannot read the `Set-Cookie` from `POST /ui/session`, so the Web build refuses to attempt the exchange. Pair in the desktop or Android client first. Running the built Web site in a browser is also not verified in this repository.
2. **No connection indicator mid-session.** The client reconnects automatically with exponential backoff (capped at 30 s), but the connection state is only shown while the session list first loads.
3. **A session can attach twice on one connection.** This is a known baseline issue. Normal use is unaffected, but events may be processed twice on a long-lived connection; restarting the client clears it.

**Verification gaps.** macOS and Linux desktop builds are unverified. Android has been tested on an API 30 device and an API 37 emulator, not across a wider device matrix. The real-core streaming chat path (`ui/submit` / `turn.delta`) has not been re-verified end to end in this tree; protocol attach and pairing are covered by tests.

## Documentation

| What you need | Where |
|---|---|
| Project design, protocol and decision log | [`docs/` in lshub2010/Vade](https://github.com/lshub2010/Vade) |
| Step-by-step setup and common errors | [`docs/QUICKSTART.md`](docs/QUICKSTART.md) |
| Build matrix, gates, anti-regression constraints | [`docs/DEVELOPING.md`](docs/DEVELOPING.md) |
| Build environment and pinned versions | [`docs/ENVIRONMENT.md`](docs/ENVIRONMENT.md) |
| Visual language (radii, shadows, blur, tokens) | [`docs/visual-language.md`](docs/visual-language.md) |
| Protocol contracts (rpc / events / schema) | [`contracts/vade`](contracts/vade) (git submodule) |
| Alpha status and known defects | [`docs/ALPHA.md` in lshub2010/Vade](https://github.com/lshub2010/Vade/blob/main/docs/ALPHA.md) |

## License

**Apache License 2.0** — see [`LICENSE`](LICENSE) for the full terms and [`NOTICE`](NOTICE) for attribution and third-party notices.

If you modify and redistribute Vade, keep `LICENSE` and `NOTICE`, state prominently in modified files that you changed them, do not imply that the original authors endorse your version, and do not use the project's trademarks. In short: you may modify, commercialise and closed-source your changes, but you may not remove the original attribution or present a modified version as the original.

## Citation

If you use Vade in academic work, cite it using the metadata in [`CITATION.cff`](CITATION.cff):

```bibtex
@software{vade2026,
  title     = {Vade: a thin-core, module-first agent runtime},
  author    = {{Vade contributors}},
  year      = {2026},
  url       = {https://github.com/lshub2010/vade-client},
  note      = {Client for Android / Desktop / Web (Kotlin Multiplatform)}
}
```

## Third-party components

The project builds on the open-source components below. Their licenses are independent of this project's license, but their notices must be retained when redistributing binaries. Versions are taken from this repository's build files; license identifiers follow the upstream projects' published metadata.

| Component | Version | License | Use / link |
|---|---|---|---|
| Compose Multiplatform | 1.12.0 | Apache-2.0 | UI runtime for all three targets · [JetBrains](https://github.com/JetBrains/compose-multiplatform) |
| Kotlin | 2.4.20 | Apache-2.0 | Language runtime |
| Ktor Client (core / websockets / content-negotiation / cio / okhttp / js) | 3.6.1 | Apache-2.0 | Transport and WebSocket · [Ktor](https://ktor.io) |
| AndroidX Activity Compose | 1.9.3 | Apache-2.0 | Android entry point |
| **Miuix** (`miuix-ui`) | 0.9.4 | **Apache-2.0** | Theme, spring and component set · [miuix](https://github.com/miuix-kotlin-multiplatform/miuix) |
| **haze** (haze / haze-blur) | 2.1.0 | **Apache-2.0** | Real Gaussian background blur · [haze](https://github.com/chrisbanes/haze) |
| **Tabler Icons** (icons-tabler-cmp / outline / filled) | 2.2.1 | **MIT** | Vector icons, packaged via [composeicons](https://github.com/composablehorizons/composeicons); icon set Copyright (c) 2018-2026 Tabler contributors |
| **KernelSU** (floating-capsule interaction port) | — | **Apache-2.0** | Damped drag indicator, press scale, velocity stretch, rubber band, inner shadow · Copyright (c) 2023-2026 weishu & contributors · [KernelSU](https://github.com/tiann/KernelSU) (original implementation adapted from `IosLiquidGlassNavigationBar` in compose-miuix-ui) |

All of the above are Apache-2.0 or MIT, which is compatible with this project's Apache-2.0 distribution. Their copyright and license notices are kept with the source and binaries; see [`NOTICE`](NOTICE).
