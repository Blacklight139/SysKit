#!/usr/bin/env bash
# 在已连接的设备/模拟器（google_apis userdebug 镜像）上运行完整验证
# 覆盖：L1 权限路径（pm grant + Settings API 开关）+ L3 root 命令正确性
set -euo pipefail
cd "$(dirname "$0")/.."

ADB=${ADB:-adb}
PKG=com.syskit.tools

SERIAL=$($ADB devices | awk 'NR>1 && /device[[:space:]]*$/{print $1; exit}')
[ -n "$SERIAL" ] || { echo "未找到 adb 设备"; exit 1; }
echo "[*] 使用设备：$SERIAL"

echo "[*] 构建并安装（debug + androidTest）"
./gradlew -q assembleDebug assembleDebugAndroidTest
$ADB install -r app/build/outputs/apk/debug/app-debug.apk
$ADB install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk

echo "[*] L1：授予 WRITE_SECURE_SETTINGS"
$ADB shell pm grant $PKG android.permission.WRITE_SECURE_SETTINGS || echo "  （grant 失败，L1 相关测试将被跳过）"

echo "[*] 运行 instrumented 测试"
$ADB shell am instrument -w ${PKG}.test/androidx.test.runner.AndroidJUnitRunner

echo "[*] L1 效果验证：应用内 ADB 开关等价于 settings put（由测试覆盖），此处验证回读"
$ADB shell settings get global adb_enabled

echo "[*] L3：root 命令正确性（需 userdebug/模拟器 adb root）"
if $ADB root 2>/dev/null; then
  sleep 2
  echo "  -- 开发者选项开关（与 SystemOps.cmdSetDevOptions 一致）"
  $ADB shell "settings put global development_settings_enabled 1; settings get global development_settings_enabled"
  echo "  -- 商店守卫命令效果（与 StoreGuard.guardCommands 一致）"
  $ADB shell "appops set $PKG REQUEST_DELETE_PACKAGES deny; appops get $PKG REQUEST_DELETE_PACKAGES"
  $ADB shell "appops reset $PKG"   # 还原
  echo "  -- 固化 ADB 命令（与 SystemOps.persistUsbAdb 一致）"
  $ADB shell "getprop persist.sys.usb.config"
else
  echo "  （adb root 不可用：真机生产镜像属正常，跳过命令验证）"
fi

echo "[✓] 验证流程结束"
