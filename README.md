# SysKit 系统工具箱

面向系统部署的 Android 设备管理工具：**基础版**通过系统应用权限开关 ADB；**高级版**通过 Magisk 提权实现「获得 Root 后立即禁用应用商店卸载能力」「固化 ADB（永久开启）」「开启开发者选项」；v1.1.0 新增**卸载守卫**——所有卸载流程须经同意（亮屏弹窗询问、熄屏自动拦截）。

## 需求实现对照

| 需求 | 实现 |
|---|---|
| 选项能打开 ADB（添加为系统应用对系统操作） | 以 `/system/priv-app` 特权应用身份持有 `WRITE_SECURE_SETTINGS`，直接写入 `Settings.Global.adb_enabled`，无需 root |
| 通过 Magisk 提权 | 常驻交互式 `su` 会话（一次授权多次复用），成功后立即执行后续动作 |
| 获得 Root 后立即禁用系统应用商店卸载能力 | Root 授权成功瞬间自动吊销商店的 `REQUEST_DELETE_PACKAGES` / `DELETE_PACKAGES` 并 `appops deny`（分层冗余），开机 watchdog 重应用 |
| 选项：开启开发者选项（设置入口） | `development_settings_enabled=1`，设置 → 系统 → 开发者选项；带侧栏（抽屉式设置，如平板/部分 ROM）的设备会同步出现入口，应用内亦提供直达按钮 |
| 选项：固化 ADB（永久开启） | 三重保障：`adb_enabled=1` + `persist.sys.usb.config` 合并 `adb`（插线即连）+ 开机 `BOOT_COMPLETED` 自动重应用；可选无线 ADB（TCP 5555） |
| 所有卸载流程须经同意（亮屏弹窗询问 / 熄屏自动拦截） | **卸载守卫三层**：① Magisk 模块替换 `/system/bin/pm`，事前拦截 `pm uninstall`（文件协议 + 同意对话框，30s 无响应拒绝）② 看板狗前台服务监听 `PACKAGE_REMOVED`：熄屏自动恢复、亮屏弹「保持卸载/恢复」③ `/data/app` APK 预备份，新装包自动补备份 |

## 权限分层模型

```
L1  adb shell pm grant ... WRITE_SECURE_SETTINGS   → 基础：ADB / 开发者选项开关
L2  系统特权应用（/system/priv-app + 白名单）        → 同 L1，且自身不可常规卸载
L3  Magisk Root（su 会话）                          → 全部高级功能
```

应用内「权限状态」卡片实时显示五项状态：系统应用 / 特权应用 / 写系统设置 / Root / 设备所有者。

## 构建

要求：JDK 17、Android SDK（platform 35 / build-tools 35）。

```bash
./gradlew assembleRelease          # 仅 APK（有 syskit-release.jks 则自动签名）
# 或一键构建 APK + Magisk 模块：
tools/build_all.sh
```

产物：`dist/SysKit-v<版本>.apk`、`dist/SysKit-MagiskModule-v<版本>.zip`

> 首次构建自动生成自签名密钥（口令 `syskit123456`，可用环境变量 `SYSKIT_STORE_PASS` 等覆盖）。
> **正式出厂/刷机包部署**：请替换为你自己的签名，或直接使用平台签名（platform key），并保持签名固定以便升级。

## 部署方式

### 方式一：Magisk 模块（推荐，免改 /system 分区）

1. 将 `SysKit-MagiskModule-v<版本>.zip` 传入手机
2. Magisk App → 模块 → 从本地安装 → 选择 zip → 重启
3. 模块自动完成：
   - `/system/priv-app/SysKit/SysKit.apk`（systemless 挂载）
   - `/system/etc/permissions/privapp-permissions-com.syskit.tools.xml`（特权权限白名单，Android 8+ 必需）
4. 启动 SysKit → 点「获取 Root」→ Magisk 授权

> 建议在 Magisk 超级用户中把 SysKit 设为**自动授权**，开机 watchdog 才能静默重应用固化配置。

### 方式二：直接写入系统分区（自定义 ROM / 可 remount 设备）

```bash
tools/install_as_priv_app.sh dist/SysKit-v1.0.0.apk
# 等价于手动：
#   adb root && adb remount
#   adb push xxx.apk /system/priv-app/SysKit/SysKit.apk   (0644 root:root)
#   adb push 白名单.xml /system/etc/permissions/privapp-permissions-com.syskit.tools.xml
#   adb reboot
```

ROM 源码集成：把 APK 与白名单 XML 放入对应目录（`SYSTEM_EXT_PRIV_APPS` / `PRODUCT_COPY_FILES`）编译即可。

### 方式三：临时授权（无 root / 未做系统应用）

```bash
adb shell pm grant com.syskit.tools android.permission.WRITE_SECURE_SETTINGS
```

重启后失效（需重新执行），仅开放基础功能（ADB / 开发者选项开关）。

## 功能与底层命令

| 功能 | 命令/API |
|---|---|
| ADB 开关（L1/L2） | `Settings.Global.putInt("adb_enabled", 1)` |
| ADB 开关（L3） | `su -c settings put global adb_enabled 1` |
| 固化 ADB | `setprop persist.sys.usb.config mtp,adb`（自动合并现有配置） |
| 无线 ADB | `setprop persist.adb.tcp.port 5555` + `stop adbd && start adbd` |
| 开发者选项 | `settings put global development_settings_enabled 1` |
| 商店守卫 | `pm revoke <商店> android.permission.REQUEST_DELETE_PACKAGES`<br>`pm revoke <商店> android.permission.DELETE_PACKAGES`<br>`appops set <商店> REQUEST_DELETE_PACKAGES deny`<br>`appops set <商店> DELETE_PACKAGES deny` |
| 商店恢复 | `pm grant ... REQUEST_DELETE_PACKAGES` + `appops reset <商店>` |
| 设备所有者（增强） | `dpm set-device-owner com.syskit.tools/.admin.AdminReceiver` → `setUninstallBlocked` |
| 卸载守卫 · pm 包装器 | 替换 `/system/bin/pm`：拦截 `uninstall` / `uninstall-existing`，经 `/data/local/tmp/syskit` 文件协议询问 App；`ALLOW` 放行、`DENY`/超时拒绝（fail-closed） |
| 卸载守卫 · 看板狗 | `PACKAGE_REMOVED` → 熄屏：`pm install-existing` 或备份 `pm install-multiple -r` 立即恢复；亮屏：弹窗询问（30s 无响应自动恢复） |
| 卸载守卫 · 备份 | `pm path <pkg>` → `cp` 至 `/data/local/tmp/syskit/backups/<pkg>/`；启用时全量、新装包自动增量 |

内置识别的商店：Google Play、小米、华为、荣耀、OPPO、vivo、应用宝、百度、360、豌豆荚、魅族、联想、三星；支持添加任意自定义包名。

## 可靠性设计

- **分层冗余**：商店守卫四条命令任一生效即判定成功（不同 ROM 支持面不同）
- **开机 watchdog**：`BOOT_COMPLETED` 重建配置；Magisk 自动授权时无需任何交互
- **幂等操作**：所有命令可重复执行；USB 配置合并前先读取现状，避免覆盖 MTP/PTP
- **回读验证**：操作后从 `Settings.Global` 回读真实状态刷新 UI
- **全程日志**：应用内「操作日志」卡片记录每条命令与结果，便于排障

## 安全说明

- 本应用**不申请网络权限**，不外传任何数据
- ADB 认证（RSA 密钥）保持开启，固化仅保证「开关为开」，不会降级为免认证 adb
- 无线 ADB（TCP 5555）仅限可信局域网使用；关闭即 `persist.adb.tcp.port=-1` 并重启 adbd
- 设备所有者设置后本应用将无法被卸载，可在应用内一键注销（`clearDeviceOwnerApp`）
- 商店守卫只针对「卸载」相关权限，不影响商店的安装/更新能力

## 测试

```bash
# 单元测试（卸载守卫纯逻辑：校验/决策/过滤/恢复计划/文件协议）
./gradlew test

# pm 包装器主机端行为测试（无需设备：透传/拦截/ALLOW/DENY/超时/fail-closed）
bash tools/test_pm_wrapper.sh

# 设备/模拟器完整验证（需已连接 adb 设备，google_apis 镜像可验证 root 命令）
tools/run_tests.sh
```

`run_tests.sh` 自动完成：安装应用与测试 APK → `pm grant` 授权 → 运行 instrumented 测试
（ADB 开关 / 开发者选项开关的真实 Settings 读写断言）→ 以 `adb root` 验证与
`SystemOps` / `StoreGuard` 完全一致的 shell 命令效果（development_settings_enabled、
appops deny、persist.sys.usb.config）。

> 注：新版 Android 模拟器在 x86_64 主机强制要求 KVM 硬件加速；CI/无 KVM 环境无法启动模拟器，
> 请使用支持虚拟化的开发机或真机执行上述脚本。

## 常见问题

**Q：开发者选项入口在哪里？**
开启后进入 设置 → 系统 → 开发者选项；侧栏（抽屉）式设置界面的设备（平板、部分 ROM）会在侧栏同步出现入口；应用内也提供「打开开发者选项」直达按钮。入口显示由系统 Settings 根据 `development_settings_enabled` 控制。

**Q：商店守卫执行后商店还能卸载应用吗？**
常规卸载流程被吊销（点击卸载会失败/无响应）。若商店自带 root 或以其他方式卸载，开启「设备所有者」增强可彻底封死。

**Q：商店升级后权限回来了怎么办？**
开机 watchdog 会重新吊销；也可在应用内点「立即执行」。

**Q：`dpm set-device-owner` 提示失败？**
设备已配置账户（已激活）时系统会拒绝，属正常现象；不影响商店守卫生效。

**Q：为什么 `pm revoke DELETE_PACKAGES` 显示失败？**
该权限为签名级，部分系统不允许 revoke，属预期内；守卫判定不依赖这一条。

**Q：卸载守卫能拦截哪些卸载？拦不住哪些？**
可事前拦截：`adb shell pm uninstall`、su 脚本里的 pm 卸载（经包装器）。无法事前暂停的（设置页用户手动卸载、binder 层静默卸载、`cmd package` 直连）由看板狗事后确认/自动恢复兜底；第三方应用恢复自 APK 备份（全新数据，除非卸载者用了 `-k`），系统应用经 `install-existing` 原样恢复。确认框 30 秒无响应：ask 按拒绝、removed 按自动恢复。

**Q：卸载守卫为什么需要 Magisk 模块？**
事前拦截依赖模块挂载的 `/system/bin/pm` 包装器；仅装 APK（无模块）时守卫自动退化为「事后恢复」模式（备份目录与状态文件仍由 root 会话维护）。

**Q：想卸载 SysKit 自己怎么办？**
先在应用内关闭「卸载确认拦截」开关（guard_state 置 0），否则 adb 卸载会被包装器拒绝（fail-closed）；守卫关闭后即可正常卸载。

## 项目结构

```
app/src/main/java/com/syskit/tools/
├── MainActivity.kt            # 入口
├── root/SuSession.kt          # 常驻 su 会话（marker 回显收码）
├── ops/SystemOps.kt           # ADB / 开发者选项 / 固化（命令构建纯函数可测）
├── ops/StoreGuard.kt          # 应用商店卸载守卫（分层冗余）
├── ops/UninstallGuard.kt      # 卸载守卫核心（校验/决策/文件协议/备份恢复，纯逻辑可测）
├── ops/DeviceOwnerOps.kt      # 设备所有者增强
├── admin/AdminReceiver.kt     # DeviceAdminReceiver
├── receiver/BootReceiver.kt   # 开机 watchdog
├── service/UninstallGuardService.kt  # 看板狗前台服务（轮询包装器 + REMOVED 确认/恢复 + 备份）
├── prefs/AppPrefs.kt          # 持久配置
├── log/OpsLog.kt              # 操作日志
├── vm/MainViewModel.kt        # 状态与业务编排
└── ui/                        # Compose 界面（含 ConsentDialogActivity 卸载同意对话框）
module/                        # Magisk 模块（priv-app + 白名单 + system/bin/pm 包装器）
tools/                         # 构建 / 系统部署 / 测试脚本（test_pm_wrapper.sh 为主机端包装器行为测试）
```
