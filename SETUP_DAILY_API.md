# 每日任务推送接入：独角兽API（免费）

> 目标：让 App 每天自动显示/推送**今日光遇每日任务 + 大蜡烛位置**，而不是只有提醒。
> 数据源：[独角兽API](https://www.ovoav.com/) 的「光遇每日任务文字」接口（免费密钥）。

## 已接好的接口

| 功能 | 接口 |
|---|---|
| 每日任务（文字） | `https://ovoav.com/api/sky/rwwz/rw1?key=你的密钥` |
| 每日任务（备用） | `https://ovoav.com/api/sky/rwwz/rw` |
| 大蜡烛位置 | `https://ovoav.com/api/sky/rwby/dl?key=你的密钥` |
| 季节蜡烛位置 | `https://ovoav.com/api/sky/rwby/jl?key=你的密钥` |

> 接口本身免费，但**需要一个 key**（注册账号后在控制台免费申请）。

## 3 步开通（小白版）

1. 打开 <https://www.ovoav.com/user/register> 免费注册（邮箱或 QQ 都行）
2. 登录后进「控制台 → 密钥管理」，**复制你的 key**
3. 运行下面任一方式写入仓库 Secret：

**方式 A（推荐，一条命令自动写入 + 触发抓取）：**

```powershell
cd "E:\gongzuoqu\SkyNews\SkyNews-20261111(1)\SkyNews"
$env:SKYNEWS_GH_TOKEN="你的GitHub令牌"
powershell -ExecutionPolicy Bypass -File tools\set_daily_key.ps1 -Key "你的独角兽API密钥"
```

**方式 B（手动）：** GitHub 仓库 → Settings → Secrets and variables → Actions → New repository secret：
- Name: `UNICORN_KEY`
- Value: 你的密钥

## 生效后

- 中继每 10 分钟抓一次，feed 里多出「每日任务」源（文字任务 + 大蜡烛）
- App「动态」页能看到「X月X日 每日任务」卡片（全文）
- App「设置 → 每日任务提醒」到点推送的通知里**直接带今日任务内容**（不再只是提醒）

## 备注

- key 只存在 GitHub 加密 Secret，不会写进代码/仓库
- 免费额度有限（每日次数），10 分钟一次大约每天 144 次；如果超限，可把中继频率调低或只在早上抓一次
- 密钥过期/失效时，该源会显示「失败」，其它源不受影响
