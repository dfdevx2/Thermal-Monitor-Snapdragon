<p align="center">
  <img src="docs/icon.svg" alt="Thermal Monitor Snapdragon Icon" width="120" />
</p>

# Thermal Monitor — Snapdragon

​**Real-time floating overlay for Qualcomm Snapdragon devices** — per-cluster CPU temp, frequency & load, Adreno GPU stats, bus frequencies, RAM/SWAP, battery power and temperature, 24h history chart and hotspot counter.

[![Latest Release](https://img.shields.io/github/v/release/dfdevx2/Thermal-Monitor-Snapdragon)](https://github.com/dfdevx2/Thermal-Monitor-Snapdragon/releases)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Android 8.0+ (API 26)](https://img.shields.io/badge/Android-8.0%2B_%28API_26%29-brightgreen)](https://developer.android.com/about/versions/oreo)
[![Build Workflow](https://github.com/dfdevx2/Thermal-Monitor-Snapdragon/actions/workflows/build.yml/badge.svg)](https://github.com/dfdevx2/Thermal-Monitor-Snapdragon/actions)

---

## What is it?

​**Thermal Monitor — Snapdragon** is a real-time hardware monitoring overlay for Android devices powered by Qualcomm Snapdragon SoCs. It displays:

- **CPU**: per-cluster temperature, frequency and load
- **Adreno GPU**: temperature, frequency, load and memory usage
- **Bus frequencies**: L3 cache, LLCC and DDR memory bus speeds (kernel 5.15+, Snapdragon 8 Gen 2+)
- **RAM / SWAP**: current usage and swap
- **Battery**: charging status, power draw and temperature
- **24-hour history chart**: rolling temperature graph
- **Hotspot counter**: number of active wireless hotspots

The overlay floats on top of any app and is fully customizable (size, position, content, theme).

## Features

- **Automatic SoC detection** on first launch — reads `Build.SOC_MODEL`, `ro.soc.model` and `ro.board.platform`.
- **Runtime hardware mapping** (`HwProbe.kt`) — scans sysfs at runtime to discover CPU clusters, thermal zones, GPU and bus frequencies. No hardcoded paths.
- **CPU core-to-thermal-zone mapping** with a three-tier resolution:
  1. Cooling devices linked to thermal zones (device-tree table)
  2. Heuristic fallback (cluster 0 = efficiency cluster)
- **Thermal HAL fallback** — when the manufacturer blocks sysfs thermal data, temperatures are read via `dumpsys thermalservice`.
- **Fully customizable overlays** — adjust content, size, position, colors and themes.
- **Settings import / export** as JSON backup files.
- **Dual access backends**: ROOT (via libsu) and ADB (via Shizuku), with automatic selection.

## Access Modes: Root vs ADB

| Feature | ADB (Shizuku) | Root |
|---|---|---|
| CPU frequency per cluster | Yes (`scaling_cur_freq`, governor request) | Yes (`cpuinfo_cur_freq`, actual frequency) |
| CPU load per cluster (`/proc/stat`) | Yes | Yes |
| Temperatures (CPU, GPU, L3/CPUSS, DDR, NSP, video, camera) | Yes on most devices* | Yes |
| GPU load | Yes (`gpubusy`) | Yes |
| GPU frequency | No | Yes |
| GPU memory (kgsl) | No | Yes |
| L3 / LLCC / DDR frequency (kernel 5.15+, 8 Gen 2+) | Yes | Yes |
| DDR frequency on older kernels (865/888, debugfs) | No | Yes if debugfs is mounted |
| Voltages | No | Only rails exposed by the kernel |
| Battery (power, temperature, charging) | Yes | Yes |

> * Qualcomm reference SELinux policy allows `/sys/class/thermal` for all processes. If a manufacturer restricts it, the app falls back to the Thermal HAL automatically.

Hexagon (NPU), ISP and display block frequencies are not exposed by the Snapdragon kernel; those blocks show temperature only.

## Supported Chips

The app supports **all Qualcomm Snapdragon families**, including:

- **8 series**: 8 Elite Gen 5, 8 Gen 5, 8 Elite, 8s Gen 4, 8 Gen 3, 8s Gen 3, 8 Gen 2, 8+ Gen 1, 8 Gen 1, 888, 865/870, 855/860, 845
- **7 series**: 7 Gen 4, 7+ Gen 3, 7s Gen 3, 7 Gen 3, 7+ Gen 2, 7 Gen 1, 7s Gen 2, 780G, 778G, 765/768G, 750G, 730/732G, 720G, 712, 710
- **6 series**: 6 Gen 5, 6 Gen 4, 6 Gen 3, 6 Gen 1, 6s Gen 4, 695, 680, 675, 665, 662
- **4 series**: 4 Gen 5, 4s Gen 2, 4 Gen 2, 4 Gen 1, 480

Thanks to runtime detection, **any Snapdragon should work**. Reports of tested/untested devices are welcome.

## Installation

1. Download the latest APK from the [Releases](https://github.com/dfdevx2/Thermal-Monitor-Snapdragon/releases) page.
2. Grant the **Display over other apps** permission when prompted.
3. If using **Root mode**, grant root access via Magisk / KernelSU / APatch prompt.
4. If using **ADB mode**, start Shizuku and ensure it is running.

## Usage

- Launch the app and grant overlay permission.
- The floating overlay appears automatically showing real-time hardware stats.
- Tap the overlay to open settings: customize content, size, position, theme and more.
- Go to **Settings** for access mode selection, hardware remapping, and settings import/export.

## Building from Source

​**Requirements:**
- JDK 17
- Android SDK 35 (API level 35)
- Gradle wrapper included (`./gradlew`)

\`\`\`bash
./gradlew assembleDebug
\`\`\`

The APK will be produced at: `app/build/outputs/apk/debug/app-debug.apk`

## Project Structure

| File | Purpose |
|---|---|
| `AccessManager.kt` | Chooses root or ADB backend, requests su access, `RootCommandService` (libsu root process) |
| `HwProbe.kt` | Scans sysfs and builds the hardware map (runs in the privileged process) |
| `SocTable.kt` | Commercial chip names, core types, thermal-zone-to-core mapping table |
| `CommandService.kt` | Collector service (single thread, persistent file descriptors), bulk protocol |
| `SysNode.kt` | Sysfs node reader — no per-sample file reopen |
| `HwProfile.kt` | Detects the chip and applies the hardware map to overlay settings |
| `ThermalReader.kt` | Client: bind (root/Shizuku), watchdog, value caching |
| `OverlayService.kt` | Floating window manager, content rendering |
| `ThermalOverlayView.kt` | Main overlay canvas drawing |
| `InfoPanelView.kt` | Info panel components |
| `HistoryOverlayView.kt` | 24-hour temperature history chart |
| `BatteryMonitor.kt` | Battery power and temperature monitoring |
| `SettingsActivity.kt` | Settings UI (access mode, detected hardware, remap) |
| `SettingsBackup.kt` | Settings import / export as JSON |
| `OverlayPrefs.kt` | Overlay preference storage |
| `AppTheme.kt` | Theme definitions |

## Credits & Contributors

- **SiliconFET** — Original author. Created Thermal Monitor and wrote practically the entire app: the overlay UI, rendering, settings, themes, history chart, battery panel and the collection architecture. The original was built for the Exynos 2600; this fork adapts it to Qualcomm Snapdragon.
- **DFDX047** — Snapdragon port. Adapted the original Exynos-only app to Qualcomm Snapdragon: hardware detection and mapping, root/ADB backends, Adreno/kgsl support, bus_dcvs support and performance optimizations.

### Third-party

- [Shizuku API](https://github.com/RikkaApps/Shizuku) (RikkaApps) — ADB-based privileged shell API
- [libsu](https://github.com/topjohnwu/libsu) (topjohnwu) — Root access library supporting Magisk, KernelSU and APatch
- **Styrene B** font — Bundled with the app for tabular numerals. Check the font license before redistributing; if it is not freely redistributable, please remove it from your build.

## License

This project is licensed under the [MIT License](LICENSE).
