#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""SkyNews 云中继：把「手机直连不到」的官方动态源搬到云端抓，输出统一 JSON / RSS。

设计目标：
  * 只用标准库（GitHub Actions 上零依赖）
  * 每个源独立 try/except，单源失败不影响其它源
  * 输出稳定格式，App 端只认 items[]（id/src/t/ts/text/url）

直连源（任何网络都应可用）：
  - 官网 news JSON: sixhorse.game.163.com
  - TapTap 官方帖: webapiv2/feed/v7/by-group?group_id=47615
  - B 站动态: WBI 签名 + buvid3（无需登录）

中继源（需要外部条件，按环境变量启用）：
  - WEIBO_COOKIE      : 微博 cookie。有 cookie 时优先走 m.weibo.cn 直连
  - RSSHUB_BASE       : 自建/可用 RSSHub 地址（如 https://rsshub.example.com），
                        用它抓 weibo / douyin / xiaohongshu 路由
  - 都没有时：微博/抖音/小红书标记为 unavailable，只出直连源

用法：
  python3 relay.py --out docs            # 写 docs/feed.json + docs/feed.xml
  python3 relay.py --out docs --print    # 同时打印摘要
"""
import argparse
import hashlib
import json
import os
import re
import time
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET

UA = ("Mozilla/5.0 (Linux; Android 13; SM-G991B) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")

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


def http(url, referer=None, cookie=None, timeout=20):
    req = urllib.request.Request(url, headers={
        "User-Agent": UA,
        "Accept": "application/json, text/plain, */*",
    })
    if referer:
        req.add_header("Referer", referer)
    if cookie:
        req.add_header("Cookie", cookie)
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return r.read().decode("utf-8", "replace")


def ts_to_text(ts):
    if not ts:
        return ""
    return time.strftime("%m-%d %H:%M", time.localtime(ts))


def item(ident, src, ts, text, url):
    try:
        stamp = int(ts or 0)
    except (TypeError, ValueError):
        stamp = 0
    return {"id": ident, "src": src, "ts": stamp,
            "t": ts_to_text(stamp), "text": (text or "").strip(), "url": url}


# ---------------------------------------------------------------- 直连源
def fetch_official():
    out, problems = [], []
    try:
        data = json.loads(http(OFFICIAL_NEWS, "https://sky.163.com/news/"))
        for row in data.get("result", []):
            url = row.get("url", "")
            if url.startswith("//"):
                url = "https:" + url
            ts = int(row.get("showtime", 0) / 1000)
            out.append(item("gf-" + str(row.get("code", "")), "官网", ts,
                            row.get("title", ""), url))
    except Exception as e:  # noqa: BLE001
        problems.append("官网: %r" % (e,))
    return out, problems


def fetch_taptap():
    out, problems = [], []
    try:
        data = json.loads(http(TAPTAP_FEED, "https://www.taptap.cn/"))
        for row in data.get("data", {}).get("list", []):
            m = row.get("moment") or {}
            topic = m.get("topic") or {}
            mid = m.get("id_str", "")
            if not mid:
                continue
            text = ((topic.get("title", "") + "\n") if topic.get("title") else "") \
                + topic.get("summary", "")
            out.append(item("tt-" + mid, "TapTap", m.get("created_time", 0), text,
                            "https://www.taptap.cn/moment/" + mid))
    except Exception as e:  # noqa: BLE001
        problems.append("TapTap: %r" % (e,))
    return out, problems


def _bili_sign(params, img_key, sub_key):
    raw = img_key + sub_key
    mixin = "".join(raw[i] for i in MIXIN if i < len(raw))[:32]
    params = dict(params)
    params["wts"] = int(time.time())
    q = urllib.parse.urlencode(sorted(params.items()))
    params["w_rid"] = hashlib.md5((q + mixin).encode()).hexdigest()
    return urllib.parse.urlencode(sorted(params.items()))


def fetch_bilibili():
    out, problems = [], []
    try:
        spi = json.loads(http("https://api.bilibili.com/x/frontend/finger/spi",
                              "https://www.bilibili.com/"))
        sd = spi.get("data") or {}
        b3, b4 = sd.get("b_3", ""), sd.get("b_4", "")
        cookie = "buvid3=%s; buvid4=%s; b_nut=%d" % (b3, b4, time.time())
        nav = json.loads(http("https://api.bilibili.com/x/web-interface/nav",
                              "https://www.bilibili.com/", cookie))
        wbi = (nav.get("data") or {}).get("wbi_img") or {}
        img = wbi.get("img_url", "").rsplit("/", 1)[-1].split(".")[0]
        sub = wbi.get("sub_url", "").rsplit("/", 1)[-1].split(".")[0]
        q = _bili_sign({"host_mid": BILI_MID, "timezone_offset": -480,
                        "features": "itemOpusStyle,listOnlyfans,opusBigCover,onlyfansVote"},
                       img, sub)
        data = json.loads(http(
            "https://api.bilibili.com/x/polymer/web-dynamic/v1/feed/space?" + q,
            "https://space.bilibili.com/%d/dynamic" % BILI_MID, cookie))
        if data.get("code") != 0:
            raise RuntimeError("code=%s %s" % (data.get("code"), data.get("message")))
        for row in (data.get("data") or {}).get("items", []):
            mod = row.get("modules") or {}
            au = mod.get("module_author") or {}
            dy = mod.get("module_dynamic") or {}
            text = ((dy.get("desc") or {}).get("text") or "")
            if not text:
                major = dy.get("major") or {}
                opus = major.get("opus") or {}
                text = (opus.get("summary") or {}).get("text") or \
                    (major.get("archive") or {}).get("title") or ""
            did = row.get("id_str", "")
            out.append(item("bl-" + did, "B站", au.get("pub_ts", 0), text,
                            "https://t.bilibili.com/" + did))
    except Exception as e:  # noqa: BLE001
        problems.append("B站: %r" % (e,))
    return out, problems


# ---------------------------------------------------------------- 中继源
def fetch_weibo_direct():
    """有 WEIBO_COOKIE 时直连 m.weibo.cn（云端出口 IP 通常不被 432）。"""
    out, problems = [], []
    cookie = os.environ.get("WEIBO_COOKIE")
    if not cookie:
        problems.append("微博: 未配置 WEIBO_COOKIE")
        return out, problems
    try:
        url = ("https://m.weibo.cn/api/container/getIndex?type=uid&value=%d"
               "&containerid=107603%d" % (WEIBO_UID, WEIBO_UID))
        data = json.loads(http(url, "https://m.weibo.cn/u/%d" % WEIBO_UID, cookie))
        for card in data.get("data", {}).get("cards", []):
            mb = card.get("mblog")
            if not mb:
                continue
            text = re.sub(r"<[^>]+>", "", mb.get("text", ""))
            ts = int(time.time()) if mb.get("created_at") in (None, "刚刚") else 0
            out.append(item("wb-" + str(mb.get("id", "")), "微博", ts, text,
                            "https://m.weibo.cn/detail/" + str(mb.get("id", ""))))
    except Exception as e:  # noqa: BLE001
        problems.append("微博: %r" % (e,))
    return out, problems


def fetch_rsshub():
    """RSSHUB_BASE 指向可用实例时，抓 weibo/douyin/xiaohongshu 路由。"""
    out, problems = [], []
    base = (os.environ.get("RSSHUB_BASE") or "").rstrip("/")
    if not base:
        return out, ["RSSHub: 未配置 RSSHUB_BASE（抖音/小红书只能走这里）"]
    routes = [
        ("微博", "/weibo/user/%d" % WEIBO_UID),
        ("抖音", "/douyin/user/光遇手游"),
        ("小红书", "/xiaohongshu/user/光遇"),
    ]
    for name, route in routes:
        try:
            xml = http(base + route, base + "/")
            root = ET.fromstring(xml)
            for entry in root.iter("item"):
                title = (entry.findtext("title") or "").strip()
                link = (entry.findtext("link") or "").strip()
                desc = re.sub(r"<[^>]+>", " ",
                              entry.findtext("description") or "")
                pub = entry.findtext("pubDate") or ""
                stamp = 0
                try:
                    from email.utils import parsedate_to_datetime
                    stamp = int(parsedate_to_datetime(pub).timestamp())
                except Exception:  # noqa: BLE001
                    pass
                key = hashlib.md5((name + link + title).encode()).hexdigest()[:16]
                out.append(item("rh-" + key, name, stamp,
                                (title + "\n" + desc).strip(), link))
        except Exception as e:  # noqa: BLE001
            problems.append("%s(RSSHub): %r" % (name, e))
    return out, problems


# ---------------------------------------------------------------- 汇总
def build():
    items, problems = [], []
    for fn in (fetch_taptap, fetch_official, fetch_bilibili):
        got, bad = fn()
        items += got
        problems += bad
    got, bad = fetch_weibo_direct()
    items += got
    problems += bad
    got, bad = fetch_rsshub()
    items += got
    problems += bad

    seen, merged = set(), []
    for it in sorted(items, key=lambda x: x["ts"], reverse=True):
        if it["id"] in seen:
            continue
        seen.add(it["id"])
        merged.append(it)
    return {
        "updated": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "count": len(merged),
        "problems": problems,
        "items": merged[:150],
    }


def to_rss(data):
    head = ('<?xml version="1.0" encoding="UTF-8"?>\n'
            '<rss version="2.0"><channel><title>SkyNews 光遇官方动态</title>'
            '<link>https://sky.163.com/news/</link>'
            '<description>TapTap / B站 / 官网 / 微博 官方动态聚合</description>')
    body = []
    for it in data["items"]:
        body.append(
            "<item><title>%s</title><link>%s</link><guid>%s</guid>"
            "<description>%s</description></item>" % (
                _xml(it["text"][:80] or it["src"]),
                _xml(it["url"]), _xml(it["id"]), _xml(it["text"])))
    return head + "".join(body) + "</channel></rss>"


def _xml(s):
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default="docs")
    ap.add_argument("--print", dest="show", action="store_true")
    args = ap.parse_args()
    data = build()
    os.makedirs(args.out, exist_ok=True)
    with open(os.path.join(args.out, "feed.json"), "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=1)
    with open(os.path.join(args.out, "feed.xml"), "w", encoding="utf-8") as f:
        f.write(to_rss(data))
    if args.show:
        print("count=%d updated=%s" % (data["count"], data["updated"]))
        for p in data["problems"]:
            print("  !", p)
        for it in data["items"][:12]:
            print("  -", it["src"], it["t"], it["text"][:60].replace("\n", " "))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
