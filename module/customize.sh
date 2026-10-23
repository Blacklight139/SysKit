# Magisk 模块安装脚本（由官方 update-binary 调用）
SKIPUNZIP=0

ui_print "************************************"
ui_print " SysKit 系统工具箱 → 系统特权应用"
ui_print "************************************"

# APK 由 tools/build_all.sh 打包进 system/priv-app/SysKit/
if [ ! -f "$MODPATH/system/priv-app/SysKit/SysKit.apk" ]; then
  abort "! 模块缺少 SysKit.apk，请先运行 tools/build_all.sh 重新打包"
fi

# 权限修正：APK 与白名单均为 0644 root:root
set_perm "$MODPATH/system/priv-app/SysKit/SysKit.apk" 0 0 0644
set_perm "$MODPATH/system/etc/permissions/privapp-permissions-com.syskit.tools.xml" 0 0 0644

ui_print "- 挂载 /system/priv-app/SysKit/SysKit.apk"
ui_print "- 挂载 /system/etc/permissions/privapp-permissions-com.syskit.tools.xml"
ui_print "- 重启后生效；首次启动请到 Magisk 为 SysKit 授予 Root 权限"
ui_print "- 建议：Magisk 超级用户中将 SysKit 设为自动授权，"
ui_print "  以便开机 watchdog 静默重应用『固化 ADB / 商店守卫』"
