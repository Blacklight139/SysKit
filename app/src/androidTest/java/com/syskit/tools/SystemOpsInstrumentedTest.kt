package com.syskit.tools

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.syskit.tools.ops.SystemOps
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 模拟器/真机 instrumented 测试：验证 L1 路径（pm grant WRITE_SECURE_SETTINGS）。
 * 运行前需执行：adb shell pm grant com.syskit.tools android.permission.WRITE_SECURE_SETTINGS
 * （gradle connectedDebugAndroidTest 会自动重新安装应用，需再补一次 grant 后单独跑，
 *   或直接用 am instrument 在已授权设备上执行。）
 */
@RunWith(AndroidJUnit4::class)
class SystemOpsInstrumentedTest {

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun privilegeDetection_doesNotCrash() {
        SystemOps.hasWriteSecureSettings(ctx)
        SystemOps.isSystemApp(ctx)
        SystemOps.isPrivApp(ctx)
        SystemOps.installPath(ctx)
    }

    @Test
    fun adbToggle_viaSettingsApi() {
        assumeTrue("需要 WRITE_SECURE_SETTINGS（pm grant）", SystemOps.hasWriteSecureSettings(ctx))
        assertTrue("开启 ADB 失败", SystemOps.setAdbEnabledApi(ctx, true))
        assertTrue("回读未生效", SystemOps.getAdbEnabled(ctx))
        // 注意：不在 adb 会话中关闭 ADB，避免断开测试连接
    }

    @Test
    fun devOptionsToggle_viaSettingsApi() {
        assumeTrue("需要 WRITE_SECURE_SETTINGS（pm grant）", SystemOps.hasWriteSecureSettings(ctx))
        assertTrue(SystemOps.setDevOptionsEnabledApi(ctx, false))
        assertFalse(SystemOps.getDevOptionsEnabled(ctx))
        assertTrue(SystemOps.setDevOptionsEnabledApi(ctx, true))
        assertTrue(SystemOps.getDevOptionsEnabled(ctx))
    }
}
