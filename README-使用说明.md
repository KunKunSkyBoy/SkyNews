# 光遇公告 App（com.dsh.skynews）使用说明 v2.6

## 装在哪
- 最新 APK：`dist/SkyNews-v2.6.apk`（本地构建，约 257 KB，debug 签名，versionCode 8）
- 源码：`app/src/com/dsh/skynews/MainActivity.java`（单文件）+ `app/assets/sky/*.json`（光遇静态资料）
- 构建：`powershell -ExecutionPolicy Bypass -File build_win.ps1`（PC，无需 Gradle/Android Studio）
- 发布：`powershell -ExecutionPolicy Bypass -File release.ps1`（自动构建 → `dist/` + build-info.json）
- 冒烟：`powershell -ExecutionPolicy Bypass -File tools\smoke_test_apk.ps1 -Serial <设备序列号>`

## 七个页签（v2.6）
1. **公告**：官方公告全文，金色小标题渲染，底部「复制全文 / 原文 / 刷新」
2. **动态**：官网新闻 + TapTap + B 站 + 云中继（微博/抖音/小红书）统一时间线；★收藏、只看收藏、搜索
3. **变更历史**：公告变化自动留档，可回看旧版本
4. **设置**：官方动态源、云中继地址、检测频率（15/30/60/120 分钟）、常驻监控、极速模式、**源健康**、通知关键词/免打扰、深色模式、清空历史
5. **预载**：资源预载/更新相关
6. **日历**：从公告正文解析活动时间窗（进行中/即将开始/已结束）
7. **资料**（v2.5 新增）：光遇静态资料库
   - 物品/装扮 1748 件、先祖 243、每日任务 404、世界任务 368、赛季 31（2–32 季中文名）、活动排期（静态快照）
   - 顶部搜索框可实时过滤；数据来自 `app/assets/sky/*.json`（0.16.8 / 421629 生成，离线可用）

## 数据来源（公开 GET，无需登录）
| 内容 | URL |
|---|---|
| 公告正文 | `https://ma75.update.netease.com/game_notice/announcement_live.json` |
| TapTap 官方帖 | `https://www.taptap.cn/webapiv2/feed/v7/by-group?group_id=47615&type=official`（需 X-UA） |
| 官网新闻流 | `https://sixhorse.game.163.com/news/outer/newslist_search.do?...` |
| B 站官方动态 | `https://api.bilibili.com/x/polymer/web-dynamic/v1/feed/space?host_mid=211700578`（WBI 签名） |
| 云中继（可选） | `https://kunkunskyboy.github.io/SkyNews/feed.json`（微博/抖音/小红书经中继并入） |

## 抓取稳定性（v2.5 重点）
- 每个源独立超时 8s + 2 次指数退避重试，单源失败不影响其它源；
- 无网络时跳过抓取（不重试轰炸）；
- 中继**旧 schema 与 schema:2 双兼容**；schema:2 的 `sources` 健康信息展示在设置页；
- 失败源保留上次成功条目（灰显 stale），不会凭空消失；
- 状态栏显示「上次成功 / 上次检测 / 服务器时间 / 数据源」；
- 常驻监控（前台服务）15 分钟一次，极速模式 60 秒（需常驻开启）。

## 云中继部署（要覆盖微博/抖音/小红书时）
1. 仓库 Secret：`WEIBO_COOKIE`（可选 `NTFY_TOPIC` 失败通知）
2. 仓库 Variable：`RSSHUB_BASE`（自建 RSSHub 地址，用于抖音/小红书/微博兜底）
3. 其余由 `.github/workflows/relay.yml` 每 10 分钟自动跑，输出 `docs/feed.json`
4. App「设置 → 云中继地址」填 `https://<user>.github.io/<repo>/feed.json`

> 未部署也不影响：公告/动态/官网/TapTap/B 站由 App 本地直抓。

## 已知边界
- 微博/抖音/小红书受平台风控/签名限制，App 本地直连不可用（走中继）；
- GitHub Actions 境外 IP 访问 B 站可能 412（CI 默认跳过 B 站，由 App 本地补）；
- 资料页是**静态快照**（生成时间见页面提示），过期需重跑 `tools/build_skynews_assets.py`；
- 系统省电模式可能推迟 JobScheduler（安卓最小 15 分钟）；更准时用「常驻监控 + 极速模式」。

## 一键脚本（v2.6 新增）
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

## v2.6 修复
- 源健康显示不再重复：有 schema:2 的 sources 时忽略旧 problems，并自动清理旧源名
- 中继源名中文化（官网/TapTap/B站/微博/抖音/小红书）
- 中继条目的 stale 标记会带进 App（失败源旧条目灰显）
- 版本 2.6 (8)