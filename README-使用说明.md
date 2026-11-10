# 光遇公告 App（com.dsh.skynews）使用说明

## 装在哪
- APK：`/storage/emulated/0/MT2/mcp/SkyNews/skynews.apk`（187,194 B，v2.4 / versionCode 6，已签名，装机名「光遇公告」）
- 源码：`/storage/emulated/0/MT2/mcp/SkyNews/app/`（MainActivity.java 60 KB + AndroidManifest.xml）

## 数据从哪里来（公开 GET，无需登录）
| 内容 | URL |
|---|---|
| 公告正文 | `https://ma75.update.netease.com/game_notice/announcement_live.json` |
| TapTap 官方帖（社区官方号） | `https://www.taptap.cn/webapiv2/feed/v7/by-group?group_id=47615&type=official&sort=created` + `X-UA` 头 |
| 官网新闻流（公告/更新/活动/攻略） | `https://sixhorse.game.163.com/news/outer/newslist_search.do?start=1&limit=30&contentkind=26689,26690,26691,29298` |
| B 站官方动态（WBI 签名 + buvid3） | `https://api.bilibili.com/x/polymer/web-dynamic/v1/feed/space?host_mid=211700578` |
| 8+ 适龄提示（仍抓，仅不再单独展示） | `https://ma75.update.netease.com/game_notice/age_alert_content.json` |
| 更新配置（含 CC 直播开关） | `https://ma75.update.netease.com/config/0.16.7-421589-Android-obt.json` |
| 服务器时间 | `https://mgbsdk.matrix.netease.com/ma75/sdk/server_time` |

全部是公开 GET、无需登录、http/https 都行，所以**不需要抓包、不需要开游戏**。

## 六个页签（v2.4）
1. **公告**：全文（10 小节，金色小标题渲染）+ 底部「复制全文 / 原文 / 刷新」
2. **动态**：TapTap 官方帖 + B 站官方动态 + 官网新闻的统一时间线（按时间倒序，点条目跳原文）；有新条目发通知
3. **变更历史**：公告变化时自动留档，可回看旧版本
4. **设置**：
   - 官方动态源：TapTap / B 站 / 官网（App 内可抓）、微博（当前网络被风控，点此手动打开）
   - 云中继地址：默认 `https://kunkunskyboy.github.io/SkyNews/feed.json`（已部署）；微博/抖音/小红书动态经云端并入（见 `relay/README.md`）
   - 检测频率：15 / 30 / 60 / 120 分钟
   - 常驻监控：开前台服务（通知栏常驻，JobScheduler 兜底，已实测 `isForeground=true`）
   - 状态：上次检测 / 上次变更 / 公告指纹 / 服务器日期 / 数据源
   - 清空变更历史、立即检测一次

## 判断「有没有更新」的方式
只比 `Title` + `NeteaseMessage` 的 sha256，**不把服务器日期算进去**（日期每次都会变，会误报）。

## 备用方案（不用手机常开）
原先放在 `.pp/watcher/` 的 GitHub Actions 方案（每 5 分钟抓一次 + ntfy/Bark/PushDeer 推送）已随
2026-11-09 的工作区清理删除，本目录不再保留该实现；App 自带的「常驻监控」（前台服务 + JobScheduler）就是当前唯一在用的轮询方案。
若要恢复云端方案，按同样思路重建即可：`watch.py`（抓取+比对+推送，`--once` / `--loop N` / `--dry-run`）、
`gha-watch.yml`（公开仓库免费）、`test-local.sh`（本机自测）。

## 已采集的历史存档
2026-11-04 01:14 抓的公告/适龄/配置/服务器时间 + HTTP 头 + meta 原本在 `.pp/live/captures/20261104-011412/`，
该目录已随同次清理删除；同批公告全文另存为工作区根目录的 [`公告合集.md`](../公告合集.md)。
