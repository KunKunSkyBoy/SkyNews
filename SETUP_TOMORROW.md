# 明日任务（排期）取数说明

> 面向维护者。讲清楚「App 里的明日任务排期是从哪来的、额度怎么省的、坏了怎么查」。

## 1. 数据流

```
独角兽 API (ovoav.com)
  └─ GET /api/sky/mrrw/rw?key=<KEY>
        │  免费额度：3 次/天 · 10 次/月 · 15 次总额
        │  只返回「今天 + 明天」两天排期，不认 date 参数
        ▼
relay/relay.py :: write_tomorrow()      ← 每 10 分钟被 workflow 调一次，自带限流
        │  成功 → docs/tomorrow.json
        │  失败 → 不落盘（旧文件继续服务）
        ▼
GitHub Pages  https://kunkunskyboy.github.io/SkyNews/tomorrow.json
        │  静态文件，读多少次都不耗额度、不带 key
        ▼
App (SkyPanel.fetchTomorrow)
        ① 本地缓存命中（mrrw_date == 明天 && mrrw_day == 今天）→ 直接用
        ② 读中继静态文件 → 唯一网络来源，无限次
        （App 不直连 API：3 次/天的额度只留给中继）
```

关键点：**API 密钥只在 GitHub Actions 里出现**，不进 APK、不进网页。

## 2. 为什么不让 App 直连

该接口免费额度只有 **3 次/天**。App 直连的后果是：一个用户一天开三次 App 就把当天额度吃光，第 4 次起明日段静默回退成「任务池参考」——而且这是**所有用户共享同一个额度**，实际等于全天只有前三次刷新有效。

放到中继后：

| 项目 | App 直连 | 走中继 |
|---|---|---|
| 每日额度消耗 | 随用户刷新次数线性增长 | 固定 1 次/天 |
| 密钥暴露面 | 在 APK 里 | 仅 GitHub Secrets |
| 离线/超额时表现 | 空白或报错 | 继续读上一份静态文件 |

## 3. 需要的 Secret

仓库 Settings → Secrets and variables → Actions：

| Secret 名 | 用途 | 是否必需 |
|---|---|---|
| `OVOAV_MRRW_KEY` | 独角兽 API 密钥，workflow 里映射为环境变量 `SKYNEWS_MRRW_KEY` | 必需 |
| `T1QQ_KEY` | 另一条数据源，与明日任务无关 | — |

`relay.py` 里 `_mrrw_key()` 按 `SKYNEWS_MRRW_KEY` → `SKYNEWS_DAILY_KEY` 顺序取，前者为空时回退后者（兼容旧配置）。两者都没配时抛 `NotConfigured`，workflow 会标红，不会静默产出空文件。

## 4. 限流与日界

`relay.yml` 是 `cron: "*/10 * * * *"`，一天 144 次。`write_tomorrow()` 自己挡三道：

1. **今日已抓且覆盖明天** → 直接 return，不发请求（正常情况一天只请求 1 次）；
2. **今日尝试 ≥ 3 次** → 跳过（额度上限，防失败后无限重试把月额度烧光）；
3. **每次真请求前先 `+1` 落盘** 到 `docs/.mrrw-date`，所以即使请求抛异常，计数也已持久化。

日界用 `_bj_now()` = UTC+8，对齐国内 0 点额度刷新。`docs/.mrrw-date` 内容格式：

```
2026-11-13 1        # 日期 当日已尝试次数；日期不是今天则视为 0
```

该文件随仓库提交，是**跨 workflow run 的唯一状态**（Actions 每次都是全新容器，不能靠内存）。

## 5. tomorrow.json 字段

```json
{
  "schema": 1,
  "generated_at": "2026-11-13T07:36:43Z",
  "fetched_day": "2026-11-13",
  "today_date": "2026-11-13",
  "tomorrow_date": "2026-11-14",
  "today":    [{"date":"2026-11-13","quest_id":"pickup_30_wax","quest_cn":"收集30点烛火"}],
  "tomorrow": [{"date":"2026-11-14","quest_id":"...","quest_cn":"..."}],
  "raw_count": 8
}
```

| 字段 | 说明 |
|---|---|
| `fetched_day` | 抓取当天（北京时间），用于判断「今天是否已抓过」 |
| `today_date` / `tomorrow_date` | 从返回数据里排序取的两个日期，**不是**自己算的 |
| `today` / `tomorrow` | 按日期分组后的任务，各 4 条 |
| `quest_id` | 与游戏内部资源 id 同源（见 `apk_build/src/assets/sky/quests.json` 的 `i` 字段） |
| `raw_count` | 原始 `task_list` 条数，排查用 |

App 端 `fromRelay()` 会校验 `tomorrow_date` **必须等于本机算出的明天**，不等就丢弃走兜底——所以跨天不会拿到过期数据。

## 6. App 端三级回退

```java
if (mrrw_date == 明天 && mrrw_day == 今天) return;   // ① 缓存命中，不发请求
fromRelay(sp, tmr, today);                          // ② 读中继静态文件（唯一网络来源）
```

缓存键：`mrrw`（任务名 JSON 数组）、`mrrw_date`（该数据对应日期）、`mrrw_day`（拉取当天）、`mrrw_ts`（时间戳，仅诊断用）。

读取失败（404 / 日期不符 / 网络错）时明日段显示「排期获取中 · 点右上角刷新后更新 · 以下为任务池参考」，并回落到任务池卡片——**不拿假数据冒充真排期**。

> 早期版本里还有一层「直连 API 兜底」，已在 4.1 后续构建中移除：
> 该接口额度全局共享，App 与中继同时直连会互相抢额度（中继的 `.mrrw-date` 计数也统计不到 App 的消耗），
> 结果两边都拿不到数据。现在额度只归中继，App 侧零额度消耗、APK 内也不再含密钥。

## 7. 轮换密钥

1. 在独角兽后台换 key；
2. 更新仓库 Secret `OVOAV_MRRW_KEY`；
3. 手动跑一次 relay workflow（Actions → relay → Run workflow）确认产出 `docs/tomorrow.json`；
4. 不需要重新发版 APK（App 不持有 key）。

## 8. 已知限制

- **额度耗尽期间不会产出文件。** 当日 3 次尝试失败后 `tomorrow.json` 保持缺失或保持旧值，需等次日 0 点（北京时间）自动恢复。若仓库里从来没有过该文件，Pages 上会一直是 404，App 走直连兜底，直连也失败则显示回退文案。
- **上游是人工维护的**（其产品目录写的是「光遇任务合集（数据来源游离）」），可能晚于游戏实际更新，也可能偶发文案错字——这不是 bug，是数据源特性。
- **只覆盖两天。** 接口本身只返回今天和明天，没有更远的排期，App 里「后天」只能用任务池参考。
- **`.mrrw-date` 是单点状态。** 手动触发 workflow 也会消耗计数；调试时请用假 key（会返回 403 且不计额度）验证逻辑，别拿真 key 反复试。
- **中继是单点。** 直连兜底移除后，若 relay 长期失败（Secret 失效、API 改地址），明日段会一直显示任务池参考，没有第二条网络来源。换 key 流程见第 7 节。

## 9. 排查

| 现象 | 查什么 |
|---|---|
| workflow 报 `独角兽 API code=400 msg=超出每日免费额度` | 正常现象，额度用完了。看 `docs/.mrrw-date` 的计数 |
| workflow 报 `fatal: pathspec 'docs/tomorrow.json' did not match any files` | `git add` 需要加文件存在判断（已修，`relay.yml` 里是 `if [ -f ... ]`） |
| workflow 报 `未配置 SKYNEWS_MRRW_KEY` | Secret 没配或名字写错 |
| Pages 上 404 | 文件从未成功生成过，等次日额度刷新 |
| App 明日段一直是「任务池参考」 | 抓包看 `tomorrow.json` 是否 200；再看 `tomorrow_date` 是否等于明天 |
