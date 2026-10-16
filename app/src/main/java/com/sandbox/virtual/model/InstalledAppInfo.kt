package com.sandbox.virtual.model

import java.io.Serializable

data class InstalledAppInfo(
    val packageName: String,
    val appName: String,
    val versionName: String,
    val versionCode: Long,
    val apkPath: String,
    val dataDir: String,
    val libDir: String,
    val mainActivity: String,
    val iconPath: String? = null,
    val installTime: Long = System.currentTimeMillis(),
    var isRunning: Boolean = false,
    var assignedProcessIndex: Int = 0,
    val requestedPermissions: List<String> = emptyList(),
    val activities: List<String> = emptyList(),
    val services: List<String> = emptyList(),
    val receivers: List<String> = emptyList(),
    val providers: List<String> = emptyList()
) : Serializable
