# Village Architect

**让村民自己扩建村庄的 Fabric 模组** · Minecraft **1.21.1**

![Minecraft](https://img.shields.io/badge/Minecraft-1.21.1-62b47a)
![Fabric](https://img.shields.io/badge/Fabric-0.16.x-dbb69c)
![License](https://img.shields.io/badge/License-MIT-blue)

> 没绑床的成年村民会自己寻找一块**平整开阔的空地**，喊来**石匠**当监工，用原版村庄的建筑结构把村庄一点点盖大。
> 每盖好**两间住房**，他们会再盖一栋**随机职业房**（图书室、石匠屋、渔夫小屋……）。

---

## 这是什么

原版 Minecraft 的村庄是"死"的：房子早就盖好，村民除了种地不会做别的事。

这个模组给村民加了一条很朴素的规则：**没地方睡的村民，就自己盖房子。**

不需要命令方块、不需要数据包、不需要手动摆结构——装进 `mods` 文件夹，村民就会开始动工。

---

## 特性

### 一、建房条件（四条，全部满足才动工）

| # | 条件 | 说明 |
|---|------|------|
| 1 | **至少 2 名没绑床的村民** | 村民的床位记录在它的 `HOME` 记忆里：睡过一张床、认领过一张床，就不再参与建房 |
| 2 | **必须站在开阔空间** | 建房点周围要有一块平整的空地（默认 16×16，九个采样点同高度，上方无遮挡），**室内一律不建** |
| 3 | **只能是成年村民** | 小村民只会围观，不参与 |
| 4 | **附近 8 格内有石匠** | 石匠是监工，没有石匠就不开工 |

只有**没绑床**的村民才会进入"待建房"状态，绑了床的村民永远不干活。

### 二、村民 AI 行为

1. **自动找地**——没有床的村民会自己在附近转悠，寻找适合盖房的平地（找不到会走远一点再找，不会原地发呆）。
2. **叫上石匠**——村民一旦选定地点开始赶路，附近的石匠就会被吸引过来，一路跟着它。
3. **一对一**——每位要盖房的村民最多"占用"一位石匠；石匠忙完当前的活，会立刻去找下一位待建房的村民（**没有冷却时间**）。
4. **绿宝石回礼**——如果村民在村庄建筑之外的露天位置绑了床，它会自动走到 8 格内的玩家面前，送上 **8 颗绿宝石**（每位村民只送一次）。

### 三、两间住房 → 一栋职业房

村庄里的**未配对住房**攒够两栋，村民就会在这两栋之间盖一栋**随机职业房**：图书室、石匠屋、屠夫店、制图师、渔夫小屋、制箭师、牧羊人、盔甲匠、工具匠、武器匠、皮匠……

这两栋住房与这栋职业房绑定，**同样两间房不会再生成第二栋职业房**。

### 四、诊断命令

不知道为什么不盖房？用 OP 权限执行：

```
/villagearchitect status
```

会打印建筑数量、下一栋要盖什么、附近村民/石匠/没绑床村民的数量，以及每个正在张罗的村民卡在哪一步。

潜行 + 右键村民，也会直接说这个村民现在在干什么（在找地 / 正赶路 / 已就位 / 已经绑床）。

---

## 安装

1. 安装 **Fabric Loader**（0.15.0 或更高）对应的 **Minecraft 1.21.1**。
2. 下载 **Fabric API** 并放进 `mods`。
3. 把本模组的 jar 放进 `mods`。
4. **完全退出游戏**再启动（不是退回标题界面）。

模组是**双端通用**的：单人游戏、自己开的服务器都能用。

> 纯服务端也能跑：如果你只把它装在服务器上，客户端不需要装，村民照样会盖房（盖出来的房子是原版结构，客户端本来就认识）。

---

## 快速上手（5 分钟看到第一栋房子）

```
1. 找一片平地（草原最好），确保 16×16 范围内没有坑、没有树
2. /summon villager ~ ~ ~ {VillagerData:{profession:"minecraft:none",level:1,type:"minecraft:plains"}}
   ← 连着放 2~3 只，不要给它们床
3. /summon villager ~2 ~ ~ {VillagerData:{profession:"minecraft:mason",level:1,type:"minecraft:plains"}}
   ← 放 1~2 只石匠
4. 等几秒，村民会开始走动、石匠跟过去
5. /villagearchitect status  ← 看进度
6. 房子落下后，村民会被自动请到房子外面，不会卡在墙里
```

如果村民只是走来走去不站定：**多半是附近真的没有 16×16 的平地**，换块地方，或者把 `openSpaceSize` 调到 12。

---

## 配置

首次运行会在 `config/villagearchitect.json` 生成配置文件，改完重启游戏生效。

| 键 | 默认 | 说明 |
|---|---|---|
| `intervalTicks` | `10` | 每隔多少 tick 跑一次 AI（20 tick = 1 秒） |
| `villagerScanRadius` | `48` | 以玩家为中心扫描村民的半径 |
| `searchRadius` | `20` | 找空地的搜索半径 |
| `openSpaceSize` | `16` | 空地要求的长宽（格） |
| `buildClusterRadius` | `16` | 两位村民的建房点最远相距多少格才能一起开工 |
| `masonRadius` | `8` | 石匠必须离建房点多近 |
| `attractRadius` | `24` | 石匠被吸引的距离 |
| `giftRadius` | `8` | 送绿宝石时找玩家的距离 |
| `giftAmount` | `8` | 送几颗 |
| `searchCooldownTicks` | `400` | 找地失败的冷却 |
| `buildCooldownTicks` | `0` | 两栋房子之间的冷却（`0` = 无冷却） |
| `minHouseGap` | `2` | 两栋房子之间至少隔几格 |
| `buildDelayTicks` | `60` | 从"就位"到"落成"的施工时间（`20` = 1 秒） |
| `enableBuilding` | `true` | 总开关 |
| `enableMasonAttraction` | `true` | 石匠是否会被吸引 |
| `enableGifts` | `true` | 绿宝石回礼 |

建筑记录（已盖的房子、职业房配对情况）存在 `config/villagearchitect-houses.json`，删掉它等于让模组"忘记"这些房子。

---

## 工作原理（简）

- 村民用的是 **Brain AI**（不是 `GoalSelector`），所以模组不用 Mixin 注入任务，而是每个 tick 读它的 `HOME` 记忆判断有没有床，用 `EntityNavigation` 驱动它走路。
- 空地检测走"九点采样"：四角 + 四边中点 + 中心，每个点向下找地面、比高度、检查上方是否有遮挡——比逐个方块扫 16³ 快得多，50 个村民也不卡。
- 落成时直接调用 `StructureTemplateManager` 放置**原版村庄结构**（`minecraft:village/<生物群系>/houses/*`），**不做旋转**：旋转会让结构绕 pivot 甩出去半栋房子的距离，看起来就像"房子错位了"。
- 落地后补齐结构方块留下的空洞（拼接点），并把房子底层的泥土层换成当地地表方块，不会在地上留一个大坑。
- 建房前后都会把村民请出房子范围，避免被方块"顶到屋顶"。

---

## 已知限制

- 只认**原版村庄结构**，不加载第三方结构包。
- 覆盖范围之外的老房子不会自动补墙，需要手动清理（房子之间的间距检查对老房子有效）。
- 如果服务器上大量村民同时找地，第一次搜索会有轻微卡顿（可以调大 `intervalTicks`）。
- 不支持 1.20.x / 1.21.2+，只针对 1.21.1 编译。

---

## 从源码构建

需要 **JDK 21**。

```bash
# Windows
gradlew.bat build

# macOS / Linux
./gradlew build
```

> 仓库里没有附带 gradle wrapper。第一次构建前，用 IDEA 打开项目（会自动生成 wrapper），或者在装了 Gradle 8.8+ 的机器上执行一次 `gradle wrapper`。

产物在 `build/libs/villagearchitect-1.0.0.jar`。

项目结构：

```
src/main/java/io/github/xqpeppa/architect/
├── ArchMod.java          模组入口、命令、右键交互
├── ArchitectManager.java  AI 主循环：找地、配对、建房、送礼
├── Config.java            配置文件
├── GroundProbe.java       九点空地检测
├── OpenSpaceFinder.java   旧的空地搜索（送礼判定还在用 isOutdoor）
├── StructurePlacer.java   选结构、放置、补墙、铺地
└── HouseBox.java          建筑包围盒与间距判定
docs/development-notes.md  开发过程中的详细变更记录
```

---

## 发布

首次上传 GitHub、创建 Release、挂 jar 的完整步骤见 [docs/PUBLISHING.md](docs/PUBLISHING.md)。

## 问题反馈

提 Issue 时请附上：

1. 游戏版本、Fabric Loader 版本、Fabric API 版本
2. `logs/latest.log`（至少包含模组加载那一段）
3. `/villagearchitect status` 的输出
4. 一张现场的截图（如果是"房子长得不对"）

---

## 许可

[MIT](LICENSE) © 2026 xqpeppa

不是官方 Minecraft 产品，与 Mojang / Microsoft 无关。
