# 一键写入微博 Cookie 到 GitHub Actions Secret，并触发一次抓取测试（新手友好）
#
# 用法：
#   $env:SKYNEWS_GH_TOKEN="ghp_xxx"          # 只在当前窗口，不要写进文件
#   powershell -ExecutionPolicy Bypass -File tools\set_weibo_cookie.ps1
#   # 或直接带参数：
#   powershell -ExecutionPolicy Bypass -File tools\set_weibo_cookie.ps1 -Cookie "SUB=...; SUBP=..."
#
# 说明：
#   * Cookie 只会通过 GitHub 官方加密方式写入仓库 Secret（不会进代码/仓库文件）；
#   * 脚本会自动检查并安装 Python 依赖 pynacl（用于加密）；
#   * 写入后自动触发一次 relay 抓取，并显示微博抓取结果。
param(
    [string]$Cookie = '',
    [string]$Repo = 'KunKunSkyBoy/SkyNews',
    [switch]$DryRun,
    [switch]$NoTrigger
)
$ErrorActionPreference = 'Stop'

$token = $env:SKYNEWS_GH_TOKEN
if (-not $token) { $token = $env:GH_TOKEN }
if (-not $token) { throw '缺少令牌：请先设置 $env:SKYNEWS_GH_TOKEN（GitHub → Settings → Developer settings → Personal access tokens）' }

if (-not $Cookie) {
    Write-Host '请粘贴微博 Cookie（浏览器 F12 → 网络 → 请求标头里的 Cookie 整行，必须包含 SUB=）' -ForegroundColor Cyan
    $Cookie = Read-Host 'Cookie'
}
$Cookie = $Cookie.Trim()
if ($Cookie -notmatch 'SUB=') { throw 'Cookie 里没有 SUB=，请重新复制（要复制完整 Cookie 请求头）' }
Write-Host ("Cookie 长度: {0} 字符" -f $Cookie.Length) -ForegroundColor Green

$py = 'E:\gongzuoqu\_archive\qq_stack_20261111\zhenxun_bot\venv\Scripts\python.exe'
if (-not (Test-Path $py)) { $py = 'python' }

# 1) 确保 pynacl（GitHub Secret 加密需要）
$oldEap = $ErrorActionPreference
$ErrorActionPreference = "Continue"
& $py -c "import nacl" 2>$null
$naclOk = ($LASTEXITCODE -eq 0)
$ErrorActionPreference = $oldEap
if (-not $naclOk) {
    Write-Host '首次运行：安装加密依赖 pynacl ...' -ForegroundColor Yellow
    & $py -m pip install --disable-pip-version-check -q pynacl
    if ($LASTEXITCODE -ne 0) { throw 'pynacl 安装失败，请检查网络/代理' }
}

# 2) 用 Python 完成：取公钥 → sealed box 加密 → PUT secret
$env:SKYNEWS_REPO = $Repo
$env:SKYNEWS_TOKEN = $token
$env:SKYNEWS_COOKIE_RAW = $Cookie
$env:SKYNEWS_DRY_RUN = if ($DryRun) { '1' } else { '0' }
$env:PYTHONUTF8 = '1'

Write-Host '正在写入 GitHub Secret ...' -ForegroundColor Cyan
& $py -c @"
import os, json, base64, urllib.request, urllib.error
from nacl.public import PublicKey, SealedBox

repo = os.environ['SKYNEWS_REPO']
tok = os.environ['SKYNEWS_TOKEN']
cookie = os.environ['SKYNEWS_COOKIE_RAW'].encode()
dry = os.environ.get('SKYNEWS_DRY_RUN') == '1'
api = 'https://api.github.com/repos/%s' % repo

def call(url, data=None, method='GET'):
    r = urllib.request.Request(url, data=data, method=method, headers={
        'Authorization': 'token ' + tok,
        'User-Agent': 'skynews-cookie-helper',
        'Accept': 'application/vnd.github+json',
        'Content-Type': 'application/json'})
    try:
        with urllib.request.urlopen(r, timeout=30) as resp:
            return resp.status, json.loads(resp.read().decode() or '{}')
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read().decode('utf-8', 'replace') or '{}')

st, meta = call(api + '/actions/secrets/public-key')
if st != 200:
    print('获取公钥失败:', st, meta.get('message'))
    raise SystemExit(1)
key_id = meta['key_id']
pub = PublicKey(base64.b64decode(meta['key']))
sealed = SealedBox(pub).encrypt(cookie)
enc = base64.b64encode(sealed).decode()
if dry:
    print('DRY-RUN: 加密成功（%d 字节密文），未写入' % len(sealed))
    raise SystemExit(0)
st, res = call(api + '/actions/secrets/WEIBO_COOKIE',
               data=json.dumps({'encrypted_value': enc, 'key_id': key_id}).encode(),
               method='PUT')
print('写入 Secret:', st)
if st not in (201, 204):
    print('失败:', res.get('message'))
    raise SystemExit(1)
print('OK: WEIBO_COOKIE 已加密写入仓库 Secret')
"@
if ($LASTEXITCODE -ne 0) { throw '写入 Secret 失败' }
if ($DryRun) { Write-Host 'DryRun 完成，未写入。' -ForegroundColor Yellow; return }

# 3) 触发一次抓取并等待结果
if (-not $NoTrigger) {
    Write-Host '触发一次抓取测试 ...' -ForegroundColor Cyan
    $body = '{"ref":"main"}'
    $body | Out-File -Encoding ascii (Join-Path $env:TEMP 'skynews-dispatch.json')
    curl.exe -s -X POST -H "Authorization: token $token" -H "User-Agent: skynews-cookie-helper" -H "Accept: application/vnd.github+json" -H "Content-Type: application/json" --data-binary "@$(Join-Path $env:TEMP 'skynews-dispatch.json')" -o NUL "https://api.github.com/repos/$Repo/actions/workflows/relay.yml/dispatches"

    # 等待最新一次手动触发的 run
    $runId = ''
    for ($i = 0; $i -lt 12 -and -not $runId; $i++) {
        Start-Sleep -Seconds 5
        $runs = curl.exe -s -H "Authorization: token $token" -H "User-Agent: skynews-cookie-helper" "https://api.github.com/repos/$Repo/actions/runs?per_page=5"
        $runs | Out-File -Encoding utf8 (Join-Path $env:TEMP 'skynews-runs.json')
        $runId = & $py -c "import json,sys;raw=open(sys.argv[1],'rb').read().lstrip(b'\xef\xbb\xbf');d=json.loads(raw.decode());r=[x for x in d.get('workflow_runs',[]) if x['name']=='skynews-relay' and x['event']=='workflow_dispatch'];print(r[0]['id'] if r else '')" (Join-Path $env:TEMP 'skynews-runs.json') 2>$null
    }
    if ($runId) {
        Write-Host "等待抓取完成（run $runId）..." -ForegroundColor Cyan
        for ($i = 0; $i -lt 40; $i++) {
            Start-Sleep -Seconds 8
            $r = curl.exe -s -H "Authorization: token $token" -H "User-Agent: skynews-cookie-helper" "https://api.github.com/repos/$Repo/actions/runs/$runId"
            $r | Out-File -Encoding utf8 (Join-Path $env:TEMP 'skynews-run.json')
            $st = & $py -c "import json,sys;raw=open(sys.argv[1],'rb').read().lstrip(b'\xef\xbb\xbf');d=json.loads(raw.decode());print(d['status'])" (Join-Path $env:TEMP 'skynews-run.json') 2>$null
            if ($st -notmatch 'in_progress|queued') { break }
        }
    }

    # 读取仓库 docs/feed.json 里的 weibo 状态
    Start-Sleep -Seconds 5
    curl.exe -s -H "Authorization: token $token" -H "User-Agent: skynews-cookie-helper" "https://api.github.com/repos/$Repo/contents/docs/feed.json?ref=main" -o (Join-Path $env:TEMP 'skynews-feed.json')
    & $py -c @"
import json, base64, sys, os
p = os.path.join(os.environ['TEMP'], 'skynews-feed.json')
d = json.load(open(p, encoding='utf-8'))
f = json.loads(base64.b64decode(d['content']).decode('utf-8'))
w = (f.get('sources') or {}).get('weibo') or {}
if w.get('ok'):
    print('微博抓取成功: %d 条' % w.get('count', 0))
else:
    print('微博仍失败:', (w.get('error') or '')[:160])
    print('→ 可能原因: Cookie 不含登录态（要登录 weibo.com 后复制）、或 Cookie 已过期')
"@
}

Write-Host ''
Write-Host '完成！以后打开 App 刷新就能看到微博动态（中继每 10 分钟自动更新）。' -ForegroundColor Green
Write-Host 'Cookie 一般几周过期；过期后重新运行本脚本即可。' -ForegroundColor DarkGray
