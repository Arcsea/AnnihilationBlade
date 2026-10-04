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

代码采用 [MIT](LICENSE)，材质（模型及配套刀贴图）采用 [CC BY-NC 4.0](LICENSE-MODELS.md)，非商用。具体范围与条款见协议文件，许可说明和完整正文均随模组 JAR 打包。

上述范围针对本次重构后的资产，不追溯改变先前版本已授予的许可。
