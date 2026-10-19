# BuildFlight

基于 **Kotlin 2.x + TabooLib 6.x** 的 Minecraft 建筑飞行插件，目标版本为 **Minecraft / Paper 26.3**，并为 **Folia** 的区域线程模型做了专门处理。编译基线为 Paper API 1.20.4，插件只使用跨版本稳定的 Bukkit API。

玩家通过 GUI 把背包中的材料兑换为“飞行能量”，再在同一个 GUI 中开启/关闭建筑飞行。飞行期间使用独立 BossBar 显示剩余能量，能量耗尽时自动关闭飞行并给予缓降与落地前摔落免伤。

---

## 功能

- `/bflight` 或 `/bf` 打开操作面板。
- GUI 内展示当前能量、每秒消耗、血条基准与飞行/战斗状态。
- **一键存入**：扫描玩家主背包，把所有在 `config.yml` 中配置的材料一次性兑换为能量。
- **分类存入**：每种材料一个按钮，左键存入全部、右键存入 1 件、Shift+左键存入 64 件。
- **飞行开关**：能量 > 0 时开启 `allowFlight` + `flying`；关闭时若仍离地，自动给予缓降保护。
- **BossBar**：仅飞行期间显示，标题为 `&e⚡ 建筑飞行模式 &7| &f剩余能量: &a{energy} &f秒`，进度按 `max-energy-reference` 计算。
- **每秒扣费**：只有 `player.isFlying == true` 且双脚离地时才扣 1 点；落地暂停。
- **双击空格降落**：建筑飞行模式下双击空格可收起原版飞行；空中会立即补上缓降/摔落保护，落地行走不扣费，再次双击即可继续飞行。彻底退出模式仍在 GUI 中操作。
- **耗尽保护**：能量归零立即 `allowFlight = false`，并给予 `SLOW_FALLING` + 落地前摔落免伤；保护最长持续 `fall-protection-seconds + 5` 秒，落地或超时都会强制清除。
- **战斗打断**：受到实体攻击或主动攻击实体时立即中断飞行，并进入冷却；即使当时没有飞行，冷却也会阻止立刻起飞逃跑。
- **跨世界 / 死亡 / 退出 / 重载 / 卸载**：自动关闭飞行并清理 BossBar。
- **SQLite 持久化**：基于 TabooLib DatabasePlayer，能量按 UUID 隔离存储。

---

## 兼容性

| 环境 | 状态 |
|---|---|
| Paper 26.3（build 206，Java 25） | 已实机启动验证：插件成功启用，`config.yml` / `messages.yml` / `data.db` 正常生成 |
| Paper API 26.3 | 已逐项核对本插件调用的 Bukkit / Folia 接口签名 |
| Folia / Lophine 26.3 | 已实现区域调度并通过编译，未在真机验证 |
| 1.20.4 ~ 1.21.x | 编译基线，未做回归测试 |
| 更老版本 | 不支持；字节码为 Java 17，服务端需 Java 17+ |

- 兼容性核对覆盖 `Player#isOnGround`、`setAllowFlight`、`BossBar`、`PotionEffectType.SLOW_FALLING`、`EntityScheduler` 等实际调用的接口。
- 生成的 `plugin.yml` 自带 `api-version: 1.13` 与 `folia-supported: true`。
- 首次启动时 TabooLib 会从远程仓库下载运行时模块与语言文件，服务器需要能访问对应仓库；受限网络下可能卡在这一步。
- 不承诺“全版本通用”：大版本之间仍可能出现 API 移除或行为变化，升级服务端后请先在测试服加载。

---

要求 **JDK 21** 构建（项目字节码为 Java 17，服务端需要 Java 17+；26.3 实测使用 Java 25）：

```bash
./gradlew build
```

Windows PowerShell：

```powershell
.\gradlew.bat build
```

产物：

```
build/libs/BuildFlight-1.0.0.jar
```

本机验证记录（2026-10-19）：

- `JAVA_HOME=D:\Dev-Env\Java\store\zulu21`
- `.\gradlew.bat build` 成功（Gradle 8.10.1、Kotlin 2.1.0、TabooLib Gradle 插件 2.0.39、运行时 TabooLib 6.3.0-0e3a911）。
- 若本机无法访问 `repo.spongepowered.org`，可临时使用 `--init-script skip-spongepowered.init.gradle`；该脚本只是本机网络环境的绕过手段，不属于项目必需文件。

---

## 安装

1. 将 `BuildFlight-1.0.0.jar` 放入服务端 `plugins/`。
2. 首次启动后自动生成：
   - `plugins/BuildFlight/config.yml`
   - `plugins/BuildFlight/messages.yml`
   - `plugins/BuildFlight/data.db`（SQLite）
3. 修改配置后执行 `/bflight reload`。

> TabooLib 6 插件在首次启动时会从 TabooLib 仓库下载运行时模块（Basic / Bukkit / BukkitUI / DatabasePlayer / MinecraftChat）。服务器需要能访问对应仓库。

---

## 命令与权限

| 命令 | 说明 | 权限 |
|---|---|---|
| `/bflight`、`/bf` | 打开建筑飞行面板 | `buildflight.use`（默认所有人） |
| `/bflight help` | 查看帮助 | `buildflight.use` |
| `/bflight reload` | 重载 `config.yml` / `messages.yml`，并重建在线玩家的 BossBar | `buildflight.admin`（默认 OP） |

---

## GUI 操作

```
┌────────────────────────────────────────────┐
│ 4            当前能量 / 状态                │
│ 11           一键存入全部材料               │
│ 15           开启 / 关闭飞行                │
│ 19..25       材料按钮（第 1 行）            │
│ 28..34       材料按钮（第 2 行）            │
│ 37..43       材料按钮（第 3 行）            │
└────────────────────────────────────────────┘
```

- 一键存入：扫描主背包（36 格），扣除所有配置允许的材料并结算能量。
- 材料按钮：
  - 左键：存入当前背包中该材料的全部数量
  - 右键：存入 1 件
  - Shift + 左键：存入 64 件
- 面板点击与拖拽被整体锁定，玩家无法把菜单物品取出，也无法把背包物品塞进菜单。
- 每次操作后会在下一 tick 自动重开面板，刷新能量与数量；如果玩家已经关闭面板，则不会强行拉回。
- 建筑飞行模式开启后，玩家可以直接双击空格收起原版飞行：空中会自动获得缓降保护，落地行走暂停扣费，再次双击空格即可继续飞行。BossBar 与模式本身保留；若要彻底退出并收起 BossBar，点击 GUI 的“关闭建筑飞行”。

---

## 配置文件

`config.yml` 关键项：

| 键 | 说明 | 默认 |
|---|---|---|
| `bossbar.color` | 血条颜色：`PINK/BLUE/RED/GREEN/YELLOW/PURPLE/WHITE` | `YELLOW` |
| `bossbar.style` | 血条样式：`SOLID/SEGMENTED_6/SEGMENTED_10/SEGMENTED_12/SEGMENTED_20` | `SOLID` |
| `bossbar.max-energy-reference` | 血条 100% 对应的能量点数（只影响显示比例） | `3600` |
| `combat.cancel-on-damage` | 受到/造成实体伤害时是否打断飞行 | `true` |
| `combat.cooldown-seconds` | 打断后的禁飞秒数 | `10` |
| `fall-protection-seconds` | 能量耗尽/主动关闭/双击空格降落时的缓降秒数；免伤硬性超时为该值 + 5 秒 | `10` |
| `disabled-worlds` | 禁止飞行的世界名列表 | `world_the_end` |
| `energy.consume-per-second` | 每秒消耗能量 | `1.0` |
| `storage.auto-save-seconds` | 缓存自动落库间隔 | `30` |
| `storage.database-file` | SQLite 文件名 | `data.db` |
| `exchange-materials` | 材料 -> 每件能量点数 | 见下 |

默认兑换表：

```yaml
exchange-materials:
  DIAMOND: 60
  EMERALD: 30
  GOLD_INGOT: 15
  NETHERITE_INGOT: 720
  NETHERITE_BLOCK: 6800
  OBSIDIAN: 10
```

也支持为每种材料单独指定 `material` / `points`，便于使用别名键：

```yaml
exchange-materials:
  my_diamond:
    material: DIAMOND
    points: 60
```

`messages.yml`：所有玩家可见文本，支持 `&` 颜色码（TabooLib 还支持 `&{#RRGGBB}` 十六进制写法）；占位符使用 `{name}` 或 `%name%`。

---

## 线程模型（Paper / Folia）

- **非 Folia**：所有玩家状态、背包、GUI、BossBar 操作通过 Bukkit 主线程调度执行。
- **Folia**：通过反射调用 Paper 的 `EntityScheduler`，把任务投递到玩家所属区域线程。
  - 不使用 `Bukkit.getScheduler().runTask`（Folia 会抛异常）。
  - 不在异步线程直接修改 `allowFlight` / `isFlying` / 背包 / GUI / BossBar。
- **数据库**：单线程 `BuildFlight-IO` 串行执行 SQLite 读写，绝不触碰 Bukkit API。
- **定时任务生命周期**：
  - 飞行扣费任务在关闭飞行、退出、死亡、跨世界、插件卸载时取消。
  - 落地检测任务与硬性超时任务在保护清除时取消。
  - 自动保存任务在插件卸载时取消。
  - 不存在无退出条件的死循环任务。

---

## 数据与防刷说明

- 能量与战斗冷却按 UUID 存入 SQLite 表 `buildflight_energy`，键为 `buildflight_energy`，载荷格式为 `能量|冷却结束时间戳`。
- 写入顺序由单线程 IO 执行器保证；退出、兑换、开关飞行、耗尽、战斗打断都会立即排队保存，另有周期自动保存。
- **原子性顺序**：先确认档案已加载 -> 从背包精确扣除物品 -> 再写入内存能量 -> 最后异步落库。内存加能量不会失败，因此不存在“先加能量后扣物”的刷物窗口。
- 扣物过程中若抛异常，会把本次已移除的物品按原槽位放回。
- GUI 整体锁定点击与拖拽，避免通过菜单漏洞转移物品。
- 同一玩家的兑换有串行锁，避免连点重复结算。

**已知崩溃窗口**：物品扣掉后、异步落库完成前若服务器崩溃，玩家可能损失这批物品对应的能量（不会凭空增加能量）。这是“先扣物、后落库”策略下的可接受偏差；如需更强一致性，可把落库改为同步等待，但会增加玩家线程阻塞。

---

## 已知限制

- 已在本地 **Paper 26.3（build 206）+ Java 25** 启动验证：插件正常启用，配置文件与 SQLite 正常生成；但未做真人进服、GUI 点击与飞行手感的交互测试。
- 未在 **Folia / Lophine 26.3** 实机验证；Folia 调度路径目前只有代码审查与编译保证。
- 未做 1.20.4 ~ 1.21.x 的版本回归；这些版本仅作为编译基线。
- Folia 在插件 `disable` 阶段无法保证再向各区域线程投递任务；此时 `BossBar` 采用 `removeAll()` 兜底清理，飞行状态由服务端关闭流程回收。
- 摔落保护标记未持久化（仅本次在线会话有效），但单次保护有 `fall-protection-seconds + 5` 秒的硬性上限，不会无限免伤；战斗冷却随能量一起写入数据库，重连后仍然生效。
- 兑换只扫描主背包 36 格，不处理副手、盔甲槽与光标物品。

---

## 目录结构

```
src/main/kotlin/cn/stingcraft/buildflight/
├── BuildFlightPlugin.kt          插件入口
├── command/
│   └── MainCommand.kt            /bflight、/bf
├── config/
│   ├── PluginConfig.kt           config.yml
│   └── PluginMessages.kt         messages.yml
├── data/
│   └── PlayerProfile.kt          玩家运行期档案
├── database/
│   └── EnergyStorage.kt          SQLite 持久化
├── listener/
│   ├── PlayerListener.kt         生命周期事件
│   └── CombatListener.kt         战斗打断 / 摔落免伤
├── manager/
│   ├── EnergyManager.kt          能量缓存与自动保存
│   ├── FlightManager.kt          飞行状态机
│   ├── BossBarManager.kt         BossBar 生命周期
│   └── ExchangeManager.kt        材料兑换与原子扣除
├── ui/
│   └── ExchangeMenu.kt           主面板
└── util/
    ├── Scheduler.kt              Paper/Folia 统一调度
    └── Formats.kt                数字与文本格式化
```
