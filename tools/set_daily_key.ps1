# 一键写入独角兽API密钥（UNICORN_KEY）到 GitHub Actions Secret，并触发一次抓取验证
# 用法：
#   $env:SKYNEWS_GH_TOKEN="你的GitHub令牌"
#   powershell -ExecutionPolicy Bypass -File tools\set_daily_key.ps1 -Key "你的独角兽API密钥"
param(
    [string]$Key = '',
    [string]$Repo = 'KunKunSkyBoy/SkyNews',
    [switch]$DryRun,
    [switch]$NoTrigger
)
$ErrorActionPreference = 'Stop'
$token = $env:SKYNEWS_GH_TOKEN
if (-not $token) { $token = $env:GH_TOKEN }
if (-not $token) { throw '缺少令牌：请先设置 $env:SKYNEWS_GH_TOKEN' }
if (-not $Key) { $Key = (Read-Host '粘贴独角兽API密钥（控制台→密钥管理）').Trim() }
if ($Key.Length -lt 8) { throw '密钥太短，请确认复制完整' }

$py = 'E:\gongzuoqu\_archive\qq_stack_20261111\zhenxun_bot\venv\Scripts\python.exe'
if (-not (Test-Path $py)) { $py = 'python' }

$oldEap = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
& $py -c "import nacl" 2>$null
$naclOk = ($LASTEXITCODE -eq 0)
$ErrorActionPreference = $oldEap
if (-not $naclOk) {
    Write-Host '安装加密依赖 pynacl ...'
    & $py -m pip install --disable-pip-version-check -q pynacl
    if ($LASTEXITCODE -ne 0) { throw 'pynacl 安装失败' }
}

$env:SKYNEWS_REPO = $Repo
$env:SKYNEWS_TOKEN = $token
$env:SKYNEWS_KEY_RAW = $Key
$env:SKYNEWS_DRY_RUN = if ($DryRun) { '1' } else { '0' }
$env:PYTHONUTF8 = '1'

Write-Host '正在写入 GitHub Secret（UNICORN_KEY）...' -ForegroundColor Cyan
& $py -c @"
import os, json, base64, urllib.request, urllib.error
from nacl.public import PublicKey, SealedBox
repo = os.environ['SKYNEWS_REPO']; tok = os.environ['SKYNEWS_TOKEN']
val = os.environ['SKYNEWS_KEY_RAW'].encode(); dry = os.environ.get('SKYNEWS_DRY_RUN') == '1'
api = 'https://api.github.com/repos/%s' % repo
def call(url, data=None, method='GET'):
    r = urllib.request.Request(url, data=data, method=method, headers={
        'Authorization': 'token ' + tok, 'User-Agent': 'skynews-daily-key',
        'Accept': 'application/vnd.github+json', 'Content-Type': 'application/json'})
    try:
        with urllib.request.urlopen(r, timeout=30) as resp:
            return resp.status, json.loads(resp.read().decode() or '{}')
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read().decode('utf-8', 'replace') or '{}')
st, meta = call(api + '/actions/secrets/public-key')
if st != 200:
    print('获取公钥失败:', st, meta.get('message')); raise SystemExit(1)
sealed = SealedBox(PublicKey(base64.b64decode(meta['key']))).encrypt(val)
if dry:
    print('DRY-RUN: 加密成功（%d 字节），未写入' % len(sealed)); raise SystemExit(0)
st, res = call(api + '/actions/secrets/UNICORN_KEY',
               data=json.dumps({'encrypted_value': base64.b64encode(sealed).decode(),
                                'key_id': meta['key_id']}).encode(), method='PUT')
print('写入 Secret:', st)
if st not in (201, 204):
    print('失败:', res.get('message')); raise SystemExit(1)
print('OK: UNICORN_KEY 已写入')
"@
if ($LASTEXITCODE -ne 0) { throw '写入 Secret 失败' }
if ($DryRun) { Write-Host 'DryRun 完成（未写入）' -ForegroundColor Yellow; return }

if (-not $NoTrigger) {
    Write-Host '触发一次抓取验证 ...' -ForegroundColor Cyan
    '{"ref":"main"}' | Out-File -Encoding ascii (Join-Path $env:TEMP 'skynews-dispatch.json')
    curl.exe -s -X POST -H "Authorization: token $token" -H "User-Agent: skynews-daily-key" -H "Accept: application/vnd.github+json" -H "Content-Type: application/json" --data-binary "@$(Join-Path $env:TEMP 'skynews-dispatch.json')" -o NUL "https://api.github.com/repos/$Repo/actions/workflows/relay.yml/dispatches"
    Write-Host '已触发；约 40 秒后可打开 https://kunkunskyboy.github.io/SkyNews/feed.json 看「每日任务」条目。' -ForegroundColor Green
}
Write-Host ''
Write-Host '完成！之后 App 动态页会显示今日每日任务，到点提醒也会带任务内容。' -ForegroundColor Green
