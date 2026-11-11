# SkyNews 发布脚本（Windows / PC）
# 顺带：构建 → 校验版本 → 复制到 dist/ → 可选安装到设备
# 用法：
#   powershell -ExecutionPolicy Bypass -File release.ps1
#   powershell -ExecutionPolicy Bypass -File release.ps1 -Install HA25G639
param(
    [string]$Install = ''
)
$ErrorActionPreference = 'Stop'
$ROOT = Split-Path -Parent $MyInvocation.MyCommand.Path
$SDK = 'E:\gongzuoqu\逆向环境\android-sdk'
$BT = Join-Path $SDK 'build-tools\35.0.0'
$ADB = Join-Path $SDK 'platform-tools\adb.exe'
$AAPT2 = Join-Path $BT 'aapt2.exe'

Write-Host '== build =='
& powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $ROOT 'build_win.ps1')

$APK = Join-Path $ROOT 'skynews.apk'
if (-not (Test-Path $APK)) { throw "APK not found: $APK" }

$badging = & $AAPT2 dump badging $APK 2>$null | Select-Object -First 1
$verName = ''
$verCode = ''
if ($badging -match "versionCode='(\d+)'") { $verCode = $Matches[1] }
if ($badging -match "versionName='([^']+)'") { $verName = $Matches[1] }
Write-Host "version: $verName ($verCode)"

$dist = Join-Path $ROOT 'dist'
New-Item -ItemType Directory -Force $dist | Out-Null
$outApk = Join-Path $dist ("SkyNews-v{0}.apk" -f $verName)
Copy-Item $APK $outApk -Force

$info = [ordered]@{
    name        = 'SkyNews'
    package     = 'com.dsh.skynews'
    versionName = $verName
    versionCode = $verCode
    builtAt     = (Get-Date).ToString('yyyy-MM-ddTHH:mm:ssK')
    sha256      = (Get-FileHash $APK -Algorithm SHA256).Hash.ToLower()
    size        = (Get-Item $APK).Length
    apk         = $outApk
}
$info | ConvertTo-Json | Set-Content (Join-Path $dist 'build-info.json') -Encoding UTF8
Write-Host "dist: $outApk"

if ($Install) {
    Write-Host "== install to $Install =="
    & $ADB -s $Install install -r $APK
    & $ADB -s $Install shell am start -n com.dsh.skynews/.MainActivity
    Write-Host 'installed & launched'
}
