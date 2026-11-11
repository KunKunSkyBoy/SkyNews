# 自建可行性研究（2026-11-12）

> 结论先行：**「数据源本体」不适合自建**；**「聚合层」我们已经自建并加固**（多源容错 + 每日历史存档）。

## 一、上游数据是怎么来的（读源码结论）

我们用的两个开源项目，数据链路是同一个：**网易《光·遇》游戏内「小精灵」客服接口**。

### 1. GuNanOvO/skydaily（skydaily.nankki.com）

- 部署形态：Cloudflare Worker + Durable Object（`wrangler.jsonc`，cron `*/5`、`*/10`）
- 取 token：`src/upstream/token.ts`
  ```ts
  POST env.NETEASE_TOKEN_API
  body: { cmd: "kefu_get_token", uid: env.SKY_UID, game_uid: env.SKY_GAME_UID,
          os: "android", game_server: Number(env.SKY_GAME_SERVER), login_from: 1,
          map: "CandleSpace", return_buff: "false" }
  ```
- 问数据：`src/upstream/sprite.ts`
  ```ts
  POST env.NETEASE_TASK_API
  headers: { token, "token-type": "gmsdk", origin, referer }
  body: { ismanual: 0, loginFrom: "sprite", method: "link", question: "今日任务指南" }
  ```
- 问题清单（`src/config.ts`）：`今日任务指南` / `天气预报` / `日历` / `大蜡烛`
- 数据来源自述：`DATA_SOURCE = "游戏内小精灵"`

### 2. oivio-up/SkyDailyInfo（备用源）

- 同样需要：`NETEASE_TOKEN_API`、`NETEASE_TASK_API`、`NETEASE_EVENT_API`、`SKY_UID`、`SKY_GAME_UID`、`SKY_GAME_SERVER`（见其 `SETUP.md`）
- 自己再套一层 Cloudflare Worker + `API_SECRET` 做中转

## 二、为什么「数据源自建」不可行

| 缺什么 | 说明 |
|---|---|
| 网易客服接口地址 | 两个项目都**刻意不写死**（`src/env.ts` 只有变量名，`.dev.vars.example` 值为空；SETUP 原文："避免代码中硬编码敏感的 API 地址"）。GitHub 代码搜索里也只有这两个仓库出现 `kefu_get_token`，没有公开 URL |
| 游戏账号凭证 | `SKY_UID` / `SKY_GAME_UID` / `SKY_GAME_SERVER` 必须是真实账号，且 token 由客服接口按账号签发 |
| 合规边界 | 客服小精灵接口属于网易内部服务，抓包/复用做自动化属于灰色地带，不在「本地客户端分析」范围内 |

**即使拿到 URL + 账号，也只是复刻第三方已在做的事**，收益极低、风险高，不推荐。

## 三、我们已经自建的部分（可行且已加固）

```
网易/公开接口 ──▶ 我们的 relay（GitHub Actions，每 10 分钟）
                    ├─ 每日任务：skydaily → 应天API → oivio（三级容错）
                    ├─ 速览：季蜡/大蜡/魔法/赛季倒计时（应天API）
                    ├─ tools.html：图片走 GitHub Release，Pages 自动发布
                    └─ docs/history/YYYY-MM-DD.json ← 新增：每日历史存档（自建数据资产）
```

新增历史存档（`relay/relay.py: write_history`）：

- 每天第一次抓取时落盘 `docs/history/YYYY-MM-DD.json`
- 内容：日期、生成时间、各源状态、每日任务 + 速览全文
- 体积约 **1 KB/天**（≈0.4 MB/年），仓库压力可忽略
- 好处：即使所有第三方同时挂掉，我们仓库里仍有历史 + 上一版 `feed.json` 缓存可用

## 四、如果一定要「完全自建」

只有两条路，且都不推荐：

1. **抓包还原网易客服接口**（需要真机 + 账号 + 逆向客服 H5），再自建 Worker —— 合规风险高、维护成本大
2. **换一个公开数据源**（目前未发现免 key 且长期稳定的光遇每日任务源；skydaily/oivio/应天已是能找到的最优组合）

## 五、结论

- 数据源本体：**不自建**，继续用三级免费容错（已上线）
- 聚合 + 存档：**已自建**（relay + history + Pages）
- 后续可选：App 里加「历史任务」页读 `docs/history/`，或把存档做成周报统计
