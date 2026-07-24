<div align="center">
  <a href="README.md"><img src="https://img.shields.io/badge/中文-README-blue?style=for-the-badge" alt="中文"></a>
</div>

<div align="center">
  <img src="./public/image/LightingLuminol_LL横_白.png" alt="LightingLuminol Logo" width="400">

  <h1>LightingLuminol-BakaFork</h1>
  <p><strong>A fork of LightingLuminol for Folia</strong></p>
  <p>Tracking Mojang's latest Minecraft versions with Bukkit plugin compatibility and vanilla feature restoration</p>

  <p>
    <a href="https://github.com/Baka-Sky/LightingLuminol-Fork/actions/workflows/build.yml"><img src="https://github.com/Baka-Sky/LightingLuminol-Fork/actions/workflows/build.yml/badge.svg" alt="Actions Build Status"></a>
    <a href="https://github.com/Baka-Sky/LightingLuminol-Fork/issues"><img src="https://img.shields.io/github/issues/Baka-Sky/LightingLuminol-Fork" alt="GitHub Issues"></a>
    <a href="https://github.com/Baka-Sky/LightingLuminol-Fork/commits"><img src="https://img.shields.io/github/last-commit/Baka-Sky/LightingLuminol-Fork" alt="Last Commit"></a>
    <a href="LICENSE.md"><img src="https://img.shields.io/badge/License-GPL--3.0-blue" alt="License"></a>
  </p>
</div>

---

## Disclaimer

> The original Luminol project was developed by **EarthME**, but was archived due to personal reasons. This BakaFork (Baka-Sky) version is an **unofficial release** aimed at adapting to Mojang's latest Minecraft versions.
>
> The technology stack may change in the future to **Luminol + ported patches + modifications** (current stack: **Folia + ported patches + modifications**).
>
> Due to the **GNU GPL V3.0** open source license requirements, any modifications, derivatives, or distributions based on this project must be released under the same open source license in full.
>
> **CanvasMC** (the team primarily responsible for the original repository's archival) -- we will never forget.

---

## Features

| Feature | Description | Status |
|---------|-------------|--------|
| **Bukkit Plugin Compatibility** | `FoliaSchedulerCompatibility` auto-detects plugin scheduling needs and bridges Bukkit scheduler tasks to Folia's region scheduler or falls back to the main thread; supports forced scheduling policy | Implemented |
| **Complete Configuration Framework** | Full migration of Luminol's configuration loading logic, fully compatible with Luminol 26.1.2 — supports brand name customization, experimental feature toggles, fixes, performance optimizations, etc. | Implemented |
| **Region Architecture Enhancements** | Max pending logins limit, block update protection, vehicle sync, reference-counted regionized world data pool | Implemented |
| **Performance Monitoring** | TpsBar, Region Profiler, Watchdog thread, chunk system throughput counters | Implemented |
| **Base Dirty Patches** | Fixes known thread-safety issues, block updates, entity teleportation and other issues under Folia's regionized architecture | Implemented |

---

## Tech Stack

| Component | Version | Description |
|-----------|---------|-------------|
| **Minecraft** | 26.2 | Upstream Mojang version |
| **Folia** | 26.2 | Regionized multi-threaded server core |
| **Paper** | `1569b8dc` ref | Upstream Paper commit |
| **Luminol** | `ba28403f` ref | Luminol 26.1.2 reference commit |
| **Java** | 25 | Compile and runtime JDK |
| **Gradle** | 9.x | Build tool (wrapper included) |
| **Paperweight Patcher** | 2.0.0-beta.21 | Patch application and project management plugin |
| **Mache** | bundled with paperweight | Mojang mapping decompiler |
| **CI/CD** | GitHub Actions (windows-latest) | Windows build environment |
| **Git** | >= 2.x | Required by the patch system |

---

## Building

### Prerequisites

- **JDK 25** (Zulu or GraalVM recommended)
- **Git 2.x+**
- **Windows 10/11** or **Linux/macOS** (this project's CI uses Windows)
- Network access to GitHub, Mojang official sources, and the PaperMC Maven repository
- **Gradle** wrapper is included (no separate installation required)

### Build Instructions

```bash
# Clone the repository
git clone https://github.com/Baka-Sky/LightingLuminol-Fork.git
cd LightingLuminol-Fork

# Apply all patches
.\gradlew.bat applyAllPatches          # Windows
./gradlew applyAllPatches              # Linux / macOS

# Build the runnable Paperclip JAR
.\gradlew.bat createPaperclipJar       # Windows
./gradlew createPaperclipJar           # Linux / macOS
```

The `applyAllPatches` task will: pull upstream Paper 26.2 source via `paperRef` → apply `lightingluminol-api/paper-patches` → apply `lightingluminol-server/{paper,minecraft,luminol}-patches` → merge the Luminol core sources under `src/main/java` into the compile path.

### One-click Build

```bash
.\gradlew.bat applyAllPatches createPaperclipJar
```

### Available Gradle Tasks

| Task | Description |
|------|-------------|
| `applyAllPatches` | Apply all patches to the upstream source |
| `createPaperclipJar` | Build the runnable Paperclip JAR (recommended for production) |
| `createBundlerJar` | Build a Bundler JAR (includes all dependencies) |
| `:lightingluminol-server:jar` | Compile and package server classes only (no dependencies) |
| `:lightingluminol-server:compileJava` | Compile Java sources only (compile validation) |
| `runPaperclip` | Spin up a test server directly |
| `rebuildPatches` | Regenerate patch files from the current source |

### Build Output

The runnable Paperclip JAR is located at:

```
lightingluminol-server/build/libs/lightingluminol-paperclip-26.2.0-R0.1-SNAPSHOT.jar
```

### Deploy & Run

Rename the Paperclip JAR and drop it into your server directory:

```bash
# Rename to the name expected by start.bat
copy lightingluminol-paperclip-26.2.0-R0.1-SNAPSHOT.jar D:\SkyServer\lightingluminol-26.2.jar
```

```bat
@echo off
java -Xms1024M -Xmx1024M -jar lightingluminol-26.2.jar --nogui
pause
```

### Patch System

This project maintains three patch layers via Paperweight, all under `lightingluminol-server/`:

| Patch Directory | Count | Main Contents |
|-----------------|-------|---------------|
| `paper-patches/features/` | 10 | Region threading base, logo, build changes, region profiler, watchdog, TPS, rebrand, dirty patches, Bukkit scheduler compat |
| `minecraft-patches/features/` | 13 | Region threading base, login queue, chunk counters, block update protection, entity read protection, vehicle sync, watchdog, teleport fixes, scheduler compat, region data exposure |
| `luminol-patches/features/` | 4 | Rebrand, Folia flag check, auto-update, scheduler compat |

> Patches are applied in numbered order. To modify a patch, use the `rebuildPatches` task to regenerate it.

---

## Configuration

On first startup, the server automatically generates a `luminol_config/` configuration directory.

### Configuration Files

| File | Description |
|------|-------------|
| `server_mod_name.yml` | Server brand name displayed in the F3 debug screen |
| `disable_check_for_folia_supported.yml` | Controls the Folia plugin support check bypass |
| `folia_scheduler_compatibility.yml` | Controls scheduler routing for legacy plugins |

### Example: `server_mod_name.yml`

```yaml
# Server brand name shown in F3 debug overlay
name: LightingLuminol
# Force vanilla brand name (overrides plugins and this config)
vanilla_spoof: false
```

### Example: `disable_check_for_folia_supported.yml`

```yaml
# Bypass Folia support check for Spigot/Bukkit/Paper plugins
disable_for_paper: true
# Bypass Folia support check for Leaves plugins
disable_for_leaves: true
```

### Example: `folia_scheduler_compatibility.yml`

```yaml
# Enable automatic scheduler routing based on plugin metadata
enabled: true
# Force specific plugins to use the Folia scheduler
force_folia_scheduler_plugins: []
# Force specific plugins to use the Bukkit scheduler
force_bukkit_scheduler_plugins: []
```

---

## License

This project is licensed under the **GNU General Public License v3.0**. See [LICENSE.md](LICENSE.md) for the full license text.

> **GPLv3 Notice:** Any modifications, derivatives, or distributions of this project must be released under the same GPLv3 license with full source code disclosure.

---

## Acknowledgments

| Contributor | Role |
|-------------|------|
| **EarthME** | Original Luminol project author |
| **PaperMC** | Folia and Paper server framework |
| **LightingLuminol** | Vanilla feature restoration layer |
| **BakaSky** | BakaFork branch maintainer |

---

<div align="center">
  <p><sub>Built by BakaSky -- Licensed under GPLv3</sub></p>
  <p>
    <a href="https://github.com/Baka-Sky/LightingLuminol-Fork">Repository</a>
    &nbsp;&middot;&nbsp;
    <a href="https://github.com/Baka-Sky/LightingLuminol-Fork/issues">Issue Tracker</a>
    &nbsp;&middot;&nbsp;
    <a href="https://github.com/Baka-Sky/LightingLuminol-Fork/releases">Releases</a>
  </p>
</div>
