#!/usr/bin/env bash
# 通过 adb 将 SysKit 直接部署为系统特权应用
# 适用：可 adb root + remount 的设备（userdebug / 模拟器 / 已 root）
set -euo pipefail
cd "$(dirname "$0")/.."

APK=${1:-dist/SysKit-v1.0.0.apk}
PKG=com.syskit.tools
[ -f "$APK" ] || { echo "未找到 APK：$APK（先运行 tools/build_all.sh）"; exit 1; }

adb wait-for-device
adb root >/dev/null 2>&1 || true
sleep 2
adb remount >/dev/null 2>&1 || adb shell "mount -o rw,remount /" || true

echo "[*] 部署 APK → /system/priv-app/SysKit/"
adb shell "mkdir -p /system/priv-app/SysKit"
adb push "$APK" /system/priv-app/SysKit/SysKit.apk
adb shell "chown root:root /system/priv-app/SysKit/SysKit.apk; chmod 644 /system/priv-app/SysKit/SysKit.apk"

echo "[*] 部署特权权限白名单"
TMP=$(mktemp --suffix=.xml)
cat > "$TMP" <<XML
<permissions>
    <privapp-permissions package="$PKG">
        <permission name="android.permission.WRITE_SECURE_SETTINGS"/>
    </privapp-permissions>
</permissions>
XML
adb push "$TMP" /system/etc/permissions/privapp-permissions-$PKG.xml
rm -f "$TMP"
adb shell "chown root:root /system/etc/permissions/privapp-permissions-$PKG.xml; chmod 644 /system/etc/permissions/privapp-permissions-$PKG.xml"

echo "[✓] 部署完成，重启后生效"
adb reboot || true
