# SkyNews 光遇数据资产（app/assets/sky）

给「光遇资料 / 活动日历」用的**离线静态数据**，不依赖运行时网络。
全部 UTF-8（**无 BOM**）、紧凑 JSON（无缩进）、字段名尽量短以省 APK 体积。

- 数据版本：**0.16.8 / 421629**（resource 76318），生成时间见 `meta.json` 的 `generated_at`
- 生成脚本：`tools/build_skynews_assets.py`（可重跑）
- 总大小：**约 356 KB / 6 个文件**（APK 打包时 zip 还会再压缩一次）
- 时间基准：国服 **Asia/Shanghai（UTC+8）**；活动时间语义 = `base_time + when`（秒），持续 `duration` 分钟

> 说明：本目录由 sky-data 生成，只放数据；**不要手改 JSON**，改数据请改生成脚本后重跑。

---

## 文件清单

| 文件 | 内容 | 条数 | 大小 |
|---|---|---|---|
| `items.json` | 装扮/物品 | 1748 | ~237 KB |
| `spirits.json` | 先祖 | 243 | ~20 KB |
| `quests.json` | 每日任务 404 + 世界任务 368 | 772 | ~78 KB |
| `seasons.json` | 2–32 季中文名 + 推算起止 | 31 | ~3 KB |
| `activities.json` | 活动前缀中文映射 + 当前/未来快照 | 前缀 49 / active 103 / upcoming 22 | ~17 KB |
| `meta.json` | 版本、条目数、各文件字节数、限制说明 | — | ~1.3 KB |

所有文件顶层都有 `v`（schema 版本，当前都是 `1`）；列表型文件顶层是 `n`（条数）+ 数据数组。

---

## items.json（装扮/物品）

```json
{"v":1,"n":1748,"items":[
  {"i":"CharSkyKid_Hair_APJuggler_01","n":"凛冬紫毛披风","t":"wing","r":"store",
   "s":"season_05","sc":"音韵季","d":1,"c":1,"e":"ChibiOutfit"},
  {"i":"CharSkyKid_Body_Ghost","n":"裤子/裙装（Ghost）","t":"body","r":"derived","tc":"裤子/裙装"}
]}
```

| 字段 | 类型 | 含义 |
|---|---|---|
| `i` | string | 内部 ID（游戏资源名，唯一键） |
| `n` | string | 显示名（中文优先；见 `r`） |
| `t` | string | 衣柜类别代码：`body/hair/wing/mask/horn/hat/neck/feet/prop/face/tail` |
| `tc` | string? | 类别中文（`裤子/裙装`、`发型`、`斗篷`…），可能缺省 |
| `r` | string | 名字来源：`store`=官方商城礼包中文名（69 件，最可靠）/ `derived`=推导名（1679 件，`赛季·类别（英文key）`）/ `none`=无 |
| `s` | string? | 赛季键，如 `season_05`；活动季也有 `fortune_23` 这类 |
| `sc` | string? | 赛季中文名（`音韵季`），来自 arsc + `season_names_extra` |
| `d` | 0/1? | 可染色（有主/副染色位时出现） |
| `c` | 0/1? | 国服专属（`cn_only` 时出现） |
| `e` | string? | 特殊外观事件（如 `ChibiOutfit`），一般没有 |

**重要限制**：游戏客户端本身不显示逐件物品名，官方没有中文物品名；`r=derived` 的名字是「赛季+类别+英文内部 key」拼出来的检索名，不是官方名。UI 上可直接显示 `n`，也可只显示 `tc`+`sc` 组合。

---

## spirits.json（先祖）

```json
{"v":1,"n":243,"items":[
  {"i":"acknowledge","n":"敬礼护卫","en":"Saluting Protector",
   "s":"season_02","sc":"感谢季","r":"暮土"}
]}
```

| 字段 | 类型 | 含义 |
|---|---|---|
| `i` | string | 先祖 ID（`npcs[].name`） |
| `n` | string | 中文名（242/243 有；其余 1 条回退英文） |
| `en` | string? | 英文名 |
| `s` / `sc` | string? | 所属赛季键 / 中文名（常驻先祖可能没有） |
| `r` | string? | 所在地图中文（由 constellation.location 反推，78/243 有） |
| `c` | 0/1? | 国服专属 |
| `g` | 0/1? | 幽灵先祖 |
| `ce` | int? | 斗篷等级/解锁档（`cape_level`，>0 时出现） |

说明：数据包 `npcs` 共 690 条（含非先祖 NPC/占位），这里只保留 `spirit=true` 且**有中/英文名**的 243 条。

---

## quests.json（每日任务 + 世界任务）

```json
{"v":1,
 "daily":{"n":404,"items":[
   {"i":"bloom_harvesting","n":"在云野花园收集向日葵种子微光","m":"云野：仙乡",
    "g":"explore","t":"bloom_harvesting","np":"挥手钟匠"}]},
 "world":{"n":368,"items":[
   {"i":"ap09_fetch_01","m":"霞谷：圆梦村"}]}}
```

| 字段 | 类型 | 含义 |
|---|---|---|
| `i` | string | 任务 ID |
| `n` | string? | 中文描述（daily 390/404 有） |
| `m` | string? | 地图/地点中文（取自 `names_cn.level`；daily 341、world 353） |
| `g` / `t` | string? | 任务分组 / 统计类型（`explore`、`take_companion`…） |
| `np` | string? | 关联 NPC 中文名（daily） |
| `lk` | string? | 世界任务的 localization_key（仅 9 条有） |

**限制**：世界任务（world）**没有中文标题**——数据包里没有、arsc 字符串表也匹配不上（`localization_key` 0 命中），所以只有地图/类型/id。做 UI 时可用地图+序号或 id 展示。

---

## seasons.json（赛季）

```json
{"v":1,"n":31,"est":1,"items":[
  {"n":32,"k":"season_32","zh":"珍珠季","start":"2026-10-22","est":1},
  {"n":31,"k":"season_31","zh":"致梵高季","start":"2026-07-17","end":"2026-10-21","est":1}
]}
```

| 字段 | 类型 | 含义 |
|---|---|---|
| `n` | int | 赛季号（2–32；另有 `furniture`=家具，不在列表内） |
| `k` | string | `season_NN` |
| `zh` | string | 中文季名（2–32 全有） |
| `start` / `end` | string? | `YYYY-MM-DD`；**由活动排期反推，非官方赛季表** |
| `est` | 1 | 顶层与条目都标了 `est:1`，UI 上建议显示「约」或「推算」 |

2–5 季没有排期数据，只有名字、没有起止；最新一季（32）没有 `end`。`end` 的算法 = 下一季 `start` 前一天。

---

## activities.json（活动日历）

```json
{"v":1,"generated_at":"2026-11-12T00:17:37+08:00","timezone":"Asia/Shanghai",
 "base_time":1590595200,"server_time":1794413171,
 "snapshot":true,"note":"活动排期静态快照；生成后不再更新，过期请重跑 tools/build_skynews_assets.py",
 "prefix_cn":{"aurora":"欧若拉联动（极光演唱会）","visiting":"旅行先祖（复刻）", "...": "..."},
 "active":[{"n":"aurora_concert_2025","p":"aurora","cn":"欧若拉联动（极光演唱会）",
            "st":"2026-11-12 08:00","et":"2026-11-12 09:30","mm":90}],
 "upcoming":[...],
 "loops_active":18,"loops_upcoming":151,
 "min_minutes_listed":30,
 "permanent_active":437,"permanent_upcoming":0}
```

| 字段 | 类型 | 含义 |
|---|---|---|
| `generated_at` | string | 生成时间（ISO8601，UTC+8）。**这就是快照时间** |
| `base_time` | int | 国服开服基准 `2020-05-28 00:00`（秒）；绝对时间 = `base_time + when` |
| `server_time` | int | 抓取时服务端时间（秒），用于校准 |
| `prefix_cn` | object | 活动名前缀 → 中文（49 项），UI 可直接拿来当前缀标签 |
| `active[]` | array | 当前进行中的**限时活动**（`min_minutes_listed=30` ≤ 时长 < 30 天），最多 120 条，按时长降序 |
| `upcoming[]` | array | 未来 7 天内开始的同类活动，最多 120 条，按开始时间升序 |
| 事件对象 | — | `n`=事件名（内部代号）、`p`=前缀、`cn`=前缀中文、`st/et`=`YYYY-MM-DD HH:mm`（UTC+8）、`mm`=时长（分钟） |
| `loops_active/_upcoming` | int | 短周期玩法数量（<30 分钟，如贪吃蛇/极光轮换），**只计数不列出** |
| `permanent_active/_upcoming` | int | 长驻/解锁标记数量（≥30 天，如 `*_ever_started`、先祖商店排期），**只计数不列出** |

快照策略：`active` 只列限时活动；短循环与长驻标记各给一个计数，避免日历被 600+ 内部事件刷屏（437 = 长驻解锁类，与 `GAME_FEATURES_EVENTS.md` §2.2 的统计一致）。
**过期判断建议**：读 `meta.json.generated_at` 或 `activities.json.generated_at`，超过 7 天就在 UI 上提示「活动数据可能已过期，请更新素材」。

---

## meta.json（元数据）

```json
{"v":1,"generated_at":"...","game_version":{"app_version":"0.16.8","version_code":421629,
 "resource_version":"76318","game":"光·遇（国服）"},
 "counts":{"items":1748,"spirits":243,"daily_quests":404,"world_quests":368,"seasons":31,
 "activities_active":103,"activities_upcoming":22,"activity_prefixes":49,...},
 "files":{"items.json":242384,"spirits.json":20768,...},
 "total_bytes":356396,
 "sources":{"data_pack":"...","event_schedule":"..."},
 "notes":["物品名来源说明","世界任务无中文标题","赛季起止为推算","活动为静态快照"]}
```

Java 端适合在**设置/关于页**展示 `game_version`，在资料页底部展示 `generated_at`。

---

## 更新方式（换版本 / 活动过期）

1. 更新数据包：`E:\gongzuoqu\sky_app\skycore\data\`（`defs/*`、`names_cn.json`、`names_outfit_cn.json`、`version.json`）
2. 更新活动快照：`E:\gongzuoqu\_team\lead\event_schedule.json`（从 `/account/get_event_schedule` 抓的新快照，含 `base_time/when/duration`）
3. 重跑（无第三方依赖，Python 3.8+ 即可）：

```powershell
cd "E:\gongzuoqu\SkyNews\SkyNews-20261111(1)\SkyNews"
python tools\build_skynews_assets.py
# 或显式指定路径：
python tools\build_skynews_assets.py --data <data目录> --events <event_schedule.json> --out app\assets\sky
```

脚本会重写 6 个 JSON 并打印每个文件条数/体积；`README.md` 不需要重生成。

**体积守卫**（脚本输出会显示）：items ≤300KB、spirits ≤150KB、quests ≤200KB、总计 ≤1.2MB；当前均大幅低于上限。

---

## Java 端加载建议（Android 内置 `org.json`，无第三方依赖）

- **首次进入「光遇资料」页时异步加载一次**，缓存到内存（`static` 或单例），后续翻页/搜索直接查内存：
  - `items.json` 237KB / 1748 条：`JSONArray` 解析约 **30–80ms**（中端机），内存占用约 1.5–2MB（Java 对象比 JSON 大）；
  - 其余文件都在 80KB 以下，解析 <20ms。
- **不要**在 `onCreate` 主线程一次性 parse 全部 6 个文件；建议按页面懒加载：
  - 资料页：`meta.json`（先显示版本/时间）+ `items.json`
  - 先祖页：`spirits.json`
  - 任务页：`quests.json`
  - 日历页：`activities.json`（+ `meta.json` 判断过期）
  - 赛季页：`seasons.json`
- 读取方式：

```java
private String readAsset(String name) throws IOException {
    try (InputStream is = getAssets().open("sky/" + name);
         ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
        byte[] buf = new byte[8192]; int n;
        while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }
}
// JSONArray items = new JSONObject(readAsset("items.json")).getJSONArray("items");
// JSONObject meta = new JSONObject(readAsset("meta.json"));
```

- **搜索**：在内存里对 `n`（物品/任务名）、`en`、`tc`、`sc` 做 `String.contains` 即可；1748 条全量过滤 <5ms。
- **按需过滤**：`q.json`→`JSONArray` 遍历一次按 `s`/`t` 建 `HashMap<String, List<JSONObject>>` 索引，避免每次重新遍历。
- 为什么不做 gzip：APK 里的 assets 在 zip 中本身已被 deflate 压缩，额外 `.gz` 需要在 Java 侧 `GZIPInputStream` 解压、增加复杂度，实际安装包体积收益只有几 KB，故不采用。
- 若不想用内置 `org.json`，也可把 JSON 当字节流自己解析（不推荐）；`android.jar` 自带 `org.json`，无需 Gradle 依赖，符合本项目「无 Gradle」约束。

---

## 数据来源与许可

- 装扮/先祖/任务/赛季：国服 `0.16.8` 数据包（用户本机 APK 提取，字段见 `sky_app/skycore/data`）。
- 物品中文名：`names_outfit_cn.json`（官方商城中文名 69 件 + 推导名 1679 件，由 `_team/sky-data` 的 `build_outfit_names.py` 生成）。
- 赛季中文名：`resources.arsc` 的 `name_season_*` + `season_names_extra.json`。
- 活动排期：云端 `/account/get_event_schedule` 的本地快照（`_team/lead/event_schedule.json`，616 个事件 / 47 类前缀）。
- 仅自用；若对外分发数据，请自行评估网易/《光·遇》素材的版权与条款。
