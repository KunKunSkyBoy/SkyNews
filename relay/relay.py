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
import hashlib
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
from datetime import datetime, timezone
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
    if args.show:
        print_summary(data)

    ok = sum(1 for m in data["sources"].values() if m.get("ok"))
    print("sources ok=%d/%d items=%d → %s" % (ok, len(data["sources"]), data["count"],
                                              os.path.join(args.out, "feed.json")))
    return 0


if __name__ == "__main__":
    sys.exit(main())
