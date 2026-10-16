package com.sandbox.virtual.core

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import com.sandbox.virtual.model.SecurityEventType
import com.sandbox.virtual.server.VirtualPackageManagerService
import com.sandbox.virtual.stub.StubActivity
import java.lang.reflect.Method

class InstrumentationProxy(
    private val base: Instrumentation,
    private val hostPackageName: String
) : Instrumentation() {

    companion object {
        private const val TAG = "InstrumentationProxy"

        fun inject(hostPackageName: String) {
            try {
                val activityThreadClass = Class.forName("android.app.ActivityThread")
                val currentActivityThreadMethod = activityThreadClass.getDeclaredMethod("currentActivityThread")
                currentActivityThreadMethod.isAccessible = true
                val currentActivityThread = currentActivityThreadMethod.invoke(null) ?: return

                val mInstrumentationField = activityThreadClass.getDeclaredField("mInstrumentation")
                mInstrumentationField.isAccessible = true
                val originalInstrumentation = mInstrumentationField.get(currentActivityThread) as? Instrumentation ?: return

                if (originalInstrumentation !is InstrumentationProxy) {
                    val proxy = InstrumentationProxy(originalInstrumentation, hostPackageName)
                    mInstrumentationField.set(currentActivityThread, proxy)
                    Log.i(TAG, "Successfully injected InstrumentationProxy")
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to inject InstrumentationProxy", t)
            }
        }
    }

    /**
     * Intercept startActivity for Android 8.0 - 14 via reflection on hidden execStartActivity
     */
    fun execStartActivity(
        who: Context,
        contextThread: IBinder,
        token: IBinder?,
        target: Activity?,
        intent: Intent,
        requestCode: Int,
        options: Bundle?
    ): ActivityResult? {
        val targetPkg = intent.`package` ?: intent.component?.packageName

        var wrappedIntent = intent
        if (targetPkg != null && VirtualPackageManagerService.isAppInstalled(targetPkg)) {
            val processIndex = ProcessManager.allocateProcess(targetPkg)
            val stubClass = ProcessManager.getStubActivityClass(processIndex)

            Log.i(TAG, "Intercepted startActivity for guest app: $targetPkg -> wrapping into $stubClass")

            wrappedIntent = Intent()
            wrappedIntent.setClassName(hostPackageName, stubClass)
            wrappedIntent.putExtra(StubActivity.EXTRA_TARGET_INTENT, intent)
            wrappedIntent.putExtra(StubActivity.EXTRA_TARGET_PKG, targetPkg)
            wrappedIntent.putExtra(StubActivity.EXTRA_TARGET_ACTIVITY, intent.component?.className ?: "")

            VirtualCore.recordSecurityLog(
                packageName = targetPkg,
                type = SecurityEventType.IPC_INTERCEPTED,
                summary = "Component Stubbing (startActivity)",
                detail = "Wrapped guest Activity into $stubClass"
            )
        }

        return try {
            val execMethod = Instrumentation::class.java.getDeclaredMethod(
                "execStartActivity",
                Context::class.java,
                IBinder::class.java,
                IBinder::class.java,
                Activity::class.java,
                Intent::class.java,
                Int::class.javaPrimitiveType,
                Bundle::class.java
            )
            execMethod.isAccessible = true
            execMethod.invoke(base, who, contextThread, token, target, wrappedIntent, requestCode, options) as? ActivityResult
        } catch (t: Throwable) {
            Log.e(TAG, "Error invoking base execStartActivity", t)
            null
        }
    }

    override fun newActivity(cl: ClassLoader, className: String, intent: Intent): Activity {
        val targetPkg = intent.getStringExtra(StubActivity.EXTRA_TARGET_PKG)
        val targetActivity = intent.getStringExtra(StubActivity.EXTRA_TARGET_ACTIVITY)

        if (targetPkg != null && targetActivity != null && targetActivity.isNotEmpty()) {
            val app = VirtualPackageManagerService.getInstalledApp(targetPkg)
            if (app != null) {
                try {
                    val guestLoader = VirtualClassLoader.getOrCreateClassLoader(
                        VirtualCore.getContext(),
                        targetPkg,
                        app.apkPath
                    )
                    Log.i(TAG, "Instantiating real guest activity: $targetActivity")
                    return base.newActivity(guestLoader, targetActivity, intent)
                } catch (t: Throwable) {
                    Log.w(TAG, "Falling back to stub activity creation: ${t.message}")
                }
            }
        }

        return base.newActivity(cl, className, intent)
    }

    override fun callActivityOnCreate(activity: Activity, icicle: Bundle?) {
        base.callActivityOnCreate(activity, icicle)
    }

    override fun callActivityOnResume(activity: Activity) {
        base.callActivityOnResume(activity)
    }

    override fun callActivityOnPause(activity: Activity) {
        base.callActivityOnPause(activity)
    }

    override fun callActivityOnDestroy(activity: Activity) {
        base.callActivityOnDestroy(activity)
    }
}
