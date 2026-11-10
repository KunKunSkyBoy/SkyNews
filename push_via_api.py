#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把当前目录推成一个 GitHub 提交（Git Data API），绕开被墙的 github.com:443。

api.github.com 在本机可用，github.com 的 git-upload-pack 不通（SSL EOF），
所以用 blobs → tree → commit → ref 四步，一次干净提交。

用法：python3 push_via_api.py [owner/repo]
"""
import base64
import json
import os
import sys
import urllib.error
import urllib.request

API = "https://api.github.com"
ROOT = os.path.dirname(os.path.abspath(__file__))
TOKEN = open(os.path.join(ROOT, "GitHubtoken.txt"), encoding="utf-8").read().strip()
SLUG = sys.argv[1] if len(sys.argv) > 1 else "KunKunSkyBoy/SkyNews"
OWNER, REPO = SLUG.split("/", 1)

EXCLUDE_DIRS = {".git", "app/build", "docs"}
EXCLUDE_FILES = {"GitHubtoken.txt", "debug.keystore"}


def api(method, path, payload=None, ok=(200, 201)):
    data = json.dumps(payload).encode() if payload is not None else None
    req = urllib.request.Request(API + path, data=data, method=method, headers={
        "Authorization": "Bearer " + TOKEN,
        "Accept": "application/vnd.github+json",
        "User-Agent": "skynews-push",
        "Content-Type": "application/json",
    })
    try:
        with urllib.request.urlopen(req, timeout=90) as r:
            body = r.read().decode()
            return r.status, (json.loads(body) if body else {})
    except urllib.error.HTTPError as e:
        body = e.read().decode()
        return e.code, {"error": body[:400]}


def keep(path):
    parts = path.split("/")
    if parts[0] in EXCLUDE_DIRS:
        return False
    if path in EXCLUDE_FILES:
        return False
    if path.endswith(".idsig") or ".bak-" in path:
        return False
    if path.endswith(".keystore"):
        return False
    return True


def collect():
    out = []
    for root, dirs, names in os.walk(ROOT):
        relroot = os.path.relpath(root, ROOT).replace(os.sep, "/")
        pruned = []
        for d in dirs:
            rel = d if relroot == "." else relroot + "/" + d
            if rel in (".git", "app/build", "docs"):
                pruned.append(d)
        dirs[:] = [d for d in dirs if d not in pruned]
        for n in names:
            full = os.path.join(root, n)
            rel = os.path.relpath(full, ROOT).replace(os.sep, "/")
            if keep(rel):
                out.append(rel)
    return sorted(out)


def ensure_initialized():
    """空仓库不接受 blobs（409）；先用 Contents API 造一个初始提交。"""
    code, res = api("GET", "/repos/%s/%s/git/ref/heads/main" % (OWNER, REPO))
    if code == 200:
        return res["object"]["sha"]
    code, res = api("PUT", "/repos/%s/%s/contents/.gitkeep" % (OWNER, REPO), {
        "message": "init",
        "content": base64.b64encode(b"skynews\n").decode(),
    })
    if code in (200, 201):
        return res["commit"]["sha"]
    print("bootstrap fail", code, res)
    return None


def main():
    files = collect()
    print("files:", len(files))
    parent = ensure_initialized()
    print("parent:", (parent or "(none)")[:8])
    entries = []
    for i, rel in enumerate(files, 1):
        with open(os.path.join(ROOT, rel), "rb") as f:
            content = base64.b64encode(f.read()).decode()
        code, res = api("POST", "/repos/%s/%s/git/blobs" % (OWNER, REPO),
                        {"content": content, "encoding": "base64"})
        if code not in (200, 201):
            print("blob fail", rel, code, res)
            return 1
        mode = "100755" if rel.endswith(".sh") else "100644"
        entries.append({"path": rel, "mode": mode, "type": "blob", "sha": res["sha"]})
        if i % 10 == 0 or i == len(files):
            print("  blobs %d/%d" % (i, len(files)))

    code, tree = api("POST", "/repos/%s/%s/git/trees" % (OWNER, REPO), {"tree": entries})
    if code not in (200, 201):
        print("tree fail", code, tree)
        return 1
    print("tree:", tree["sha"])

    code, commit = api("POST", "/repos/%s/%s/git/commits" % (OWNER, REPO), {
        "message": "SkyNews: 公告 + 官方动态（TapTap/B站/官网）+ 云中继",
        "tree": tree["sha"],
        "parents": [parent] if parent else [],
    })
    if code not in (200, 201):
        print("commit fail", code, commit)
        return 1
    print("commit:", commit["sha"])

    code, ref = api("POST", "/repos/%s/%s/git/refs" % (OWNER, REPO),
                    {"ref": "refs/heads/main", "sha": commit["sha"]})
    if code not in (200, 201):
        if code == 422:
            code, ref = api("PATCH", "/repos/%s/%s/git/refs/heads/main" % (OWNER, REPO),
                            {"sha": commit["sha"], "force": True})
        # 分支已存在时改为更新指针
        code, ref = api("PATCH", "/repos/%s/%s/git/refs/heads/main" % (OWNER, REPO),
                        {"sha": commit["sha"], "force": True})
        if code not in (200, 201):
            print("ref fail", code, ref)
            return 1
    print("ref main ->", commit["sha"][:8])

    code, repo = api("PATCH", "/repos/%s/%s" % (OWNER, REPO), {"default_branch": "main"})
    print("default_branch:", repo.get("default_branch", code))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
