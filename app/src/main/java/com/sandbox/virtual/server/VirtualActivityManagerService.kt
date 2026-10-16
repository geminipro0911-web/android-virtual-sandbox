package com.sandbox.virtual.server

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import com.sandbox.virtual.core.ProcessManager
import com.sandbox.virtual.core.VirtualCore
import com.sandbox.virtual.model.SecurityEventType
import com.sandbox.virtual.stub.StubActivity

object VirtualActivityManagerService {
    private const val TAG = "VAMS"

    fun launchApp(context: Context, packageName: String): Boolean {
        val app = VirtualPackageManagerService.getInstalledApp(packageName)
        if (app == null) {
            Log.e(TAG, "Cannot launch app: $packageName not installed")
            return false
        }

        val targetActivity = app.mainActivity
        if (targetActivity.isEmpty()) {
            Log.e(TAG, "Cannot launch app: no launchable activity found for $packageName")
            return false
        }

        try {
            val processIndex = ProcessManager.allocateProcess(packageName)
            val stubClassName = ProcessManager.getStubActivityClass(processIndex)

            Log.i(TAG, "Launching $packageName with mainActivity=$targetActivity via $stubClassName in :virtual_p$processIndex")

            val targetIntent = Intent().apply {
                component = ComponentName(packageName, targetActivity)
                action = Intent.ACTION_MAIN
                addCategory(Intent.CATEGORY_LAUNCHER)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }

            val launchIntent = Intent().apply {
                setClassName(context.packageName, stubClassName)
                putExtra(StubActivity.EXTRA_TARGET_INTENT, targetIntent)
                putExtra(StubActivity.EXTRA_TARGET_PKG, packageName)
                putExtra(StubActivity.EXTRA_TARGET_ACTIVITY, targetActivity)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }

            context.startActivity(launchIntent)

            VirtualCore.recordSecurityLog(
                packageName = packageName,
                type = SecurityEventType.PROCESS_LIFECYCLE,
                summary = "Virtual Activity Launched",
                detail = "Activity: $targetActivity mapped to $stubClassName"
            )
            return true
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to launch $packageName", t)
            return false
        }
    }

    fun forceStop(context: Context, packageName: String): Boolean {
        Log.w(TAG, "Force stopping $packageName")
        val stopped = ProcessManager.killApp(context, packageName)
        val app = VirtualPackageManagerService.getInstalledApp(packageName)
        if (app != null) {
            app.isRunning = false
        }
        return stopped
    }
}
