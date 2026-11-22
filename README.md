# Programmable Furnace（可编程熔炉）

一款基于 NeoForge 的《我的世界》模组：把原版熔炉改造成**脚本驱动的可编程烧炼机器**。
玩家用模组自带的脚本语言编写烧炼逻辑（补料、点火、推进进度、配方查询、产物搬运），由机器按游戏 tick 自动执行。

## 版本信息

| 项 | 版本 |
| --- | --- |
| 模组版本 | **1.0.0.0** |
| Minecraft | **26.3** |
| NeoForge | **26.3.0.76** |
| Java | **25**（MC 26.3 原生面向 Java 25） |
| Mod Dev 插件 | net.neoforged.moddev 2.0.148 |
| Mod ID | `programmablefurnace` |

## 环境要求

- **Minecraft** 26.3（需安装 NeoForge 26.3.0.76）
- **Java** 25
- 仅服务端或单人（目前为 BOTH/服务端逻辑）

## 核心功能

### 可编程熔炉（Programmable Furnace）
- **脚本驱动烧炼**：烧炼决策与产物搬运由放入「依赖库容器」的可执行文件脚本决定；`RunLoop` 每 game tick 执行一次。
- **多输入并行**：工作界面按输入/输出槽布局（1~4 输入槽），每个输入槽独立进度与产物。
- **燃料平衡**：燃料的燃时、进度上限、每 tick 增量、燃烧效率由作者内置（随模组分发的 `fuel_config.json`），玩家不可见，保证游戏平衡。
- **GUI**：火焰/箭头图标随鼠标悬停显示当前进度/总进度。

### 编译与文件物品
- **编译器（Compiler）**：把源码文件编译为可执行文件。
- **代码文件**：空白文件 / 可执行文件 / 只读可执行文件 / 失败文件，支持通过合成相互转换。

### 拓展物品
- **熔炉扩容器（Furnace Expander，I/II/III）**：放入拓展槽，切换工作界面输入/输出槽布局（212 / 313 / 414）。
- **燃烧优化器（Burn Optimizer，I/II/III）**：放入拓展槽，逐项优化本次燃烧的燃时、进度上限、每 tick 增量与效率（多档取最高，不叠加）。

### 脚本语言
- 专用脚本语言：模块级函数、`break`/`return`/`continue` 控制流、自动分拣辅助函数、容器/槽位/物品对象模型。
- 玩家燃烧接口统一为 `furnace.advance()`，其余推进/上限等内部参数不暴露。

## 合成配方（摘要）

| 物品 | 配方要点 |
| --- | --- |
| 编译器 | 工作台居中 + 2 铜锭 + 2 铁锭 + 4 红石（红石四角，铜铁位置随意） |
| 可编程熔炉 | 熔炉居中 + 2 铜锭 + 2 铁锭 + 4 红石（同上） |
| 熔炉扩容器 I | 高炉居中 + 4 铜锭（角）+ 4 铁锭 |
| 熔炉扩容器 II / III | 扩容器 I 周围一圈金锭 / 钻石 |
| 燃烧优化器 I | 煤炭居中 + 4 铜锭（角）+ 4 铁锭 |
| 燃烧优化器 II / III | 优化器 I 周围一圈金锭 / 钻石 |
| 可执行 → 只读 | 可执行文件 + 金粒 |
| 文件 → 空白 | 单个文件（还原为 4 个空白文件） |
| 空白文件 | 纸居中 + 红石/铁/铜/金十字排列，一次 4 个 |

> 完整配方见 `src/main/resources/data/programmablefurnace/recipe/<物品>/`。

## 构建

需要 JDK 25 与 Gradle（项目自带 wrapper）。

```bash
# 编译 + 运行单元测试
./gradlew.bat test --no-daemon

# 启动客户端（开发环境）
./gradlew.bat runClient

# 打包发行 jar（输出到 build/libs/）
./gradlew.bat build
```

> 注：国内网络环境如遇下载失败，请勿配置终端代理（本项目已移除代理配置，直连已验证可用）。

## 安装（发行版）

1. 安装对应版本的 Minecraft 26.3 与 NeoForge 26.3.0.76。
2. 将构建出的 `ProgrammableFurnace-1.0.0.0.jar` 放入 `.minecraft/mods/`。
3. 启动游戏即可。

## 目录结构

```
src/main/java/dev/programmablefurnace/
├── base/          # CodeStorage、MachineConfigs 等基础设施
├── compiler/      # 脚本语言编译器/分析器/运行器
├── furnace/       # 可编程熔炉方块实体、GUI、Env 运行期桥接
├── item/          # 代码文件、熔炉扩容器、燃烧优化器等物品
├── machine/       # 槽位映射、通用机器 GUI
├── recipe/        # 自定义合成配方（转只读等）
src/main/resources/
├── programmablefurnace/       # fuel_config.json、furnace_config.json、示例脚本
└── data/programmablefurnace/  # 配方、物品 tag
```

## 许可

本项目以 **MIT 许可证** 发布，全文见 [LICENSE](LICENSE)。

简言之：你可以自由使用、修改、再发布（包括商用），只需保留版权声明与许可声明；软件按"原样"提供，不附带任何担保。

> 注意：`Minecraft`、`NeoForge` 及其相关商标归各自所有者所有；本许可证只覆盖本仓库的源代码、资源与脚本，不授予任何游戏或其模组加载器的权利。
