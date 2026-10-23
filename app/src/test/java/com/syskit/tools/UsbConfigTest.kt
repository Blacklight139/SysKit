package com.syskit.tools

import com.syskit.tools.ops.SystemOps
import org.junit.Assert.assertEquals
import org.junit.Test

/** USB 持久配置合并逻辑（固化 ADB 的核心纯函数） */
class UsbConfigTest {

    @Test
    fun `null 配置回退为 mtp,adb`() {
        assertEquals("mtp,adb", SystemOps.mergeAdbIntoUsbConfig(null))
    }

    @Test
    fun `空配置回退为 mtp,adb`() {
        assertEquals("mtp,adb", SystemOps.mergeAdbIntoUsbConfig(""))
        assertEquals("mtp,adb", SystemOps.mergeAdbIntoUsbConfig("   "))
    }

    @Test
    fun `none 配置回退为 mtp,adb`() {
        assertEquals("mtp,adb", SystemOps.mergeAdbIntoUsbConfig("none"))
        assertEquals("mtp,adb", SystemOps.mergeAdbIntoUsbConfig(" none "))
    }

    @Test
    fun `已有 adb 保持不变`() {
        assertEquals("mtp,adb", SystemOps.mergeAdbIntoUsbConfig("mtp,adb"))
        assertEquals("rndis,adb", SystemOps.mergeAdbIntoUsbConfig("rndis,adb"))
        assertEquals("adb", SystemOps.mergeAdbIntoUsbConfig("adb"))
    }

    @Test
    fun `无 adb 追加`() {
        assertEquals("mtp,adb", SystemOps.mergeAdbIntoUsbConfig("mtp"))
        assertEquals("ptp,adb", SystemOps.mergeAdbIntoUsbConfig("ptp"))
        assertEquals("mtp,ptp,adb", SystemOps.mergeAdbIntoUsbConfig("mtp,ptp"))
    }

    @Test
    fun `多余空格被规整`() {
        assertEquals("mtp,adb", SystemOps.mergeAdbIntoUsbConfig(" mtp ,  adb "))
    }
}
