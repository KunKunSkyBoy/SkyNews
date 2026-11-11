# relay 稳定性报告（task-32）

> 时间：2026-11-12 00:15–00:47（本地 Windows / Python 3.13）｜对象：`relay/relay.py` v2
> 结论：每源独立超时+重试+退避，单源失败不影响其它源；失败源**沿用上次成功条目（stale）**，条目不会消失；
> 本机连跑 5 次（3 次常规 + 1 次补丁验证 + 1 次模拟 CI 回退）全部完成，单轮总耗时 3.2–3.4s。

## 1. 连跑记录（每轮各源 ok / count / elapsed_ms）

| 轮次 | 场景 | official | taptap | bili | weibo | douyin | xhs | items | 总耗时 |
|---|---|---|---|---|---|---|---|---|---|
| run1 | 本机常规 | ✅30 / 710ms | ✅10 / 1163ms | ⚠️0 / 1255ms* | ❌未配置 / 2913ms | ❌未配置 / 3258ms | ❌未配置 / 2780ms | 40 | 3.4s |
| run2 | 本机常规 | ✅30 / 604ms | ✅10 / 1442ms | ⚠️0 / 1441ms* | ❌未配置 / 3053ms | ❌未配置 / 2488ms | ❌未配置 / 2875ms | 40 | 3.2s |
| run3 | 本机常规 | ✅30 / 638ms | ✅10 / 731ms | ✅13 / 988ms | ❌未配置 / 3051ms | ❌未配置 / 3133ms | ❌未配置 / 2961ms | 53 | 3.3s |
| run4 | 补丁后（空=失败） | ✅30 / 621ms | ✅10 / 745ms | ✅13 / 919ms | ❌未配置 / 2865ms | ❌未配置 / 3064ms | ❌未配置 / 2993ms | 53 | 3.3s |
| run5 | 模拟 CI（GITHUB_ACTIONS=true） | ✅30 / 610ms | ✅10 / 701ms | 🟡**stale 13** / 3213ms | ❌未配置 / 3022ms | ❌未配置 / 2958ms | ❌未配置 / 3175ms | 53（13 stale） | 3.4s |

\* run1/run2 时 B 站偶发返回空列表（未报错），补丁后「返回 0 条」按失败处理（重试/回退），run4 起恢复 13 条。
**run5 验证了回退策略**：B 站被 CI 策略跳过（`ok=false`），但保留上次成功的 13 条，`stale=true`，
`sources.bili.last_success` 保持上次成功时间，feed 总条目不减少。
离线自检 `python relay/relay.py --self-test`：10/10 PASS（字段/去重/stale/ISO 时间等）。

## 2. 稳定性机制

1. **每源独立**：`ThreadPoolExecutor` 并发抓取，每源在自己的线程里做「超时 → 重试 3 次（指数退避+抖动）」，互不阻塞。
2. **超时**：单次请求 `SKYNEWS_TIMEOUT`（默认 8s）；整轮 `SKYNEWS_TOTAL_TIMEOUT`（默认 60s），到点未完成的源用上次结果回退。
3. **退避**：第 n 次重试等待 `base*2^n + random(0,jitter)`（默认 0.8s 基数、0.5s 抖动），避免同时打爆。
4. **失败回退**：读取上一次 `feed.json`，按源分组缓存条目；本次失败 → 旧条目 `stale=true` 输出，并在 `sources.<name>` 记录 `ok=false/error/last_success/stale=true`。
5. **跨源去重**：id 优先，其次 URL 归一化（去 utm/from/share、尾斜杠、host 小写）；非 stale 优先、newer 优先。
6. **体积控制**：`SKYNEWS_MAX_ITEMS` 默认 200；实测 53 条 = 42.8 KB，200 条约 160 KB（< 1 MB 限制）。
7. **兼容**：schema:2 同时保留旧字段 `src/ts/t/text/url` 与 `updated/count/problems`，旧 App 不白屏；新 App 用 `sources` 判健康、`stale` 标缓存。

## 3. 失败场景与回退策略

| 场景 | 表现 | 回退 |
|---|---|---|
| B 站 412（CI 海外出口） | `sources.bili.ok=false, error=412/CI 跳过` | 默认 CI 跳过、App 本地抓；`SKYNEWS_BILI_CI=1` 强制时重试 3 次 + 上次条目 stale 回退 |
| 微博 cookie 过期/未配置 | `sources.weibo.ok=false`，error 提示 | 有 RSSHub 路由时走 RSSHub；否则保持旧条目 stale |
| RSSHub 不可达/路由错 | `douyin/xhs` error | 其它源不受影响；旧条目 stale 保留 |
| 官网/TapTap 网络超时 | 重试 3 次（退避+抖动） | 失败后用上次成功条目 stale 回退 |
| GitHub schedule 延迟 | 发布间隔变长（非抓取失败） | 自建服务器 + cron 可解决 |
| 输出/提交失败 | CI 里 commit/push 有 3 次 rebase 重试；失败 ntfy 通知（可选） | — |

## 4. 监控建议

- **CI 摘要**：workflow 每轮把 `sources` 表（ok/count/耗时/error）写进 Actions Step Summary，并提示 stale 条目数。
- **告警**：设 `NTFY_TOPIC` secret，workflow 失败时推送 ntfy.sh；也可对 `problems` 做外部轮询。
- **健康判据**：`sources.official.ok && sources.taptap.ok` 为核心基线；`weibo/douyin/xhs` 视配置；
  `sources.*.last_success` 超过数小时未更新 → 该源异常。
- **artifact**：每轮 feed.json 上传 3 天，便于对比回归。

## 5. 复现

```bash
python3 relay/relay.py --self-test
python3 relay/relay.py --out /tmp/run1 --print
python3 relay/relay.py --out /tmp/run2 --cache /tmp/run1/feed.json --print   # 观察 stale 回退
```
本次证据目录：`relay/_verify/run1..run5/feed.json`（本地临时，可删）。
