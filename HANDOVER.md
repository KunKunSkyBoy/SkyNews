# SkyNews 项目交接文档（v2.6 · 2026-11-12）

> 本文件是「后来者/未来的自己」的入口：项目是什么、每块在哪、怎么跑、怎么发版、已知边界。

## 1. 项目一句话

**SkyNews（光遇公告 / 光遇公告阅读器）** = Android 端《光·遇》国服**公告 + 官方动态聚合**应用（纯 Java、无 Gradle、单文件 UI），加一个**云端中继**（Python 标准库 + GitHub Actions + GitHub Pages）把 App 抓不到的源（微博/抖音/小红书）统一成一份 JSON/RSS。

- 包名：`com.dsh.skynews`（debug 签名）；当前 **v2.6（versionCode 8）**
- 中继线上地址：`https://kunkunskyboy.github.io/SkyNews/feed.json`（RSS：`feed.xml`）
- 发布页：`https://github.com/KunKunSkyBoy/SkyNews/releases`

## 2. 目录地图

```
SkyNews/
├── app/                          # Android 应用（纯 Java，无 Gradle）
│   ├── AndroidManifest.xml
│   ├── src/com/dsh/skynews/MainActivity.java   # 全部代码（UI+抓取+存储+通知+定时）
│   ├── assets/sky/*.json         # 光遇静态资料（物品/先祖/任务/赛季/活动）
│   ├── res/                      # 图标与 drawable
│   └── debug.keystore
├── relay/                        # 云端中继（Python 标准库）
│   ├── relay.py                  # 抓取+合并+输出 feed.json/feed.xml
│   ├── README.md                 # 中继配置说明
│   ├── STABILITY.md              # 稳定性设计与验收
│   └── SOURCES.md                # 各源可用性实测（微博/抖音/小红书为什么不能全绿）
├── .github/workflows/
│   ├── relay.yml                 # 每 10 分钟抓取 → docs/（支持手动调参）
│   └── pages.yml                 # GitHub Actions 部署 docs/ 到 Pages
├── docs/                         # Pages 产物（feed.json / feed.xml / .nojekyll）
├── tools/
│   ├── build_skynews_assets.py   # 从光遇数据包生成 app/assets/sky
│   └── smoke_test_apk.ps1        # 安装+启动+截图+日志 冒烟
├── build_win.ps1                 # PC 构建 APK（javac+aapt2+d8+apksigner）
├── release.ps1                   # 构建 + 输出 dist/SkyNews-vX.Y.apk + build-info.json
├── oneclick.ps1                  # 一键：data/release/deploy/verify/all
├── README.md / README-使用说明.md / UPGRADE_PLAN.md / HANDOVER.md（本文件）
└── skynews.apk                   # 仓库根的最新构建产物（同时发布到 Releases）
```

## 3. 常用命令（Windows / PC）

```powershell
# 环境：JDK21 + Android SDK build-tools 35 + platform android-35（脚本里已写死路径）
# 1) 只构建
powershell -ExecutionPolicy Bypass -File build_win.ps1

# 2) 构建 + 发布到 dist/
powershell -ExecutionPolicy Bypass -File release.ps1

# 3) 装机冒烟（MuMu / 真机）
powershell -ExecutionPolicy Bypass -File tools\smoke_test_apk.ps1 -Serial HA25G639

# 4) 一键（推荐）：重生成资料 → 构建 → 发布 → 装机冒烟 → 推送 GitHub → 验证线上
$env:SKYNEWS_GH_TOKEN="ghp_xxx"      # 只放当前窗口，不写文件
powershell -ExecutionPolicy Bypass -File oneclick.ps1 -Action all -Device HA25G639

# 5) 只部署
powershell -ExecutionPolicy Bypass -File oneclick.ps1 -Action deploy
```

> **构建注意（踩过的坑）**
> 1. PowerShell 脚本必须 **UTF-8 带 BOM**，否则 PS5.1 会把中文路径读坏（javac「无效标记」）。
> 2. Windows 下 aapt2 写 assets 路径用反斜杠 → apksigner 报 Name mismatch；`build_win.ps1` 已做 zip 名归一化。
> 3. 重打包后 `resources.arsc` 被压缩 → Android 11+ 安装失败；已加 `zipalign -f -p 4`。
> 4. 目录同步用「先删后拷」，否则 `Copy-Item` 会生成嵌套副本（`relay/relay` 之类）。

## 4. 数据源与配置

| 源 | 状态 | 依赖 |
|---|---|---|
| 官网新闻/公告 | ✅ App 本地 + 中继 | 无 |
| TapTap 官方帖 | ✅ App 本地 + 中继 | `X-UA` 头 |
| B 站官方动态 | ✅ App 本地（13 条）；CI 常 412 | WBI 签名 + buvid3 |
| 微博官方 | ❌ 需 `WEIBO_COOKIE` | 见 `relay/SOURCES.md`（4 个公共 RSSHub 均失败） |
| 抖音/小红书 | ❌ 需自建 RSSHub + 路由 + cookie | `RSSHUB_BASE` / `SKYNEWS_DOUYIN_ROUTE` / `SKYNEWS_XHS_ROUTE` |

仓库 Settings → Secrets and variables → Actions：
- Secret：`WEIBO_COOKIE`（可选）、`NTFY_TOPIC`（可选失败通知）
- Variable：`RSSHUB_BASE`（可选）、`SKYNEWS_BILI_CI`（默认 0 跳过 CI B站）

## 5. 发版流程

1. 改代码 → `oneclick.ps1 -Action release -Device <设备>` 本地验证。
2. 升版本：`app/AndroidManifest.xml` 的 `versionCode`/`versionName`（关于页文案也一起改）。
3. `oneclick.ps1 -Action deploy` 推送 GitHub（自动同步 relay/App/CI/docs 保护）。
4. GitHub Releases：新增 tag（如 `v2.7`）、上传 `dist/SkyNews-v2.7.apk`。
5. 中继无需手动发版：CI 每 10 分钟自动更新 `docs/`，Pages 由 `pages.yml` 部署。

## 6. CI/CD 结构

- `relay.yml`：每 10 分钟 + 手动；`workflow_dispatch` 支持 `rsshub`/`sources`/`bili_ci` 临时覆盖；内容变化才 commit；失败源 stale 回退；Step Summary 输出源健康表。
- `pages.yml`：push `docs/**` 或手动触发 → 上传 docs 为 Pages artifact → 部署。
- Pages 配置：**build_type=workflow**（不再用 legacy Jekyll；`docs/.nojekyll` 仍保留）。

## 7. 已知边界

- 微博/抖音/小红书没有 cookie/自建 RSSHub 时不可用（不是 bug，见 `relay/SOURCES.md`）。
- 微博公开时间线在 GitHub Actions 也被风控（432/HTML），已实测。
- B 站 CI 412：Relay 默认跳过并保留 stale 13 条；App 本地抓取补齐。
- App 资料页是**静态快照**（`app/assets/sky/meta.json` 记生成时间），过期需重跑 `tools/build_skynews_assets.py`。
- 通知/定时依赖系统省电策略；要准点用「常驻监控 + 极速模式」。
- 中继 `problems` 兼容字段仍在（老 App 用），新 App 只读 `sources`。

## 8. 安全与凭据

- 构建/部署脚本**不保存 token**：`oneclick.ps1` 只读 `$env:SKYNEWS_GH_TOKEN`（或 `GH_TOKEN`）。
- 历史聊天中出现过的 PAT 建议**已撤销**；新 token 只需 `repo` + `workflow` 权限（推送 workflow 文件需要 workflow scope）。
- 不提交任何 cookie/token 到仓库；`WEIBO_COOKIE` 只放 Actions Secret。

## 9. 验证记录（2026-11-12）

- 构建：`skynews.apk` 257,303 B，versionName 2.6 / versionCode 8，aapt2 badging 通过。
- 装机：MuMu（emulator-5554）+ 真机 HA25G639 均安装、启动、截图正常。
- 真机源健康：`中继 正常 53 条 / 官网 30 / TapTap 10 / B站 失败(CI跳过·保留13条) / 微博抖音小红书 未配置`。
- 线上：`feed.json` 200（schema:2，53 条）、`feed.xml` 200；Pages 由 Actions 部署成功；Release `v2.6` 已带 APK 资产。
