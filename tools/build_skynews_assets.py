# -*- coding: utf-8 -*-
"""
build_skynews_assets.py —— 为 SkyNews App 生成「光遇资料/活动日历」数据资产。

输入（本机，均可配置）：
  * 数据包：E:\\gongzuoqu\\sky_app\\skycore\\data\\（index/defs/names_cn/names_outfit_cn/season_names_extra/version）
  * 活动排期快照：E:\\gongzuoqu\\_team\\lead\\event_schedule.json
    （时间语义：absolute = base_time + when（秒），区间 = [absolute, absolute + duration*60)）

输出：<SkyNews>/app/assets/sky/*.json（UTF-8 无 BOM，紧凑分隔符）+ README 由手工维护的模板文件

用法：
  python build_skynews_assets.py
  python build_skynews_assets.py --out "E:\\...\\app\\assets\\sky" --data "E:\\gongzuoqu\\sky_app\\skycore\\data"

注意：只写 assets/sky/ 与自身脚本；不碰 Java/res/relay。
"""
from __future__ import annotations

import argparse
import collections
import datetime as dt
import io
import json
import os
import re
import sys

# ----------------------------------------------------------------- 路径默认值
HERE = os.path.dirname(os.path.abspath(__file__))
DEFAULT_ROOT = os.path.abspath(os.path.join(HERE, "..", "app", "assets", "sky"))
DEFAULT_DATA = r"E:\gongzuoqu\sky_app\skycore\data"
DEFAULT_EVENTS = r"E:\gongzuoqu\_team\lead\event_schedule.json"

TZ = dt.timezone(dt.timedelta(hours=8))  # Asia/Shanghai（国服时间）

TYPE_CN = {
    "body": "裤子/裙装",
    "hair": "发型",
    "wing": "斗篷",
    "mask": "面具",
    "horn": "发饰",
    "hat": "头饰",
    "neck": "项链",
    "feet": "鞋子",
    "prop": "道具",
    "face": "面部装饰",
    "tail": "尾巴",
    "voice": "声音",
}

# 活动前缀 -> 中文（来源：_team/lead/tools/event_schedule.py 的 PREFIX_CN，补了 ap18/ap29/sales 等）
PREFIX_CN = {
    "season": "季节/赛季", "aurora": "欧若拉联动（极光演唱会）", "skyfest": "天空节（烟花秀）",
    "halloween": "万圣节", "feast": "宴会节", "wildlifepark": "野生动物园联动",
    "musicshop": "音乐商店", "netease": "网易云音乐联动", "cloud": "云上活动场景",
    "mbti": "MBTI 活动", "personality": "性格测验", "cafe": "咖啡厅",
    "te": "旅行日记（剧情）", "snake": "贪吃蛇小游戏", "radiant": "归巢星光/黑暗石",
    "activitycenter": "活动中心", "mainstreet": "小镇（MainStreet）", "tgcoffice": "TGC 办公室",
    "creativehall": "创意大厅", "social": "社交活动", "visiting": "旅行先祖（复刻）",
    "spiritshopitem": "先祖商店排期", "quest": "任务", "collectible": "动作/收集品",
    "fireworks": "烟花", "dusk": "暮土/黄昏", "sunset": "霞谷", "elder": "长老",
    "prairie": "云野", "rain": "雨林", "stormy": "暴风眼", "projector": "投影仪（留言）",
    "weekly": "周常轮换", "sales": "商城促销", "gen": "商店轮换", "event": "通用事件",
    "enable": "功能开关", "permanent": "常驻增益", "wing": "光翼转化", "emote": "表情",
    "global": "全局计时器", "activity": "活动中心公告", "ap18": "0.18 版本活动",
    "ap29": "0.29 版本活动", "AP27CapeHealingSpa": "0.27 斗篷治愈温泉",
    "fetch": "任务（跑腿）", "treasure": "寻宝", "island": "岛屿活动", "ne": "网易联动（短代号）",
}

# constellation.location -> 中文地图
LOC_REALM = {
    "dawn": "晨岛", "prairie": "云野", "day": "云野", "forest": "雨林", "rain": "雨林",
    "sunset": "霞谷", "dusk": "暮土", "night": "禁阁", "wasteland": "暮土", "vault": "禁阁",
    "windpaths": "风行网道", "mainstreet": "小镇", "aviary": "云巢", "candle": "光域",
    "eden": "伊甸", "storm": "暴风眼", "void": "虚空", "office": "办公室",
}


# ----------------------------------------------------------------- 工具
def load_json(path: str):
    with io.open(path, encoding="utf-8") as f:
        return json.load(f)


def dump_json(path: str, obj) -> int:
    """紧凑 JSON + UTF-8 无 BOM + LF；返回字节数。"""
    os.makedirs(os.path.dirname(path), exist_ok=True)
    data = json.dumps(obj, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    with open(path, "wb") as f:
        f.write(data)
    return len(data)


def ts_str(sec: int) -> str:
    return dt.datetime.fromtimestamp(sec, TZ).strftime("%Y-%m-%d %H:%M")


def dstr(sec: int) -> str:
    return dt.datetime.fromtimestamp(sec, TZ).strftime("%Y-%m-%d")


def season_cn_map(data_dir: str) -> dict:
    """赛季/活动季中文名：season_names_extra + defs/seasons.json(names)。"""
    out = {}
    ex = os.path.join(data_dir, "season_names_extra.json")
    if os.path.exists(ex):
        out.update(load_json(ex).get("names") or {})
    sp = os.path.join(data_dir, "defs", "seasons.json")
    if os.path.exists(sp):
        out.update(load_json(sp).get("names") or {})
    for k in list(out):
        if k == "furniture":
            out["furniture"] = "家具"
    return out


def load_outfit_names(path: str) -> dict:
    """names_outfit_cn.json 有两种形态：{id:{zh,strategy}} 或 {outfits:{id:{...}}}。"""
    d = load_json(path)
    if isinstance(d, dict):
        for key in ("outfits", "names"):  # 兼容两种导出形态
            inner = d.get(key)
            if isinstance(inner, dict):
                return inner
    return d if isinstance(d, dict) else {}


def load_version(data_dir: str) -> dict:
    v = load_json(os.path.join(data_dir, "version.json"))
    pick = {}
    for k, val in v.items():
        if isinstance(val, (str, int, float)) and k.lower() in (
            "app_version", "version_code", "client_version", "resource_version",
            "package_name", "svn_version", "game", "config_branch", "version_name",
        ):
            pick[k] = val
    return pick


# ----------------------------------------------------------------- 各文件生成
def build_items(data_dir: str, out_dir: str, season_cn: dict) -> tuple[int, int]:
    outfits = load_json(os.path.join(data_dir, "defs", "outfits.json"))
    names = load_outfit_names(os.path.join(data_dir, "names_outfit_cn.json"))
    items = []
    for o in outfits:
        oid = o.get("name") or ""
        if not oid:
            continue
        rec = names.get(oid)
        if isinstance(rec, dict):
            zh, src = rec.get("zh") or "", rec.get("strategy") or "derived"
        elif isinstance(rec, str):
            zh, src = rec, "derived"
        else:
            zh, src = "", "none"
        t = o.get("type") or ""
        s = o.get("season") or ""
        item = {
            "i": oid,
            "n": zh or oid,
            "t": t,
            "r": "store" if src.startswith("official") else ("derived" if src == "derived" else "none"),
        }
        tc = TYPE_CN.get(t)
        if tc:
            item["tc"] = tc
        if s:
            item["s"] = s
            sc = season_cn.get(s)
            if sc:
                item["sc"] = sc
        if o.get("cn_only"):
            item["c"] = 1
        if o.get("dyeable_primary") or o.get("dyeable_secondary"):
            item["d"] = 1
        ev = o.get("outfit_event") or ""
        if ev:
            item["e"] = ev
        items.append(item)
    size = dump_json(os.path.join(out_dir, "items.json"), {"v": 1, "n": len(items), "items": items})
    return len(items), size


def build_spirits(data_dir: str, out_dir: str, season_cn: dict) -> tuple[int, int]:
    npcs = load_json(os.path.join(data_dir, "defs", "npcs.json"))
    names = load_json(os.path.join(data_dir, "names_cn.json"))
    cnmap = names.get("npc") or {}
    items = []
    for x in npcs:
        if not x.get("spirit"):
            continue
        nid = x.get("name") or ""
        rec = cnmap.get(nid) if isinstance(cnmap.get(nid), dict) else None
        zh = (rec or {}).get("zh") or ""
        en = (rec or {}).get("en") or ""
        if not zh and not en:
            continue  # 无名 NPC（内部测试/占位）不放进资料库
        item = {"i": nid, "n": zh}
        if en:
            item["en"] = en
        cons = x.get("constellation") or {}
        if isinstance(cons, dict):
            sl = (cons.get("season") or "")
            if sl:
                item["s"] = sl
                if sl in season_cn:
                    item["sc"] = season_cn[sl]
            loc = (cons.get("location") or "")
            m = re.search(r"location_desc_([a-z0-9_]+)", loc)
            if m:
                realm = LOC_REALM.get(m.group(1))
                if realm:
                    item["r"] = realm
            grp = cons.get("group") or ""
            if grp and not item.get("s"):
                mm = re.match(r"name_(season_\d+)", grp)
                if mm:
                    item["s"] = mm.group(1)
                    if mm.group(1) in season_cn:
                        item["sc"] = season_cn[mm.group(1)]
        if x.get("cn_only"):
            item["c"] = 1
        if x.get("ghost"):
            item["g"] = 1
        ce = x.get("cape_level") or 0
        if ce:
            item["ce"] = ce
        items.append(item)
    size = dump_json(os.path.join(out_dir, "spirits.json"), {"v": 1, "n": len(items), "items": items})
    return len(items), size


def build_quests(data_dir: str, out_dir: str) -> tuple[dict, int]:
    dqs = load_json(os.path.join(data_dir, "defs", "daily_quests.json"))
    wqs = load_json(os.path.join(data_dir, "defs", "world_quests.json"))
    names = load_json(os.path.join(data_dir, "names_cn.json"))
    qcn = names.get("quest") or {}
    lvcn = names.get("level") or {}
    npcn = names.get("npc") or {}

    daily = []
    for q in dqs:
        rec = qcn.get(q.get("id") or "")
        zn = rec if isinstance(rec, str) else (rec or {}).get("zh") if isinstance(rec, dict) else ""
        lm = q.get("landmark") or ""
        item = {"i": q.get("id") or "", "n": zn or ""}
        if lm:
            m = lvcn.get(lm)
            if m:
                item["m"] = m
        g = q.get("stat_group") or ""
        if g:
            item["g"] = g
        t = q.get("stat_type") or ""
        if t:
            item["t"] = t
        npc = q.get("npc") or ""
        if npc and isinstance(npcn.get(npc), dict):
            item["np"] = npcn[npc].get("zh") or ""
        daily.append(item)

    world = []
    for q in wqs:
        lm = q.get("level") or ""
        item = {"i": q.get("id") or ""}
        m = lvcn.get(lm)
        if m:
            item["m"] = m
        t = q.get("type") or ""
        if t:
            item["t"] = t
        lk = q.get("localization_key") or ""
        if lk:
            item["lk"] = lk
        world.append(item)

    payload = {
        "v": 1,
        "daily": {"n": len(daily), "items": daily},
        "world": {"n": len(world), "items": world},
    }
    size = dump_json(os.path.join(out_dir, "quests.json"), payload)
    return {"daily": len(daily), "world": len(world)}, size


def build_seasons(data_dir: str, out_dir: str, season_cn: dict, events: dict) -> tuple[int, int]:
    base = int(events["base_time"])
    # 按赛季号收集事件起始（跳过 when=0 的占位，除非全无）
    starts = collections.defaultdict(list)
    for e in events.get("events") or []:
        name = e.get("name") or ""
        m = re.match(r"^(?:season_(\d{2})_|quest_ap(\d{2})_|ap(\d{2})_|visiting_vendor_questap(\d{2}))", name)
        if not m:
            continue
        nums = [g for g in m.groups() if g]
        if not nums:
            continue
        for w in e.get("when") or []:
            starts[int(nums[0])].append(base + int(w))
    season_nums = sorted(int(k.split("_")[1]) for k in season_cn if k.startswith("season_"))
    items = []
    for n in sorted(set(season_nums) | set(starts)):
        key = "season_%02d" % n
        rec = {"n": n, "k": key, "zh": season_cn.get(key) or ""}
        ss = [s for s in starts.get(n, []) if s > base]
        if not ss and n == 6 and starts.get(6):
            ss = starts[6]  # season_06_ever_started 的 when=0 -> 开服日
        if ss:
            st = min(ss)
            rec["start"] = dstr(st)
        rec["est"] = 1  # 由活动排期反推，非官方赛季表
        items.append(rec)
    # 补 end：下一季开始前一天（仅当都有 start）
    for i, rec in enumerate(items):
        nxt = None
        for j in range(i + 1, len(items)):
            if items[j].get("start"):
                nxt = items[j]["start"]
                break
        if rec.get("start") and nxt:
            d = dt.datetime.strptime(nxt, "%Y-%m-%d") - dt.timedelta(days=1)
            rec["end"] = d.strftime("%Y-%m-%d")
    size = dump_json(os.path.join(out_dir, "seasons.json"), {"v": 1, "n": len(items), "est": 1, "items": items})
    return len(items), size


def build_activities(out_dir: str, events_doc: dict, days: int = 7) -> tuple[dict, int]:
    es = events_doc.get("event_schedule") or events_doc
    base = int(es["base_time"])
    now = int(es.get("server_time") or dt.datetime.now().timestamp())
    active, upcoming = [], []
    seen = set()
    for e in es.get("events") or []:
        name = e.get("name") or ""
        dur_min = int(e.get("duration") or 0)
        for w in e.get("when") or []:
            start = base + int(w)
            end = start + dur_min * 60
            key = (name, start)
            if key in seen:
                continue
            seen.add(key)
            prefix = name.split("_")[0]
            rec = {
                "n": name,
                "p": prefix,
                "cn": PREFIX_CN.get(prefix, "其他"),
                "st": ts_str(start),
                "et": ts_str(end),
                "mm": dur_min,
            }
            if start <= now < end:
                active.append((start, rec))
            elif now < start <= now + days * 86400:
                upcoming.append((start, rec))
    # 小于 MIN_MM 分钟的循环玩法不进日历，只做计数；>= PERM_MM 的是"解锁标记"，也不当日历事件
    MIN_MM = 30
    PERM_MM = 30 * 24 * 60  # 30 天
    perm_active = sum(1 for _, r in active if r["mm"] >= PERM_MM)
    perm_upcoming = sum(1 for _, r in upcoming if r["mm"] >= PERM_MM)
    active = [(s, r) for s, r in active if r["mm"] < PERM_MM]
    upcoming = [(s, r) for s, r in upcoming if r["mm"] < PERM_MM]
    loops_active = sum(1 for _, r in active if r["mm"] < MIN_MM)
    loops_upcoming = sum(1 for _, r in upcoming if r["mm"] < MIN_MM)
    active = [(s, r) for s, r in active if r["mm"] >= MIN_MM]
    upcoming = [(s, r) for s, r in upcoming if r["mm"] >= MIN_MM]
    active.sort(key=lambda x: (-x[1]["mm"], -x[0]))  # 长活动优先，其次最近开始
    upcoming.sort(key=lambda x: x[0])                 # 最近的未来在前
    active = [r for _, r in active[:120]]
    upcoming = [r for _, r in upcoming[:120]]
    payload = {
        "v": 1,
        "generated_at": dt.datetime.now(TZ).isoformat(timespec="seconds"),
        "timezone": "Asia/Shanghai",
        "base_time": base,
        "server_time": now,
        "snapshot": True,
        "note": "活动排期静态快照；生成后不再更新，过期请重跑 tools/build_skynews_assets.py",
        "prefix_cn": PREFIX_CN,
        "active": active,
        "upcoming": upcoming,
        "loops_active": loops_active,
        "loops_upcoming": loops_upcoming,
        "min_minutes_listed": MIN_MM,
        "permanent_active": perm_active,
        "permanent_upcoming": perm_upcoming,
    }
    size = dump_json(os.path.join(out_dir, "activities.json"), payload)
    return {"active": len(active), "upcoming": len(upcoming), "prefix": len(PREFIX_CN),
            "loops_active": loops_active, "loops_upcoming": loops_upcoming,
            "permanent_active": perm_active, "permanent_upcoming": perm_upcoming}, size


# ----------------------------------------------------------------- main
def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description="生成 SkyNews 光遇数据资产")
    ap.add_argument("--data", default=DEFAULT_DATA, help="skycore/data 目录")
    ap.add_argument("--events", default=DEFAULT_EVENTS, help="event_schedule.json")
    ap.add_argument("--out", default=DEFAULT_ROOT, help="输出目录（app/assets/sky）")
    args = ap.parse_args(argv)

    if not os.path.isdir(args.data):
        print("[ERR] 数据包目录不存在:", args.data, file=sys.stderr)
        return 2
    os.makedirs(args.out, exist_ok=True)

    season_cn = season_cn_map(args.data)
    events_doc = load_json(args.events)
    version = load_version(args.data)

    print("[1/6] items.json ...")
    n_items, sz_items = build_items(args.data, args.out, season_cn)
    print(f"      {n_items} 条 / {sz_items/1024:.1f} KB")
    print("[2/6] spirits.json ...")
    n_sp, sz_sp = build_spirits(args.data, args.out, season_cn)
    print(f"      {n_sp} 条 / {sz_sp/1024:.1f} KB")
    print("[3/6] quests.json ...")
    qcnt, sz_q = build_quests(args.data, args.out)
    print(f"      {qcnt} / {sz_q/1024:.1f} KB")
    print("[4/6] seasons.json ...")
    n_ss, sz_ss = build_seasons(args.data, args.out, season_cn, events_doc["event_schedule"])
    print(f"      {n_ss} 条 / {sz_ss/1024:.1f} KB")
    print("[5/6] activities.json ...")
    acnt, sz_ac = build_activities(args.out, events_doc)
    print(f"      {acnt} / {sz_ac/1024:.1f} KB")
    print("[6/6] meta.json ...")

    files = {}
    total = 0
    for fn in ("items.json", "spirits.json", "quests.json", "seasons.json", "activities.json"):
        sz = os.path.getsize(os.path.join(args.out, fn))
        files[fn] = sz
        total += sz
    meta = {
        "v": 1,
        "generated_at": dt.datetime.now(TZ).isoformat(timespec="seconds"),
        "game_version": version,
        "counts": {
            "items": n_items,
            "spirits": n_sp,
            "daily_quests": qcnt["daily"],
            "world_quests": qcnt["world"],
            "seasons": n_ss,
            "activities_active": acnt["active"],
            "activities_upcoming": acnt["upcoming"],
            "activity_prefixes": acnt["prefix"],
            "activity_loops_active": acnt["loops_active"],
            "activity_loops_upcoming": acnt["loops_upcoming"],
            "activity_permanent_active": acnt["permanent_active"],
        },
        "files": files,
        "total_bytes": total,
        "sources": {
            "data_pack": args.data,
            "event_schedule": args.events,
        },
        "notes": [
            "物品名：官方商城名（r=store）或推导名（r=derived，赛季·类别（英文key）），游戏本身没有官方逐件物品名",
            "世界任务（world）无中文标题，仅有地图/类型/id（localization_key 未在字串表中命中）",
            "赛季起止（est=1）由活动排期反推，不是官方赛季表；2-5 季无排期数据故无起止",
            "活动为静态快照，生成时间见 activities.json/generated_at；过期请重跑生成脚本",
        ],
    }
    sz_meta = dump_json(os.path.join(args.out, "meta.json"), meta)
    total += sz_meta
    print(f"      meta / {sz_meta/1024:.1f} KB")
    print(f"[DONE] 6 个文件，总计 {total/1024:.1f} KB -> {args.out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())




