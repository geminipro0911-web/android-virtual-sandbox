package com.sandbox.virtual.core

import android.app.Activity
import android.content.Context
import android.content.Intent
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
import java.util.concurrent.CopyOnWriteArrayList

object VirtualCore {
    private const val TAG = "VirtualCore"

    private lateinit var appContext: Context
    private var isInitialized = false

    private val securityLogList = CopyOnWriteArrayList<SecurityLog>()
    private val _securityLogsFlow = MutableStateFlow<List<SecurityLog>>(emptyList())
    val securityLogsFlow: StateFlow<List<SecurityLog>> = _securityLogsFlow.asStateFlow()

    fun initialize(context: Context) {
        if (isInitialized) return
        appContext = context.applicationContext
        isInitialized = true

        Log.i(TAG, "Initializing VirtualCore engine in process pid=${Process.myPid()}")

        // 1. Initialize Virtual File System
        VirtualFileSystem.init(appContext)

        // 2. Initialize Virtual Package Manager
        VirtualPackageManagerService.init(appContext)

        // 3. Initialize Device Spoofing Manager
        DeviceSpoofManager.init(appContext)

        // 4. Hook ServiceManager & Binder IPC
        BinderHookManager.initHooks()

        // 5. Hook Instrumentation & Handler
        InstrumentationProxy.inject(appContext.packageName)
        HCallbackHook.inject()

        recordSecurityLog(
            packageName = "system",
            type = SecurityEventType.PROCESS_LIFECYCLE,
            summary = "VirtualCore Initialized",
            detail = "Host: ${appContext.packageName}, PID: ${Process.myPid()}"
        )
    }

    fun ensureInitialized(context: Context) {
        if (!isInitialized) {
            initialize(context)
        }
    }

    fun getContext(): Context = appContext

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
        // Merge native C++ POSIX logs
        val nativeLogs = NativeBridge.fetchNativeLogs()
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

            // Notify process manager
            val processIndex = ProcessManager.getProcessIndex(targetPkg)
            ProcessManager.onProcessStarted(processIndex, Process.myPid(), targetPkg)

            Log.i(TAG, "Launching guest activity $targetActivityName with classLoader $classLoader")

            // Display sandbox guest container banner inside the stub window
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
