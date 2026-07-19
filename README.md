<div align="center">
  <img src="./public/image/LightingLuminol_LL方_白_字.png" alt="LightingLuminol Logo" width="300">
  
  # LightingLuminol
  
  *LightingLuminol 是一个基于 Luminol 的分支，具有许多有用的优化、可配置的原版特性和更多的 API 支持，目标是在 Folia 上运行更多 Bukkit 插件*
  
  ![Created At](https://img.shields.io/github/created-at/LuminolMC/LightingLuminol?style=for-the-badge&color=blue)
  [![License](https://img.shields.io/github/license/LuminolMC/LightingLuminol?style=for-the-badge&color=green)](LICENSE.md)
  [![Issues](https://img.shields.io/github/issues/LuminolMC/LightingLuminol?style=for-the-badge&color=orange)](https://github.com/LuminolMC/LightingLuminol/issues)
  
  ![Commit Activity](https://img.shields.io/github/commit-activity/w/LuminolMC/LightingLuminol?style=for-the-badge&color=purple)
  ![CodeFactor Grade](https://img.shields.io/codefactor/grade/github/LuminolMC/LightingLuminol?style=for-the-badge&color=yellow)
  ![GitHub all releases](https://img.shields.io/github/downloads/LuminolMC/LightingLuminol/total?style=for-the-badge&color=red)
  
  ![Repo contributors](https://img.shields.io/github/contributors/LuminolMC/LightingLuminol?style=for-the-badge&color=brightgreen)
  
  [English](./README_EN.md) | **中文**
</div>

---

> **📌 关于本项目**
> 
> LightingLuminol 停更后，我失去了进一步优化 BakaCore 的能力（BakaCore 使用此核心编译），包括将 BakaCore 升级到新版 Minecraft 的能力。我曾尝试手动编译新版本，因此 fork 了本仓库并继续维护。

---

## ✨ 核心特性

- 🔌 **部分 Bukkit 插件支持** - 支持部分 Bukkit 插件
- ⚙️ **可配置的原版特性** - 灵活的配置选项，支持服务器名称、性能参数等自定义设置
- 📊 **TpsBar 支持** - 内置性能监控功能，提供实时的服务器性能指标显示
- 🛠️ **更多插件开发 API 支持** - 扩展的 API 接口，为插件开发者提供更丰富的开发支持（持续开发中）

## 📥 下载

### 稳定版本
所有发布版本都可以在 [Releases](https://github.com/LuminolMC/LightingLuminol/releases) 页面找到。

### 开发版本
如果您想体验最新功能，可以通过以下步骤自行构建。

### 构建步骤

```bash
# 克隆项目
git clone https://github.com/LuminolMC/LightingLuminol.git
cd LightingLuminol

# 应用补丁并构建 Paperclip JAR
./gradlew applyAllPatches && ./gradlew createPaperclipJar
