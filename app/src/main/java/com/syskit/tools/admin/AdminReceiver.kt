package com.syskit.tools.admin

import android.app.admin.DeviceAdminReceiver

/** 设备管理器接收器：配合 `dpm set-device-owner`（root）成为设备所有者 */
class AdminReceiver : DeviceAdminReceiver()
