# Annihilation Blade

基于 SlashBlade Resharped 的 Minecraft 扩展模组，提供湮灭之刃、专属 SA「空间破碎」、配套材料和终结实体系统。

## 项目版本

| 目录 | Minecraft / 加载器 | Java |
| --- | --- | --- |
| `AnnihilationBlade-forge-1.20.1` | 1.20.1 / Forge | 17 |
| `AnnihilationBlade-neoforge-1.21.1` | 1.21.1 / NeoForge | 21 |

两个项目独立构建。在对应目录执行 `./gradlew build`（Windows 使用 `.\gradlew.bat build`）。
GitHub Actions 会分别构建两个版本。

## 功能

- 湮灭之刃和重新制作的模型、贴图。
- 空间破碎：选取玩家周围半径 128 格内的有效目标进行终结。
- 通用血量账本处理、原生死亡、超时升级和强制移除。
- 实体本体解析、墓碑记录、防复活、深度驱逐和客户端移除同步。
- 持刀者保护、增益、飞行和耐久修复。

## 许可

**代码采用 MIT；材质（模型及配套刀贴图）非商用。**

- 代码及其他未被排除的项目文件：[MIT License](LICENSE)。
- 两个版本的 `assets/annihilationblade/model/` 目录及
  `assets/annihilationblade/textures/item/blade.png`：
  [Model Non-Commercial License 1.0](LICENSE-MODELS.md)。
- 材质允许保留署名和许可的非商业修改、分享及免费整合包使用，禁止商业使用。
- 两份许可均随构建的模组 JAR 打包。模型提取或格式转换不会改变其许可。

上述范围针对本次重构后的资产，不追溯改变先前版本已授予的许可。
