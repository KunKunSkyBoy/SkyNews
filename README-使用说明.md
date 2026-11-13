# 光遇公告 App（com.dsh.skynews）使用说明 v4.1

## 装在哪
- 最新 APK：`dist/SkyNews-v4.1.apk`（本地构建，约 298 KB，debug 签名，versionName **4.1** / versionCode **16**）
- 源码：`app/src/com/dsh/skynews/MainActivity.java`（单文件）+ `app/assets/sky/*.json`（光遇静态资料）
- 构建：`powershell -ExecutionPolicy Bypass -File build_win.ps1`（PC，无需 Gradle / Android Studio）
- 发布：`powershell -ExecutionPolicy Bypass -File release.ps1`（自动构建 → `dist/` + build-info.json）
- 冒烟：`powershell -ExecutionPolicy Bypass -File tools\smoke_test_apk.ps1 -Serial <设备序列号>`

## 九个页签（v4.1）

1. **公告**：官方公告全文，金色小标题渲染，底部「复制全文 / 原文 / 刷新」
2. **动态**：官网新闻 + TapTap + B 站 + 云中继（微博 / 抖音 / 小红书）统一时间线；★收藏、只看收藏、搜索；列表预览 600 字，点开读全文
3. **变更历史**：公告变化自动留档，可回看旧版本，能看到某段正文被改过
4. **设置**：官方动态源、云中继地址、检测频率（15 / 30 / 60 / 120 分钟）、常驻监控、极速模式、**源健康**、通知关键词 / 免打扰、每日任务提醒、碎片提醒、深色模式、清空历史
5. **预载**：预载判断 —— 列出「已预载但还没开始」的活动，提前量越大说明官方提前得越早；可一键复制预载清单
6. **日历**：从公告正文解析活动时间窗（进行中 / 即将开始 / 已结束）
7. **碎片**：红石 / 黑石碎片日程（今天 + 本周规则：周一 / 周四固定无碎片、1–15 号周三 / 周五无、16 号后周二 / 周六无）；支持开始前 10 分钟提醒与一键复制日程
8. **资料**（v2.5）：光遇静态资料库
   - 物品/装扮 1748 件、先祖 243、每日任务 404、世界任务 368、赛季 31（2–32 季中文名）、活动排期（静态快照）
   - 顶部搜索框可实时过滤；数据来自 `app/assets/sky/*.json`（0.16.8 / 421629 生成，离线可用）
9. **工具**（v4.0 / v4.1）：9 个免费工具入口
   - 每日任务 / 今日速览 / 红石天气 / 季节蜡烛图 / 大蜡烛图 / 复刻先祖 / 活动日历 / 每日任务位置图 / 季节倒计时
   - 点按钮在 App 内打开，**支持图片显示**（季蜡 / 大蜡 / 复刻 / 日历 / 天气图）
   - **近期日程**（v4.1）：接 `第三方光遇日历源（地址略）` 日历，未来 10 天红石时间 + 复刻先祖离开时间；「今日速览」自动追加最近 3 条日程

## 数据来源（公开 GET，无需登录）

| 内容 | 来源 |
|---|---|
| 公告正文 | `网易公告 JSON 接口（地址略）` |
| TapTap 官方帖 | `TapTap 官方社区号接口（地址略）`（需 X-UA） |
| 官网新闻流 | `官网新闻列表 JSON 接口（地址略）` |
| B 站官方动态 | `B 站空间动态接口（地址略）`（WBI 签名） |
| 每日任务 / 大蜡烛 | `每日任务数据接口（地址略）`（公开 API，免费） |
| 近期日程（ICS） | `第三方光遇日历 ICS 源（地址略）`（红石 / 黑石 / 活动 / 旅行先祖 / 赛季） |
| 工具页 | `工具页（Pages 托管，地址略）` |
| 云中继（可选） | `云中继 feed（地址略）`（微博 / 抖音 / 小红书经中继并入） |

## 抓取稳定性（v2.5 重点）
- 每个源独立超时 8s + 2 次指数退避重试，单源失败不影响其它源；
- 无网络时跳过抓取（不重试轰炸）；
- 中继**旧 schema 与 schema:2 双兼容**；schema:2 的 `sources` 健康信息展示在设置页；
- 失败源保留上次成功条目（灰显 stale），不会凭空消失；
- 状态栏显示「上次成功 / 上次检测 / 服务器时间 / 数据源」；
- 常驻监控（前台服务）15 分钟一次，极速模式 60 秒（需常驻开启）。

## 云中继部署（要覆盖微博 / 抖音 / 小红书时）
1. 仓库 Secret：`WEIBO_COOKIE`（可选 `NTFY_TOPIC` 失败通知）
2. 仓库 Variable：`RSSHUB_BASE`（自建 RSSHub 地址，用于抖音 / 小红书 / 微博兜底）
3. 其余由 `.github/workflows/relay.yml` 每 10 分钟自动跑，输出 `docs/feed.json`
4. App「设置 → 云中继地址」填 `自己的 Pages 地址（略）`

> 未部署也不影响：公告 / 动态 / 官网 / TapTap / B 站由 App 本地直抓。

## 已知边界
- 微博 / 抖音 / 小红书受平台风控 / 签名限制，App 本地直连不可用（走中继）；
- GitHub Actions 境外 IP 访问 B 站可能 412（CI 默认跳过 B 站，由 App 本地补）；
- 资料页是**静态快照**（生成时间见页面提示），过期需重跑 `tools/build_skynews_assets.py`；
- 近期日程与工具页图片依赖 `第三方光遇日历源（地址略）` / GitHub Pages，网络受限时可能加载慢；
- 系统省电模式可能推迟 JobScheduler（安卓最小 15 分钟）；更准时用「常驻监控 + 极速模式」。

## 一键脚本
```powershell
# 完整流程：重生成资料 → 构建 → 发布到 dist → 装机冒烟 → 推送 GitHub → 验证线上
$env:SKYNEWS_GH_TOKEN="你的 token"   # 仅当前窗口，不写文件
powershell -ExecutionPolicy Bypass -File oneclick.ps1 -Action all -Device HA25G639

# 只构建+发布
powershell -ExecutionPolicy Bypass -File oneclick.ps1 -Action release
# 只重生成光遇资料并发布
powershell -ExecutionPolicy Bypass -File oneclick.ps1 -Action data
# 只部署（推 relay/App/CI 到 GitHub）并验证线上 feed
powershell -ExecutionPolicy Bypass -File oneclick.ps1 -Action deploy
# 只验证线上 feed（schema:2/源健康）
powershell -ExecutionPolicy Bypass -File oneclick.ps1 -Action verify
```

## 更新记录（v2.6 → v4.1）

| 版本 | 主要内容 |
|---|---|
| v2.6 | 源健康去重 + 中文化；`oneclick.ps1` 一键脚本 |
| v2.7 | 动态全文：中继带回正文，列表预览 600 字，点开直接读 |
| v2.8 | 公告类条目不再显示原始 JSON，改为正常文本 / 内置网页阅读 |
| v2.9 | 每日任务提醒：开关 + 时间（默认 08:00），当天只提醒一次，兼容免打扰 |
| v3.0 | 中继新增 daily 源（独角兽 API，需 `UNICORN_KEY`） |
| v3.1 | 每日任务改走 `每日任务数据接口（地址略）` 免费公开 API；修复中继时间戳单位导致排序错误 |
| v4.0 | 新增「工具」页：9 个入口，App 内打开且支持图片 |
| v4.1 | 新增「近期日程」：接 `第三方光遇日历源（地址略）`，未来 10 天红石 + 复刻先祖离开时间 |

完整说明见 `README.md`。
