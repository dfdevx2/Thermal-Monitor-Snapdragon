# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [0.5.0] - 2026-01-10

### Added

- **Snapdragon port** of Thermal Monitor (originally built for Exynos 2600 by SiliconFET).
- **Automatic SoC detection** on first launch and re-detection via hardware probing — reads `Build.SOC_MODEL`, `ro.soc.model`, `ro.board.platform` and `/proc/cpuinfo`.
- **Runtime hardware mapping** (`HwProbe.kt`): scans sysfs at runtime to discover CPU clusters, thermal zones, GPU, bus frequencies — no hardcoded paths.
- **CPU core-to-thermal-zone mapping** with a three-tier resolution: cooling devices linked to zones → device-tree table from Qualcomm (`SocTable.kt`) → heuristic fallback (cluster 0 = efficiency cluster).
- **Thermal HAL fallback**: when the manufacturer blocks sysfs thermal data, temperatures are read via `dumpsys thermalservice`.
- **Adreno GPU support** via `kgsl`: frequency, load and memory stats.
- **L3 / LLCC / DDR bus frequencies** via `bus_dcvs` (kernel 5.15+, Snapdragon 8 Gen 2 and later).
- **Dual access backends**:
  - **ROOT** — Magisk / KernelSU / APatch via [libsu](https://github.com/topjohnwu/libsu) 6.0.0 (`RootService`).
  - **ADB** — via [Shizuku](https://github.com/RikkaApps/Shizuku) API 13.1.5 (privileged shell).
- **Automatic access-mode selection** with a per-user preference: Root, Shizuku or Auto.
- **Settings import / export** as JSON backup files.
- **Generic battery extras**: cycle count and recorded maximum temperature (when available from the kernel).
- **New Settings sections**: Access Mode, Detected Hardware + Remap.
- **Application ID**: `com.siliconfet.thermalmonitor.snapdragon` (can coexist with the original Exynos version).

### Changed

- **Polling interval**: default reduced to 500 ms for a lighter footprint.
- **Rendering optimizations**:
  - `clipRect` instead of `clipPath` on bar draws for better performance.
  - Cached 24-hour temperature history chart (no per-tick recomputation).
  - Info panels no longer relayout every tick.
- **Single-thread collector** with persistent file descriptors (`SysNode`) — no per-sample file opens.
- **Generic battery panel**: works on any Snapdragon device, not tied to Samsung-specific paths.

### Notes

- Based on [Thermal Monitor v2.1.3](https://github.com/SiliconFET/Thermal-Monitor) (Exynos 2600) by **SiliconFET**.
- Requires Android 8.0+ (API 26).
