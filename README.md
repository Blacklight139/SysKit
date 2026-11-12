# SysKit 系统工具箱

面向系统部署的 Android 设备管理工具：**基础版**通过系统应用权限开关 ADB；**高级版**通过 Magisk 提权实现固化 ADB、开发者选项；v1.2.0 提供**卸载管控**（禁止系统卸载 / 接管系统卸载：单次弹窗确认、频繁与批量自动禁止并独立记录）、**APK 安装器**（接管 .apk 打开方式）与**内嵌 Magisk（Root 引导）**。

## 需求实现对照

| 需求 | 实现 |
|---|---|
| 选项能打开 ADB（添加为系统应用对系统操作） | 以 `/system/priv-app` 特权应用身份持有 `WRITE_SECURE_SETTINGS`，直接写入 `Settings.Global.adb_enabled`，无需 root |
| 通过 Magisk 提权 | 常驻交互式 `su` 会话（一次授权多次复用），成功后立即执行后续动作 |
| 获得 Root 后立即禁用应用商店卸载能力（v1.0 需求，v1.2.0 已升级） | 原实现按商店吊销卸载权限；v1.2.0 起被「禁止系统卸载 / 接管系统卸载」全局开关取代（对所有卸载路径统一管控，见下） |
| 选项：开启开发者选项（设置入口） | `development_settings_enabled=1`，设置 → 系统 → 开发者选项；带侧栏（抽屉式设置，如平板/部分 ROM）的设备会同步出现入口，应用内亦提供直达按钮 |
| 选项：固化 ADB（永久开启） | 三重保障：`adb_enabled=1` + `persist.sys.usb.config` 合并 `adb`（插线即连）+ 开机 `BOOT_COMPLETED` 自动重应用；可选无线 ADB（TCP 5555） |
| 禁止系统卸载（所有卸载均禁止） | 开关开启后：pm 包装器对一切卸载请求回 `DENY`；看板狗对 `PACKAGE_REMOVED` 自动恢复（系统应用 `install-existing`，第三方从备份重装）；优先于接管开关 |
| 接管系统卸载（单次弹窗，频繁/批量自动禁止） | 单次单个应用卸载 → 弹窗确认（允许则放行卸载）；60 秒内 ≥3 次卸载尝试（频繁或一次性批量，被拒尝试同样计数）→ 自动禁止并写入**独立审计日志**（GuardEventLog，独立 UI 展示）；熄屏自动拒绝 |
| APK 安装器（接管 .apk 安装） | 注册 `.apk` 打开方式（文件管理器中可选用/设为默认）+ 应用内安装页；通道分级：Root 静默 `pm install` → priv-app `INSTALL_PACKAGES` 静默会话 → 系统确认会话。边界：平台 PackageInstaller 是框架组件，非平台签名应用无法真正替换，本应用提供等效安装通道；安装始终需手动确认 |
| 内嵌 Magisk（Root 引导，装模块/对其他应用授权） | 内嵌官方 Magisk APK（构建时锁定 v31.1 并钉 SHA256）：一键安装管理器 → 在 Magisk 中修补 boot 并刷入获得 Root → 此后 Root 授权与模块安装由 Magisk 完成；检测到 Magisk 已装 / 已有 root / 配套模块已部署（priv-app）任一条件 → 入口自动隐藏并关闭。另有 Root 授权管理面板（magisk.db 读写，实验性） |

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
| 设备所有者（增强） | `dpm set-device-owner com.syskit.tools/.admin.AdminReceiver` → `setUninstallBlocked` |
| 卸载管控 · pm 包装器 | 替换 `/system/bin/pm`：拦截 `uninstall` / `uninstall-existing`，经 `/data/local/tmp/syskit` 文件协议询问 App；`ALLOW` 放行、`DENY`/超时拒绝（fail-closed）；guard_state=0 时完全透传 |
| 卸载管控 · 决策 | `modeOf(blockAll, takeover)`：BLOCK_ALL → 一律 DENY；TAKEOVER → 限速（60s/3 次）+ 熄屏判定后弹窗；决策纯函数有单测 |
| 卸载管控 · 独立审计日志 | GuardEventLog：`RATE_LIMIT` / `BLOCK_ALL` / `SCREEN_OFF` / `RESTORED` / `ALLOWED` / `CONSENT_DENIED`，内存环形 + filesDir 持久化（独立于操作日志，UI 独立展示） |
| 卸载管控 · 备份/恢复 | `PACKAGE_REMOVED` → `pm install-existing` 或备份 `pm install-multiple -r`；备份 `pm path <pkg>` → `cp` 至 `/data/local/tmp/syskit/backups/<pkg>/` |
| APK 安装器 | `PackageInstaller` 会话（INSTALL_PACKAGES 静默 / 系统确认）或 root `pm install -r`；暂存于 cacheDir 后提交 |
| 内嵌 Magisk | 内嵌 `assets/magisk/Magisk.apk`（v31.1，SHA256 钉版）→ 经安装器通道安装；显示判定纯函数可测 |
| Root 授权管理 | `magisk --sqlite "SELECT uid,policy,until FROM policies"` / `REPLACE INTO policies ...` / `DELETE FROM policies WHERE uid=...`（实验性：policy 2=允许，0=拒绝；精确控制建议用 Magisk App） |

## 可靠性设计

- **fail-closed**：包装器超时 / root 会话丢失 / 请求写入失败一律按拒绝处理；决策函数纯逻辑有单测覆盖
- **开机 watchdog**：`BOOT_COMPLETED` 重建配置与看板狗；Magisk 自动授权时无需任何交互
- **幂等操作**：所有命令可重复执行；USB 配置合并前先读取现状，避免覆盖 MTP/PTP
- **回读验证**：操作后从 `Settings.Global` 回读真实状态刷新 UI
- **全程日志**：应用内「操作日志」卡片记录每条命令与结果，便于排障

## 安全说明

- 本应用**不申请网络权限**，不外传任何数据
- ADB 认证（RSA 密钥）保持开启，固化仅保证「开关为开」，不会降级为免认证 adb
- 无线 ADB（TCP 5555）仅限可信局域网使用；关闭即 `persist.adb.tcp.port=-1` 并重启 adbd
- 设备所有者设置后本应用将无法被卸载，可在应用内一键注销（`clearDeviceOwnerApp`）
- 卸载管控对所有卸载路径统一生效；「禁止系统卸载」关闭前 adb 卸载会被拒绝（fail-closed）
- APK 安装器：安装始终需要手动点击确认，外部传入的安装请求不会被静默代装；外部入口不触发 su
- **内嵌 Magisk 许可说明**：内嵌的 Magisk APK 为 GPLv3 软件，源码见 github.com/topjohnwu/Magisk；构建脚本锁版本并校验 SHA256（可用 `SYSKIT_SKIP_MAGISK=1` 跳过内嵌）

## 测试

```bash
# 单元测试（47 项：卸载管控决策/限速器、卸载守卫校验/决策/过滤/恢复计划/文件协议、内嵌 Magisk 判定/模块解析、USB 配置合并）
./gradlew test

# pm 包装器主机端行为测试（无需设备：透传/拦截/ALLOW/DENY/超时/fail-closed）
bash tools/test_pm_wrapper.sh

# 设备/模拟器完整验证（需已连接 adb 设备，google_apis 镜像可验证 root 命令）
tools/run_tests.sh
```

`run_tests.sh` 自动完成：安装应用与测试 APK → `pm grant` 授权 → 运行 instrumented 测试
（ADB 开关 / 开发者选项开关的真实 Settings 读写断言）→ 以 `adb root` 验证与
`SystemOps` 完全一致的 shell 命令效果（development_settings_enabled、appops deny、persist.sys.usb.config）。

> 注：新版 Android 模拟器在 x86_64 主机强制要求 KVM 硬件加速；CI/无 KVM 环境无法启动模拟器，
> 请使用支持虚拟化的开发机或真机执行上述脚本。

## 常见问题

**Q：开发者选项入口在哪里？**
开启后进入 设置 → 系统 → 开发者选项；侧栏（抽屉）式设置界面的设备（平板、部分 ROM）会在侧栏同步出现入口；应用内也提供「打开开发者选项」直达按钮。入口显示由系统 Settings 根据 `development_settings_enabled` 控制。

**Q：禁止系统卸载和接管系统卸载同时开启会怎样？**
「禁止」优先：所有卸载一律拒绝（无弹窗），看板狗对已发生的卸载自动恢复。想恢复弹窗确认模式，请先关闭「禁止系统卸载」。

**Q：频繁/批量卸载的判定标准是什么？**
滑动窗口限速：60 秒内第 4 次及以后的卸载尝试（含被拒的尝试）自动禁止，并记入独立审计日志。被拒尝试同样计数，防止脚本探测绕过；看板狗重启后限速计数重置。

**Q：卸载管控能拦截哪些卸载？拦不住哪些？**
可事前拦截：`adb shell pm uninstall`、su 脚本里的 pm 卸载（经包装器）。无法事前暂停的（设置页用户手动卸载、binder 层静默卸载、`cmd package` 直连）由看板狗事后确认/自动恢复兜底；第三方应用恢复自 APK 备份（全新数据，除非卸载者用了 `-k`），系统应用经 `install-existing` 原样恢复。确认框 30 秒无响应按安全默认处理。

**Q：卸载管控为什么需要 Magisk 模块？**
事前拦截依赖模块挂载的 `/system/bin/pm` 包装器；仅装 APK（无模块）时自动退化为「事后恢复」模式（备份目录与状态文件仍由 root 会话维护）。

**Q：想卸载 SysKit 自己怎么办？**
先在应用内关闭「禁止系统卸载」与「接管系统卸载」开关（guard_state 置 0），否则 adb 卸载会被包装器拒绝（fail-closed）；守卫关闭后即可正常卸载。

**Q：APK 安装器能完全替换系统安装器吗？**
不能。平台 PackageInstaller 是框架组件，非平台签名应用无法替换；SysKit 注册为 `.apk` 打开方式（可设为默认）并提供 Root 静默 / 特权静默 / 系统确认三通道安装，安装均需手动点击确认。

**Q：内嵌 Magisk 入口为什么消失了？**
这是设计行为：检测到 Magisk 已安装 / 已有 root 会话 / SysKit 已作为配套模块部署（priv-app）任一条件，内嵌入口自动隐藏——此时 Root 管理应由 Magisk 自身完成。

**Q：Root 授权管理里「允许/拒绝」可靠吗？**
实验性功能：直接读写 magisk.db 的 policies 表（约定 policy 2=允许、0=拒绝）。如遇 ROM 差异，请以 Magisk App 内的超级用户设置为准。

## 项目结构

```
app/src/main/java/com/syskit/tools/
├── MainActivity.kt            # 入口
├── root/SuSession.kt          # 常驻 su 会话（marker 回显收码）
├── ops/SystemOps.kt           # ADB / 开发者选项 / 固化（命令构建纯函数可测）
├── ops/UninstallControl.kt    # 卸载管控决策 + 限速器（纯逻辑可测）
├── ops/UninstallGuard.kt      # 卸载管控执行核心（校验/文件协议/备份恢复，纯逻辑可测）
├── ops/ApkInstaller.kt        # APK 安装器（通道选择纯函数可测 + 会话安装）
├── ops/EmbeddedMagisk.kt      # 内嵌 Magisk 判定/模块解析/授权解析（纯逻辑可测）
├── ops/DeviceOwnerOps.kt      # 设备所有者增强
├── admin/AdminReceiver.kt     # DeviceAdminReceiver
├── receiver/BootReceiver.kt   # 开机 watchdog
├── service/UninstallGuardService.kt  # 看板狗前台服务（模式决策 + 限速 + REMOVED 确认/恢复 + 备份）
├── prefs/AppPrefs.kt          # 持久配置
├── log/OpsLog.kt              # 操作日志
├── log/GuardEventLog.kt       # 卸载管控独立审计日志（内存 + 文件持久化）
├── vm/MainViewModel.kt        # 状态与业务编排
└── ui/                        # Compose 界面（含 ConsentDialogActivity / InstallActivity）
module/                        # Magisk 模块（priv-app + 白名单 + system/bin/pm 包装器）
tools/                         # 构建 / 系统部署 / 测试脚本（test_pm_wrapper.sh 为主机端包装器行为测试）
```
