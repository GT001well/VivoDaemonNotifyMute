#!/usr/bin/env bash
# 构建 vivo-daemon-notify-mute：LSPosed 模块，拦 daemonService 的 10011/10012 通知。
# 不依赖 Gradle/AGP，只用 Android SDK 的 aapt2 / d8 / zipalign / apksigner。
set -euo pipefail
cd "$(dirname "$0")"

SDK="${ANDROID_HOME:-/opt/android-sdk}"
BT_VERSION="${BT_VERSION:-36.0.0}"
BT="$SDK/build-tools/$BT_VERSION"
PLATFORM="$SDK/platforms/android-36/android.jar"
MIN_SDK=27
TARGET_SDK=36
API_JAR="libs/api-82.jar"
KEYSTORE_DIR=".keys"
KEYSTORE="$KEYSTORE_DIR/debug.keystore"

log() { printf '[%s] %s\n' "$(date '+%F %T')" "$*"; }
die() { printf '[%s] ERROR: %s\n' "$(date '+%F %T')" "$*" >&2; exit 1; }

# 先验前提
[ -x "$BT/aapt2" ] || die "找不到 aapt2：$BT/aapt2"
[ -f "$PLATFORM" ] || die "找不到 android.jar：$PLATFORM"
[ -f "$API_JAR" ] || die "找不到 Xposed API jar：$API_JAR"
for tool in zip jar javac keytool; do
  command -v "$tool" >/dev/null || die "缺少命令：$tool"
done
for tool in d8 zipalign apksigner; do
  [ -x "$BT/$tool" ] || die "缺少 build-tools 工具：$BT/$tool"
done
if command -v sha256sum >/dev/null; then
  HASH_CMD=(sha256sum)
elif command -v shasum >/dev/null; then
  HASH_CMD=(shasum -a 256)
else
  die "缺少摘要工具：sha256sum 或 shasum"
fi

STAMP="$(date '+%Y%m%d-%H%M%S')"
BUILD="build"
OUT="out"
APK_NAME="vivo-daemon-notify-mute-$STAMP.apk"

log "清理构建目录"
rm -rf "$BUILD"
mkdir -p "$BUILD/classes" "$BUILD/dex" "$OUT" "$KEYSTORE_DIR"

log "aapt2 compile 资源"
"$BT/aapt2" compile --dir src/main/res -o "$BUILD/res.zip"

log "aapt2 link 清单（asset 目录一起打包）"
"$BT/aapt2" link \
  -o "$BUILD/base-unsigned.apk" \
  --manifest src/main/AndroidManifest.xml \
  -I "$PLATFORM" \
  --min-sdk-version "$MIN_SDK" \
  --target-sdk-version "$TARGET_SDK" \
  -A src/main/assets \
  -R "$BUILD/res.zip" \
  --auto-add-overlay

log "javac 编译 Java"
SOURCES=()
while IFS= read -r src; do
  SOURCES+=("$src")
done < <(find src/main/java -name '*.java' -print)
[ "${#SOURCES[@]}" -gt 0 ] || die "src/main/java 下没有 Java 文件"
javac --release 11 -encoding UTF-8 \
  -cp "$PLATFORM:$API_JAR" \
  -d "$BUILD/classes" \
  "${SOURCES[@]}"

log "d8 生成 dex"
# d8 不接受裸目录（报 Unsupported source file type），先打成 jar 再喂
jar --create --file "$BUILD/classes.jar" -C "$BUILD/classes" .
"$BT/d8" --release --min-api "$MIN_SDK" --lib "$PLATFORM" \
  --output "$BUILD/dex" "$BUILD/classes.jar"

log "把 classes.dex 塞进 APK"
( cd "$BUILD/dex" && zip -q -X "../base-unsigned.apk" classes.dex )

log "zipalign"
"$BT/zipalign" -p -f 4 "$BUILD/base-unsigned.apk" "$BUILD/base-aligned.apk"

# keystore 放 .keys/，不随 build 目录清理，保证多次构建签名一致
if [ ! -f "$KEYSTORE" ]; then
  log "生成本地调试签名（$KEYSTORE，仅本机，不入库）"
  keytool -genkeypair -keystore "$KEYSTORE" -alias saya \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass android -keypass android \
    -dname "CN=Saya Local, OU=Dev, O=Local, L=Local, ST=Local, C=CN" >/dev/null 2>&1
fi

log "apksigner 签名"
"$BT/apksigner" sign \
  --ks "$KEYSTORE" --ks-pass pass:android --key-pass pass:android \
  --ks-key-alias saya \
  --out "$OUT/$APK_NAME" \
  "$BUILD/base-aligned.apk"

log "apksigner 校验"
"$BT/apksigner" verify --print-certs "$OUT/$APK_NAME"

log "产物：$(pwd)/$OUT/$APK_NAME"
log "SHA256：$("${HASH_CMD[@]}" "$OUT/$APK_NAME" | awk '{print $1}')"
