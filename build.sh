#!/data/data/com.dsharnessmobile.shell/files/usr/bin/bash
# 光遇公告 APK 构建脚本（纯 javac + aapt2 + d8 + apksigner，不用 gradle）
set -e
P=/data/data/com.dsharnessmobile.shell/files/usr
JAVA=$P/lib/jvm/bin/java
JAVAC=$P/lib/jvm/bin/javac
KEYTOOL=$P/lib/jvm/bin/keytool
ANDROID_JAR=/storage/emulated/0/MT2/mcp/skyforge-mcp/android-sdk/platforms/android-36/android.jar
APP=/storage/emulated/0/MT2/mcp/SkyNews/app
ROOT=/storage/emulated/0/MT2/mcp/SkyNews
OUT=$APP/build
D8_JAR=$P/share/java/d8.jar
APKSIGNER_JAR=$P/share/java/apksigner.jar

echo "[1/6] 清理"
rm -rf "$OUT"; mkdir -p "$OUT/classes" "$OUT/dex"

echo "[2/6] javac"
"$JAVAC" --release 8 -nowarn -cp "$ANDROID_JAR" -d "$OUT/classes" $(find "$APP/src" -name '*.java')

echo "[3/7] aapt2 compile（编译资源）"
rm -rf "$OUT/res"; mkdir -p "$OUT/res"
"$P/bin/aapt2" compile --dir "$APP/res" -o "$OUT/res" || true

echo "[4/7] aapt2 link（生成二进制清单 APK）"
"$P/bin/aapt2" link -o "$OUT/base.apk" -I "$ANDROID_JAR" --manifest "$APP/AndroidManifest.xml" --auto-add-overlay $(find "$OUT/res" -name '*.flat')

echo "[5/7] d8 → classes.dex"
"$JAVA" -cp "$D8_JAR" com.android.tools.r8.D8 \
  --lib "$ANDROID_JAR" --min-api 26 --release \
  --output "$OUT/dex" $(find "$OUT/classes" -name '*.class')

echo "[6/7] 打包（把 classes.dex 塞进 APK）"
python3 - "$OUT/base.apk" "$OUT/dex/classes.dex" "$OUT/unsigned.apk" <<'PY'
import sys, zipfile, shutil
src, dex, dst = sys.argv[1], sys.argv[2], sys.argv[3]
shutil.copyfile(src, dst)
with zipfile.ZipFile(dst, 'a', zipfile.ZIP_DEFLATED) as z:
    z.write(dex, 'classes.dex')
print('  entries:', zipfile.ZipFile(dst).namelist())
PY

echo "[7/7] 签名"
KS=$APP/debug.keystore
if [ ! -f "$KS" ]; then
  "$KEYTOOL" -genkeypair -keystore "$KS" -alias androiddebugkey \
    -storepass android -keypass android -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=Android Debug,O=Android,C=US" >/dev/null 2>&1
fi
"$JAVA" -jar "$APKSIGNER_JAR" sign --ks "$KS" --ks-pass pass:android --key-pass pass:android \
  --out "$ROOT/skynews.apk" "$OUT/unsigned.apk"
"$JAVA" -jar "$APKSIGNER_JAR" verify --print-certs "$ROOT/skynews.apk" | head -5
ls -l "$ROOT/skynews.apk"
echo "DONE: $ROOT/skynews.apk"
