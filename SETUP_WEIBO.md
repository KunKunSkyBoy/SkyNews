# 微博动态接入：小白 3 步教程

> 目标：让 App 的「动态」页也能看到**微博官方号**（weibo.com/skygy）的内容。
> 你只需要做一件事：**复制一段 Cookie**，然后运行一条命令。全程约 2 分钟。

---

## 第 1 步：登录微博（如果还没登录）

打开浏览器访问 <https://weibo.com>，用你自己的微博账号登录（没有就注册一个，不影响任何游戏账号）。

## 第 2 步：复制 Cookie

1. 在微博页面按 **F12**（笔记本可能要按 `Fn + F12`），打开开发者工具；
2. 点顶部 **网络 / Network** 标签；
3. 按 **F5 刷新页面**；
4. 在请求列表里点第一行（域名是 `weibo.com` 或 `sina.com.cn` 的请求）；
5. 右侧选 **标头 / Headers**，往下找到 **请求标头 / Request Headers**；
6. 找到以 `Cookie:` 开头的那一行，**把冒号后面整段内容复制下来**（很长，以 `SUB=` 开头就对了）。

> 找不到 `Cookie:` 行？把「标头」面板往下滚，或者换个请求（比如 `weibo.com/ajax/...`）再找。

## 第 3 步：运行一条命令

把下面命令里的 `粘贴你复制的Cookie` 替换成第 2 步复制的内容，粘贴到 Windows PowerShell 里回车：

```powershell
cd "E:\gongzuoqu\SkyNews\SkyNews-20261111(1)\SkyNews"
$env:SKYNEWS_GH_TOKEN="你的GitHub令牌"    # 就是之前给我那个，或重新生成一个
powershell -ExecutionPolicy Bypass -File tools\set_weibo_cookie.ps1 -Cookie "粘贴你复制的Cookie"
```

脚本会自动：
1. 用 GitHub 官方加密方式把 Cookie 写进仓库 Secret（**不会进代码、不会公开**）；
2. 触发一次抓取；
3. 打印结果：`微博抓取成功: N 条` 就说明成了。

然后打开 App → 动态页 → 刷新，就能看到微博内容（中继每 10 分钟自动更新）。

---

## 常见问题

| 问题 | 解决 |
|---|---|
| `Cookie 里没有 SUB=` | 复制错了，重看第 2 步，要复制**请求标头**里的 Cookie 整行 |
| 微博仍失败 / 抓取 0 条 | Cookie 不是登录态（没登录微博）或已过期 → 重新登录再复制 |
| Cookie 会泄露吗？ | 只存在 GitHub 加密 Secret 里，仓库文件和代码里都没有；但它能代表你的微博登录，**不要发给别人** |
| 多久失效？ | 一般几周；过期后重跑第 3 步即可 |

---

## 想同时接入抖音 / 小红书？

那两个平台需要「自建 RSSHub + 平台 Cookie + 账号路由」，小白不建议折腾；目前 App 已覆盖：官网 / TapTap / B站（本地抓）/ 公告，够用。

如果以后想加，配置写在 [`relay/SOURCES.md`](../relay/SOURCES.md)。
