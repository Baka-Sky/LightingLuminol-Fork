<div align="center">
  <img src="./public/image/LightingLuminol_LL横_白.png" alt="LightingLuminol Logo" width="400">

  <h1>LightingLuminol-BakaFork</h1>
  <p><strong>LightingLuminol for Folia</strong></p>
  <p>基于 Folia 的 LightingLuminol 分支，旨在跟随 Mojang 最新 Minecraft 版本 + 适配 Bukkit 插件且修复原版特性</p>

***

## ⚠️ 重要声明

> **原 Luminol 开发者为 EarthME**，但因开发者自身原因选择归档处理。本 BakaFork（Baka-Sky）版本为非官方发布，旨在适配 Mojang 最新 Minecraft 版本。
>
> **后续可能会更换技术栈**为 Luminol + 搬老 Patch + 并修改（当前技术栈为 **Folia + 搬老 Patch 并修改**）。
>
> **因 GNU GPL V3.0 开源协议强制要求**，本修改版及其他人 Fork 后制作的项目必须以同样的开源协议全部开源。
>
> <span style="color:red">CanvasMC 我去你大爷！！！</span>（导致原仓库归档的主要责任团队）

***

## ✨ 核心特性

| 特性                  | 描述                                                      | 状态    |
| ------------------- | ------------------------------------------------------- | ----- |
| 🔌 **Bukkit 插件兼容性** | 通过禁用 Folia 插件检查和恢复 Bukkit 调度器，支持更多 Bukkit 插件在 Folia 上运行 | ✅ 已实现 |
| ⚙️ **原版特性修复**       | 修复刷线机、刷地毯机等 Paper 核心破坏的 Minecraft 原版特性                  | ✅ 已实现 |
| 📊 **性能优化**         | 基于 Folia 的区域化多线程架构，提供更好的性能扩展                            | ✅ 已实现 |
| 🛠️ **配置系统**        | 自动生成 YAML 配置文件，支持热更新和向后兼容                               | ✅ 已实现 |
| 🔄 **线程安全**         | 关键方法添加同步保护，确保多线程环境下的数据一致性                               | ✅ 已实现 |

***

## 🛠️ 技术栈

| 组件          | 版本              | 说明                |
| ----------- | --------------- | ----------------- |
| 🌐 **核心框架** | Folia 26.2      | 区域化多线程服务器核心       |
| 🌿 **特性修复** | LightingLuminol | 原版特性修复、Bukkit 兼容性 |
| 📦 **构建工具** | Gradle 9.x      | 项目构建与依赖管理         |
| 📝 **配置系统** | SnakeYAML       | YAML 配置文件读写       |
| 🔌 **API**  | Folia API 26.2  | 扩展的 Bukkit API    |
| ☕ **运行环境**  | Java 21+        | JDK 运行时环境         |

***

## 📦 构建流程

### 📋 环境要求

- **Java**: 21 或更高版本
- **Git**: 版本控制工具
- **Gradle**: 项目构建工具（已内置 wrapper）

### 🚀 构建步骤

```bash
# 克隆仓库
git clone https://github.com/BakaSky/LightingLuminol-Folia.git
cd LightingLuminol-Folia

# 初始化子模块（首次构建）
git submodule update --init --recursive

# 构建服务器 JAR
./gradlew.bat :folia-server:createPaperclipJar
```

### 📁 构建产物

构建完成后，服务器 JAR 文件位于：

```
folia-server/build/libs/folia-paperclip-26.2.0-SNAPSHOT.jar
```

### 🎮 运行服务器

```bash
# 基础运行
java -Xms2G -Xmx4G -jar folia-server/build/libs/folia-paperclip-26.2.0-SNAPSHOT.jar

# 推荐配置（生产环境）
java -Xms8G -Xmx8G \
  -XX:+UseG1GC \
  -XX:+ParallelRefProcEnabled \
  -XX:MaxGCPauseMillis=200 \
  -XX:+UnlockExperimentalVMOptions \
  -XX:+DisableExplicitGC \
  -XX:+AlwaysPreTouch \
  -jar folia-server/build/libs/folia-paperclip-26.2.0-SNAPSHOT.jar
```

***

## 📁 配置文件

服务器启动后会自动生成配置文件夹 `lightingluminol/`：

| 配置文件                                    | 说明                  |
| --------------------------------------- | ------------------- |
| `disable_check_for_folia_supported.yml` | 控制是否禁用 Folia 插件支持检查 |
| `folia_scheduler_compatibility.yml`     | 控制调度器兼容性模式          |

### 配置示例

**disable\_check\_for\_folia\_supported.yml**：

```yaml
disable_for_paper: true    # 禁用 Paper 插件检查
disable_for_leaves: true   # 禁用 Leaves 插件检查
```

**folia\_scheduler\_compatibility.yml**：

```yaml
enabled: true                              # 启用调度器兼容性
force_folia_scheduler_plugins: []          # 强制使用 Folia 调度器的插件
force_bukkit_scheduler_plugins: []         # 强制使用 Bukkit 调度器的插件
```

***

## 📜 许可证

本项目基于 **GNU GPL V3.0** 开源协议发布。详见 [LICENSE.md](LICENSE.md) 文件。

> **GPL V3.0 强制要求**：任何基于本项目的修改、派生或分发，都必须以相同的开源协议发布源代码。

***

## 🙏 致谢

| 项目/开发者              | 贡献               |
| ------------------- | ---------------- |
| **EarthME**         | 原 Luminol 项目开发者  |
| **PaperMC**         | Folia 和 Paper 项目 |
| **LightingLuminol** | 原版特性修复和优化        |
| **BakaSky**         | BakaFork 分支维护    |

***

<div align="center">
  <p>Made with ❤️ by BakaSky</p>
  <p><a href="https://github.com/BakaSky/LightingLuminol-Folia">GitHub Repository</a> | <a href="https://github.com/BakaSky/LightingLuminol-Folia/issues">Report Issues</a></p>
</div>
