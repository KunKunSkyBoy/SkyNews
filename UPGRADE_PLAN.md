# SkyNews 完善计划（v2.5）

> 2026-11-12 启动；目标：**抓取最准确、稳定性最高**，同时完善阅读/通知/光遇资料，并能一键构建发布。

## 现状基线

- 版本：v2.4（versionCode 6），APK 187,194 B，装机名「光遇公告」。
- 构建：PC 端已打通（无 Gradle）：`build_win.ps1`（javac + aapt2 + d8 + apksigner）→ `skynews.apk`。
- 发布：`release.ps1` → `dist/SkyNews-v<版本>.apk` + `build-info.json`（sha256）。
- 已装 MuMu 模拟器验证启动正常。
- 中继实例在线：`https://kunkunskyboy.github.io/SkyNews/feed.json`（旧 schema，40 条）。

## 五条工作线

| # | 方向 | 负责 | 交付 |
|---|---|---|---|
| 1 | 抓取稳定性 + 多源扩展（最高优先级） | cn-probe（task-32） | relay schema:2、每源超时/重试/退避、失败保留旧条目、微博/抖音/小红书/TapTap、CI 修复 |
| 2 | App 阅读/通知/稳定性 | sky-plugin（task-33） | 深色模式、搜索、收藏、WebView 阅读、通知关键词/免打扰、源健康 UI、App 端重试、v2.5 |
| 3 | 光遇数据底座 | sky-data（task-34） | `app/assets/sky/*.json`（物品/先祖/任务/赛季/活动）+ 生成脚本 |
| 4 | 集成 + 构建 | Lead | 合并、构建、版本校验、APK 产物 |
| 5 | 装机验收 | Lead（+用户设备） | 真机 HA25G639 / MuMu 安装、抓取/通知/阅读冒烟测试 |

## 抓取稳定性设计（关键）

**relay（云端）**
- 每源独立超时（8s）+ 重试 3 次（指数退避 + 抖动），失败不影响其它源；
- 统一请求头（UA/Referer/`Accept-Encoding: identity`，B 站 gzip 坑）；
- 输出 schema:2：`sources{name:{ok,count,error,elapsed_ms,last_success}}`；
- **失败源保留上次成功条目（stale）**，客户端不会"丢条目"；
- 跨源去重（id 优先，url 归一化）；
- CI：10 分钟周期、concurrency、内容变化才 commit。

**App（本地）**
- 无网直接跳过（不重试轰炸）；每个源独立 try/catch + 超时；
- 解析时兼容旧/新 schema；
- 设置页显示每源健康（最后成功时间、耗时、错误）与"上次成功";
- 中继与本地抓取结果按 id 去重合并。

## 验收清单（v2.5）

- [x] 构建通过：versionName 2.5 / versionCode 7
- [x] 装机：MuMu 启动正常；真机 HA25G639 安装并启动正常
- [x] 公告页正常渲染、无数据/失败提示友好
- [x] 动态页：本地 3 源 + 中继合并（实测 54 条，含 TapTap/B站/官网）
- [ ] 中继断网/超时场景：保留旧条目、UI 有 stale 标记、不崩溃
- [x] 搜索：公告/动态/历史/资料 实时过滤（已实现，资料页可过滤）
- [x] 收藏：添加/取消/持久化/只看收藏（已实现）
- [x] 内嵌阅读：正常页面/加载失败回退浏览器（已实现）
- [x] 深色模式：跟随/浅色/深色三态，重启保持（已实现）
- [x] 通知：关键词白/黑名单、免打扰时段（已实现，待长时间观察）
- [x] 源健康：设置页每源状态（已实现，中继 schema:2 待部署）
- [x] 光遇资料页：物品/先祖/每日任务/世界任务/赛季/活动均可查（已实机验证）

## 风险

- 微博/抖音/小红书依赖 cookie/RSSHub，本机可能拿不到 → 用 fallback（跳过并显示问题）而不是阻塞。
- GitHub Actions 境外 IP 被 B 站 412 → CI 跳过 B 站，App 本地抓；relay 输出 `sources.bili.ok=false` 但条目由 App 补。
- 单文件 App 改动大，需防回归：每步构建 + MuMu 冒烟。

## 部署记录（2026-11-12 00:40 完成）
- 已推送到 `KunKunSkyBoy/SkyNews` 仓库（commit `6e5d8e3`），包含 relay v2、App v2.5 源码、assets、构建/发布/冒烟脚本、README/使用说明。
- GitHub Actions `skynews-relay` 已手动触发并成功；线上 `https://kunkunskyboy.github.io/SkyNews/feed.json` 已是 **schema:2**（official 30 / taptap 10 / bili 13；weibo/douyin/xhs 未配置）。
- 真机 HA25G639 验证：设置页「源健康」正确显示各源 ok/耗时/last_success（schema:2 解析 OK）。
- 已知小瑕疵：relay 兼容字段 `problems` 与 `sources` 同时存在时，App 源健康里会出现重复的失败项（如「中继·B站」与「中继·bili」各一条），仅显示层面，不影响抓取。
- 待用户配置（可选）：仓库 Secret `WEIBO_COOKIE`；仓库 Variable `RSSHUB_BASE`（抖音/小红书/微博兜底）。