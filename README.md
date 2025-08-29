<div align="center">
  <img src="./public/image/LightingLuminol_LL方_白_字.png" alt="LightingLuminol Logo" width="300">
  
  # LightingLuminol
  
  *LightingLuminol 是一个基于Luminol的分支，具有许多有用的优化、可配置的原版特性和更多的API支持，目标是在Folia上运行更多Bukkit插件*
  
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

## ✨ 核心特性

- 🔌 **部分 Bukkit 插件支持** - 支持部分Bukkit插件
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
./gradlew applyAllPatches && ./gradlew createMojmapPaperclipJar
```

构建完成后，您可以在 `lightingluminol-server/build/libs` 目录中找到生成的 JAR 文件。

## 🔌 API 使用

### Gradle 配置

```kotlin
repositories {
    maven {
        url = "https://repo.menthamc.org/repository/maven-public/"
    }
}

dependencies {
    compileOnly("me.earthme.lightingluminol:lightingluminol-api:$VERSION")
}
```

### Maven 配置

```xml
<repositories>
    <repository>
        <id>menthamc</id>
        <url>https://repo.menthamc.org/repository/maven-public/</url>
    </repository>
</repositories>

<dependencies>
    <dependency>
        <groupId>me.earthme.lightingluminol</groupId>
        <artifactId>lightingluminol-api</artifactId>
        <version>$VERSION</version>
        <scope>provided</scope>
    </dependency>
</dependencies>
```

## 💬 社区与支持

> 如果您对这个项目感兴趣或有任何问题，请随时向我们提问。

### 加入我们的社区

- **QQ群**: [1015048616](http://qm.qq.com/cgi-bin/qm/qr?_wv=1027&k=QML5kIVsniPi1PlZvnjHQT_02EHsZ5Jc&authKey=%2FTCJsZC7JFQ9sxAroPCKuYnlV57Z5fyqp36ewXZk3Sn4iJ9p4MB1JKdc%2FFcX3HOM&noverify=0&group_code=1015048616)
- **QQ频道**: [点击加入](https://pd.qq.com/s/eq9krf9j)
- **Telegram**: [点击加入](https://t.me/LuminolMinecraft)
- **Discord**: [点击加入](https://discord.gg/Qd7m3V6eDx)

### 获取帮助

- 📋 [提交 Issue](https://github.com/LuminolMC/LightingLuminol/issues)
- 💬 [GitHub Discussions](https://github.com/LuminolMC/LightingLuminol/discussions)
- 📖 [项目文档](./docs/)

## 🐛 问题反馈

当您遇到任何问题时，请向我们提问，我们将尽力解决。请记得：

- 📝 **清楚描述问题** - 详细说明问题的具体表现
- 📋 **提供完整日志** - 包含错误日志和相关配置信息
- 🔍 **环境信息** - 说明服务器版本、插件列表等环境详情
- 🔄 **复现步骤** - 如果可能，请提供问题复现的具体步骤

## 🤝 贡献代码

我们欢迎社区贡献！详细的贡献指南请查看：

- 📖 [贡献指南 (中文)](./docs/CONTRIBUTING.md)
- 📖 [Contributing Guide (English)](./docs/CONTRIBUTING_EN.md)

## 📊 项目统计

### BStats 数据

![bStats](https://bstats.org/signatures/server-implementation/LightingLuminol.svg "bStats")

---

## ⭐ 请给我们一个 Star！

> 你的每一个免费的 ⭐Star 就是我们每一个前进的动力。

### Star 历史

<a href="https://star-history.com/#LuminolMC/Luminol&LuminolMC/LightingLuminol&LuminolMC/Lophine&Date">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="https://api.star-history.com/svg?repos=LuminolMC/Luminol%2CLuminolMC/LightingLuminol%2CLuminolMC/Lophine&type=Date&theme=dark" />
    <source media="(prefers-color-scheme: light)" srcset="https://api.star-history.com/svg?repos=LuminolMC/Luminol%2CLuminolMC/LightingLuminol%2CLuminolMC/Lophine&type=Date" />
    <img alt="Star历史表" src="https://api.star-history.com/svg?repos=LuminolMC/Luminol%2CLuminolMC/LightingLuminol%2CLuminolMC/Lophine&type=Date" />
  </picture>
</a>

<div align="center">
  <b>如果这个项目对您有帮助，请不要忘记给我们一个 ⭐Star！</b>
</div>