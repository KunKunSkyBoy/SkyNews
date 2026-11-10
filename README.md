# 光遇公告（SkyNews）

一个不依赖 Gradle 的最小安卓 App：抓《光·遇》国服的**公告 + 官方动态**，列表展示，发现新条目发系统通知。

- 「公告」页：网易官方公告 JSON（正文变更即通知）
- 「动态」页：官网新闻流（公告/更新/活动/攻略）+ B 站官方账号动态，统一时间线（2026-11 新增）
- 「适龄提示」页已被「动态」替代（数据仍会在抓取时入库，只是不再单独展示）

## 目录

```
SkyNews/
├── app/
│   ├── AndroidManifest.xml                     # 清单（package=com.dsh.skynews，minSdk 26 / targetSdk 32）
│   ├── src/com/dsh/skynews/MainActivity.java   # 全部代码（UI + 抓取 + 存储 + 通知 + 定时任务）
│   └── debug.keystore                          # 首次构建自动生成
├── build.sh                                    # javac → aapt2 → d8 → apksigner
└── skynews.apk                                 # 构建产物（已签名）
```

## 构建

```bash
# 先确认工具链（这台新装默认没有）：JDK 17 / aapt2 / d8 / apksigner
bash /storage/emulated/0/MT2/mcp/skyforge-mcp/setup-env.sh openjdk-17 aapt2 d8 apksigner
# 再打包
bash /storage/emulated/0/MT2/mcp/SkyNews/build.sh
```

工具链全部在本机（无需电脑、无需 Gradle）：

| 步骤 | 工具 | 位置 |
|---|---|---|
| 编译 | javac (JDK 21) | `$PREFIX/lib/jvm/bin/javac` |
| 清单打包 | aapt2 2.20 (Termux 包，aarch64 原生) | `$PREFIX/bin/aapt2` |
| 转 dex | d8 (纯 Java) | `$PREFIX/share/java/d8.jar` |
| 签名 | apksigner (纯 Java) | `$PREFIX/share/java/apksigner.jar` |
| 编译目标 | android.jar (API 36) | `/storage/emulated/0/MT2/mcp/skyforge-mcp/android-sdk/platforms/android-36/android.jar` |

## 动态页（v2.1）

| 源 | 实现 | 登录 | 实测 |
|---|---|---|---|
| TapTap 官方帖 | `www.taptap.cn/webapiv2/feed/v7/by-group?group_id=47615&type=official`（必须带 `X-UA`，缺了 400） | 不需要 | 200，官方帖 total **2670**，每次 10 条（社区官方号「Sky光·遇」） |
| 官网新闻 | `sixhorse.game.163.com/news/outer/newslist_search.do` JSON | 不需要 | 200，30 条/次 |
| B 站官方 | `api.bilibili.com/x/polymer/web-dynamic/v1/feed/space` + WBI 签名 + `buvid3`+`buvid4` 访客 cookie | 不需要 | 200，26 条/次（mid `211700578` 光遇手游，认证 2.6M 粉） |
| 公告正文 | `ma75.update.netease.com/.../announcement_live.json` | 不需要 | 已有的公告源，合并进时间线 |

WBI 签名（B 站 2023 起空间接口必需）在 `MainActivity.wbiQuery()` 里实现：取 `nav` 的 `wbi_img`
的 img/sub key，按固定置换表拼 mixinKey，参数排序 + `wts` 后 md5 得 `w_rid`。签名与请求都已在
JDK 17 上跑过同一份代码做验证（200 / `code:0` / 26 items）。

**B 站的两个坑（都实测过）**：
1. 必须同时带 `buvid3` 和 `buvid4`（只带 b3 会概率性返回空列表）；
2. 请求头带 `Accept-Encoding: gzip` 时接口返回 **空列表**（同一 URL 改 `identity` 就恢复 26 条）——
   所以抓取用的 `http()` 重载显式发 `identity`。

**抓不到的源（当前手机网络实测）**：

| 平台 | 现象 | 原因 |
|---|---|---|
| 微博 `m.weibo.cn/api/...`（官方 UID **6355968578**，即 weibo.com/skygy） | HTTP **432**（带浏览器 UA/Referer、拿过访客 SUBP cookie 也一样） | 运营商出口 IP 被微博风控；`weibo.com/ajax/statuses/mymblog` 带匿名 cookie 也是 403「请登录后使用」 |
| 微博 `weibo.cn` / `m.weibo.cn/u/...` | 200 但返回「登录 / Sina Visitor System」 | 需要登录态 |
| 抖音 | `aweme/v1/web/user/profile/other/` 200 但 0 字节，页面是 `_$jsvmprt` JS 壳 | 需要 a_bogus / ttwid 签名 |
| 小红书 | `edith.xiaohongshu.com/api/sns/web/v1/user_posted` → **406** `{"code":-1}` | 需要 x-s/x-t + a1 签名 |
| 微信公众号 | 搜狗微信搜索 200 但「暂无相关官方认证订阅号」 | 无公开 feed |
| 公共 RSSHub 实例（rsshub.app 等 9 个） | 000 / 503 | 本机网络不可达 |

所以 v2.1 里微博只作为「设置 → 官方动态源」里的手动入口；要覆盖微博/抖音/小红书，需要一个
**能访问这些平台的云端中继**（自建 RSSHub 或 GitHub Actions），再由 App 拉中继的 RSS/JSON。

## 功能

- **动态**：官网新闻 + B 站动态合并成一条时间线，按时间倒序，点击跳原文；有新条目发通知（首次运行不刷屏）
- **列表**：显示「日期 [公告/活动] + 标题」，按官网顺序（新→旧），点条目用浏览器打开原文。
- **刷新**：手动抓取；启动时自动抓一次。
- **后台轮询**：`JobScheduler` 每 15 分钟一次（系统允许的最小周期），有网即抓，开机自动恢复。
- **通知**：发现新条目发系统通知（首次运行只入库、不刷通知，避免装完就炸一屏）。
- **存储**：`SharedPreferences`（已读 id 集合 + 列表快照），断网也能看到上次数据。

## 抓取实现

**主源 = 官网列表页 JS 里调用的 JSON 接口**（比 HTML 列表更早、更准）：

```
https://sixhorse.game.163.com/news/outer/newslist_search.do?start=1&limit=40&contentkind=26689,26690,26691,29298
```

- 返回 `{result:[{code,title,url,showtime,updateTime,...}],count:685}`，`showtime` 是**毫秒级时间戳**
- 响应头**没有 cache-control**（直连 nginx），而 HTML 列表页是 `max-age=300` 的 CDN 缓存 ⇒ 同一条公告最多能早 **300 秒**被察觉
- `contentkind` 含义：`26689`=公告(official, 350 条) `26690`=更新(update, 247) `26691`=活动(activity, 69) `29298`=攻略(strategy, 19)
- 去重键用 `code`；栏目靠 `url` 里的 `/news/(official|update|activity|strategy)/` 判定（API 不回栏目字段）

**兜底 = HTML 列表页**（接口挂了才走）：

1. 取 `https://sky.163.com/news/`（首页）与 `index_2.html`（第二页）
2. 正则抽 `<a href="//sky.163.com/news/{official|update|activity|strategy}/{YYYYMMDD}/{id}.html">` 块
3. 块内 `<div class="text"><div>日期</div><div>标题</div><div>摘要</div>`
4. 以最后一段 `id` 去重，与已读集合比对得出新条目

更早来源的完整调研见 [RESEARCH-提前公告.md](RESEARCH-提前公告.md)（结论：**没有**比官网更早的公开可读源；公告是定时预发布，`updateTime` 常比 `showtime` 早 13 小时以上，但没有公开草稿接口）。

## 云中继（relay/，v2.3 新增）

手机直连拿不到微博 / 抖音 / 小红书，`relay/` 把抓取搬到云端，App 只拉一份统一 JSON：

- `relay/relay.py`：**纯标准库**，输出 `docs/feed.json`（App 用）+ `docs/feed.xml`（RSS）
- `.github/workflows/relay.yml`：每 10 分钟跑一次并提交到 `docs/`，GitHub Pages 直接给出 URL
- 源：TapTap / 官网 / B 站（零配置）· 微博（`WEIBO_COOKIE`）· 抖音/小红书/微博兜底（`RSSHUB_BASE`）
- 本地实测：**53 条**（官网 30 + B 站 13 + TapTap 10）；没配的源只写进 `problems`，不影响其它源
- GitHub Actions 上实测：官网/TapTap 正常，**B 站返回 412**（境外 IP 被 B 站地域拦截）——B 站条目由 App 本地抓，中继里缺它不影响
- App 端：设置 → **云中继地址** 填 `https://<user>.github.io/<repo>/feed.json`，条目以「中继」来源并入
- **已部署实例**：<https://kunkunskyboy.github.io/SkyNews/feed.json>（GitHub Pages，App v2.4 起默认用它）
- App 端按 id 去重：中继里的官网/TapTap/B站条目与本地抓到的同 id 条目只保留一条

部署步骤见 [`relay/README.md`](relay/README.md)。

## 开源方案调研（2026-11-10）

「全网抓官方账号动态」这个需求社区已有现成轮子，按平台列一下（都不适合直接塞进这个纯 Java 小 App，
但可以作为云端中继的底座）：

| 项目 | 覆盖 | 依赖 | 备注 |
|---|---|---|---|
| [RSSHub](https://github.com/DIYgod/RSSHub) | 微博 / B 站 / 小红书 / 抖音 / TapTap 等上百站点 | **AGPL-3.0**，46.9k★；weibo/user 需 `WEIBO_COOKIES`+Puppeteer，bilibili/user/dynamic 需 `BILIBILI_COOKIE_*`+Puppeteer，小红书/抖音需 cookie/浏览器；`taptap/topic` 零配置 | 最通用；公共 rsshub.app 本网络 30s×3 全超时，只能自建 |
| [crawl4weibo](https://github.com/Praeviso/crawl4weibo) | 微博用户时间线 | MIT，44★；默认 Playwright 取 cookie | 轻量爬虫 |
| [@hkai-ai/weibo-api](https://www.npmjs.com/package/@hkai-ai/weibo-api) | 微博（封装 m.weibo.cn） | MIT，npm 1.1.7，2025-06 后停更；需 cookie | 直接对应我们被 432 的那个接口 |
| [bilibili-api-python](https://github.com/Nemo2011/bilibili-api) | B 站（含 WBI 签名） | 4,155★，**2026-07-06 已归档**；需 cookie | 我们手写的 WBI 与它同源 |
| [nonebot-plugin-bilidyn](https://pypi.org/project/nonebot-plugin-bilidyn/) | B 站 UP 主动态订阅 | PyPI 0.4.1，原 GitHub 仓库已 404 | 已失效，仅作参考 |
| [koishi-plugin-sky-renwu-weibo](https://gitee.com/vincent-zyu/koishi-plugin-sky-renwu-weibo) | 光遇国服每日任务（抓微博博主） | 2★，2026-11 仍更新 | 光遇社区先例，但抓的是个人号不是官方号 |
| [RSSHub Radar](https://github.com/DIYgod/RSSHub-Radar) | 发现站点 RSS | 浏览器扩展 | 找源用 |

结论：**TapTap（零配置）、B 站（WBI）、官网接口可以在手机端直接抓；微博/抖音/小红书不行**——它们
要么风控 IP、要么要签名/登录。要「全网」，正确做法是自建 RSSHub/GitHub Actions 中继，App 只拉 RSS。
完整调研（含每条 curl 实测、账号 ID 核验）在 `$HOME/tmp/sky-official-dynamics-report.md`。

## 已知边界

- 「提前获取更新公告」= **抢先**（15 分钟级轮询 + 首次启动立即抓），不是拿到官方未发布的稿子。走 JSON 接口已能消掉 HTML 那 5 分钟的 CDN 缓存延迟；更早的来源见 `RESEARCH-提前公告.md`。
- 系统在省电模式下可能推迟 JobScheduler（安卓允许的最小周期就是 15 分钟）；想要更准时需改成前台服务（未做，避免常驻通知）。
- 不带图标/资源，纯代码 UI；想加图标把 PNG 放进 res 再改 build.sh 的 aapt2 参数即可。
- 动态页的 B 站源依赖 `buvid3` 访客 cookie + WBI 签名，B 站改签名规则时需要同步更新 `wbiQuery()`；
  微博源在当前网络被 432 风控，App 内不做重试轰炸。
