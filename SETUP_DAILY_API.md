# 每日任务推送：已接入免费公共 API（无需注册、无需密钥）

> 好消息：不用独角兽API 的 key 了。已接入 **skydaily.nankki.com** 的公开接口，免费、无需注册、无需密钥，直接返回光遇国服今日每日任务、大蜡烛、天气、公告等。

## 用到的接口（实测可用）

| 功能 | 接口 |
|---|---|
| 今日完整数据 | https://skydaily.nankki.com/v1/daily |
| 今日任务列表 | https://skydaily.nankki.com/v1/daily/tasks |
| 蜡烛（大蜡烛位置） | https://skydaily.nankki.com/v1/daily/candles |
| 今日天气 | https://skydaily.nankki.com/v1/daily/weather |
| 本月日历 | https://skydaily.nankki.com/v1/daily/calendar |
| 当前公告 | https://skydaily.nankki.com/v1/notice |

> 限速：同一 IP 每分钟约 30 次，超限返回 429；中继 10 分钟一次完全够用。

## 现在就能用（v3.0）

1. 中继每 10 分钟抓一次，「动态」页会出现 **「11月12日 每日任务」** 卡片（4 个任务 + 大蜡烛位置）
2. 「设置 → 每日任务提醒」打开并设时间，到点通知里**直接显示今日任务内容**
3. 不需要任何 key、不需要注册

## 可选：其它数据

同接口还有天气、日历、公告、蜡烛，后续想加到 App 里再说；现在先保证每日任务推送可用。
