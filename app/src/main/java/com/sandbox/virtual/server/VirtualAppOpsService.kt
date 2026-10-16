package com.sandbox.virtual.server

import android.app.AppOpsManager
import android.util.Log
import com.sandbox.virtual.core.VirtualCore
import com.sandbox.virtual.model.SecurityEventType

object VirtualAppOpsService {
    private const val TAG = "VirtualAppOpsService"

    val SENSITIVE_PERMISSIONS = setOf(
        "android.permission.READ_CONTACTS",
        "android.permission.WRITE_CONTACTS",
        "android.permission.READ_SMS",
        "android.permission.SEND_SMS",
        "android.permission.RECEIVE_SMS",
        "android.permission.ACCESS_FINE_LOCATION",
        "android.permission.ACCESS_COARSE_LOCATION",
        "android.permission.READ_MEDIA_IMAGES",
        "android.permission.READ_MEDIA_VIDEO",
        "android.permission.READ_MEDIA_AUDIO",
        "android.permission.READ_EXTERNAL_STORAGE",
        "android.permission.WRITE_EXTERNAL_STORAGE",
        "android.permission.CAMERA",
        "android.permission.RECORD_AUDIO",
        "android.permission.READ_PHONE_STATE",
        "android.permission.READ_CALL_LOG"
    )

    /**
     * Intercept AppOps check/note calls. Returns MODE_ALLOWED so app logic continues,
     * but queries to sensitive content providers will return empty mock cursors.
     */
    fun checkOperation(code: Int, uid: Int, packageName: String): Int {
        Log.d(TAG, "checkOperation: code=$code, uid=$uid, pkg=$packageName")
        VirtualCore.recordSecurityLog(
            packageName = packageName,
            type = SecurityEventType.PERMISSION_INTERCEPTED,
            summary = "AppOps checkOperation intercepted",
            detail = "Op code: $code for $packageName - Granted safe sandbox mode",
            blocked = false
        )
        return AppOpsManager.MODE_ALLOWED
    }

    fun noteOperation(code: Int, uid: Int, packageName: String): Int {
        Log.d(TAG, "noteOperation: code=$code, uid=$uid, pkg=$packageName")
        VirtualCore.recordSecurityLog(
            packageName = packageName,
            type = SecurityEventType.PERMISSION_INTERCEPTED,
            summary = "AppOps noteOperation intercepted",
            detail = "Op code: $code for $packageName - Virtualized return",
            blocked = false
        )
        return AppOpsManager.MODE_ALLOWED
    }

    fun checkPermission(permission: String, packageName: String): Int {
        val isSensitive = SENSITIVE_PERMISSIONS.contains(permission)
        VirtualCore.recordSecurityLog(
            packageName = packageName,
            type = SecurityEventType.PERMISSION_INTERCEPTED,
            summary = if (isSensitive) "Dangerous Permission Mocked" else "Standard Permission Checked",
            detail = "Permission: $permission - Granted in sandbox",
            blocked = isSensitive
        )
        return android.content.pm.PackageManager.PERMISSION_GRANTED
    }
}
