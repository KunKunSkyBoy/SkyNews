# SkyNews 云中继（relay）

手机端直连能拿到 **TapTap / B 站 / 官网**，但拿不到 **微博 / 抖音 / 小红书**（IP 风控、签名、登录）。
这个目录把抓取搬到云端，输出一份统一 JSON（附 RSS），App 里填一个地址就能把结果并进「动态」页。

## 产出

| 文件 | 用途 |
|---|---|
| `docs/feed.json` | App 拉取用：`{"updated","count","problems","items":[{id,src,ts,t,text,url}]}` |
| `docs/feed.xml` | RSS 2.0，可直接丢进任意阅读器 |

每个源独立 `try/except`：某个源失败只写进 `problems`，不影响其它源。

## 支持的源

| 源 | 是否需要额外配置 | 说明 |
|---|---|---|
| TapTap 官方帖 | 无 | `group_id=47615`，社区官方号 |
| 官网新闻 | 无 | `sixhorse.game.163.com` JSON |
| B 站动态 | 无 | WBI 签名 + `buvid3/buvid4` 访客 cookie（实测缺 `buvid4` 会返回空列表） |
| 微博 | `WEIBO_COOKIE` | 有 cookie 才抓；GitHub 出口 IP 未必被风控，值得一试 |
| 抖音 / 小红书 / 微博兜底 | `RSSHUB_BASE` | 指向一个能用的 RSSHub 实例，走 `/douyin/user/...`、`/xiaohongshu/user/...`、`/weibo/user/<uid>` |

## 部署（GitHub Actions + Pages，全免费）

1. 把 `SkyNews/` 整个目录推到一个 **public** 仓库（`docs/` 和 `.github/` 都要在仓库根）。
2. 仓库 **Settings → Pages**：Source 选 *Deploy from a branch*，分支 `main`，目录 `/docs`。
3. 仓库 **Settings → Secrets and variables → Actions**：
   - Secret `WEIBO_COOKIE`（可选）：浏览器登录微博后复制整段 Cookie；
   - Variable `RSSHUB_BASE`（可选）：例如 `https://rsshub.yourdomain.com`。
4. **Actions** 页启用 workflow，点一次 *Run workflow*。
5. 等 1~2 分钟，访问 `https://<你的用户名>.github.io/<仓库名>/feed.json` 确认有 `items`。
6. App → 设置 → **云中继地址** 填这个 URL，保存后下次刷新即生效（条目会以「中继」来源并入时间线）。

## 本地跑

```bash
python3 relay/relay.py --out docs --print
```

只依赖 Python 3 标准库。手机 / 服务器 / GitHub Actions 都能跑；能连微博的网络会多出微博条目。

## 已知限制

- GitHub Actions 的定时任务**不保证准时**（高峰期可延迟 5~20 分钟）；要准就自建服务器 + cron。
- 公共 RSSHub 实例在多数国内网络不可达，`RSSHUB_BASE` 建议自建（Docker 一行）。
- 微博 cookie 会过期，失效时 `problems` 里会出现对应报错，重新复制即可。
