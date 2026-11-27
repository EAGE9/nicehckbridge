#!/bin/bash
# 构建原道耳机系统通道模块（无 Gradle，直接用 AOSP 自带工具链）
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
AOSP="${AOSP:-$HOME/Android/uwuaosp}"
API_VER=${API_VER:-102}
BIN=$AOSP/out/host/linux-x86/bin
ANDROID_JAR=$AOSP/prebuilts/sdk/36/public/android.jar
LIBX=${LIBX:-$HERE/libs/api$API_VER/classes.jar}
OUT=$HERE/build
KEYDIR="${KEYDIR:-$HOME/Android/keys/uwu-release}"

# ---- 版本号：唯一来源是 version.properties ----
VER_FILE="$HERE/version.properties"
if [ -f "$VER_FILE" ]; then
  VCODE=$(sed -n 's/^code=//p' "$VER_FILE")
  VNAME=$(sed -n 's/^name=//p' "$VER_FILE")
else
  VCODE=1; VNAME=1.0
fi
VCODE=${VCODE:-1}; VNAME=${VNAME:-1.0}
mkdir -p "$HERE/src/io/github/eage9/nicehckbridge"
cat > "$HERE/src/io/github/eage9/nicehckbridge/BuildInfo.java" <<EOF
package io.github.eage9.nicehckbridge;

/** 由 build.sh 依据 version.properties 自动生成，勿手改（已 .gitignore） */
public final class BuildInfo {
    public static final String VERSION_NAME = "$VNAME";
    public static final int VERSION_CODE = $VCODE;
    private BuildInfo() {}
}
EOF
echo "== version: $VCODE ($VNAME) =="

rm -rf "$OUT"; mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex"

# 把本次版本写入 manifest（仓库中 manifest 始终反映最近一次构建的版本）
sed -i "s#android:versionCode=\"[0-9]*\"#android:versionCode=\"$VCODE\"#" "$HERE/AndroidManifest.xml"
sed -i "s#android:versionName=\"[^\"]*\"#android:versionName=\"$VNAME\"#" "$HERE/AndroidManifest.xml"
echo "== aapt2 compile/link =="
"$BIN/aapt2" compile --dir "$HERE/res" -o "$OUT/res.zip"
"$BIN/aapt2" link -o "$OUT/app-unsigned.apk" \
  -I "$ANDROID_JAR" \
  --manifest "$HERE/AndroidManifest.xml" \
  -A "" --java "$OUT/gen" "$OUT/res.zip" 2>/dev/null || \
"$BIN/aapt2" link -o "$OUT/app-unsigned.apk" \
  -I "$ANDROID_JAR" --manifest "$HERE/AndroidManifest.xml" --java "$OUT/gen" "$OUT/res.zip"

echo "== aidl =="
"$AOSP/out/host/linux-x86/bin/aidl" --lang=java -I"$HERE/aidl" -o"$OUT/aidl" $(find "$HERE/aidl" -name '*.aidl')

echo "== javac =="
javac --release 17 -nowarn -encoding UTF-8 \
  -classpath "$ANDROID_JAR:$LIBX" \
  -d "$OUT/classes" \
  $(find "$HERE/src" -name '*.java') \
  $(find "$OUT/gen" -name 'R.java') \
  $(find "$OUT/aidl" -name '*.java')

echo "== d8 =="
"$BIN/d8" --lib "$ANDROID_JAR" --min-api 33 --output "$OUT/dex" \
  $(find "$OUT/classes" -name '*.class')

echo "== package =="
cd "$OUT"
cp app-unsigned.apk app-dex.apk
zip -q -j app-dex.apk dex/classes.dex
# libxposed 模块元数据（LSPosed 靠这个识别模块）；
# targetApiVersion 必须等于框架 API，否则会因 101 的破坏性变更被判不兼容而停用
rm -rf meta && mkdir -p meta/META-INF/xposed
cp "$HERE/xposed/META-INF/xposed/java_init.list" meta/META-INF/xposed/
cp "$HERE/xposed/META-INF/xposed/scope.list" meta/META-INF/xposed/
{
  # minApiVersion 放宽到 100（Compat 层已用反射兼容 101 的破坏性变更）
  echo "minApiVersion=100"
  echo "targetApiVersion=$API_VER"
  echo "staticScope=true"
} > meta/META-INF/xposed/module.prop
(cd meta && zip -q -r ../app-dex.apk META-INF)

"$BIN/zipalign" -f -p 4 app-dex.apk app-aligned.apk
KEY_NAME=${KEY_NAME:-release}
"$BIN/apksigner" sign \
  --key "$KEYDIR/$KEY_NAME.pk8" --cert "$KEYDIR/$KEY_NAME.x509.pem" \
  --v2-signing-enabled true --v3-signing-enabled true \
  --out "nicehck-system-bridge-api$API_VER.apk" app-aligned.apk
cp "nicehck-system-bridge-api$API_VER.apk" nicehck-system-bridge.apk
mkdir -p "$HERE/dist" && cp "nicehck-system-bridge-api$API_VER.apk" "$HERE/dist/"

echo "== done =="
ls -la "$OUT/nicehck-system-bridge.apk"
"$BIN/apksigner" verify --print-certs "$OUT/nicehck-system-bridge.apk" | head -5

# ---- 构建成功后自增版本（设 BUMP=0 可关闭）----
if [ "${BUMP:-1}" = "1" ]; then
  NCODE=$((VCODE + 1))
  MAJ=${VNAME%%.*}
  MIN=${VNAME#*.}
  if [ "$MAJ" = "$VNAME" ]; then MIN=0; fi
  NMIN=$((MIN + 1))
  NNAME="$MAJ.$NMIN"
  printf 'code=%s\nname=%s\n' "$NCODE" "$NNAME" > "$VER_FILE"
  echo "== next build: $NCODE ($NNAME) =="
fi
