# 光遇公告 APK 构建脚本（Windows / PC 版，javac + aapt2 + d8 + apksigner，不用 Gradle）
# 用法：powershell -ExecutionPolicy Bypass -File build_win.ps1
$ErrorActionPreference = 'Stop'
$ROOT = Split-Path -Parent $MyInvocation.MyCommand.Path
$APP = Join-Path $ROOT 'app'
$OUT = Join-Path $APP 'build'

$JDK = 'C:\Program Files\Java\jdk-21.0.12'
$JAVA = Join-Path $JDK 'bin\java.exe'
$JAVAC = Join-Path $JDK 'bin\javac.exe'
$KEYTOOL = Join-Path $JDK 'bin\keytool.exe'

$SDK = 'E:\gongzuoqu\逆向环境\android-sdk'
$BT = Join-Path $SDK 'build-tools\35.0.0'
$AAPT2 = Join-Path $BT 'aapt2.exe'
$D8 = Join-Path $BT 'd8.bat'
$APKSIGNER = Join-Path $BT 'apksigner.bat'
$ANDROID_JAR = Join-Path $SDK 'platforms\android-35\android.jar'

$PY = 'E:\gongzuoqu\_archive\qq_stack_20261111\zhenxun_bot\venv\Scripts\python.exe'
if (-not (Test-Path $PY)) { $PY = 'python' }

Write-Host '[1/6] clean'
if (Test-Path $OUT) { Remove-Item $OUT -Recurse -Force }
New-Item -ItemType Directory -Force (Join-Path $OUT 'classes'), (Join-Path $OUT 'dex'), (Join-Path $OUT 'res') | Out-Null

Write-Host '[2/6] javac'
$srcs = @(Get-ChildItem (Join-Path $APP 'src') -Recurse -Filter *.java | ForEach-Object { $_.FullName })
& $JAVAC --release 8 -nowarn -encoding UTF-8 -cp $ANDROID_JAR -d (Join-Path $OUT 'classes') @srcs
if ($LASTEXITCODE -ne 0) { throw 'javac failed' }

Write-Host '[3/6] aapt2 compile'
& $AAPT2 compile --dir (Join-Path $APP 'res') -o (Join-Path $OUT 'res')
if ($LASTEXITCODE -ne 0) { throw 'aapt2 compile failed' }

Write-Host '[4/6] aapt2 link'
$flats = @(Get-ChildItem (Join-Path $OUT 'res') -Recurse -Filter *.flat | ForEach-Object { $_.FullName })
$assetsDir = Join-Path $APP 'assets'
$linkArgs = @('link', '-o', (Join-Path $OUT 'base.apk'), '-I', $ANDROID_JAR, '--manifest', (Join-Path $APP 'AndroidManifest.xml'), '--auto-add-overlay')
if (Test-Path $assetsDir) { $linkArgs += @('-A', $assetsDir) }
$linkArgs += $flats
& $AAPT2 @linkArgs
if ($LASTEXITCODE -ne 0) { throw 'aapt2 link failed' }

Write-Host '[4b/6] normalize asset entry names (Windows aapt2 uses backslash in local headers)'
& $PY -c @"
import sys, zipfile, shutil
src = sys.argv[1]
tmp = src + '.norm'
with zipfile.ZipFile(src) as zi, zipfile.ZipFile(tmp, 'w', zipfile.ZIP_DEFLATED) as zo:
    for info in zi.infolist():
        data = zi.read(info.filename)
        name = info.filename.replace(chr(92), '/')
        out = zipfile.ZipInfo(name, date_time=info.date_time)
        out.compress_type = info.compress_type
        out.external_attr = info.external_attr
        zo.writestr(out, data)
shutil.move(tmp, src)
print('normalized entries:', len(zipfile.ZipFile(src).namelist()))
"@ (Join-Path $OUT 'base.apk')
if ($LASTEXITCODE -ne 0) { throw 'zip normalize failed' }

Write-Host '[5/6] d8'
$classes = @(Get-ChildItem (Join-Path $OUT 'classes') -Recurse -Filter *.class | ForEach-Object { $_.FullName })
& $D8 --lib $ANDROID_JAR --min-api 26 --release --output (Join-Path $OUT 'dex') @classes
if ($LASTEXITCODE -ne 0) { throw 'd8 failed' }

Write-Host '[6/6] package + sign'
$unsigned = Join-Path $OUT 'unsigned.apk'
& $PY -c @"
import sys, zipfile, shutil
src, dex, dst = sys.argv[1], sys.argv[2], sys.argv[3]
shutil.copyfile(src, dst)
with zipfile.ZipFile(dst, 'a', zipfile.ZIP_DEFLATED) as z:
    z.write(dex, 'classes.dex')
print('entries:', zipfile.ZipFile(dst).namelist()[:8], '...')
"@ (Join-Path $OUT 'base.apk') (Join-Path $OUT 'dex\classes.dex') $unsigned
if ($LASTEXITCODE -ne 0) { throw 'zip append failed' }

Write-Host '[6b/6] zipalign (resources.arsc 必须 4 字节对齐且不压缩)'
$ZIPALIGN = Join-Path $BT 'zipalign.exe'
$aligned = Join-Path $OUT 'aligned.apk'
if (Test-Path $aligned) { Remove-Item $aligned -Force }
& $ZIPALIGN -f -p 4 $unsigned $aligned
if ($LASTEXITCODE -ne 0) { throw 'zipalign failed' }

$KS = Join-Path $APP 'debug.keystore'
if (-not (Test-Path $KS)) {
    & $KEYTOOL -genkeypair -keystore $KS -alias androiddebugkey -storepass android -keypass android -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Android Debug,O=Android,C=US" | Out-Null
}
$apk = Join-Path $ROOT 'skynews.apk'
if (Test-Path $apk) { Remove-Item $apk -Force }
& $APKSIGNER sign --ks $KS --ks-pass pass:android --key-pass pass:android --out $apk $aligned
if ($LASTEXITCODE -ne 0) { throw 'apksigner failed' }
& $APKSIGNER verify --print-certs $apk | Select-Object -First 5
Get-Item $apk | Select-Object FullName, Length, LastWriteTime
Write-Host "DONE: $apk"
