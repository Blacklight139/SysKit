#!/usr/bin/env bash
# SysKit 一键构建：生成签名密钥（首次）→ Release APK → Magisk 模块包
set -euo pipefail
cd "$(dirname "$0")/.."

VERSION=$(grep -oP 'versionName\s*=\s*"\K[^"]+' app/build.gradle.kts)
VERSION_CODE=$(grep -oP 'versionCode\s*=\s*\K\d+' app/build.gradle.kts)
KEYSTORE=syskit-release.jks

# 1) 签名密钥（正式出厂部署建议改用平台签名，替换本密钥并重新签名）
if [ ! -f "$KEYSTORE" ]; then
  echo "[*] 生成自签名密钥 $KEYSTORE（口令 syskit123456，可经环境变量覆盖）"
  keytool -genkeypair -v -keystore "$KEYSTORE" -alias syskit -keyalg RSA -keysize 2048 \
    -validity 10950 -storepass syskit123456 -keypass syskit123456 \
    -dname "CN=SysKit, OU=Tools, O=SysKit, L=Local, ST=Local, C=CN" >/dev/null 2>&1
fi

# 1.5) 内嵌 Magisk（锁定版本 + SHA256 校验；SYSKIT_SKIP_MAGISK=1 可跳过，APK 内入口将显示“未内嵌”）
MAGISK_VER=v31.1
MAGISK_SHA256=89cefac0a318afa39fb3880c45c87b036c05bc1ebf02a7a715b7f8d7c26ac6e7
MAGISK_ASSET=app/src/main/assets/magisk/Magisk.apk
if [ "${SYSKIT_SKIP_MAGISK:-0}" = "1" ]; then
  rm -f "$MAGISK_ASSET"
  echo "[*] 按要求跳过内嵌 Magisk"
else
  mkdir -p "$(dirname "$MAGISK_ASSET")"
  if [ ! -f "$MAGISK_ASSET" ] || [ "$(sha256sum "$MAGISK_ASSET" | cut -d' ' -f1)" != "$MAGISK_SHA256" ]; then
    echo "[*] 下载内嵌 Magisk $MAGISK_VER（约 10MB）"
    curl -sL --connect-timeout 15 -o "$MAGISK_ASSET" \
      "https://github.com/topjohnwu/Magisk/releases/download/${MAGISK_VER}/Magisk-${MAGISK_VER}.apk"
  fi
  echo "$MAGISK_SHA256  $MAGISK_ASSET" | sha256sum -c - >/dev/null \
    || { echo "内嵌 Magisk SHA256 校验失败"; exit 1; }
fi

# 2) 构建
echo "[*] Gradle assembleRelease（v$VERSION / build $VERSION_CODE）"
./gradlew --no-daemon assembleRelease

APK=app/build/outputs/apk/release/app-release.apk
[ -f "$APK" ] || APK=app/build/outputs/apk/release/app-release-unsigned.apk

mkdir -p dist
cp "$APK" "dist/SysKit-v${VERSION}.apk"

# 3) Magisk 模块（版本号同步）
sed -i "s/^version=.*/version=v${VERSION}/" module/module.prop
sed -i "s/^versionCode=.*/versionCode=${VERSION_CODE}/" module/module.prop
MODDIR=module/system/priv-app/SysKit
mkdir -p "$MODDIR"
cp "dist/SysKit-v${VERSION}.apk" "$MODDIR/SysKit.apk"
chmod 755 module/system/bin/pm   # 卸载守卫包装器须可执行（python 打包分支按此位写入）

OUT="dist/SysKit-MagiskModule-v${VERSION}.zip"
rm -f "$OUT"
if command -v zip >/dev/null 2>&1; then
  (cd module && zip -qr9 "../$OUT" module.prop customize.sh META-INF system)
else
  python3 - "$OUT" <<'PY'
import os, sys, zipfile
out = sys.argv[1]
with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as z:
    for root, _, files in os.walk("module"):
        for f in files:
            p = os.path.join(root, f)
            arc = os.path.relpath(p, "module")
            mode = 0o755 if arc == "system/bin/pm" else 0o644
            with open(p, "rb") as fh:
                zi = zipfile.ZipInfo(arc)
                zi.external_attr = (0o100000 | mode) << 16   # 保留 unix 权限位
                zi.compress_type = zipfile.ZIP_DEFLATED
                z.writestr(zi, fh.read())
PY
fi
rm -f "$MODDIR/SysKit.apk"

echo "[✓] 构建完成"
ls -la dist/
