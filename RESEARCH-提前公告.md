# 《光·遇》国服「更新 / 维护公告」提前获取调研

> 调研时间：2026-11-03 23:30–23:55（Asia/Shanghai）
> 方式：全部为**只读 GET**，无登录、无 POST、单域名 ≤20 次/分钟
> 结论标注：**【实测】**= 本次 curl 真实回包；**【推测】**= 由机制/结构推导，未直接观测到

---

## 0. 结论先行（TL;DR）

1. **没有找到任何「比官网更早」的公开可读源**。官网 CMS 就是网易自己的首发口，微博/大神/游戏内都没有无登录可读的更早副本。**但"拿到"的时间可以大幅压缩**——见第 2 条。
2. **找到了真正的 JSON 接口（本次最大发现）**：
   `https://sixhorse.game.163.com/news/outer/newslist_search.do`
   返回带**秒级发布时间戳**的 JSON，**不经 CDN 缓存**（响应头无 `cache-control`、无 `age`），而官网 HTML 列表页是 `max-age=300` 的 CDN 缓存。⇒ **相对官网列表页最多可早 300 秒（5 分钟）拿到同一条公告**，且能拿到精确到秒的发布时间（HTML 只有"日期"）。
3. **旧脚本有漏抓 bug**：官网其实有 **4 个栏目**（`official` / `update` / `activity` / `strategy`），`sky-news.py` 的正则只匹配 `official|activity`，**完全漏掉 `update` 栏目——而所有更新公告、全部 6 条停服维护公告都在 `update` 栏目里**。必须补。
4. **APK 建议**：主源轮询 sixhorse JSON（3–5 分钟一次，可接受的流量约 8–30 KB/次），备源 `https://sky.163.com/news/update/`（加 `?t=<ts>` 破 CDN 缓存）。判定规则见第 5 节。
5. **不建议轮询**：listsvr 配置接口（403）、活动日历静态 JSON（内容已陈旧 2 个月）、微博（访客墙）、`ma75.gsf.netease.com`（404）、`skynet.radiance.thatgamecompany.com`（国内超时）。

---

## 1. 官网结构：4 个栏目（旧脚本只抓了 2 个）

实测枚举（首页 ~ 第 5 页里出现的所有栏目路径）：

```bash
for n in 1 2 3 4 5; do
  u="https://sky.163.com/news/"; [ $n -gt 1 ] && u="https://sky.163.com/news/index_$n.html"
  curl -sS -A "Mozilla/5.0 ..." "$u" | grep -oE '//sky\.163\.com/news/[a-z]+/'; sleep 1
done | sort | uniq -c
```

结果：

| 栏目 URL 段 | CMS contentkind | 栏目页实测 | 说明 |
|---|---|---|---|
| `official` | 26689 | `GET /news/official/` → **200**（10,573 B） | 官方新闻/活动预告 |
| `update` | **26690** | `GET /news/update/` → **200**（10,342 B，分页至 `index_83.html`） | **更新内容公告 + 全部停服维护公告** |
| `activity` | 26691 | `GET /news/activity/` → **404** | 活动/激励，只能从合并列表看 |
| `strategy` | 29298 | `GET /news/strategy/` → **200**（16,641 B） | 攻略/设定分享 |

- 每页 **3 条**；主列表 `index_1..index_229.html`，`update` 栏目 `index_1..index_83.html`。
- 一致性校验：JSON `count=685`，HTML 229 页 × 3 ≈ 687 —— 两套口径基本对得上，说明就是同一份 CMS 数据。

---

## 2. 重大发现：`sixhorse` JSON 接口（可直接当 APK 主数据源）

### 2.1 接口

```
GET https://sixhorse.game.163.com/news/outer/newslist_search.do
    ?start=1&limit=50&contentkind=26689,26690,26691,29298
```

实测：

```bash
curl -sS -A "Mozilla/5.0" \
  -H "Referer: https://sky.163.com/news/" \
  -o sh50.json -w "[HTTP %{http_code}] bytes=%{size_download}\n" \
  "https://sixhorse.game.163.com/news/outer/newslist_search.do?start=1&limit=50&contentkind=26689,26690,26691,29298"
```

- **`[HTTP 200]`**，`content-type: application/javascript;charset=UTF-8`，`server: nginx`，t≈0.29 s，50 条 = 75,158 B。
- **响应头无 `cache-control`、无 `age`、无 `expires`** ⇒【实测】每次请求直达应用服务器，**不吃 CDN 缓存**。
- **不带 Referer、不带 UA 也返回 200**【实测】⇒ APK 里 `HttpURLConnection`/OkHttp 裸 GET 即可，无需伪造头。

### 2.2 返回结构

```json
{"result":[{
  "id":"a081428b-...","code":"1317244",
  "title":"来场不一样的恶作剧！丨《光·遇》恶作剧之日更新内容公告",
  "url":"//sky.163.com/news/update/20261021/26690_1317244.html",
  "showtime":1792468800000,        // 秒级发布时间（ms）
  "updateTime":1793078569000,      // 最后修改时间（ms）
  "author":"","imageFileId":"...","imageUrl":"...","imageUrls":"...","comment":"..."
}],"succ":1,"count":685}
```

- `showtime` / `updateTime` 是 **13 位毫秒时间戳**，转 `Asia/Shanghai` 即为**精确到秒**的发布时刻 —— HTML 列表页**只有日期**。
- `count=685` 全站总条数，`start`/`limit` 分页翻历史（回填用）。
- `contentkind` 可单点某个栏目（如只取 `26690` 只要更新公告）。
- **`keyword` 参数可用**【实测】：`&keyword=维护` → `count=6`；`&keyword=更新内容公告` → `count=75`。

### 2.3 由此得到的「提前量」定量依据

| 通道 | 缓存策略【实测】 | 最坏陈旧度 | 时间精度 |
|---|---|---|---|
| `sky.163.com/news/` HTML | `Cache-Control: max-age=300`，实测 `Age: 15/17`（命中） | **最多 300 秒** | 只有日期 |
| `sky.163.com/m/news/` 移动版 | 同样 `max-age=300` | 最多 300 秒 | 只有日期 |
| **sixhorse JSON** | **无 cache-control / 无 age** | **0（直连）** | **秒级** |
| `sky.163.com/news/?t=<ts>` 破缓存 | 加随机参数 → 实测 `Age: 0`（回源） | 0 | 只有日期 |

> 所以：**JSON 通道相对 HTML 通道的可观测提前量 = 0 ~ 300 秒**（机制实测；单次能早多少取决于你请求时 CDN 边缘缓存已陈旧多少）。附加收益是**秒级时间戳**，可让 APK 精确记录"我发现它的时刻"与"官方发布时刻"的差。

---

## 3. 公告是「定时预发布」的（实测统计）

对 `limit=50` 的 50 条样本做统计（`showtime` = 发布，`updateTime` = 最后修改）：

| 指标 | 结果【实测】 |
|---|---|
| 发布时间落在**整点整分**（10:00 / 12:00 / 17:00…） | **47 / 50** |
| `updateTime` 早于 `showtime`（内容先写好、到点才放出来） | **15 / 50** |
| 预写提前量（中位 / 最大） | **13.2 小时 / 138.1 小时（≈5.8 天）** |
| 发布时段 | 集中在 10:00–18:30 工作时段 |

**含义**：运营是**提前几小时到几天**把公告写好、设定时上线的。这段"提前"理论上是最早的信息，但——
- 官网页无 `/api`、无 JSON、无 sitemap/robots（`GET /robots.txt`、`/sitemap.xml` 全 **404** 通用错误页）；
- CMS 后台接口未发现无鉴权入口（本次未做爆破/枚举，仅按前端 JS 里出现的公开地址探测）；
⇒【推测】这段提前量**目前无法通过公开 GET 拿到**，只能拿"上线瞬间"。

---

## 4. 逐源实测表

| # | 源 | 实测命令/URL | 实测状态 | 可自动化 | 提前量 vs 官网列表页 | 依据 |
|---|---|---|---|---|---|---|
| 1 | 官网合并列表 HTML | `GET https://sky.163.com/news/` | **200**，`max-age=300`，`Age:15` | **是** | **基准 0** | 【实测】 |
| 2 | 官网 `update` 栏目页 | `GET https://sky.163.com/news/update/` | **200**，同上 300 s 缓存 | **是（推荐备源）** | 0（与主列表同 CDN 策略） | 【实测】 |
| 3 | 官网 `official` 栏目页 | `GET /news/official/` | **200** | 是 | 0 | 【实测】 |
| 4 | 官网 `activity` 栏目页 | `GET /news/activity/` | **404** | 否（只能用合并列表/JSON 过滤） | — | 【实测】 |
| 5 | 官网 `strategy` 栏目页 | `GET /news/strategy/` | **200**（16,641 B） | 是（非公告，可忽略） | 0 | 【实测】 |
| 6 | 移动版列表 | `GET https://sky.163.com/m/news/` 、`/m/news/update/` | **200**，同 `max-age=300` | 是（无额外收益） | 0 | 【实测】 |
| 7 | **sixhorse JSON** | `GET https://sixhorse.game.163.com/news/outer/newslist_search.do?start=1&limit=50&contentkind=26689,26690,26691,29298` | **200**，无 cache-control | **是（首选主源）** | **最多早 300 秒**，且秒级时间戳 | 【实测】 |
| 8 | 活动日历 SPA | `GET https://sky.h5.163.com/h5/calendar.html` | **200**（2,749 B，Vite SPA 壳） | 壳无数据 | — | 【实测】 |
| 9 | 日历真实数据 | `GET https://sky.h5.163.com/static/cfg/calendar.json` | **200**，260,392 B，`max-age=300` | 技术上可抓 | **负值（陈旧）** | 【实测】 |
| 10 | listsvr 配置接口 | `GET https://listsvr.x.netease.com:6678/h5_pl/ma75/sky.h5.163.com/static/cfg`（4 种头组合） | **403 ×4**（nginx + Trace ID） | **否** | — | 【实测】 |
| 11 | 官方微博 | `GET https://weibo.com/skygy/`（`-L`） | 302→302→**200 但正文空壳**（9,618 B，落到 `visitor.passport.weibo.cn`） | **否**（访客 Cookie 墙） | — | 【实测】 |
| 12 | 网易大神 | `GET https://ds.163.com/g/ma75`、`/webapp/square/ma75` | **404** / **500** | **否**（未找到公开 JSON） | — | 【实测】 |
| 13 | 网易客服 H5（ma75） | `GET https://kefu.ds.163.com/a19/v1/home?productId=ma75&channel=godlike_gm163` | **200**（24,728 B，SPA，`no-cache`），无内联 JSON | 否（需交互/登录） | — | 【实测】 |
| 14 | 国际服 helpshift 已知问题 | `GET https://thatgamecompany.helpshift.com/a/sky-children-of-the-light/?p=all&s=known-issues`（`-L`） | **200**，落到 `/hc/en/17-.../section/111-known-issues/`，136,765 B，**服务端渲染 107 条 FAQ** | **是** | 【推测】内容领先国服数天~数周，但**不是国服公告** | 【实测】200 + 107 条 |
| 15 | helpshift patch notes 栏目 | `GET .../section/112-patch-notes/` | **404**；首页含 "Patch Notes" 字样 8 次 | 需再定位栏目 id | 同上（参考，不作触发源） | 【实测】 |
| 16 | `sky.thatg.co` | `GET https://sky.thatg.co/` | **200** | 未解析出公告接口 | — | 【实测】 |
| 17 | 游戏内公告域名 A | `GET https://ma75.gsf.netease.com/`（http/https） | **404 / 404**（IP 202.110.125.66） | **否**（无公开静态资源） | — | 【实测】 |
| 18 | 游戏内公告域名 B | `GET https://skynet.radiance.thatgamecompany.com/` | **curl (28) 超时 12 s** | **否**（国内不可达） | — | 【实测】 |
| 19 | 游戏内公告本体 | so 符号 `GetPatchNoticeFromGame` / `SetPatchNoticeData` / `mail_type_activity_announcement` | 需登录态+自定义协议 | **否**（APK 无法直读） | 【推测】可能比官网早数分钟~数小时，但不可自动化 | 符号存在，未运行时验证 |

### 4.1 关于 #9 日历 JSON 为什么"不推荐"

```bash
curl -sS "https://sky.h5.163.com/static/cfg/calendar.json" -o cal.json   # [HTTP 200] 260392 B
```

- 结构：`[{title, description, coverImageUrl, location, isSpecial, schedules:[{time:"2025-08-20T08:00:00+08:00"}]}]`，共 **23 个活动**。
- 时间跨度：**2025-08-20 ~ 2026-09-03**。调研当日为 **2026-11-03** ⇒ 内容**已落后约 2 个月**，是一份静态导出快照，不是活数据。
- 结论：**不能作为提前源**；最多每天探一次看它是否"复活"。

### 4.2 关于 listsvr 403 的实测细节

```
A) UA + Referer(https://sky.h5.163.com/) + Origin + Accept  → 403
B) 仅 UA                                                    → 403
C) 同上 + ?t=1                                              → 403
D) 去掉 :6678 端口走 443                                     → 403
```

四组全部 **403 Forbidden（nginx + Trace ID）** ⇒ 是**网关层拒绝**（非 UA/Referer 校验），基本确定对外不可用。日历前端 JS 里写死的正是该地址（`calendar-a5fd1eba.js` 中 `listsvr.x.netease.com:6678/h5_pl/ma75/...`），说明它只服务内部/白名单环境。

---

## 5. 公告自身的"提前量"（内容维度，实测）

用 `keyword=维护` 取全部历史维护公告（[HTTP 200]，`count=6`）：

| 公告发布时间【实测】 | 标题 | 维护日期 | 提前 |
|---|---|---|---|
| 2023-05-18 14:32 | 《光·遇》5月19日 停服维护公告 | 5/19 | **1 天** |
| 2022-12-12 11:15 | 12月13日《光•遇》维护通知 | 12/13 | **1 天** |
| 2022-01-02 12:00 | 《光·遇》1月6日 合服维护公告 | 1/6 | **4 天** |
| 2021-01-13 19:27 | 《光·遇》1月15日停服维护公告 | 1/15 | **2 天** |
| 2020-08-20 17:25 | 《光•遇》8月21日临时维护公告 | 8/21 | **1 天** |
| 2020-03-27 17:57 | 《光•遇》3月30日临时维护公告 | 3/30 | **3 天** |

要点：
- **6 条全部落在 `update` 栏目（contentkind=26690）**；最近的停服维护公告是 **2023-05**，之后国服基本不再发停服公告。
- 维护公告发布提前 **1–4 天**（中位 1–2 天）——**这是"提前量"的真实上限**：一旦官网发了，用户最多提前 1–4 天知道。
- 「更新内容公告」提前量：样本如 恶作剧之日公告 10-20 发、活动 10-24 开（4 天）；珍珠季 10-13 发。⇒ **0–4 天**。

`update` 栏目近 14 页 42 条样本的构成【实测】：

| 类型 | 条数 | 说明 |
|---|---|---|
| 标题含「更新」 | 33 / 42 | 绝大多数是「XX 更新内容公告」 |
| 标题含「维护」或「停服」 | **0 / 42** | 近 2 年该栏目无维护公告（维护公告止于 2023-05） |
| 其它（公示/指引/渠道找回/外挂打击） | 9 / 42 | 需要排除，别误报成更新公告 |

---

## 6. 给 APK 的落地方案

### 6.1 轮询源与间隔

| 优先级 | 源 | URL | 间隔 | 备注 |
|---|---|---|---|---|
| **P0 主源** | sixhorse JSON | `https://sixhorse.game.163.com/news/outer/newslist_search.do?start=1&limit=10&contentkind=26689,26690,26691,29298` | **3 分钟**（工作日 09:30–20:00）；其余时段 **15 分钟** | 无头裸 GET；单次 ≈8–30 KB；`limit=10` 足够判新 |
| **P1 备源** | 官网 update 栏目 | `https://sky.163.com/news/update/?t=<epoch>` | 与主源错开 90 秒，10 分钟一次 | `?t=` 破 300 s CDN 缓存（实测 `Age:0`） |
| P2 兜底 | 关键词探针 | `...newslist_search.do?start=1&limit=5&contentkind=26690&keyword=维护` | 30 分钟一次 | 基线 `count=6`；**一旦 >6 立即高优告警** |
| P3 参考 | 国际服 helpshift 已知问题 | `https://thatgamecompany.helpshift.com/hc/en/17-sky-children-of-the-light/section/111-known-issues/` | 6 小时一次 | 只做"将来会有什么"参考，**不进主通知** |

实现要点：
- 最小请求头即可（实测**无 Referer/无 UA 也 200**），但建议带 UA 与 `Referer: https://sky.163.com/news/` 以示正常客户端。
- 超时 10 s，失败指数退避（3→6→12 min），连续 5 次失败降级为只轮询 P1。
- **去重键用 `code`**（如 `1317244`）；首轮把 `code` 列表落本地作为基线，之后只推新增。
- 从 `url` 里解析栏目：`/news/(official|update|activity|strategy)/`，API 不回传 contentkind，**必须靠 url 判定**。
- 用 `showtime` 与本地"首次观测时间"求差，长期可得到**自己的实测提前量曲线**（这是把本次结论从"机制推导"升级为"实测量化"的唯一办法）。

### 6.2 「这是更新公告」判定规则

```text
栏目强规则：url 含 /news/update/            → 更新类（历史 42/42 更新+维护公告都在此栏目）
关键词强规则：标题匹配
    （更新内容公告|更新公告|版本更新|更新说明|更新预告|停服|停机维护|维护公告|维护通知|临时维护|合服维护）
优先级：
    P0 = 停服|停机|维护|临维            （最急，1–4 天内生效）
    P1 = 更新内容公告|更新公告|版本更新   （0–4 天内生效）
    P2 = 其它 /news/update/ 条目
排除词（避免误报）：
    （奖励发放|获奖名单|公示|兑换码|兑换指引|资产找回|下载指引|渠道.*指引|打击非法第三方|封禁）
```

参考正则：

```regex
^(?=.*(光·遇|光•遇|Sky))(?!.*(奖励发放|获奖名单|公示|兑换码|资产找回|下载指引|打击非法第三方)).*(停服|停机|维护|更新内容公告|更新公告|版本更新|更新说明|更新预告)
```

### 6.3 必须修掉的旧脚本 bug（给 Lead）

`~/.dsh/tools/sky-news.py` 的 `ITEM` 正则：

```python
r'//sky\.163\.com/news/(?P<type>official|activity)/...'   # ← 只有两个栏目
```

应改为（否则**永远抓不到更新公告**）：

```python
r'//sky\.163\.com/news/(?P<type>official|activity|update|strategy)/...'
```

同时 `--type` 的 `choices` 也要补 `update`、`strategy`；`typ` 映射里 `official/update/strategy` 都要给中文名。

---

## 7. 复现命令清单（可直接复制）

```bash
# 1) 官网主列表 + 缓存头
curl -sS -A "Mozilla/5.0" -D - -o /dev/null "https://sky.163.com/news/"

# 2) 破 CDN 缓存（Age 变 0 = 回源）
curl -sS -A "Mozilla/5.0" -D - -o /dev/null "https://sky.163.com/news/?t=$(date +%s)"

# 3) update 栏目（更新/维护公告就在这里）
curl -sS -A "Mozilla/5.0" "https://sky.163.com/news/update/" | grep -o '//sky\.163\.com/news/update/[^"]*'

# 4) ★ 首选：sixhorse JSON（秒级时间戳，无 CDN 缓存）
curl -sS "https://sixhorse.game.163.com/news/outer/newslist_search.do?start=1&limit=10&contentkind=26689,26690,26691,29298"

# 5) 关键词探针（维护类）
curl -sS --get --data-urlencode "keyword=维护" \
  "https://sixhorse.game.163.com/news/outer/newslist_search.do?start=1&limit=20&contentkind=26689,26690,26691,29298"

# 6) 日历静态数据（已验证存在，但内容陈旧）
curl -sS "https://sky.h5.163.com/static/cfg/calendar.json" -o cal.json

# 7) 国际服已知问题（-L 必跟，才能从 301 到达真实页）
curl -sS -L "https://thatgamecompany.helpshift.com/a/sky-children-of-the-light/?p=all&s=known-issues" -o hs.html
```

---

## 8. 未做 / 不能做的事（边界声明）

- 未做 CMS 后台接口枚举、未做参数爆破、未登录任何账号 —— 因此"是否存在未公开但可读的草稿接口"**无法断言，只能说没找到**。
- 游戏内公告（`GetPatchNoticeFromGame` 那条链路）**未做运行时验证**：需要登录态与自定义协议，本次调研被限定为只读 GET。
- 微博若要自动化，需要完成一次访客 Cookie 握手（`visitor.passport.weibo.cn`）+ 复用 cookie 调 `m.weibo.cn` 接口；**本次判定为"否（脆弱）"**，未实现。
- 若后续要**实测**提前量（而不是机制推导），唯一办法是让轮询程序跑起来：记录每条公告的 `showtime` 与"首次观测到的时间"，积累 1–2 周即可得到真实分布。
