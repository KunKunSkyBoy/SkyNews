# SkyNews 云中继（relay v2）

手机端直连能拿到 **TapTap / B 站 / 官网**，但拿不到 **微博 / 抖音 / 小红书**（IP 风控、签名、登录）。
本目录把抓取搬到云端，输出统一 JSON（附 RSS），App 填一个地址即可并入「动态」页。

**v2 重点：抓取稳定性** —— 每源独立超时/重试/退避+抖动、失败不拖累其它源、失败保留上次成功条目（stale）、
输出 schema:2 健康信息，同时**保留旧字段**让旧版 App 不白屏。

## 产出

| 文件 | 用途 |
|---|---|
| `docs/feed.json` | App 拉取：schema:2（含旧字段兼容），约 40–200 条，体积通常 <200 KB（上限 1 MB） |
| `docs/feed.xml` | RSS 2.0，可直接丢进阅读器 |

### feed.json 结构（schema:2）

```json
{
 "schema": 2,
 "generated_at": "2026-11-11T16:16:42Z",
 "updated": "2026-11-11T16:16:42Z",
 "count": 53,
 "sources": {
   "official": {"ok": true, "count": 30, "error": null, "elapsed_ms": 610, "last_success": "..."},
   "bili":     {"ok": false, "count": 13, "error": "CI 跳过 B 站…（重试 3 次）", "elapsed_ms": 3213,
                "last_success": "2026-11-11T16:16:42Z", "stale": true}
 },
 "problems": ["B站: …", "微博: …"],
 "items": [
   {"id":"tt-...", "source":"taptap", "title":"…", "url":"https://…",
    "time":"2026-11-11T13:50:15Z", "summary":"…", "stale": false,
    "src":"TapTap", "ts":1794405015, "t":"11-11 21:50", "text":"标题\n摘要"}
 ]
}
```

**字段映射（旧 App 兼容）**：`src=source`、`ts=time 的 epoch 秒`、`t=本地时间文本`、`text=title+summary`；
顶层保留 `updated / count / problems` 旧字段。旧 App 不升级也能正常显示；新版可按 `sources` 判源健康、按 `stale` 标记缓存条目。

## 支持的源

| 源名（SKYNEWS_SOURCES） | 是否需要配置 | 说明 |
|---|---|---|
| `official`（默认开） | 无 | 官网新闻 JSON：`sixhorse.game.163.com` |
| `tap` / `taptap`（默认开） | 无 | TapTap 官方帖 `group_id=47615` |
| `taptap_hot`（可选，默认关） | `SKYNEWS_TAPTAP_HOT=1` + `SKYNEWS_TAPTAP_HOT_ROUTE` + `RSSHUB_BASE` | TapTap 热帖/评论（经 RSSHub 路由） |
| `bili`（默认开） | 无（本地网络）；CI 需 `SKYNEWS_BILI_CI=1` | B 站动态（WBI 签名 + buvid3/4）。**GitHub Actions 海外出口 412 高频，默认 CI 跳过、由 App 本地抓** |
| `weibo`（默认开） | `WEIBO_COOKIE`（直连） 或 `RSSHUB_BASE` | 有 cookie 走 m.weibo.cn；否则走 RSSHub `/weibo/user/<uid>` |
| `douyin`（默认开） | `RSSHUB_BASE` + `SKYNEWS_DOUYIN_ROUTE` | RSSHub 路由（需自建实例，公共实例多不可用） |
| `xhs`（默认开） | `RSSHUB_BASE` + `SKYNEWS_XHS_ROUTE` | 小红书，同上 |
| 自定义 RSS 源 | `RSSHUB_BASE` + `SKYNEWS_RSSHUB_ROUTES="name:path,name2:path2"` | 任意 RSSHub 路由并入（source=name） |

> 未配置的源会出现在 `sources` 里（`ok=false, count=0`）并在 `problems` 中说明原因，不影响其它源。

## 环境变量

| 变量 | 默认 | 说明 |
|---|---|---|
| `SKYNEWS_SOURCES` | `tap,official,bili,weibo,douyin,xhs` | 源开关（逗号分隔；`official`/`tap`/`bili`/`weibo`/`douyin`/`xhs`/`taptap_hot`） |
| `SKYNEWS_TIMEOUT` | `8` | 单次请求超时（秒） |
| `SKYNEWS_RETRIES` | `3` | 每源重试次数（指数退避+抖动） |
| `SKYNEWS_BACKOFF_BASE` / `SKYNEWS_JITTER` | `0.8` / `0.5` | 退避基数/抖动（秒） |
| `SKYNEWS_TOTAL_TIMEOUT` | `60` | 整轮抓取总预算（秒），到点未完成的源用上次结果回退 |
| `SKYNEWS_MAX_ITEMS` | `200` | 输出条目上限（控制体积 <1 MB） |
| `SKYNEWS_CONCURRENCY` | `6` | 并发源数量 |
| `WEIBO_COOKIE` | — | 微博 cookie（Secret；日志不会打印） |
| `RSSHUB_BASE` | — | 自建 RSSHub 地址，如 `https://rsshub.example.com` |
| `SKYNEWS_WEIBO_ROUTE` / `SKYNEWS_DOUYIN_ROUTE` / `SKYNEWS_XHS_ROUTE` | weibo 默认 `/weibo/user/6355968578`，其它空 | RSSHub 路由（空=该源不可用） |
| `SKYNEWS_RSSHUB_ROUTES` | — | 额外 RSS 源：`name:path,name2:path2` |
| `SKYNEWS_BILI_CI` | `0` | CI 中是否强制抓 B 站（`1`=尝试；412 时自动重试/失败回退） |
| `NTFY_TOPIC` | — | 可选，CI 失败时推送到 ntfy.sh |

## 本地运行 / 自测

```bash
# 离线自检（schema/去重/失败回退，不联网；CI 第一步也会跑）
python3 relay/relay.py --self-test

# 抓取并打印每源状态
python3 relay/relay.py --out docs --print

# 不联网调试：用上次 feed 做失败回退（stale）
python3 relay/relay.py --out /tmp/out --cache docs/feed.json --print
```

## 部署（GitHub Actions + Pages）

1. 把 `SkyNews/` 推到 **public** 仓库（`docs/` 与 `.github/` 在仓库根）。
2. Settings → Pages：Deploy from branch，分支 `main`，目录 `/docs`。
3. Settings → Secrets and variables → Actions：
   - Secret `WEIBO_COOKIE`（可选）；
   - Secret `NTFY_TOPIC`（可选，失败通知）；
   - Variable `RSSHUB_BASE`（可选，自建 RSSHub 地址）。
4. Actions 页启用 workflow（`skynews-relay`，每 10 分钟 + 手动触发）。
5. 访问 `https://<用户名>.github.io/<仓库>/feed.json` 确认 `schema:2` 且 `sources` 存在。
6. App → 设置 → 云中继地址 填该 URL。

## 已知限制

- GitHub 定时任务不保证准时（高峰延迟 5–20 分钟）；要准就自建服务器跑同一脚本 + cron（`python relay.py --out docs --print`）。
- **B 站 412**：Actions 海外出口高发；v2 默认 CI 跳过（`SKYNEWS_BILI_CI=0`），B 站条目由 App 本地抓；如需 CI 抓可设 `SKYNEWS_BILI_CI=1` 并接受重试/回退。
- 微博 cookie 会过期（`sources.weibo.error` 会提示）；重新复制即可。
- 抖音/小红书依赖自建 RSSHub（公共实例多数国内不可用）。
- 失败源沿用上次成功条目（`stale=true`），`sources.*.last_success` 可判断新鲜度；持续失败需看 `problems`。
