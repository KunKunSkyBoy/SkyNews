# SkyNews APK smoke test (PC): install -> launch -> alive check -> screenshot -> error log
# usage:
#   powershell -ExecutionPolicy Bypass -File tools\smoke_test_apk.ps1 -Serial emulator-5554
#   powershell -ExecutionPolicy Bypass -File tools\smoke_test_apk.ps1 -Serial HA25G639 -Apk .\skynews.apk
param(
    [Parameter(Mandatory = $true)][string]$Serial,
    [string]$Apk = '',
    [int]$WaitSec = 8
)
$ErrorActionPreference = 'Stop'
$ROOT = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
if (-not $Apk) { $Apk = Join-Path $ROOT 'skynews.apk' }
$SDK = 'E:\gongzuoqu\逆向环境\android-sdk'
$ADB = Join-Path $SDK 'platform-tools\adb.exe'
$PKG = 'com.dsh.skynews'
$ACT = 'com.dsh.skynews/.MainActivity'
$OUT = Join-Path $ROOT 'smoke'
New-Item -ItemType Directory -Force $OUT | Out-Null
$ts = Get-Date -Format 'yyyyMMdd-HHmmss'

Write-Host "== install $Apk to $Serial =="
& $ADB -s $Serial install -r $Apk
if ($LASTEXITCODE -ne 0) { throw 'install failed' }

Write-Host '== clear logcat =='
& $ADB -s $Serial logcat -c 2>$null

Write-Host '== launch =='
& $ADB -s $Serial shell am start -W -n $ACT

Start-Sleep -Seconds $WaitSec

$alive = (& $ADB -s $Serial shell "pidof $PKG" 2>$null | Out-String).Trim()
$focus = (& $ADB -s $Serial shell "dumpsys window | grep mCurrentFocus" 2>$null | Out-String).Trim()

Write-Host "pid: $alive"
Write-Host "focus: $focus"

$shot = Join-Path $OUT ("skynews-$ts.png")
& $ADB -s $Serial exec-out screencap -p > $shot
Write-Host "screenshot: $shot"

$log = Join-Path $OUT ("logcat-$ts.txt")
& $ADB -s $Serial logcat -d -v time 2>$null | Select-String -Pattern "AndroidRuntime|FATAL|$PKG" | Select-Object -Last 80 | Out-File -Encoding utf8 $log
Write-Host "log: $log"

$report = [ordered]@{
    serial  = $Serial
    apk     = $Apk
    pid     = $alive
    focus   = $focus
    aliveOk = [bool]($alive -match '^\d+')
    focusOk = [bool]($focus -match 'com\.dsh\.skynews')
    shot    = $shot
    log     = $log
    at      = (Get-Date).ToString('yyyy-MM-ddTHH:mm:ssK')
}
$reportFile = Join-Path $OUT ("smoke-$ts.json")
$report | ConvertTo-Json | Set-Content $reportFile -Encoding utf8
Write-Host "report: $reportFile"
if (-not $report['aliveOk']) { throw 'app process died (see log)' }
Write-Host 'SMOKE OK'
