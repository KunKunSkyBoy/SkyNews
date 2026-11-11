# 全自动获取微博 Cookie（不用 F12，复制粘贴都不需要）
#
# 工作方式：
#   1. 打开一个「独立的 Edge 窗口」（不会影响你平时用的浏览器）
#   2. 你在里面登录微博（扫码/账号密码都行）
#   3. 回到这个窗口按 Enter，脚本自动从浏览器里取出登录 Cookie
#   4. 自动加密写入 GitHub Secret → 触发抓取 → 打印结果
#
# 用法：
#   $env:SKYNEWS_GH_TOKEN="ghp_xxx"
#   powershell -ExecutionPolicy Bypass -File tools\get_weibo_cookie_auto.ps1
param(
    [int]$Port = 9333,
    [switch]$KeepBrowser
)
$ErrorActionPreference = 'Stop'

$edge = 'C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe'
if (-not (Test-Path $edge)) { $edge = 'C:\Program Files\Microsoft\Edge\Application\msedge.exe' }
if (-not (Test-Path $edge)) { throw '没找到 Edge 浏览器（脚本目前只支持 Edge）' }

$token = $env:SKYNEWS_GH_TOKEN
if (-not $token) { $token = $env:GH_TOKEN }
if (-not $token) { throw '缺少令牌：请先设置 $env:SKYNEWS_GH_TOKEN' }

$py = 'E:\gongzuoqu\_archive\qq_stack_20261111\zhenxun_bot\venv\Scripts\python.exe'
if (-not (Test-Path $py)) { $py = 'python' }

$oldEap = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
& $py -c "import websocket" 2>$null
$wsOk = ($LASTEXITCODE -eq 0)
$ErrorActionPreference = $oldEap
if (-not $wsOk) {
    Write-Host '首次运行：安装依赖 websocket-client ...'
    & $py -m pip install --disable-pip-version-check -q websocket-client
    if ($LASTEXITCODE -ne 0) { throw 'websocket-client 安装失败' }
}

$profile = Join-Path $env:TEMP ('skynews-weibo-' + [guid]::NewGuid().ToString('N'))
Write-Host '正在打开独立的 Edge 窗口（不影响你日常浏览器）...' -ForegroundColor Cyan
$browser = Start-Process -FilePath $edge -PassThru -ArgumentList @(
    "--remote-debugging-port=$Port",
    "--remote-allow-origins=*",
    "--user-data-dir=$profile",
    "--no-first-run",
    "--no-default-browser-check",
    '--start-maximized',
    'https://weibo.com'
)

try {
    # 等调试端口就绪
    $ready = $false
    for ($i = 0; $i -lt 60; $i++) {
        Start-Sleep -Milliseconds 500
        try {
            $r = Invoke-WebRequest "http://127.0.0.1:$Port/json" -UseBasicParsing -TimeoutSec 2
            if ($r.StatusCode -eq 200) { $ready = $true; break }
        } catch { }
    }
    if (-not $ready) { throw '浏览器调试端口未就绪（可能被安全软件拦了），可重跑一次' }

    Write-Host ''
    Write-Host '【操作】在弹出的 Edge 窗口里登录微博（右上角「登录」，扫码或账号密码均可）' -ForegroundColor Yellow
    Write-Host '        登录成功后，回到这个窗口按 Enter 继续。' -ForegroundColor Yellow
    Write-Host ''
    Read-Host '登录完成后按 Enter'

    $cookie = & $py -c @"
import sys, json, urllib.request, websocket
port = sys.argv[1]
targets = json.load(urllib.request.urlopen('http://127.0.0.1:%s/json' % port, timeout=8))
pages = [t for t in targets if t.get('type') == 'page']
if not pages:
    print('')
    sys.exit(0)
weibo_pages = [t for t in pages if 'weibo' in (t.get('url') or '')]
target = (weibo_pages or pages)[0]
ws = websocket.create_connection(target['webSocketDebuggerUrl'], timeout=12)
ws.send(json.dumps({'id': 1, 'method': 'Network.getAllCookies'}))
msg = None
for _ in range(50):
    m = json.loads(ws.recv())
    if m.get('id') == 1:
        msg = m
        break
ws.close()
cookies = (msg or {}).get('result', {}).get('cookies', [])
sel = [c for c in cookies if 'weibo' in (c.get('domain') or '') or 'sina' in (c.get('domain') or '')]
header = '; '.join('%s=%s' % (c['name'], c['value']) for c in sel)
print(header)
"@ $Port 2>$null
    $cookie = ($cookie | Select-Object -Last 1).Trim()

    if (-not $cookie -or $cookie -notmatch 'SUB=') {
        Write-Host '没取到登录 Cookie（可能还没登录成功/登录的是其他网站）。' -ForegroundColor Yellow
        Write-Host '请确认浏览器里微博右上角已显示你的头像/昵称，再重跑一次本脚本。' -ForegroundColor Yellow
        throw '未获取到登录态'
    }
    Write-Host ("已获取 Cookie（{0} 字符）" -f $cookie.Length) -ForegroundColor Green

    # 写入 Secret + 触发抓取
    & powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot 'set_weibo_cookie.ps1') -Cookie $cookie
} finally {
    if (-not $KeepBrowser) {
        try { & taskkill.exe /PID $browser.Id /T /F 2>$null | Out-Null } catch { }
        Start-Sleep -Seconds 1
        Remove-Item $profile -Recurse -Force -ErrorAction SilentlyContinue
    }
}
