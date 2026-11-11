# SkyNews 一键补全/优化脚本（Windows）
#   - 构建 APK（无 Gradle）
#   - 重新生成光遇资料 assets（可选）
#   - 发布到 dist/（自动版本/大小/sha256）
#   - 一键部署到 GitHub（relay + App 源码 + CI）
#   - 部署后验证线上 feed（schema/源健康）
#   - 可选装机冒烟（MuMu / 真机）
#
# 用法：
#   powershell -ExecutionPolicy Bypass -File oneclick.ps1 -Action all
#   powershell -ExecutionPolicy Bypass -File oneclick.ps1 -Action release -Device HA25G639
#   powershell -ExecutionPolicy Bypass -File oneclick.ps1 -Action data
#   powershell -ExecutionPolicy Bypass -File oneclick.ps1 -Action deploy
#   powershell -ExecutionPolicy Bypass -File oneclick.ps1 -Action verify
#
# 部署需要令牌：设置环境变量 SKYNEWS_GH_TOKEN（或 GH_TOKEN），不要写进任何文件。
param(
    [ValidateSet('all', 'release', 'data', 'deploy', 'verify', 'build')]
    [string]$Action = 'all',
    [string]$Device = '',
    [string]$Repo = 'KunKunSkyBoy/SkyNews',
    [string]$Branch = 'main'
)
$ErrorActionPreference = 'Stop'
$ROOT = Split-Path -Parent $MyInvocation.MyCommand.Path
$PY = 'E:\gongzuoqu\_archive\qq_stack_20261111\zhenxun_bot\venv\Scripts\python.exe'
if (-not (Test-Path $PY)) { $PY = 'python' }
$SDK = 'E:\gongzuoqu\逆向环境\android-sdk'
$BT = Join-Path $SDK 'build-tools\35.0.0'
$ADB = Join-Path $SDK 'platform-tools\adb.exe'
$AAPT2 = Join-Path $BT 'aapt2.exe'

function Log($m) { Write-Host "== $m ==" }

function Build-Apk {
    Log '构建 APK'
    & powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $ROOT 'build_win.ps1')
    if ($LASTEXITCODE -ne 0) { throw 'build failed' }
}

function Release-Apk {
    Log '发布到 dist/'
    & powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $ROOT 'release.ps1')
    if ($LASTEXITCODE -ne 0) { throw 'release failed' }
}

function Regen-Data {
    Log '重新生成光遇资料 assets'
    & $PY (Join-Path $ROOT 'tools\build_skynews_assets.py')
    if ($LASTEXITCODE -ne 0) { throw 'assets build failed' }
}

function Install-Smoke([string]$serial) {
    if (-not $serial) { return }
    Log "装机冒烟: $serial"
    & powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $ROOT 'tools\smoke_test_apk.ps1') -Serial $serial
    if ($LASTEXITCODE -ne 0) { throw 'smoke test failed' }
}

function Read-Token {
    $t = $env:SKYNEWS_GH_TOKEN
    if (-not $t) { $t = $env:GH_TOKEN }
    return $t
}

function Deploy-GitHub {
    Log '部署到 GitHub'
    $ErrorActionPreference = 'Continue'   # git 会往 stderr 打进度，PS5.1 下 Stop 会误报
    $token = Read-Token
    if (-not $token) { throw '缺少令牌：请设置环境变量 SKYNEWS_GH_TOKEN 后重试' }
    $tmp = Join-Path $env:TEMP ('skynews-deploy-' + [guid]::NewGuid().ToString('N'))
    $cfg = Join-Path $tmp 'gitconfig'
    New-Item -ItemType Directory -Force $tmp | Out-Null
    Set-Content -Path $cfg -Value '' -Encoding ascii
    $oldCfg = $env:GIT_CONFIG_GLOBAL
    $oldPrompt = $env:GIT_TERMINAL_PROMPT
    try {
        $env:GIT_CONFIG_GLOBAL = $cfg
        $env:GIT_TERMINAL_PROMPT = '0'
        $url = "https://x-access-token:$token@github.com/$Repo.git"
        $clone = Join-Path $tmp 'repo'
        # 直连 GitHub 在国内可能间歇失败：直连重试 3 次，仍失败则用 ghfast 镜像做「只读克隆」
        $cloned = $false
        for ($i = 1; $i -le 3; $i++) {
            git -c user.name='SkyNews Bot' -c user.email='bot@example.com' clone --depth 1 $url $clone 2>$null | Out-Null
            if ($LASTEXITCODE -eq 0) { $cloned = $true; break }
            if (Test-Path $clone) { Remove-Item $clone -Recurse -Force -ErrorAction SilentlyContinue }
            Start-Sleep -Seconds (2 * $i)
        }
        if (-not $cloned) {
            Log '直连失败，改用 ghfast 镜像克隆（只读，推送仍走 GitHub）'
            git -c user.name='SkyNews Bot' -c user.email='bot@example.com' clone --depth 1 "https://ghfast.top/https://github.com/$Repo.git" $clone 2>$null | Out-Null
            if ($LASTEXITCODE -ne 0) { throw 'clone failed（直连与镜像均失败）' }
        }

        # 同步本地文件到仓库（保持仓库与本地一致）
        $items = @(
            @{ src = 'relay'; dst = 'relay'; dir = $true },
            @{ src = '.gitignore'; dst = '.gitignore'; dir = $false },
            @{ src = '.github\workflows'; dst = '.github\workflows'; dir = $true },
            @{ src = 'app\src'; dst = 'app\src'; dir = $true },
            @{ src = 'app\assets'; dst = 'app\assets'; dir = $true },
            @{ src = 'app\AndroidManifest.xml'; dst = 'app\AndroidManifest.xml'; dir = $false },
            @{ src = 'tools'; dst = 'tools'; dir = $true },
            @{ src = 'build_win.ps1'; dst = 'build_win.ps1'; dir = $false },
            @{ src = 'release.ps1'; dst = 'release.ps1'; dir = $false },
            @{ src = 'oneclick.ps1'; dst = 'oneclick.ps1'; dir = $false },
            @{ src = 'README.md'; dst = 'README.md'; dir = $false },
            @{ src = 'README-使用说明.md'; dst = 'README-使用说明.md'; dir = $false },
            @{ src = 'UPGRADE_PLAN.md'; dst = 'UPGRADE_PLAN.md'; dir = $false },
            @{ src = 'HANDOVER.md'; dst = 'HANDOVER.md'; dir = $false },
            @{ src = 'SETUP_WEIBO.md'; dst = 'SETUP_WEIBO.md'; dir = $false },
            @{ src = 'docs'; dst = 'docs'; dir = $true }
        )
        foreach ($it in $items) {
            $s = Join-Path $ROOT $it.src
            if (-not (Test-Path $s)) { continue }
            $d = Join-Path $clone $it.dst
            if ($it.dir) {
                # 目录用「先删后拷」保证与本地一致（避免嵌套副本 / 旧文件残留）
                if ($it.dst -eq 'docs' -and (Test-Path (Join-Path $d 'feed.json'))) {
                    # docs 由 CI 维护，已有 feed 时不覆盖，避免把线上较新的 feed 回滚
                    continue
                }
                if (Test-Path $d) { Remove-Item $d -Recurse -Force }
                New-Item -ItemType Directory -Force $d | Out-Null
                Copy-Item (Join-Path $s '*') $d -Recurse -Force
            } else {
                New-Item -ItemType Directory -Force (Split-Path $d) | Out-Null
                Copy-Item $s $d -Force
            }
        }
        # 清理历史遗留的嵌套副本（旧版脚本 Copy-Item 目录语义造成）
        $badDirs = @('.github/workflows/workflows', 'relay/relay', 'tools/tools',
                     'app/assets/assets', 'app/src/src', 'docs/docs', 'app/app')
        foreach ($b in $badDirs) {
            $bd = Join-Path $clone $b
            if (Test-Path $bd) { Remove-Item $bd -Recurse -Force; Log "清理嵌套副本 $b" }
        }
        # GitHub Pages 强制静态托管（跳过 Jekyll，避免 legacy Pages 构建报错）
        $docsD = Join-Path $clone 'docs'
        New-Item -ItemType Directory -Force $docsD | Out-Null
        if (-not (Test-Path (Join-Path $docsD '.nojekyll'))) {
            Set-Content -Path (Join-Path $docsD '.nojekyll') -Value '' -Encoding ascii
        }
        # 发布产物
        $apk = Join-Path $ROOT 'skynews.apk'
        if (Test-Path $apk) { Copy-Item $apk (Join-Path $clone 'skynews.apk') -Force }

        Push-Location $clone
        git add -A
        $msg = 'chore: one-click sync ' + (Get-Date -Format 'yyyy-MM-dd HH:mm')
        git -c user.name='SkyNews Bot' -c user.email='bot@example.com' commit -m $msg 2>&1 | Out-Null
        # push 可能遇到 CI 刚提交的 feed（非快进）或网络抖动：rebase 后重试 4 次
        $pushed = $false
        for ($i = 1; $i -le 4; $i++) {
            git push $url $Branch 2>&1 | Out-Null
            if ($LASTEXITCODE -eq 0) { $pushed = $true; break }
            Log "push 失败，pull --rebase 后重试 ($i/4)"
            git pull --rebase --autostash $url $Branch 2>&1 | Out-Null
            Start-Sleep -Seconds (2 * $i)
        }
        if (-not $pushed) { Pop-Location; throw 'push failed（重试 4 次仍失败）' }
        Pop-Location
        Log '已推送'
    } finally {
        $env:GIT_CONFIG_GLOBAL = $oldCfg
        $env:GIT_TERMINAL_PROMPT = $oldPrompt
        Remove-Item $tmp -Recurse -Force -ErrorAction SilentlyContinue
    }
}

function Verify-Online {
    Log '验证线上中继'
    Start-Sleep -Seconds 10
    $feed = curl.exe -s --max-time 20 "https://kunkunskyboy.github.io/SkyNews/feed.json"
    $file = Join-Path $env:TEMP 'skynews-feed-verify.json'
    $feed | Out-File -Encoding utf8 $file
    & $PY -c @"
import json, sys
raw = open(sys.argv[1], 'rb').read().lstrip(b'\xef\xbb\xbf')
try:
    d = json.loads(raw.decode('utf-8'))
except Exception as e:
    print('FEED PARSE FAIL:', e); sys.exit(1)
print('schema:', d.get('schema'), 'items:', len(d.get('items') or []))
ok = 0
for k, v in (d.get('sources') or {}).items():
    print('  %-10s %-4s count=%-4s %sms' % (k, 'ok' if v.get('ok') else 'fail', v.get('count'), v.get('elapsed_ms')))
    if v.get('ok'): ok += 1
print('sources ok: %d/%d' % (ok, len(d.get('sources') or {})))
sys.exit(0 if d.get('schema') == 2 else 2)
"@ $file
    if ($LASTEXITCODE -ne 0) { throw '线上 feed 验证失败' }
    Log '线上 feed 正常（schema:2）'
}

function Bump-Version {
    # 从 AndroidManifest 读取当前版本，仅展示（版本号升级手动/由需求决定）
    $m = Join-Path $ROOT 'app\AndroidManifest.xml'
    $t = Get-Content $m -Raw -Encoding UTF8
    if ($t -match 'versionName="([^"]+)"') { Log "当前版本 $($Matches[1])" }
}

Bump-Version
if ($Action -eq 'build') { Build-Apk }
elseif ($Action -eq 'release') { Build-Apk; Release-Apk; Install-Smoke $Device }
elseif ($Action -eq 'data') { Regen-Data; Build-Apk; Release-Apk; Install-Smoke $Device }
elseif ($Action -eq 'deploy') { Deploy-GitHub; Verify-Online }
elseif ($Action -eq 'verify') { Verify-Online }
elseif ($Action -eq 'all') { Regen-Data; Build-Apk; Release-Apk; Install-Smoke $Device; Deploy-GitHub; Verify-Online }
Log '完成'
