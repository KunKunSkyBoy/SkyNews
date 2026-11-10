#!/data/data/com.dsharnessmobile.shell/files/usr/bin/bash
# 一键把 SkyNews 推到 GitHub 并开 Pages + 触发中继。
# 前提：同目录 GitHubtoken.txt 里放一个有效 token（classic: repo+workflow，或 fine-grained:
#       Contents/Pages/Actions 读写 + Metadata 读）。
# 用法：bash deploy.sh [仓库名]     默认仓库名 SkyNews
set -euo pipefail
cd "$(dirname "$0")"

TOK=$(tr -d ' \r\n' < GitHubtoken.txt)
[ -n "$TOK" ] || { echo "✗ GitHubtoken.txt 为空"; exit 1; }
REPO="${1:-SkyNews}"
API="https://api.github.com"
AUTH=(-H "Authorization: Bearer $TOK" -H "Accept: application/vnd.github+json" -H "User-Agent: skynews-deploy")

echo "[1/6] 校验 token"
BODY=$(curl -sS -m 20 "${AUTH[@]}" "$API/user")
LOGIN=$(printf '%s' "$BODY" | python3 -c 'import sys,json;print(json.load(sys.stdin).get("login",""))' 2>/dev/null || true)
if [ -z "$LOGIN" ]; then
  echo "✗ token 无效：$(printf '%s' "$BODY" | head -c 200)"
  echo "  在浏览器自测：https://api.github.com/user?access_token=<TOKEN>"
  exit 1
fi
echo "    ✓ $LOGIN / $REPO"

echo "[2/6] 建仓库（已存在则复用）"
CODE=$(curl -sS -o "$HOME/tmp/gh_repo.json" -w '%{http_code}' -X POST "${AUTH[@]}" \
  "$API/user/repos" -d "{\"name\":\"$REPO\",\"private\":false,\"description\":\"光遇公告 + 官方动态（SkyNews）\"}")
case "$CODE" in
  201) echo "    ✓ 已创建" ;;
  422) echo "    ✓ 已存在，复用" ;;
  *)   echo "✗ 建仓库失败 HTTP $CODE: $(head -c 300 "$HOME/tmp/gh_repo.json")"; exit 1 ;;
esac

echo "[3/6] 提交本地内容"
git init -q 2>/dev/null || true
git add -A
if ! git diff --cached --quiet; then
  git -c user.name=skynews -c user.email=skynews@local commit -q -m "SkyNews: 公告 + 官方动态（TapTap/B站/官网）+ 云中继"
fi
git branch -M main
git remote remove origin 2>/dev/null || true
git remote add origin "https://github.com/$LOGIN/$REPO.git"

echo "[4/6] push（token 只进本次 http header，不写进 .git/config）"
git -c http.extraheader="AUTHORIZATION: bearer $TOK" push -u origin main --force

echo "[5/6] 开启 GitHub Pages（main /docs）"
CODE=$(curl -sS -o "$HOME/tmp/gh_pages.json" -w '%{http_code}' -X POST "${AUTH[@]}" \
  "$API/repos/$LOGIN/$REPO/pages" -d '{"source":{"branch":"main","path":"/docs"}}')
case "$CODE" in
  201) echo "    ✓ Pages 已开启" ;;
  409) echo "    ✓ Pages 已存在" ;;
  *)   echo "    ! Pages HTTP $CODE: $(head -c 200 "$HOME/tmp/gh_pages.json")" ;;
esac

echo "[6/6] 触发一次中继 workflow"
WF=$(curl -sS "${AUTH[@]}" "$API/repos/$LOGIN/$REPO/actions/workflows" \
  | python3 -c 'import sys,json;w=json.load(sys.stdin).get("workflows",[]);print(w[0]["id"] if w else "")' 2>/dev/null || true)
if [ -n "$WF" ]; then
  CODE=$(curl -sS -o /dev/null -w '%{http_code}' -X POST "${AUTH[@]}" \
    "$API/repos/$LOGIN/$REPO/actions/workflows/$WF/dispatches" -d '{"ref":"main"}')
  echo "    workflow=$WF dispatch HTTP $CODE（204 表示已排队）"
else
  echo "    ! 没找到 workflow（Actions 可能未启用）"
fi

URL="https://$LOGIN.github.io/$REPO/feed.json"
echo
echo "中继地址：$URL"
echo "（Pages 首次部署约 1 分钟；Actions 跑完前 feed.json 可能 404，稍等再试）"
