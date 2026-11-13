#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""SkyNews 云中继 v2：多源抓取 + 稳定性框架（每源独立超时/重试/退避/缓存回退）。

设计目标（task-32）：
  * 纯标准库，GitHub Actions 零依赖；
  * 每个源独立：独立超时（默认 8s）、重试 3 次（指数退避+抖动），单源失败不影响其它源；
  * 输出 schema:2 —— sources{name:{ok,count,error,elapsed_ms,last_success}} + items（跨源去重）；
  * 某源本次失败时，沿用上一次成功抓到的该源条目（items 打 stale=true），条目不会凭空消失；
  * 源开关：SKYNEWS_SOURCES=tap,official,bili,weibo,douyin,xhs（默认全部）；
  * 扩展：微博（WEIBO_COOKIE）/ 抖音 / 小红书 / TapTap 热帖（RSSHUB_BASE 路由）、B 站 412 缓解。

条目字段（同时保留旧字段供 App v1 兼容）：
  id, source, title, url, time(ISO8601 UTC), summary, stale?
  兼容别名：src(=source), ts(epoch), t(本地时间文本), text(title+summary)

用法：
  python3 relay.py --out docs --print     # 抓取并写 docs/feed.json + docs/feed.xml
  python3 relay.py --self-test            # 离线自检（不联网，CI 里先跑）
  python3 relay.py --out docs --no-cache  # 不用上次结果做失败回退
"""
import argparse
import base64
import hashlib
import io
import html as html_mod
import html
import json
import os
import random
import re
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime, timezone, timedelta
from email.utils import parsedate_to_datetime

SCHEMA = 2
MAX_ITEMS_DEFAULT = 200
DEFAULT_TIMEOUT = 8.0
DEFAULT_RETRIES = 3
DEFAULT_TOTAL_TIMEOUT = 60.0

# 多套 UA（B 站/微博 412 缓解；每次重试轮换）
UAS = [
    ("Mozilla/5.0 (Linux; Android 13; SM-G991B) AppleWebKit/537.36 "
     "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"),
    ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
     "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"),
    ("Mozilla/5.0 (iPhone; CPU iPhone OS 17_2 like Mac OS X) AppleWebKit/605.1.15 "
     "(KHTML, like Gecko) Version/17.2 Mobile/15E148 Safari/604.1"),
]

OFFICIAL_NEWS = ("https://sixhorse.game.163.com/news/outer/newslist_search.do"
                 "?start=1&limit=30&contentkind=26689,26690,26691,29298")
TAPTAP_FEED = ("https://www.taptap.cn/webapiv2/feed/v7/by-group?group_id=47615"
               "&type=official&sort=created"
               "&X-UA=V%3D1%26PN%3DWebApp%26VN%3D0.1.0%26LANG%3Dzh_CN%26PLT%3DPC")
BILI_MID = 211700578
WEIBO_UID = 6355968578

MIXIN = [46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35, 27, 43,
         5, 49, 33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13, 37, 48, 7, 16,
         24, 55, 40, 61, 26, 17, 0, 1, 60, 51, 30, 4, 22, 25, 54, 21, 56, 59,
         6, 63, 57, 62, 11, 36, 20, 34, 44, 52]

SOURCE_LABEL = {"official": "官网", "taptap": "TapTap", "taptap_hot": "TapTap热帖",
    "daily": "每日任务",
    "extra": "速览", "calendar": "日历",
                "bili": "B站", "weibo": "微博", "douyin": "抖音", "xhs": "小红书"}


# ============================================================ 基础工具
def env_bool(name, default=False):
    v = (os.environ.get(name) or "").strip().lower()
    if not v:
        return default
    return v in ("1", "true", "yes", "on")


def env_float(name, default):
    try:
        return float(os.environ.get(name) or default)
    except (TypeError, ValueError):
        return default


def env_int(name, default):
    try:
        return int(os.environ.get(name) or default)
    except (TypeError, ValueError):
        return default


def now_iso():
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def ts_to_text(ts):
    if not ts:
        return ""
    try:
        return time.strftime("%m-%d %H:%M", time.localtime(int(ts)))
    except (TypeError, ValueError, OSError):
        return ""


def clean_text(s, limit=600):
    s = re.sub(r"<[^>]+>", " ", str(s or ""))
    s = re.sub(r"\s+", " ", s).strip()
    return s[:limit]


_CANON_TRACKING = re.compile(r"(utm_[^=&]+|[?&](?:from|share|spm|ref|source)=[^&]*)")


def canon_url(url):
    url = (url or "").strip()
    if not url:
        return ""
    if url.startswith("//"):
        url = "https:" + url
    try:
        p = urllib.parse.urlsplit(url)
        host = (p.hostname or "").lower()
        if not host:
            return url
        q = urllib.parse.parse_qsl(p.query, keep_blank_values=False)
        q = [(k, v) for (k, v) in q if not k.lower().startswith("utm_")
             and k.lower() not in ("from", "share", "spm", "ref", "source")]
        path = re.sub(r"/+", "/", p.path or "/")
        if path != "/":
            path = path.rstrip("/")
        return urllib.parse.urlunsplit((p.scheme or "https", host, path,
                                        urllib.parse.urlencode(q), ""))
    except ValueError:
        return url


def make_item(ident, source, title, url, ts=None, summary=""):
    try:
        stamp = int(ts or 0)
    except (TypeError, ValueError):
        stamp = 0
    if stamp <= 0:
        stamp = int(time.time())
        stamp_note = ""
    else:
        stamp_note = ""
    title = clean_text(title, 300)
    summary = clean_text(summary, 4000)
    text = (title + ("\n" + summary if summary and summary != title else "")).strip()
    it = {
        "id": str(ident),
        "source": source,
        "title": title,
        "url": url or "",
        "time": datetime.fromtimestamp(stamp, timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "summary": summary,
        # ---- 旧 App 兼容字段 ----
        "src": SOURCE_LABEL.get(source, source),
        "ts": stamp,
        "t": ts_to_text(stamp),
        "text": text,
    }
    if stamp_note:
        it["time_note"] = stamp_note
    return it


def http_get(url, referer=None, cookie=None, timeout=DEFAULT_TIMEOUT,
             ua_index=0, headers_extra=None):
    """单次 HTTP GET（重试由 run_source 负责）。Accept-Encoding: identity 规避 B 站 gzip 坑。"""
    hdrs = {
        "User-Agent": UAS[ua_index % len(UAS)],
        "Accept": "application/json, text/plain, application/xml, text/xml, */*",
        "Accept-Language": "zh-CN,zh;q=0.9",
        # B站/部分站点在 gzip 响应 + 非浏览器 UA 下返回 412 或坏包，统一 identity
        "Accept-Encoding": "identity",
    }
    if referer:
        hdrs["Referer"] = referer
    if cookie:
        hdrs["Cookie"] = cookie
    if headers_extra:
        hdrs.update(headers_extra)
    req = urllib.request.Request(url, headers=hdrs)
    with urllib.request.urlopen(req, timeout=timeout) as r:
        raw = r.read()
    try:
        return raw.decode("utf-8")
    except UnicodeDecodeError:
        return raw.decode("gbk", "replace")


class NotConfigured(RuntimeError):
    """未配置（不需要重试）：跳过该源，直接记 error。"""


class Http412(RuntimeError):
    pass


def http_get_json(url, **kw):
    txt = http_get(url, **kw)
    try:
        return json.loads(txt)
    except json.JSONDecodeError as e:
        raise RuntimeError("JSON 解析失败: %s" % str(e)[:80]) from e


# ============================================================ 直连源
def src_official():
    data = http_get_json(OFFICIAL_NEWS, referer="https://sky.163.com/news/")
    out = []
    for row in data.get("result", []):
        url = row.get("url", "")
        ts = int(row.get("showtime", 0) / 1000)
        out.append(make_item("gf-" + str(row.get("code", "")), "official",
                             row.get("title", ""), url, ts))
    return out


def src_taptap():
    data = http_get_json(TAPTAP_FEED, referer="https://www.taptap.cn/")
    out = []
    for row in data.get("data", {}).get("list", []):
        m = row.get("moment") or {}
        topic = m.get("topic") or {}
        mid = m.get("id_str", "")
        if not mid:
            continue
        out.append(make_item("tt-" + mid, "taptap",
                             topic.get("title", ""),
                             "https://www.taptap.cn/moment/" + mid,
                             m.get("created_time", 0),
                             topic.get("summary", "")))
    return out


def src_taptap_hot():
    """TapTap 热帖（可选）：需要 RSSHub 路由（SKYNEWS_TAPTAP_HOT_ROUTE），默认关闭。"""
    route = (os.environ.get("SKYNEWS_TAPTAP_HOT_ROUTE") or "").strip()
    base = (os.environ.get("RSSHUB_BASE") or "").rstrip("/")
    if not (base and route):
        raise NotConfigured("未配置 RSSHUB_BASE / SKYNEWS_TAPTAP_HOT_ROUTE")
    return fetch_rss(base + route, "taptap_hot", referer=base + "/")


# ============================================================ RSSHub 源
def parse_rss_items(xml_text, source, referer=None, limit=40):
    root = ET.fromstring(xml_text)
    out = []
    entries = list(root.iter("item")) + list(root.iter("{http://www.w3.org/2005/Atom}entry"))
    for entry in entries[:limit]:
        def _find(tags):
            for t in tags:
                v = entry.findtext(t)
                if v:
                    return v.strip()
            return ""
        title = _find(("title", "{http://www.w3.org/2005/Atom}title"))
        link = _find(("link", "{http://www.w3.org/2005/Atom}link"))
        if not link:
            ln = entry.find("link")
            if ln is not None:
                link = (ln.get("href") or "").strip()
        desc = _find(("description", "summary", "{http://www.w3.org/2005/Atom}summary",
                      "{http://www.w3.org/2005/Atom}content"))
        pub = _find(("pubDate", "updated", "published",
                     "{http://www.w3.org/2005/Atom}updated",
                     "{http://www.w3.org/2005/Atom}published"))
        stamp = 0
        if pub:
            try:
                stamp = int(parsedate_to_datetime(pub).timestamp())
            except (TypeError, ValueError):
                try:
                    stamp = int(datetime.fromisoformat(pub.replace("Z", "+00:00")).timestamp())
                except (TypeError, ValueError):
                    stamp = 0
        key = hashlib.md5((source + "|" + link + "|" + title).encode("utf-8")).hexdigest()[:16]
        out.append(make_item("rh-" + key, source, title, link, stamp, desc))
    return out


def fetch_rss(url, source, referer=None):
    if not url:
        raise RuntimeError("未配置路由")
    xml_text = http_get(url, referer=referer)
    if "<rss" not in xml_text[:2000] and "<feed" not in xml_text[:2000]:
        raise RuntimeError("返回不是 RSS/Atom（%d 字节）" % len(xml_text))
    return parse_rss_items(xml_text, source, referer)


def _weibo_visitor_cookie():
    """自动获取微博访客 Cookie（SUB/SUBP，无需登录）。失败返回空串。

    流程：passport.weibo.com/visitor/genvisitor → 拿 tid → visitor?a=incarnate
    这是微博公开的访客机制，不涉及账号登录。
    """
    import http.cookiejar

    ua = ("Mozilla/5.0 (iPhone; CPU iPhone OS 16_0 like Mac OS X) "
          "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.0 Mobile/15E148 Safari/604.1")
    jar = http.cookiejar.CookieJar()
    opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar))
    try:
        body = urllib.parse.urlencode({
            "cb": "visitor_gray_callback", "tid": "", "from": "weibo", "web_rid": "",
        }).encode()
        req = urllib.request.Request(
            "https://passport.weibo.com/visitor/genvisitor2",
            data=body,
            headers={"User-Agent": ua, "Referer": "https://passport.weibo.com/visitor/visitor",
                     "Content-Type": "application/x-www-form-urlencoded",
                     "Accept-Encoding": "identity"},
            method="POST")
        with opener.open(req, timeout=8) as resp:
            raw = resp.read().decode("utf-8", "replace")
        m = re.search(r'"tid"\s*:\s*"([^"\\]+)"', raw) or re.search(r'tid%22%3A%22([^%]+)', raw)
        if not m:
            return ""
        tid = m.group(1)
        url = ("https://passport.weibo.com/visitor/visitor?a=incarnate&t=" + urllib.parse.quote(tid)
               + "&w=2&c=095&gc=&cb=cross_domain&from=weibo&_rand=" + str(random.random()))
        req2 = urllib.request.Request(url, headers={
            "User-Agent": ua, "Referer": "https://passport.weibo.com/visitor/visitor",
            "Accept-Encoding": "identity"})
        with opener.open(req2, timeout=8) as resp2:
            resp2.read()
        pairs = []
        for c in jar:
            if c.name in ("SUB", "SUBP", "SUB_2", "_T_WM", "XSRF-TOKEN"):
                pairs.append("%s=%s" % (c.name, c.value))
        return "; ".join(pairs)
    except Exception:  # noqa: BLE001
        return ""


def _weibo_web_pages():
    """无需账号 cookie：优先用自动访客 Cookie 访问 m.weibo.cn 公开时间线。"""
    referer = "https://m.weibo.cn/u/%d" % WEIBO_UID
    url = ("https://m.weibo.cn/api/container/getIndex?type=uid&value=%d"
           "&containerid=107603%d" % (WEIBO_UID, WEIBO_UID))
    cookies = [""]
    vc = _weibo_visitor_cookie()
    if vc:
        cookies.insert(0, vc)
    last = None
    for ck in cookies:
        for ua in range(len(UAS)):
            try:
                data = http_get_json(url, referer=referer, ua_index=ua,
                                     cookie=ck or None)
                if int(data.get("ok") or 0) != 1:
                    raise RuntimeError("ok=%s" % data.get("ok"))
                out = []
                for card in data.get("data", {}).get("cards", []):
                    mb = card.get("mblog")
                    if not mb:
                        continue
                    ts = parse_weibo_time(mb.get("created_at"))
                    text = re.sub(r"<[^>]+>", "", mb.get("text", ""))
                    out.append(make_item("wb-" + str(mb.get("id", "")), "weibo",
                                         clean_text(text, 4000),
                                         "https://m.weibo.cn/detail/" + str(mb.get("id", "")),
                                         ts, re.sub(r"<[^>]+>", " ", mb.get("text", ""))))
                if out:
                    return out
                raise RuntimeError("无 mblog")
            except Exception as e:  # noqa: BLE001
                last = e
    raise RuntimeError("公开时间线不可用：%s" % last)



def _strip_html(raw):
    raw = re.sub(r"<script.*?</script>", " ", raw, flags=re.S | re.I)
    raw = re.sub(r"<style.*?</style>", " ", raw, flags=re.S | re.I)
    raw = re.sub(r"<br\s*/?>", "\n", raw, flags=re.I)
    raw = re.sub(r"</p>", "\n", raw, flags=re.I)
    raw = re.sub(r"<[^>]+>", " ", raw)
    raw = html.unescape(raw)
    raw = re.sub(r"[ \t\r\f\v]+", " ", raw)
    raw = re.sub(r" *\n *", "\n", raw)
    raw = re.sub(r"\n{3,}", "\n\n", raw)
    return raw.strip()


def article_text(url, title="", limit=3000):
    """抓原文正文（官网/TapTap 等服务端渲染页面），失败返回空串。"""
    url = canon_url(url)
    if not url.startswith("http"):
        return ""
    try:
        h = http_get(url)
    except Exception:  # noqa: BLE001
        return ""
    m = re.search(r'itemprop="text"[^>]*>(.*?)(?:<!--\]--></div>|$)', h, re.S | re.I)
    raw = m.group(1) if m else h
    txt = _strip_html(raw)
    if len(txt) < 80:
        return ""
    if title:
        t = clean_text(title, 200)
        if txt.startswith(t):
            txt = txt[len(t):].strip()
    # 去掉常见的站点尾部
    for tail in ("前往论坛", "查看游戏", "申请加入测试服", "开发者中心", "TapTap 制造"):
        i = txt.find(tail)
        if i > 200:
            txt = txt[:i].strip()
            break
    return txt[:limit]


def enrich_full_text(items, cfg):
    """把官网/TapTap 的正文抓进 feed：App 里直接读全文，不用再跳浏览器。"""
    targets = [it for it in items
               if it.get("source") in ("official", "taptap")
               and len(it.get("summary") or "") < 600
               and it.get("url")]
    if not targets:
        return items
    max_n = min(len(targets), int(cfg.get("enrich_max", 40)))

    def work(it):
        try:
            body = article_text(it.get("url", ""), it.get("title", ""))
            if body and len(body) > len(it.get("summary") or "") + 40:
                it["summary"] = body[:3000]
                it["text"] = (it.get("title") or "") + "\n\n" + body[:3000]
        except Exception:  # noqa: BLE001
            pass

    with ThreadPoolExecutor(max_workers=6) as ex:
        list(ex.map(work, targets[:max_n]))
    return items


def _collect_daily_strings(obj, out, depth=0):
    """从接口返回 JSON 里递归提取任务描述文本（容错：不同接口字段不一样）。"""
    if depth > 8 or len(out) > 40:
        return
    if isinstance(obj, dict):
        for k, v in obj.items():
            _collect_daily_strings(v, out, depth + 1)
    elif isinstance(obj, list):
        for v in obj:
            _collect_daily_strings(v, out, depth + 1)
    elif isinstance(obj, str):
        t = obj.strip()
        if 4 <= len(t) <= 120 and not t.startswith("http") and t not in out:
            out.append(t)


def _daily_from_oivio():
    """备用源：oivio-up/SkyDailyInfo 的公开 data.json（免费、无需密钥）。"""
    d = http_get_json("https://oivio-up.github.io/SkyDailyInfo/data.json",
                      referer="https://oivio-up.github.io/SkyDailyInfo/")
    tasks = d.get("tasks") or []
    if not tasks:
        raise RuntimeError("oivio 备用源无任务")
    lines = []
    for i, t in enumerate(tasks[:8]):
        txt = (t.get("text") or "").strip()
        if txt:
            lines.append("%d. %s" % (t.get("number") or (i + 1), txt))
    body = "今日每日任务：\n" + "\n".join(lines)
    weather = (d.get("weather") or {}).get("text") or ""
    if weather:
        body += "\n" + weather.strip()
    date = (d.get("date") or "").strip()
    return date, body



def _daily_from_t1qq():
    """备用源：应天API 光遇每日任务（免费 key：SKYNEWS_T1QQ_KEY）。"""
    key = _t1qq_key()
    if not key:
        raise NotConfigured("未配置 SKYNEWS_T1QQ_KEY（应天API 免费密钥）")
    d = http_get_json("https://api.t1qq.com/api/sky/gyrw?key=" + urllib.parse.quote(key),
                      referer="https://api.t1qq.com/")
    rw = d.get("rw") or []
    lines = []
    for i, t in enumerate(rw[:8]):
        txt = (t.get("text") or "").replace("#r", "").strip()
        if txt:
            lines.append("%d. %s" % (i + 1, txt))
    if not lines:
        raise RuntimeError("应天API 返回空任务")
    date = datetime.now(timezone(timedelta(hours=8))).strftime("%Y-%m-%d")  # 北京时间
    return date, "今日每日任务：\n" + "\n".join(lines)



def src_extra():
    """光遇今日速览（应天API：季蜡/大蜡烛位置、免费魔法、季节倒计时）。"""
    key = _t1qq_key()
    if not key:
        raise NotConfigured("未配置 SKYNEWS_T1QQ_KEY（应天API 免费密钥）")
    lines = []
    try:
        d = http_get_json("https://api.t1qq.com/api/sky/sc/lzwz?key=" + urllib.parse.quote(key),
                          referer="https://api.t1qq.com/")
        jl = ((d.get("jlwz") or {}).get("text") or "").strip()
        dl = ((d.get("dlwz") or {}).get("text") or "").strip()
        if jl:
            lines.append("季节蜡烛：" + jl)
        if dl:
            lines.append("大蜡烛：" + dl)
    except Exception:  # noqa: BLE001
        pass
    try:
        d = http_get_json("https://api.t1qq.com/api/sky/mf/mf?key=" + urllib.parse.quote(key),
                          referer="https://api.t1qq.com/")
        mg = [ (m.get("text") or "").strip() for m in (d.get("magic") or []) ]
        mg = [m for m in mg if m]
        if mg:
            lines.append("今日免费魔法：" + "、".join(mg))
    except Exception:  # noqa: BLE001
        pass
    try:
        d = http_get_json("https://api.t1qq.com/api/sky/sc/gf/djs?key=" + urllib.parse.quote(key),
                          referer="https://api.t1qq.com/")
        data = d.get("data") or {}
        inner = data.get("data") or {}
        txt = data.get("days") or ""
        if txt:
            lines.append(txt)
        if inner.get("seasonal"):
            lines.append(str(inner.get("seasonal")))
        if inner.get("grad"):
            lines.append(str(inner.get("grad")))
    except Exception:  # noqa: BLE001
        pass
    try:
        for e in _ics_upcoming(days=5, limit=3):
            lines.append("日程：" + e)
    except Exception:  # noqa: BLE001
        pass
    if not lines:
        raise RuntimeError("速览数据为空")
    label = datetime.now(timezone(timedelta(hours=8))).strftime("%m月%d日")
    return [make_item("extra-" + datetime.now(timezone(timedelta(hours=8))).strftime("%Y%m%d"),
                      "extra", label + " 光遇速览",
                      "https://www.ovoav.com/", None, "\n".join(lines))]



STONE_CONFIG = {
    "slots": {2: [(9, 8), (14, 8), (19, 8)], 3: [(9, 8), (15, 8), (21, 8)],
              5: [(11, 8), (17, 8), (23, 8)], 6: [(10, 8), (14, 8), (22, 8)],
              0: [(7, 8), (13, 8), (19, 8)]},
    "maps": ["霞谷", "暮土", "禁阁", "云野", "雨林"],
    "areas": {
        "霞谷": {2: "滑冰场", 3: "滑冰场", 5: "圆梦村", 6: "圆梦村", 0: "雪隐峰"},
        "暮土": {2: "边陲荒漠", 3: "远古战场", 5: "黑水港湾", 6: "巨兽荒原", 0: "失落方舟"},
        "禁阁": {2: "星光沙漠", 3: "星光沙漠", 5: "商船星球", 6: "商船星球", 0: "商船星球"},
        "云野": {2: "蝴蝶平原", 3: "仙乡", 5: "云顶浮石", 6: "幽光山洞", 0: "圣岛"},
        "雨林": {2: "荧光森林", 3: "密林遗迹", 5: "大树屋", 6: "雨林神殿", 0: "秘密花园"},
    },
    "red": {"first": (6, 0), "second": (5, 0)},
    "black": {"first": (2,), "second": (3,)},
    "boundary": 15,
}
TZ8 = timezone(timedelta(hours=8))


def _events_snapshot_path():
    return os.path.join(os.path.dirname(os.path.abspath(__file__)), "data", "events.json")


def _github_json(path):
    """用 GITHUB_TOKEN 从 mo-sky-stones 数据仓同步 events.json（自建用数据快照）。"""
    tok = (os.environ.get("GITHUB_TOKEN") or os.environ.get("SKYNEWS_GH_TOKEN") or "").strip()
    if not tok:
        return None
    try:
        req = urllib.request.Request(
            "https://api.github.com/repos/mosyoyo/mo-sky-stones/contents/" + path,
            headers={"Authorization": "Bearer " + tok, "User-Agent": "skynews-relay",
                     "Accept": "application/vnd.github+json"})
        with urllib.request.urlopen(req, timeout=20) as r:
            d = json.loads(r.read().decode("utf-8"))
        return base64.b64decode(d.get("content") or "").decode("utf-8", "replace")
    except Exception:  # noqa: BLE001
        return None


def _load_events():
    """读取社区活动/复刻数据快照；有 token 时同步最新到 relay/data/events.json。"""
    raw = _github_json("data/events.json")
    local = _events_snapshot_path()
    if raw:
        try:
            new = json.loads(raw)
            if isinstance(new, list) and new:
                cur = ""
                if os.path.exists(local):
                    cur = io.open(local, encoding="utf-8").read()
                if raw != cur:
                    os.makedirs(os.path.dirname(local), exist_ok=True)
                    atomic_write(local, raw)
                return new
        except Exception:  # noqa: BLE001
            pass
    try:
        if os.path.exists(local):
            d = json.loads(io.open(local, encoding="utf-8").read())
            if isinstance(d, list):
                return d
    except Exception:  # noqa: BLE001
        pass
    return []


def _calendar_entries(days=10, limit=8):
    """自建日历：红石/黑石时间 + 活动/复刻结束时间（北京时区）。"""
    now = datetime.now(TZ8)
    out = []
    for i in range(days + 1):
        d = (now + timedelta(days=i)).date()
        dow = (d.weekday() + 1) % 7  # 0=周日
        first = d.day <= STONE_CONFIG["boundary"]
        typ = None
        if first:
            if dow in STONE_CONFIG["red"]["first"]:
                typ = "red"
            elif dow in STONE_CONFIG["black"]["first"]:
                typ = "black"
        else:
            if dow in STONE_CONFIG["red"]["second"]:
                typ = "red"
            elif dow in STONE_CONFIG["black"]["second"]:
                typ = "black"
        if typ:
            mp = STONE_CONFIG["maps"][d.day % 5]
            area = STONE_CONFIG["areas"].get(mp, {}).get(dow, "")
            for (hh, mm) in STONE_CONFIG["slots"].get(dow, []):
                dt = datetime(d.year, d.month, d.day, hh, mm, tzinfo=TZ8)
                name = "红石" if typ == "red" else "黑石"
                out.append((dt, "%d月%d日 %02d:%02d 【%s】%s·%s"
                            % (d.month, d.day, hh, mm, name, mp, area)))
    # 活动/复刻（只列结束提醒）
    for ev in _load_events():
        if not ev.get("enabled", True):
            continue
        et = (ev.get("type") or "").strip()
        if et not in ("traveling_spirit", "season", "activity", "bonus"):
            continue
        end = (ev.get("end") or "").strip()
        try:
            dt = datetime.strptime(end.replace("Z", "+0000")[:19].replace("T", " "),
                                   "%Y-%m-%d %H:%M:%S").replace(tzinfo=timezone.utc).astimezone(TZ8)
        except Exception:  # noqa: BLE001
            continue
        if dt < now or dt > now + timedelta(days=days):
            continue
        title = (ev.get("title") or "").strip()
        if not title:
            continue
        out.append((dt, "%d月%d日 %02d:%02d 【结束】%s"
                    % (dt.month, dt.day, dt.hour, dt.minute, title.replace("【", "").replace("】", " ").strip())))
    out.sort(key=lambda x: x[0])
    return out[:limit]


def _ics_upcoming(days=10, limit=6):
    """兼容旧接口：优先自建引擎，失败才回退 sky-ics。"""
    try:
        evs = _calendar_entries(days=days, limit=limit)
        if evs:
            return [e[1] for e in evs]
    except Exception:  # noqa: BLE001
        pass
    # 回退：sky-ics 第三方
    try:
        url = ("https://sky-ics.pages.dev/calendar.ics?types=red,traveling_spirit,season,activity"
               "&endOnly=traveling_spirit,season,activity")
        txt = http_get_bytes(url, referer="https://sky-ics.pages.dev/").decode("utf-8", "replace")
        txt = txt.replace("\r\n", "\n").replace("\r", "\n")
        lines = []
        for ln in txt.split("\n"):
            if ln.startswith((" ", "\t")) and lines:
                lines[-1] += ln[1:]
            else:
                lines.append(ln)
        events = []
        cur = None
        for ln in lines:
            if ln == "BEGIN:VEVENT":
                cur = {}
            elif ln == "END:VEVENT":
                if cur:
                    events.append(cur)
                cur = None
            elif cur is not None and ":" in ln:
                k, v = ln.split(":", 1)
                cur[k.split(";")[0]] = v
        now = datetime.now(timezone.utc)
        res = []
        for ev in events:
            ds = (ev.get("DTSTART") or "").strip()
            try:
                dt = datetime.strptime(ds, "%Y%m%dT%H%M%SZ").replace(tzinfo=timezone.utc)
            except Exception:  # noqa: BLE001
                continue
            if dt < now - timedelta(hours=2) or dt > now + timedelta(days=days):
                continue
            bj = dt.astimezone(TZ8)
            res.append((dt, "%d月%d日 %02d:%02d %s" % (bj.month, bj.day, bj.hour, bj.minute,
                                                      (ev.get("SUMMARY") or "").strip())))
        res.sort(key=lambda x: x[0])
        return [x[1] for x in res[:limit]]
    except Exception:  # noqa: BLE001
        return []


def write_calendar_ics(out_dir):
    """生成我们自己的 docs/calendar.ics（可订阅，自建，不再依赖第三方页面）。"""
    evs = _calendar_entries(days=60, limit=400)
    if not evs:
        return
    def fmt(dt):
        return dt.astimezone(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    lines = ["BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//SkyNews//光遇日历//ZH",
             "CALSCALE:GREGORIAN", "METHOD:PUBLISH", "X-WR-CALNAME:光遇·红石/活动",
             "X-WR-TIMEZONE:Asia/Shanghai", "REFRESH-INTERVAL;VALUE=DURATION:PT1H"]
    for dt, label in evs:
        summary = label.split("】")[-1] if "】" in label else label
        typ = "红石" if "红石" in label else ("黑石" if "黑石" in label else "日程")
        lines += ["BEGIN:VEVENT",
                  "UID:%s-%s@skynews" % (fmt(dt), abs(hash(label)) % 10 ** 8),
                  "DTSTAMP:" + fmt(datetime.now(timezone.utc)),
                  "DTSTART:" + fmt(dt), "DTEND:" + fmt(dt + timedelta(minutes=52)),
                  "SUMMARY:【%s】%s" % (typ, summary),
                  "DESCRIPTION:" + label, "END:VEVENT"]
    lines.append("END:VCALENDAR")
    atomic_write(os.path.join(out_dir, "calendar.ics"), "\r\n".join(lines) + "\r\n")
    print("calendar.ics written:", len(evs), "events")



def src_calendar():
    """光遇日历（红石/活动/旅行先祖，ICS 免费源）。"""
    evs = _ics_upcoming()
    if not evs:
        raise RuntimeError("日历接口暂无未来事件")
    lines = ["接下来 " + str(len(evs)) + " 项："] + ["· " + e for e in evs]
    label = datetime.now(timezone(timedelta(hours=8))).strftime("%m月%d日")
    return [make_item("cal-" + datetime.now(timezone(timedelta(hours=8))).strftime("%Y%m%d"),
                      "calendar", label + " 光遇日历", "https://sky-ics.pages.dev/", None,
                      "\n".join(lines))]


def src_daily():
    """光遇每日任务（主：skydaily.nankki.com 免费 API；备：oivio 公开 data.json）。"""
    date, body = "", ""
    last_err = None
    try:
        d = http_get_json("https://skydaily.nankki.com/v1/daily",
                          referer="https://skydaily.nankki.com/")
        data = d.get("data") or {}
        tasks = data.get("tasks") or []
        if tasks:
            date = d.get("date") or ""
            lines = []
            for i, t in enumerate(tasks[:8]):
                txt = (t.get("text") or "").strip()
                if txt:
                    lines.append("%d. %s" % (t.get("number") or (i + 1), txt))
            body = "今日每日任务：\n" + "\n".join(lines)
            try:
                c = http_get_json("https://skydaily.nankki.com/v1/daily/candles",
                                  referer="https://skydaily.nankki.com/")
                for e in (c.get("data") or [])[:4]:
                    title = (e.get("title") or "").strip()
                    txt = (e.get("text") or "").strip()
                    if title and txt:
                        body += "\n" + title + "：" + txt.split("\n")[0][:120]
            except Exception:  # noqa: BLE001
                pass
            try:
                w = http_get_json("https://skydaily.nankki.com/v1/daily/weather",
                                  referer="https://skydaily.nankki.com/")
                wt = ((w.get("data") or {}) if isinstance(w.get("data"), dict) else {}).get("text") or ""
                if wt:
                    body += "\n" + wt.strip()
            except Exception:  # noqa: BLE001
                pass
        else:
            raise RuntimeError("skydaily 返回空任务")
    except Exception as e:  # noqa: BLE001
        last_err = e
        date, body = "", ""
        # 备用源 1：应天API（免费 key，已配置）
        try:
            date, body = _daily_from_t1qq()
        except Exception as e1:  # noqa: BLE001
            last_err = "%s / 应天:%s" % (last_err, e1)
        # 备用源 2：oivio 公开 data.json
        if not body:
            try:
                date, body = _daily_from_oivio()
            except Exception as e2:  # noqa: BLE001
                raise RuntimeError("每日任务主/备源均失败：%s / %s" % (last_err, e2))

    parts = date.split("-")
    label = ("%s月%s日" % (parts[1], parts[2])) if len(parts) == 3 else (date or "今日")
    return [make_item("daily-" + (date or "today").replace("-", ""), "daily",
                      label + " 每日任务", "https://skydaily.nankki.com/", None, body)]



def src_weibo():
    cookie = (os.environ.get("WEIBO_COOKIE") or "").strip()
    base = (os.environ.get("RSSHUB_BASE") or "").rstrip("/")
    if cookie:
        url = ("https://m.weibo.cn/api/container/getIndex?type=uid&value=%d"
               "&containerid=107603%d" % (WEIBO_UID, WEIBO_UID))
        data = http_get_json(url, referer="https://m.weibo.cn/u/%d" % WEIBO_UID, cookie=cookie)
        out = []
        for card in data.get("data", {}).get("cards", []):
            mb = card.get("mblog")
            if not mb:
                continue
            ts = parse_weibo_time(mb.get("created_at"))
            out.append(make_item("wb-" + str(mb.get("id", "")), "weibo",
                                 clean_text(re.sub(r"<[^>]+>", "", mb.get("text", "")), 4000),
                                 "https://m.weibo.cn/detail/" + str(mb.get("id", "")),
                                 ts, re.sub(r"<[^>]+>", " ", mb.get("text", ""))))
        if out:
            return out
        raise RuntimeError("WEIBO_COOKIE 可能已失效（无 mblog）")
    if base:
        route = (os.environ.get("SKYNEWS_WEIBO_ROUTE") or "/weibo/user/%d" % WEIBO_UID).strip()
        try:
            return fetch_rss(base + route, "weibo", referer=base + "/")
        except Exception as e:  # noqa: BLE001
            # 公共 RSSHub 对微博路由多数需要实例侧 cookie；失败时再试公开时间线
            try:
                return _weibo_web_pages()
            except Exception as e2:  # noqa: BLE001
                raise RuntimeError("RSSHub 失败（%s），公开时间线也失败（%s）" % (e, e2))
    try:
        return _weibo_web_pages()
    except Exception as e:  # noqa: BLE001
        raise NotConfigured("未配置 WEIBO_COOKIE/RSSHUB_BASE，公开时间线不可用（%s）" % e)


def parse_weibo_time(s):
    s = (s or "").strip()
    if not s:
        return int(time.time())
    for fmt in ("%a %b %d %H:%M:%S %z %Y",):
        try:
            return int(datetime.strptime(s, fmt).timestamp())
        except ValueError:
            pass
    try:
        return int(parsedate_to_datetime(s).timestamp())
    except (TypeError, ValueError):
        return int(time.time())


def src_douyin():
    base = (os.environ.get("RSSHUB_BASE") or "").rstrip("/")
    route = (os.environ.get("SKYNEWS_DOUYIN_ROUTE") or "").strip()
    if not (base and route):
        raise NotConfigured("未配置 RSSHUB_BASE / SKYNEWS_DOUYIN_ROUTE")
    return fetch_rss(base + route, "douyin", referer=base + "/")


def src_xhs():
    base = (os.environ.get("RSSHUB_BASE") or "").rstrip("/")
    route = (os.environ.get("SKYNEWS_XHS_ROUTE") or "").strip()
    if not (base and route):
        raise NotConfigured("未配置 RSSHUB_BASE / SKYNEWS_XHS_ROUTE")
    return fetch_rss(base + route, "xhs", referer=base + "/")


# ============================================================ B 站（412 缓解）
def _bili_sign(params, img_key, sub_key):
    raw = img_key + sub_key
    mixin = "".join(raw[i] for i in MIXIN if i < len(raw))[:32]
    params = dict(params)
    params["wts"] = int(time.time())
    q = urllib.parse.urlencode(sorted(params.items()))
    params["w_rid"] = hashlib.md5((q + mixin).encode()).hexdigest()
    return urllib.parse.urlencode(sorted(params.items()))


def src_bili():
    # GitHub Actions 海外出口经常 412：默认跳过，除非 SKYNEWS_BILI_CI=1
    if os.environ.get("GITHUB_ACTIONS") == "true" and not env_bool("SKYNEWS_BILI_CI"):
        raise NotConfigured("CI 跳过 B 站（海外 IP 412 风控）；App 本地抓，或设 SKYNEWS_BILI_CI=1 强制")
    last_err = None
    for attempt in range(3):
        try:
            ua = attempt
            spi = http_get_json("https://api.bilibili.com/x/frontend/finger/spi",
                                referer="https://www.bilibili.com/", ua_index=ua)
            sd = spi.get("data") or {}
            b3, b4 = sd.get("b_3", ""), sd.get("b_4", "")
            if not b3:
                raise RuntimeError("spi 无 buvid3")
            cookie = "buvid3=%s; buvid4=%s; b_nut=%d" % (b3, b4, int(time.time()))
            nav = http_get_json("https://api.bilibili.com/x/web-interface/nav",
                                referer="https://www.bilibili.com/", cookie=cookie, ua_index=ua)
            wbi = (nav.get("data") or {}).get("wbi_img") or {}
            img = wbi.get("img_url", "").rsplit("/", 1)[-1].split(".")[0]
            sub = wbi.get("sub_url", "").rsplit("/", 1)[-1].split(".")[0]
            if not (img and sub):
                raise RuntimeError("nav 未取到 wbi 签名 key")
            q = _bili_sign({"host_mid": BILI_MID, "timezone_offset": -480,
                            "features": "itemOpusStyle,listOnlyfans,opusBigCover,onlyfansVote"},
                           img, sub)
            try:
                data = http_get_json(
                    "https://api.bilibili.com/x/polymer/web-dynamic/v1/feed/space?" + q,
                    referer="https://space.bilibili.com/%d/dynamic" % BILI_MID,
                    cookie=cookie, ua_index=ua)
            except urllib.error.HTTPError as e:
                if e.code == 412:
                    raise Http412("412 Precondition Failed") from e
                raise
            if data.get("code") != 0:
                raise RuntimeError("code=%s %s" % (data.get("code"), data.get("message")))
            out = []
            for row in (data.get("data") or {}).get("items", []):
                mod = row.get("modules") or {}
                au = mod.get("module_author") or {}
                dy = mod.get("module_dynamic") or {}
                text = (dy.get("desc") or {}).get("text") or ""
                if not text:
                    major = dy.get("major") or {}
                    opus = major.get("opus") or {}
                    text = (opus.get("summary") or {}).get("text") or \
                        (major.get("archive") or {}).get("title") or ""
                did = row.get("id_str", "")
                out.append(make_item("bl-" + did, "bili", clean_text(text, 4000),
                                     "https://t.bilibili.com/" + did, au.get("pub_ts", 0),
                                     text))
            return out
        except urllib.error.HTTPError as e:
            last_err = "HTTP %s" % e.code
            if e.code == 412:
                time.sleep(1.5 + random.uniform(0, 0.8))
            continue
        except Exception as e:  # noqa: BLE001
            last_err = repr(e)[:120]
            continue
    raise RuntimeError("B站抓取失败（含 412 缓解尝试）: %s" % last_err)


# ============================================================ 源注册表
def build_sources():
    """返回 {name: callable}，按 env SKYNEWS_SOURCES 过滤。"""
    req = (os.environ.get("SKYNEWS_SOURCES") or "tap,official,bili,weibo,douyin,xhs").strip()
    wanted = [x.strip().lower() for x in req.split(",") if x.strip()]
    alias = {"tap": "taptap", "official": "official", "官网": "official",
             "bili": "bili", "b站": "bili", "weibo": "weibo", "douyin": "douyin",
             "xhs": "xhs", "小红书": "xhs", "taptap": "taptap", "taptap_hot": "taptap_hot"}
    wanted = [alias.get(x, x) for x in wanted]
    table = {
        "daily": src_daily,
        "extra": src_extra,
        "calendar": src_calendar,
        "official": src_official,
        "taptap": src_taptap,
        "taptap_hot": src_taptap_hot,
        "bili": src_bili,
        "weibo": src_weibo,
        "douyin": src_douyin,
        "xhs": src_xhs,
    }
    # 可选源：tap 热帖只在显式设置时加入
    if env_bool("SKYNEWS_TAPTAP_HOT") and "taptap_hot" not in wanted:
        wanted.append("taptap_hot")
    # 额外自定义 RSSHub 路由：SKYNEWS_RSSHUB_ROUTES="name:path,name2:path2"
    extra = os.environ.get("SKYNEWS_RSSHUB_ROUTES") or ""
    extra_names = []
    for part in extra.split(","):
        if ":" not in part:
            continue
        nm, route = part.split(":", 1)
        nm, route = nm.strip(), route.strip()
        if not (nm and route):
            continue
        base = (os.environ.get("RSSHUB_BASE") or "").rstrip("/")
        table[nm] = (lambda r=route, n=nm, b=base: fetch_rss(b + r, n, referer=b + "/"))
        extra_names.append(nm)
    wanted += [n for n in extra_names if n not in wanted]
    out = {}
    for name in wanted:
        if name in table:
            out[name] = table[name]
        else:
            out[name] = (lambda n=name: (_ for _ in ()).throw(RuntimeError("未知源: %s" % n)))
    return out


# ============================================================ 缓存（上次成功结果）
def load_cache(path):
    """返回 (items_by_source, sources_meta)。支持 schema:1/2 旧 feed。"""
    if not path or not os.path.exists(path):
        return {}, {}
    try:
        with open(path, encoding="utf-8") as f:
            old = json.load(f)
    except (OSError, json.JSONDecodeError, UnicodeDecodeError):
        return {}, {}
    items_by_source = {}
    sources_meta = {}
    if isinstance(old.get("sources"), dict):
        sources_meta = old["sources"]
    legacy_map = {"官网": "official", "TapTap": "taptap", "B站": "bili",
                  "微博": "weibo", "抖音": "douyin", "小红书": "xhs"}
    for it in old.get("items") or []:
        src = it.get("source") or legacy_map.get(it.get("src"), (it.get("src") or "").lower())
        if not src:
            continue
        it2 = dict(it)
        it2.setdefault("source", src)
        items_by_source.setdefault(src, []).append(it2)
    return items_by_source, sources_meta


# ============================================================ 抓取调度
def run_source(name, fn, cfg, prev_items, prev_meta):
    t0 = time.monotonic()
    errors = []
    items = None
    for attempt in range(cfg["retries"]):
        try:
            got = fn()
            items = [it for it in got if it.get("id")]
            if not items:
                raise RuntimeError("返回 0 条（源可能被风控/空数据）")
            break
        except NotConfigured as e:
            errors.append("%s: %s" % (type(e).__name__, str(e)[:180]))
            break
        except Exception as e:  # noqa: BLE001
            errors.append("%s: %s" % (type(e).__name__, str(e)[:180]))
            if attempt < cfg["retries"] - 1:
                backoff = min(cfg["backoff_base"] * (2 ** attempt) + random.uniform(0, cfg["jitter"]), 6.0)
                time.sleep(backoff)
    elapsed = int((time.monotonic() - t0) * 1000)
    prev_ok = prev_meta.get(name) or {}
    if items is not None:
        return {"ok": True, "count": min(len(items), cfg["max_items"]), "error": None,
                "elapsed_ms": elapsed, "last_success": now_iso()}, items[:cfg["max_items"]], elapsed
    err = (errors[-1] if errors else "unknown")[:400]
    if len(errors) > 1:
        err = "%s（重试 %d 次）" % (err, len(errors))
    stale = []
    for it in prev_items.get(name, []):
        it2 = dict(it)
        it2["stale"] = True
        stale.append(it2)
    meta = {"ok": False, "count": min(len(stale), cfg["max_items"]), "error": err,
            "elapsed_ms": elapsed,
            "last_success": prev_ok.get("last_success")}
    if stale:
        meta["stale"] = True
    return meta, stale[:cfg["max_items"]], elapsed


def merge_items(items_by_source, cfg):
    """跨源去重：id 优先，其次归一化 URL；非 stale 优先，其次时间新。"""
    flat = []
    for src, lst in items_by_source.items():
        for it in lst:
            flat.append(it)

    def sort_key(it):
        return (0 if it.get("stale") else 1, int(it.get("ts") or 0))

    flat.sort(key=sort_key, reverse=True)
    seen_ids, seen_urls, merged = set(), set(), []
    for it in flat:
        ident = str(it.get("id") or "").strip()
        url = canon_url(it.get("url") or "")
        if ident and ident in seen_ids:
            continue
        if url and url in seen_urls:
            continue
        if ident:
            seen_ids.add(ident)
        if url:
            seen_urls.add(url)
        merged.append(it)
    merged.sort(key=lambda x: (bool(x.get("stale")), -int(x.get("ts") or 0)))
    return merged[:cfg["max_items"]]


def build(cfg, cache_path):
    prev_items, prev_meta = load_cache(cache_path) if not cfg["no_cache"] else ({}, {})
    sources = build_sources()
    results = {}
    items_by_source = {}

    if cfg["concurrency"] <= 1:
        for name, fn in sources.items():
            meta, got, _ = run_source(name, fn, cfg, prev_items, prev_meta)
            results[name], items_by_source[name] = meta, got
    else:
        max_workers = max(1, min(cfg["concurrency"], len(sources) or 1))
        with ThreadPoolExecutor(max_workers=max_workers) as pool:
            futures = {pool.submit(run_source, name, fn, cfg, prev_items, prev_meta): name
                       for name, fn in sources.items()}
            deadline = time.monotonic() + cfg["total_timeout"]
            for fut in as_completed(futures, timeout=None):
                name = futures[fut]
                remain = deadline - time.monotonic()
                if remain <= 0:
                    # 总时限已到：不再等待，先用上次结果回退
                    meta = {"ok": False, "count": 0, "error": "总时限内未完成", "elapsed_ms": 0,
                            "last_success": (prev_meta.get(name) or {}).get("last_success")}
                    results[name] = meta
                    stale = [dict(it, stale=True) for it in prev_items.get(name, [])]
                    results[name]["count"] = len(stale)
                    if stale:
                        results[name]["stale"] = True
                    items_by_source[name] = stale
                    continue
                try:
                    meta, got, _ = fut.result(timeout=remain)
                except Exception as e:  # noqa: BLE001
                    meta, got = {"ok": False, "count": 0, "error": repr(e)[:200],
                                 "elapsed_ms": 0,
                                 "last_success": (prev_meta.get(name) or {}).get("last_success")}, []
                results[name], items_by_source[name] = meta, got

    merged = merge_items(items_by_source, cfg)
    merged = enrich_full_text(merged, cfg)
    problems = []
    for name, meta in sorted(results.items()):
        if not meta.get("ok"):
            problems.append("%s: %s" % (SOURCE_LABEL.get(name, name), meta.get("error")))
    data = {
        "schema": SCHEMA,
        "generated_at": now_iso(),
        "updated": now_iso(),  # 兼容旧 App
        "count": len(merged),
        "sources": results,
        "problems": problems,
        "items": merged,
    }
    return data


# ============================================================ 输出
def to_rss(data):
    head = ('<?xml version="1.0" encoding="UTF-8"?>\n'
            '<rss version="2.0"><channel><title>SkyNews 光遇官方动态</title>'
            '<link>https://sky.163.com/news/</link>'
            '<description>官网 / TapTap / B站 / 微博 / 抖音 / 小红书 官方动态聚合</description>')
    body = []
    for it in data["items"]:
        body.append(
            "<item><title>%s</title><link>%s</link><guid>%s</guid>"
            "<description>%s</description><pubDate>%s</pubDate></item>" % (
                _xml(it.get("title") or it.get("text", "")[:80]),
                _xml(it.get("url") or ""), _xml(it.get("id") or ""),
                _xml(it.get("summary") or it.get("text", "")),
                _xml(_rfc822(it.get("ts")))))
    return head + "".join(body) + "</channel></rss>"


def _rfc822(ts):
    try:
        return time.strftime("%a, %d %b %Y %H:%M:%S +0000", time.gmtime(int(ts or 0)))
    except (TypeError, ValueError, OSError):
        return ""


def _xml(s):
    return (str(s or "").replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace('"', "&quot;"))


def atomic_write(path, content):
    tmp = path + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        f.write(content)
    os.replace(tmp, path)


def print_summary(data):
    print("schema=%s count=%d generated_at=%s" % (data.get("schema"), data["count"], data["generated_at"]))
    print("%-12s %-4s %6s %9s  %s" % ("source", "ok", "count", "elapsed", "error"))
    for name, meta in sorted(data.get("sources", {}).items()):
        print("%-12s %-4s %6s %8sms  %s" % (
            name, "Y" if meta.get("ok") else "N", meta.get("count", 0),
            meta.get("elapsed_ms", 0), (meta.get("error") or "")[:80]))
    for p in data.get("problems", []):
        print("  !", p)
    for it in data["items"][:10]:
        print("  -", it.get("src"), it.get("t"), (it.get("title") or it.get("text") or "")[:60].replace("\n", " "))


# ============================================================ 自检（离线）
def self_test():
    checks = []

    def chk(name, cond, extra=""):
        checks.append(bool(cond))
        print(("[PASS] " if cond else "[FAIL] ") + name + (("  " + str(extra)) if extra else ""))

    it1 = make_item("a1", "official", "标题A", "https://x.com/a?utm_source=1&id=1", 1000, "摘要")
    chk("item 新字段", all(k in it1 for k in ("id", "source", "title", "url", "time", "summary")))
    chk("item 旧字段兼容", all(k in it1 for k in ("src", "ts", "t", "text")))
    chk("url 归一化去 utm", canon_url("https://x.com/a?utm_source=1&id=1") == "https://x.com/a?id=1")
    chk("url 去尾斜杠", canon_url("https://x.com/a/") == "https://x.com/a")

    cfg = {"max_items": 50}
    src_items = {
        "official": [it1, make_item("a2", "official", "标题B", "https://x.com/b", 900)],
        "taptap": [make_item("a1", "taptap", "重复id", "https://x.com/c", 1100),
                   make_item("a3", "taptap", "同url", "https://x.com/a?id=1", 800)],
    }
    merged = merge_items(src_items, cfg)
    ids = [x["id"] for x in merged]
    urls = [canon_url(x["url"]) for x in merged]
    chk("跨源 id 去重", ids.count("a1") == 1, ids)
    chk("跨源 url 去重", urls.count("https://x.com/a?id=1") == 1, urls)
    chk("最新在前", merged[0]["ts"] >= merged[-1]["ts"])

    stale = dict(it1, stale=True)
    merged2 = merge_items({"official": [stale], "taptap": [it1]}, cfg)
    chk("非 stale 优先", merged2[0].get("stale") is not True)

    chk("时间 ISO 格式", bool(re.match(r"^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\dZ$", it1["time"])), it1["time"])

    # 失败回退：源失败时沿用上次成功条目（stale）
    prev = {"official": [make_item("old1", "official", "旧条目", "https://x.com/old", 500)]}
    fake_cfg = {"retries": 1, "backoff_base": 0.0, "jitter": 0.0, "max_items": 50}
    def _boom():
        raise RuntimeError("boom")
    meta, got, _ = run_source("official", _boom, fake_cfg, prev, {})
    chk("失败回退 stale", meta["ok"] is False and len(got) == 1
        and got[0].get("stale") is True and meta.get("stale") is True,
        "%s / %s" % (meta, [g.get("stale") for g in got]))

    print("\n[self-test] %s (%d/%d)" % ("PASS" if all(checks) else "FAIL", sum(checks), len(checks)))
    return 0 if all(checks) else 1


# ============================================================ main


def _gh_api(method, url, data=None, ctype=None):
    token = (os.environ.get("GITHUB_TOKEN") or os.environ.get("SKYNEWS_GH_TOKEN") or "").strip()
    if not token:
        return 0, b""
    req = urllib.request.Request(url, data=data, method=method, headers={
        "Authorization": "Bearer " + token, "User-Agent": "skynews-relay",
        "Accept": "application/vnd.github+json",
    })
    if data is not None and ctype:
        req.add_header("Content-Type", ctype)
    try:
        with urllib.request.urlopen(req, timeout=40) as r:
            body = r.read()
            try:
                return r.status, json.loads(body.decode("utf-8"))
            except Exception:  # noqa: BLE001
                return r.status, body
    except urllib.error.HTTPError as e:
        return e.code, e.read()


def _gh_repo():
    return os.environ.get("GITHUB_REPOSITORY") or "KunKunSkyBoy/SkyNews"


def _tools_release():
    st, d = _gh_api("GET", "https://api.github.com/repos/%s/releases/tags/skytools" % _gh_repo())
    if st == 200 and isinstance(d, dict):
        return d
    payload = json.dumps({
        "tag_name": "skytools", "name": "SkyNews 工具图片",
        "body": "CI 自动更新的光遇工具图片（季蜡/大蜡/复刻/日历）",
        "prerelease": True}, ensure_ascii=False).encode()
    st, d = _gh_api("POST", "https://api.github.com/repos/%s/releases" % _gh_repo(),
                    payload, "application/json")
    return d if st in (200, 201) and isinstance(d, dict) else None


def _upload_release_image(name, raw):
    """把图片上传到 GitHub Release（不进 git 历史），返回可直接访问的 URL。"""
    rel = _tools_release()
    if not rel:
        return ""
    rid = rel.get("id")
    if not rid:
        return ""
    for a in rel.get("assets") or []:
        if a.get("name") == name:
            _gh_api("DELETE", "https://api.github.com/repos/%s/releases/assets/%d"
                    % (_gh_repo(), a.get("id")))
    if raw[:3] == b"\xff\xd8\xff":
        ctype = "image/jpeg"
    elif raw[:4] == b"GIF8":
        ctype = "image/gif"
    else:
        ctype = "image/png"
    st, up = _gh_api("POST", "https://uploads.github.com/repos/%s/releases/%s/assets?name=%s"
                     % (_gh_repo(), rid, name), raw, ctype)
    if st in (200, 201) and isinstance(up, dict):
        return up.get("browser_download_url") or ""
    return ""


def _img_src(raw, name):
    """优先上传到 Release 得到 URL；失败才内嵌 data URI（避免仓库变大）。"""
    url = _upload_release_image(name, raw)
    return url if url else _data_uri(raw)


def http_get_bytes(url, referer=None, timeout=None):
    """GET 二进制（图片等）。"""
    req = urllib.request.Request(url, headers={
        "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120 Safari/537.36",
        "Accept": "*/*",
        "Accept-Encoding": "identity",
    })
    if referer:
        req.add_header("Referer", referer)
    with urllib.request.urlopen(req, timeout=timeout or DEFAULT_TIMEOUT) as r:
        return r.read()


def _data_uri(raw):
    if raw[:3] == b"\xff\xd8\xff":
        mime = "image/jpeg"
    elif raw[:8] == b"\x89PNG\r\n\x1a\n":
        mime = "image/png"
    elif raw[:4] == b"GIF8":
        mime = "image/gif"
    else:
        mime = "image/png"
    return "data:%s;base64,%s" % (mime, base64.b64encode(raw).decode())



def _t1qq_key():
    key = (os.environ.get("SKYNEWS_T1QQ_KEY") or "").strip()
    if key:
        return key
    try:
        p = os.path.join(os.path.dirname(os.path.abspath(__file__)), ".t1qq.key")
        if os.path.exists(p):
            return io.open(p, encoding="utf-8").read().strip()
    except Exception:  # noqa: BLE001
        pass
    return ""


def _t1qq_img(ep, key):
    try:
        raw = http_get_bytes("https://api.t1qq.com/api/sky/sc/%s?key=%s"
                             % (ep, urllib.parse.quote(key)), referer="https://api.t1qq.com/")
        if len(raw) > 512 and raw[:2] in (b"\xff\xd8", b"\x89P", b"GI"):
            return _img_src(raw, "sc_" + ep + ".png")
    except Exception:  # noqa: BLE001
        pass
    return ""




# ============================================================ 明日任务（独角兽 API）
def _mrrw_key():
    for name in ("SKYNEWS_MRRW_KEY", "SKYNEWS_DAILY_KEY"):
        v = (os.environ.get(name) or "").strip()
        if v:
            return v
    return ""


def write_tomorrow(out_dir):
    """抓独角兽 API 的「今日+明日」任务排期 → <out>/tomorrow.json。

    为什么放在中继：
      * 该 API 免费额度只有 3 次/天，App 直连很快烧穿；
      * key 存 GitHub Secrets，不进 APK；
      * 全用户共享一份静态文件，读多少次都不耗额度。
    失败时不写文件（atomic_write 在成功时才落盘），旧文件继续服务；
    App 端会校验 tomorrow 的日期，过期就回退。
    """
    key = _mrrw_key()
    if not key:
        raise NotConfigured("未配置 SKYNEWS_MRRW_KEY（独角兽 API 密钥）")
    url = "https://ovoav.com/api/sky/mrrw/rw?key=" + key
    raw = http_get(url, timeout=15)
    d = json.loads(raw)
    if int(d.get("code") or 0) != 200:
        raise RuntimeError("独角兽 API code=%s msg=%s" % (d.get("code"), d.get("msg")))
    tl = (d.get("data") or {}).get("task_list") or []
    by_date = {}
    for it in tl:
        day = (it.get("date") or "").strip()
        cn = (it.get("quest_cn") or "").strip()
        if not day or not cn:
            continue
        by_date.setdefault(day, []).append({
            "date": day,
            "quest_id": (it.get("quest_id") or "").strip(),
            "quest_cn": cn,
        })
    days = sorted(by_date)
    payload = {
        "schema": 1,
        "generated_at": now_iso(),
        "today_date": days[0] if days else "",
        "tomorrow_date": days[1] if len(days) > 1 else "",
        "today": by_date.get(days[0], []) if days else [],
        "tomorrow": by_date.get(days[1], []) if len(days) > 1 else [],
        "raw_count": len(tl),
    }
    os.makedirs(out_dir, exist_ok=True)
    atomic_write(os.path.join(out_dir, "tomorrow.json"),
                 json.dumps(payload, ensure_ascii=False, indent=1))
    return payload


def write_history(out_dir, data):
    """把每天第一次抓到的数据落一份到 docs/history/YYYY-MM-DD.json（自建存档，~按天增长）。"""
    try:
        now = datetime.now(timezone(timedelta(hours=8)))
        day = now.strftime("%Y-%m-%d")
        hdir = os.path.join(out_dir, "history")
        os.makedirs(hdir, exist_ok=True)
        path = os.path.join(hdir, day + ".json")
        if os.path.exists(path):
            return
        keep = []
        for it in data.get("items") or []:
            src = it.get("src") or it.get("source") or ""
            if src in ("每日任务", "速览"):
                keep.append({"src": src, "t": it.get("t"), "text": it.get("text"), "url": it.get("url")})
        snap = {"date": day, "generated_at": data.get("generated_at"),
                "sources": {k: {"ok": v.get("ok"), "count": v.get("count")}
                            for k, v in (data.get("sources") or {}).items()},
                "items": keep}
        atomic_write(path, json.dumps(snap, ensure_ascii=False, indent=1))
        print("history written:", day)
    except Exception as e:  # noqa: BLE001
        print("history skipped:", e)



def _t1qq_bytes(ep, key):
    """应天API 图片接口（带 key）→ 原始字节。"""
    try:
        raw = http_get_bytes("https://api.t1qq.com/api/sky/sc/%s?key=%s"
                             % (ep, urllib.parse.quote(key)), referer="https://api.t1qq.com/")
        if len(raw) > 512 and raw[:2] in (b"\xff\xd8", b"\x89P", b"GI"):
            return raw
    except Exception:  # noqa: BLE001
        pass
    return b""


def _save_tool_img(img_dir, name, raw, day_tag):
    """把图片压小后存到 docs/img/<name>，返回相对 URL（Pages 国内可访问，不依赖 GitHub Release）。"""
    try:
        os.makedirs(img_dir, exist_ok=True)
        out = None
        try:
            from PIL import Image  # noqa: PLC0415
            import io as _io
            im = Image.open(_io.BytesIO(raw))
            if im.mode not in ("RGB", "L"):
                im = im.convert("RGB")
            w, h = im.size
            if w > 720:
                im = im.resize((720, max(1, int(h * 720 / w))), Image.LANCZOS)
            path = os.path.join(img_dir, name + ".jpg")
            im.save(path, "JPEG", quality=68, optimize=True)
            out = path
        except Exception:  # noqa: BLE001
            ext = ".png" if raw[:4] == b"\x89PNG" else (".gif" if raw[:4] == b"GIF8" else ".jpg")
            out = os.path.join(img_dir, name + ext)
            with open(out, "wb") as f:
                f.write(raw)
        return "img/" + os.path.basename(out) + "?d=" + day_tag
    except Exception:  # noqa: BLE001
        return ""


def write_tools_html(out_dir, data):
    """生成 docs/tools.html：所有免费光遇工具（文字 + 图片），按天缓存。"""
    key = _t1qq_key()
    if not key:
        return
    today = datetime.now(timezone(timedelta(hours=8))).strftime("%Y-%m-%d")
    marker = os.path.join(out_dir, ".tools-date")
    try:
        if os.path.exists(marker) and io.open(marker, encoding="utf-8").read().strip() == today:
            return
    except Exception:  # noqa: BLE001
        pass

    items = data.get("items") or []
    by_src = {}
    for it in items:
        by_src.setdefault(it.get("src") or it.get("source") or "", it)

    def text_of(name):
        it = by_src.get(name)
        if not it:
            return ""
        return html_mod.escape(it.get("text") or "").replace("\n", "<br/>")

    sections = []
    daily_t = text_of("每日任务")
    extra_t = text_of("速览")
    if daily_t:
        sections.append(("daily", "每日任务", daily_t, []))
    if extra_t:
        sections.append(("extra", "今日速览（季蜡/大蜡/魔法/赛季）", extra_t, []))
    # 红石/活动/复刻 日程（ICS）
    try:
        cal_lines = _ics_upcoming(days=10, limit=8)
        if cal_lines:
            sections.append(("calendar", "近期日程（红石/活动/复刻）",
                             "<br/>".join(html_mod.escape(x) for x in cal_lines), []))
    except Exception:  # noqa: BLE001
        pass
    # 红石天气图片（保存到 docs/img，Pages 提供，国内可访问）
    img_dir = os.path.join(out_dir, "img")
    day_tag = today.replace("-", "")
    gytq_imgs = []
    try:
        d = http_get_json("https://api.t1qq.com/api/sky/gytq?key=" + urllib.parse.quote(key),
                          referer="https://api.t1qq.com/")
        for u in (d.get("data") or [])[:2]:
            if u.startswith("http"):
                try:
                    rel = _save_tool_img(img_dir, "gytq_%d" % len(gytq_imgs),
                                         http_get_bytes(u), day_tag)
                    if rel:
                        gytq_imgs.append(rel)
                except Exception:  # noqa: BLE001
                    pass
    except Exception:  # noqa: BLE001
        pass
    sections.append(("gytq", "红石/黑石天气", "来自应天API 的天气图（每日更新）", gytq_imgs))
    # 图片类（保存到 docs/img）
    for ep, title in [("scjl", "季节蜡烛位置图"), ("scdl", "大蜡烛位置图"),
                      ("scfk", "复刻先祖位置图"), ("hdrl", "活动日历图")]:
        raw = _t1qq_bytes(ep, key)
        if raw:
            rel = _save_tool_img(img_dir, "sc_" + ep, raw, day_tag)
            if rel:
                sections.append((ep, title, "", [rel]))

    img_sections = sum(1 for _, _, _, im in sections if im)
    old_path = os.path.join(out_dir, "tools.html")
    if img_sections == 0 and os.path.exists(old_path):
        try:
            old_txt = io.open(old_path, encoding="utf-8").read()
            if "<img" in old_txt:
                print("tools.html kept (no images fetched this run)")
                atomic_write(marker, today)
                return
        except Exception:  # noqa: BLE001
            pass
    parts = []
    for sid, title, text, imgs in sections:
        img_html = "".join('<div class="imgwrap"><img src="%s"/></div>' % u for u in imgs)
        parts.append('<section id="%s"><h2>%s</h2><div class="txt">%s</div>%s</section>'
                     % (sid, title, text, img_html))
    page = """<!doctype html><html lang="zh-CN"><head><meta charset="utf-8"/>
<meta name="viewport" content="width=device-width,initial-scale=1"/>
<title>光遇工具 · SkyNews</title>
<style>
body{margin:0;background:#0e141b;color:#dbe7f3;font-family:system-ui,-apple-system,"PingFang SC",sans-serif}
header{padding:16px 18px;background:#111a24;position:sticky;top:0;border-bottom:1px solid #1e2a38;z-index:2}
h1{font-size:18px;margin:0 0 4px}
.meta{font-size:12px;color:#7f93a8}
nav{display:flex;flex-wrap:wrap;gap:6px;padding:10px 14px;background:#0b1118;border-bottom:1px solid #1e2a38}
nav a{font-size:12px;color:#7bd8c4;text-decoration:none;border:1px solid #1e3a44;border-radius:8px;padding:5px 9px;background:#101c24}
section{padding:14px 16px;border-bottom:1px solid #17222e}
h2{font-size:15px;margin:0 0 8px;color:#9fd7ff}
.txt{font-size:14px;line-height:1.7;color:#cfe0ef}
.imgwrap{margin-top:10px;background:#fff;border-radius:10px;overflow:hidden}
.imgwrap img{width:100%%;display:block}
footer{padding:14px 16px;font-size:11px;color:#5f7286}
</style></head><body>
<header><h1>光遇工具 · SkyNews</h1><div class="meta">免费数据源：skydaily · 应天API · oivio ｜ 更新时间 %s</div></header>
<nav>%s</nav>
%s
<footer>每日自动更新 ｜ <a style="color:#7bd8c4" href="calendar.ics">订阅日历 ICS</a><br/>数据来自第三方公开接口，仅供参考。</footer>
</body></html>""" % (
        datetime.now(timezone(timedelta(hours=8))).strftime("%Y-%m-%d %H:%M"),
        "".join('<a href="#%s">%s</a>' % (sid, title) for sid, title, _, _ in sections),
        "".join(parts),
    )
    atomic_write(os.path.join(out_dir, "tools.html"), page)
    atomic_write(marker, today)
    print("tools.html written (%d sections)" % len(sections))


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default="docs")
    ap.add_argument("--cache", default=None, help="上次 feed.json（默认 <out>/feed.json）")
    ap.add_argument("--no-cache", action="store_true")
    ap.add_argument("--print", dest="show", action="store_true")
    ap.add_argument("--self-test", action="store_true")
    ap.add_argument("--max-items", type=int, default=None)
    ap.add_argument("--concurrency", type=int, default=None)
    args = ap.parse_args(argv)

    if args.self_test:
        return self_test()

    cfg = {
        "retries": max(1, env_int("SKYNEWS_RETRIES", DEFAULT_RETRIES)),
        "backoff_base": env_float("SKYNEWS_BACKOFF_BASE", 0.8),
        "jitter": env_float("SKYNEWS_JITTER", 0.5),
        "total_timeout": env_float("SKYNEWS_TOTAL_TIMEOUT", DEFAULT_TOTAL_TIMEOUT),
        "max_items": args.max_items or env_int("SKYNEWS_MAX_ITEMS", MAX_ITEMS_DEFAULT),
        "concurrency": args.concurrency if args.concurrency is not None
        else env_int("SKYNEWS_CONCURRENCY", 6),
        "no_cache": args.no_cache,
    }
    os.environ.setdefault("SKYNEWS_TIMEOUT", str(DEFAULT_TIMEOUT))
    cache_path = args.cache or os.path.join(args.out, "feed.json")
    data = build(cfg, cache_path)

    os.makedirs(args.out, exist_ok=True)
    atomic_write(os.path.join(args.out, "feed.json"),
                 json.dumps(data, ensure_ascii=False, indent=1))
    atomic_write(os.path.join(args.out, "feed.xml"), to_rss(data))
    try:
        write_tools_html(args.out, data)
    except Exception as e:  # noqa: BLE001
        print("tools.html skipped:", e)
    try:
        write_history(args.out, data)
    except Exception as e:  # noqa: BLE001
        print("history skipped:", e)
    try:
        write_calendar_ics(args.out)
    except Exception as e:  # noqa: BLE001
        print("calendar.ics skipped:", e)
    try:
        tw = write_tomorrow(args.out)
        print("tomorrow: today=%d tomorrow=%d (%s) → %s" % (
            len(tw["today"]), len(tw["tomorrow"]), tw["tomorrow_date"],
            os.path.join(args.out, "tomorrow.json")))
    except Exception as e:  # noqa: BLE001
        print("tomorrow skipped:", e)
    if args.show:
        print_summary(data)

    ok = sum(1 for m in data["sources"].values() if m.get("ok"))
    print("sources ok=%d/%d items=%d → %s" % (ok, len(data["sources"]), data["count"],
                                              os.path.join(args.out, "feed.json")))
    return 0


if __name__ == "__main__":
    sys.exit(main())
