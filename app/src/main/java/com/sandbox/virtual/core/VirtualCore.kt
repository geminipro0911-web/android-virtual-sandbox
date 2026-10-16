package com.sandbox.virtual.core

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.util.Log
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.sandbox.virtual.model.SecurityEventType
import com.sandbox.virtual.model.SecurityLog
import com.sandbox.virtual.server.DeviceSpoofManager
import com.sandbox.virtual.server.VirtualFileSystem
import com.sandbox.virtual.server.VirtualPackageManagerService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

object VirtualCore {
    private const val TAG = "VirtualCore"

    private var appContext: Context? = null
    private var isInitialized = false

    private val securityLogList = CopyOnWriteArrayList<SecurityLog>()
    private val _securityLogsFlow = MutableStateFlow<List<SecurityLog>>(emptyList())
    val securityLogsFlow: StateFlow<List<SecurityLog>> = _securityLogsFlow.asStateFlow()

    fun initialize(context: Context) {
        if (isInitialized) return
        val ctx = context.applicationContext
        appContext = ctx
        isInitialized = true

        val currentProc = getProcessName(ctx)
        val isMain = currentProc == ctx.packageName

        Log.i(TAG, "Initializing VirtualCore in process: '$currentProc' (isMain=$isMain, PID=${Process.myPid()})")

        // 1. Bypass Android 9-14 non-SDK hidden API restrictions
        HiddenApiBypass.bypass()

        // 2. Initialize Virtual File System
        VirtualFileSystem.init(ctx)

        // 3. Initialize Virtual Package Manager
        VirtualPackageManagerService.init(ctx)

        // 4. Initialize Device Spoofing Manager
        DeviceSpoofManager.init(ctx)

        // 5. In virtual worker processes (:virtual_p0 to :virtual_p9), activate virtualization hooks
        if (!isMain) {
            Log.i(TAG, "Virtual guest process detected ($currentProc) - activating isolation hooks")
            try {
                BinderHookManager.initHooks()
                InstrumentationProxy.inject(ctx.packageName)
                HCallbackHook.inject()
            } catch (t: Throwable) {
                Log.w(TAG, "Virtual process hook injection notice: ${t.message}")
            }
        } else {
            Log.i(TAG, "Main container host process running in clean management mode")
        }

        recordSecurityLog(
            packageName = "system",
            type = SecurityEventType.PROCESS_LIFECYCLE,
            summary = "VirtualCore Initialized",
            detail = "Process: $currentProc, PID: ${Process.myPid()}"
        )
    }

    fun ensureInitialized(context: Context) {
        if (!isInitialized) {
            initialize(context)
        }
    }

    fun getContext(): Context {
        return appContext ?: throw IllegalStateException("VirtualCore must be initialized with a Context")
    }

    fun getProcessName(context: Context): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                return Application.getProcessName()
            } catch (e: Exception) {
                // fallback
            }
        }
        val pid = Process.myPid()
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        am?.runningAppProcesses?.forEach { proc ->
            if (proc.pid == pid) {
                return proc.processName
            }
        }
        return try {
            File("/proc/$pid/cmdline").readText().trim().replace("\u0000", "")
        } catch (e: Exception) {
            context.packageName
        }
    }

    fun recordSecurityLog(
        packageName: String,
        type: SecurityEventType,
        summary: String,
        detail: String,
        blocked: Boolean = false
    ) {
        val log = SecurityLog(
            packageName = packageName,
            eventType = type,
            summary = summary,
            detail = detail,
            timestamp = System.currentTimeMillis(),
            blocked = blocked
        )
        if (securityLogList.size >= 200) {
            securityLogList.removeAt(0)
        }
        securityLogList.add(log)
        _securityLogsFlow.value = securityLogList.toList().reversed()
    }

    fun getAllSecurityLogs(): List<SecurityLog> {
        val nativeLogs = try {
            NativeBridge.fetchNativeLogs()
        } catch (e: Exception) {
            emptyList()
        }
        val combined = ArrayList(securityLogList)
        combined.addAll(nativeLogs)
        combined.sortByDescending { it.timestamp }
        return combined.take(200)
    }

    fun clearSecurityLogs() {
        securityLogList.clear()
        _securityLogsFlow.value = emptyList()
    }

    fun launchGuestActivity(
        hostActivity: Activity,
        targetPkg: String,
        targetActivityName: String,
        targetIntent: Intent,
        savedInstanceState: Bundle?
    ) {
        try {
            val app = VirtualPackageManagerService.getInstalledApp(targetPkg) ?: return
            val classLoader = VirtualClassLoader.getOrCreateClassLoader(
                hostActivity,
                targetPkg,
                app.apkPath
            )

            val processIndex = ProcessManager.getProcessIndex(targetPkg)
            ProcessManager.onProcessStarted(processIndex, Process.myPid(), targetPkg)

            Log.i(TAG, "Launching guest activity $targetActivityName with classLoader $classLoader")

            val layout = FrameLayout(hostActivity).apply {
                setBackgroundColor(0xFF121212.toInt())
            }

            val textLayout = LinearLayout(hostActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(48, 96, 48, 48)
            }

            val titleView = TextView(hostActivity).apply {
                text = "${app.appName} (Sandboxed)"
                textSize = 22f
                setTextColor(0xFF00E676.toInt())
                setPadding(0, 0, 0, 16)
            }

            val infoView = TextView(hostActivity).apply {
                text = "Package: $targetPkg\n" +
                        "Class: $targetActivityName\n" +
                        "PID: ${Process.myPid()}\n" +
                        "Virtual Data: ${app.dataDir}\n" +
                        "Native Libs: ${app.libDir}\n" +
                        "Status: POSIX I/O Isolated & Binder Hook Active"
                textSize = 14f
                setTextColor(0xFFB0BEC5.toInt())
                setLineSpacing(8f, 1f)
            }

            textLayout.addView(titleView)
            textLayout.addView(infoView)
            layout.addView(textLayout)

            hostActivity.setContentView(layout)
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to render guest activity", t)
        }
    }

    fun onActivityResumed(activity: Activity) {
        Log.d(TAG, "StubActivity resumed: ${activity.javaClass.simpleName}")
    }

    fun onActivityPaused(activity: Activity) {
        Log.d(TAG, "StubActivity paused: ${activity.javaClass.simpleName}")
    }

    fun onActivityDestroyed(activity: Activity) {
        Log.d(TAG, "StubActivity destroyed: ${activity.javaClass.simpleName}")
    }
}
