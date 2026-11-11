# SkyNews 源接入状态与实测结论（2026-11-12）

> 本文记录「全部源都尝试一遍」的结果，避免以后重复踩坑。测试环境：
> - App 本地（手机/模拟器出口）
> - GitHub Actions `ubuntu-latest`（海外出口）
> - 中继 relay v2 + `SKYNEWS_*` 环境变量

## 一、总表

| 源 | 状态 | 依赖 | 说明 |
|---|---|---|---|
| 官网新闻/公告 | ✅ 稳定 | 无 | App 本地 + 中继都可用（30 条/次） |
| TapTap 官方帖 | ✅ 稳定 | `X-UA` 头 | App 本地 + 中继都可用（10 条/次） |
| B 站官方动态 | ✅ / ⚠️ | WBI 签名 + `buvid3` | **App 本地可抓（13 条）**；GitHub Actions 海外出口 **412**，中继默认跳过并保留 stale 13 条（App 会补本地 B 站条目） |
| 微博官方 | ❌ 需 cookie | `WEIBO_COOKIE` | 本机 432；GitHub Actions 也被风控（返回 HTML/432）；4 个公共 RSSHub 实例微博路由全部失败（403/503/DNS） |
| 抖音官方 | ❌ 需路由+cookie | `RSSHUB_BASE` + `SKYNEWS_DOUYIN_ROUTE` | 无公开路由，需要自建 RSSHub + 抖音 cookie |
| 小红书官方 | ❌ 需路由+cookie | `RSSHUB_BASE` + `SKYNEWS_XHS_ROUTE` | 同上（x-s/x-t 签名） |

## 二、微博实测明细（2026-11-12 01:00）

| 方式 | 结果 |
|---|---|
| `m.weibo.cn` 公开时间线（本机） | HTTP **432**（运营商/出口 IP 风控） |
| `m.weibo.cn` 公开时间线（GitHub Actions） | 返回 HTML → JSON 解析失败（同样被风控） |
| `rsshub.app/weibo/user/6355968578` | HTTP **403** |
| `rsshub.rssforever.com` | HTTP **503** |
| `hub.slarker.me` | HTTP **503** |
| `rsshub.pseudoyu.com` | DNS 失败 |
| 结论 | **没有 cookie 就无法在 CI/本机抓微博**；需要用户在浏览器登录微博后复制 Cookie 作为仓库 Secret `WEIBO_COOKIE` |

微博官方 UID：`6355968578`（weibo.com/skygy）

## 三、抖音 / 小红书

- 两者都需要：
  1. 自建 RSSHub（`RSSHUB_BASE`）；
  2. 平台 cookie（RSSHub 实例侧配置）；
  3. 官方账号 ID/路由（`SKYNEWS_DOUYIN_ROUTE`、`SKYNEWS_XHS_ROUTE`）。
- 光遇官方抖音/小红书账号 ID 尚未核实；没有路由时 relay 会直接报「未配置」，不拖累其它源。

## 四、配置方法（想全绿时）

仓库 → Settings → Secrets and variables → Actions：

| 类型 | 名称 | 值 |
|---|---|---|
| Secret | `WEIBO_COOKIE` | 浏览器登录 weibo.com 后复制完整 Cookie（含 `SUB=`） |
| Secret | `NTFY_TOPIC`（可选） | ntfy.sh 主题名，CI 失败时推送 |
| Variable | `RSSHUB_BASE` | 自建 RSSHub 地址，如 `https://rsshub.example.com` |
| Variable | `SKYNEWS_BILI_CI` | `1` 强制 CI 抓 B 站（默认 0；海外 IP 常 412，不建议） |
| Variable | `SKYNEWS_DOUYIN_ROUTE` / `SKYNEWS_XHS_ROUTE` | 对应 RSSHub 路由路径 |

> 不配置也不影响主功能：官网/公告/TapTap/B站（App 本地）已稳定覆盖。

## 五、CI 行为

- 每 10 分钟 `skynews-relay`：official/taptap 稳定；bili 默认跳过（失败源保留上次 stale 条目）；weibo/douyin/xhs 未配置时只记 error。
- 手动触发（workflow_dispatch）支持临时覆盖：`rsshub` / `sources` / `bili_ci`（用于换实例测试，无需改代码）。
- 失败源条目以 `stale=true` 保留，客户端灰显；不会因为某源失败而丢条目。
