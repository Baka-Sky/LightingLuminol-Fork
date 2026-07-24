<div align="center">
  <a href="README_EN.md"><img src="https://img.shields.io/badge/English-README-blue?style=for-the-badge" alt="English"></a>
</div>

<div align="center">
  <img src="./public/image/LightingLuminol_LL横_白.png" alt="LightingLuminol Logo" width="400">

  <h1>LightingLuminol-BakaFork</h1>
  <p><strong>基于 Folia 的 LightingLuminol 分支</strong></p>
  <p>旨在跟随 Mojang 最新 Minecraft 版本，适配 Bukkit 插件并修复原版特性</p>

  <p>
    <a href="https://github.com/Baka-Sky/LightingLuminol-Fork/actions/workflows/build.yml"><img src="https://github.com/Baka-Sky/LightingLuminol-Fork/actions/workflows/build.yml/badge.svg" alt="Actions Build Status"></a>
    <a href="https://github.com/Baka-Sky/LightingLuminol-Fork/issues"><img src="https://img.shields.io/github/issues/Baka-Sky/LightingLuminol-Fork" alt="GitHub Issues"></a>
    <a href="https://github.com/Baka-Sky/LightingLuminol-Fork/commits"><img src="https://img.shields.io/github/last-commit/Baka-Sky/LightingLuminol-Fork" alt="Last Commit"></a>
    <a href="LICENSE.md"><img src="https://img.shields.io/badge/License-GPL--3.0-blue" alt="License"></a>
  </p>
</div>

---

## 重要声明

> **原 Luminol 开发者为 EarthME**，但因开发者自身原因选择归档处理。本 BakaFork（Baka-Sky）版本为**非官方发布**，旨在适配 Mojang 最新 Minecraft 版本。
>
> 后续可能会更换技术栈为 **Luminol + 搬老 Patch + 并修改**（当前技术栈为 **Folia + 搬老 Patch 并修改**）。
>
> 因 **GNU GPL V3.0** 开源协议强制要求，本修改版及其他人 Fork 后制作的项目必须以同样的开源协议全部开源。
>
> **CanvasMC**（导致原仓库归档的主要责任团队） -- 我们不会忘记。

---

## 核心特性

| 特性 | 描述 | 状态 |
|------|------|------|
| **Bukkit 插件兼容性** | 通过 `FoliaSchedulerCompatibility` 自动识别插件调度需求，按需桥接 Bukkit 调度任务到 Folia 区域调度器或回退主线程；支持强制调度策略 | 已实现 |
| **完整配置框架** | 完整迁移 Luminol 配置加载逻辑，配置结构与 Luminol 26.1.2 完全一致，支持品牌名称自定义、实验性功能开关、修复项、性能优化等 | 已实现 |
| **区域化架构增强** | 最大登录队列限制、方块更新保护、载具同步、引用计数区域化世界数据池 | 已实现 |
| **性能监控** | TpsBar、Region Profiler、Watchdog 看门狗线程、区块系统吞吐计数器 | 已实现 |
| **基础脏补丁** | 修复 Folia 区域化架构下的线程安全问题、方块更新、实体传送等已知问题 | 已实现 |

---

## 技术栈

| 组件 | 版本 | 说明 |
|------|------|------|
| **Minecraft** | 26.2 | 上游 Mojang 版本 |
| **Folia** | 26.2 | 区域化多线程服务器核心 |
| **Paper** | `1569b8dc` ref | 上游 Paper 提交 |
| **Luminol** | `ba28403f` ref | Luminol 26.1.2 参考提交 |
| **Java** | 25 | 编译与运行时 JDK |
| **Gradle** | 9.x | 构建工具（自带 wrapper） |
| **Paperweight Patcher** | 2.0.0-beta.21 | 补丁应用与项目管理插件 |
| **Mache** | paperweight 内置 | Mojang 映射反编译工具 |
| **CI/CD** | GitHub Actions (windows-latest) | Windows 构建环境 |
| **Git** | >= 2.x | 补丁系统依赖 |

---

## 构建

### 环境要求

- **JDK 25**（推荐 Zulu 或 GraalVM）
- **Git 2.x+**
- **Windows 10/11** 或 **Linux/macOS**（本项目 CI 使用 Windows）
- 网络可访问 GitHub、Mojang 官方源、PaperMC Maven 仓库
- **Gradle** 已内置 wrapper，无需单独安装

### 构建步骤

```bash
# 克隆仓库
git clone https://github.com/Baka-Sky/LightingLuminol-Fork.git
cd LightingLuminol-Fork

# 应用所有补丁
.\gradlew.bat applyAllPatches          # Windows
./gradlew applyAllPatches              # Linux / macOS

# 构建 Paperclip 可运行 JAR
.\gradlew.bat createPaperclipJar       # Windows
./gradlew createPaperclipJar           # Linux / macOS
```

`applyAllPatches` 任务会：从 `paperRef` 拉取上游 Paper 26.2 源码 → 应用 `lightingluminol-api/paper-patches` → 应用 `lightingluminol-server/{paper,minecraft,luminol}-patches` → 合并 `src/main/java` 下的 Luminol 核心源码到编译路径。

### 一键构建

```bash
.\gradlew.bat applyAllPatches createPaperclipJar
```

### 可用 Gradle 任务

| 任务 | 说明 |
|------|------|
| `applyAllPatches` | 应用全部补丁到上游源码 |
| `createPaperclipJar` | 构建可运行的 Paperclip JAR（推荐用于生产） |
| `createBundlerJar` | 构建 Bundler JAR（包含全部依赖） |
| `:lightingluminol-server:jar` | 仅编译打包服务端类（不含依赖） |
| `:lightingluminol-server:compileJava` | 仅编译 Java 源码（验证编译） |
| `runPaperclip` | 直接启动一个测试服务器 |
| `rebuildPatches` | 根据当前源码重新生成补丁文件 |

### 构建产物

可运行的 Paperclip JAR 位于：

```
lightingluminol-server/build/libs/lightingluminol-paperclip-26.2.0-R0.1-SNAPSHOT.jar
```

### 部署与运行

将 Paperclip JAR 重命名并放入服务器目录即可：

```bash
# 重命名为 start.bat 期望的名称
copy lightingluminol-paperclip-26.2.0-R0.1-SNAPSHOT.jar D:\SkyServer\lightingluminol-26.2.jar
```

```bat
@echo off
java -Xms1024M -Xmx1024M -jar lightingluminol-26.2.jar --nogui
pause
```

### 补丁体系

本项目通过 Paperweight 维护三层补丁，全部位于 `lightingluminol-server/` 下：

| 补丁目录 | 数量 | 主要内容 |
|----------|------|----------|
| `paper-patches/features/` | 10 | 区域线程基础、Logo、构建变更、区域分析器、看门狗、TPS、品牌重命名、脏补丁、Bukkit 调度兼容 |
| `minecraft-patches/features/` | 13 | 区域线程基础、登录队列、区块计数器、方块更新保护、实体读取保护、载具同步、看门狗、传送修复、调度兼容、区域数据暴露 |
| `luminol-patches/features/` | 4 | 品牌重命名、Folia 标志检查、自动更新、调度兼容 |

> 补丁按编号顺序应用，修改补丁请使用 `rebuildPatches` 任务重新生成。

---

## 配置文件

服务器首次启动后会自动生成 `luminol_config/` 配置目录。

### 配置文件列表

| 文件 | 说明 |
|------|------|
| `server_mod_name.yml` | 服务器 F3 调试界面显示的品牌名称 |
| `disable_check_for_folia_supported.yml` | 控制是否禁用 Folia 插件支持检查 |
| `folia_scheduler_compatibility.yml` | 控制旧版插件的调度器路由 |

### 示例: `server_mod_name.yml`

```yaml
# 服务器 F3 调试界面显示的品牌名称
name: LightingLuminol
# 强制使用原版品牌名称（覆盖插件和此配置）
vanilla_spoof: false
```

### 示例: `disable_check_for_folia_supported.yml`

```yaml
# 禁用 Spigot/Bukkit/Paper 插件的 Folia 支持检查
disable_for_paper: true
# 禁用 Leaves 插件的 Folia 支持检查
disable_for_leaves: true
```

### 示例: `folia_scheduler_compatibility.yml`

```yaml
# 启用基于插件元数据的自动调度器路由
enabled: true
# 强制指定使用 Folia 调度器的插件
force_folia_scheduler_plugins: []
# 强制指定使用 Bukkit 调度器的插件
force_bukkit_scheduler_plugins: []
```

---

## 许可证

本项目基于 **GNU General Public License v3.0** 开源协议发布。详见 [LICENSE.md](LICENSE.md)。

> **GPLv3 说明：** 任何基于本项目的修改、派生或分发，都必须以相同的 GPLv3 协议发布完整源代码。

---

## 致谢

| 贡献者 | 角色 |
|--------|------|
| **EarthME** | 原 Luminol 项目作者 |
| **PaperMC** | Folia 和 Paper 服务器框架 |
| **LightingLuminol** | 原版特性修复层 |
| **BakaSky** | BakaFork 分支维护者 |

---

<div align="center">
  <p><sub>Built by BakaSky -- Licensed under GPLv3</sub></p>
  <p>
    <a href="https://github.com/Baka-Sky/LightingLuminol-Fork">仓库</a>
    &nbsp;&middot;&nbsp;
    <a href="https://github.com/Baka-Sky/LightingLuminol-Fork/issues">问题反馈</a>
    &nbsp;&middot;&nbsp;
    <a href="https://github.com/Baka-Sky/LightingLuminol-Fork/releases">发布版本</a>
  </p>
</div>
