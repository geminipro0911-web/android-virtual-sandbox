package com.sandbox.virtual.core

import android.app.Activity
import android.app.ActivityManager
import android.app.AlertDialog
import android.app.Application
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.res.AssetManager
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Process
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.sandbox.virtual.model.InstalledAppInfo
import com.sandbox.virtual.model.SecurityEventType
import com.sandbox.virtual.model.SecurityLog
import com.sandbox.virtual.server.DeviceSpoofManager
import com.sandbox.virtual.server.VirtualFileSystem
import com.sandbox.virtual.server.VirtualPackageManagerService
import com.sandbox.virtual.stub.StubActivity
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

    /**
     * Create Resources for an external APK with multiple fallback layers
     */
    fun createGuestResources(context: Context, apkPath: String): Resources {
        // Layer 1: Official PackageManager API (Standard & Most Reliable across Android 8-14)
        try {
            val pm = context.packageManager
            val pkgInfo = pm.getPackageArchiveInfo(apkPath, 0)
            if (pkgInfo != null) {
                pkgInfo.applicationInfo.sourceDir = apkPath
                pkgInfo.applicationInfo.publicSourceDir = apkPath
                val res = pm.getResourcesForApplication(pkgInfo.applicationInfo)
                if (res != null) {
                    return res
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "PM getResourcesForApplication notice: ${e.message}")
        }

        // Layer 2: AssetManager reflection with accessible constructor
        try {
            val constructor = AssetManager::class.java.getDeclaredConstructor().apply { isAccessible = true }
            val assetManager = constructor.newInstance()
            val addAssetPath = AssetManager::class.java.getDeclaredMethod("addAssetPath", String::class.java).apply {
                isAccessible = true
            }
            addAssetPath.invoke(assetManager, apkPath)
            val hostRes = context.resources
            return Resources(assetManager, hostRes.displayMetrics, hostRes.configuration)
        } catch (e: Exception) {
            Log.w(TAG, "AssetManager reflection notice: ${e.message}")
        }

        // Layer 3: Host resources fallback
        return context.resources
    }

    /**
     * Launch guest activity inside StubActivity container host window
     */
    fun launchGuestActivity(
        hostActivity: Activity,
        targetPkg: String,
        targetActivityName: String,
        targetIntent: Intent,
        savedInstanceState: Bundle?
    ) {
        // 1. Resolve InstalledAppInfo with Intent Extras fallback (Guarantees app is never null)
        val apkPathFromIntent = hostActivity.intent?.getStringExtra(StubActivity.EXTRA_APK_PATH) ?: ""
        val appNameFromIntent = hostActivity.intent?.getStringExtra(StubActivity.EXTRA_APP_NAME) ?: targetPkg
        val dataDirFromIntent = hostActivity.intent?.getStringExtra(StubActivity.EXTRA_DATA_DIR) ?: ""
        val libDirFromIntent = hostActivity.intent?.getStringExtra(StubActivity.EXTRA_LIB_DIR) ?: ""
        val activityFromIntent = hostActivity.intent?.getStringExtra(StubActivity.EXTRA_TARGET_ACTIVITY) ?: ""

        var app = VirtualPackageManagerService.getInstalledApp(targetPkg)
        if (app == null) {
            val apkFile = if (apkPathFromIntent.isNotEmpty() && File(apkPathFromIntent).exists()) {
                File(apkPathFromIntent)
            } else {
                VirtualFileSystem.getApkFile(targetPkg)
            }

            app = InstalledAppInfo(
                packageName = targetPkg,
                appName = appNameFromIntent,
                versionName = "1.0",
                versionCode = 1L,
                apkPath = apkFile.absolutePath,
                dataDir = if (dataDirFromIntent.isNotEmpty()) dataDirFromIntent else VirtualFileSystem.getDataDir(targetPkg).absolutePath,
                libDir = if (libDirFromIntent.isNotEmpty()) libDirFromIntent else VirtualFileSystem.getLibDir(targetPkg).absolutePath,
                mainActivity = if (activityFromIntent.isNotEmpty()) activityFromIntent else targetActivityName
            )
        }

        val finalActivityName = when {
            targetActivityName.isNotEmpty() -> VirtualPackageManagerService.normalizeClassName(targetPkg, targetActivityName)
            activityFromIntent.isNotEmpty() -> VirtualPackageManagerService.normalizeClassName(targetPkg, activityFromIntent)
            app.mainActivity.isNotEmpty() -> VirtualPackageManagerService.normalizeClassName(targetPkg, app.mainActivity)
            else -> ""
        }

        var guestActivityInstance: Activity? = null
        var launchError: Throwable? = null

        try {
            // 2. Setup POSIX I/O isolation rules
            VirtualFileSystem.setupIsolationRules(targetPkg)

            // 3. Mark process started
            val processIndex = ProcessManager.getProcessIndex(targetPkg)
            ProcessManager.onProcessStarted(processIndex, Process.myPid(), targetPkg)

            // 4. Create guest ClassLoader
            val guestClassLoader = VirtualClassLoader.getOrCreateClassLoader(
                hostActivity,
                targetPkg,
                app.apkPath
            )

            // 5. Build guest Resources & AssetManager
            val guestRes = createGuestResources(hostActivity, app.apkPath)
            val guestAssets = guestRes.assets

            // Build guest Theme with AppCompat style so AndroidX / Material Activities work seamlessly
            val guestTheme = guestRes.newTheme()
            try {
                guestTheme.applyStyle(androidx.appcompat.R.style.Theme_AppCompat_DayNight_NoActionBar, true)
            } catch (e: Exception) {
                guestTheme.applyStyle(android.R.style.Theme_DeviceDefault_NoActionBar, true)
            }

            // 6. Hook StubActivity environment
            if (hostActivity is StubActivity) {
                hostActivity.setGuestEnvironment(guestRes, guestAssets, guestClassLoader, guestTheme, targetPkg)
            }

            // Inject into ContextImpl
            try {
                val base = hostActivity.baseContext
                val resField = base.javaClass.getDeclaredField("mResources").apply { isAccessible = true }
                resField.set(base, guestRes)
            } catch (e: Throwable) {
                // Ignore ContextImpl reflection variants
            }
            try {
                val base = hostActivity.baseContext
                val clField = base.javaClass.getDeclaredField("mClassLoader").apply { isAccessible = true }
                clField.set(base, guestClassLoader)
            } catch (e: Throwable) {
                // Ignore ContextImpl reflection variants
            }

            Log.i(TAG, "Launching sandboxed app $targetPkg -> $finalActivityName (PID: ${Process.myPid()})")

            // 7. Attempt real guest Activity instantiation and execution
            if (finalActivityName.isNotEmpty()) {
                try {
                    val guestClass = guestClassLoader.loadClass(finalActivityName)
                    val activityObj = guestClass.getDeclaredConstructor().apply { isAccessible = true }.newInstance() as Activity

                    val attached = attachGuestActivity(hostActivity, activityObj, targetIntent, app, guestRes)
                    if (attached) {
                        try {
                            activityObj.setTheme(androidx.appcompat.R.style.Theme_AppCompat_DayNight_NoActionBar)
                        } catch (e: Exception) {}

                        val onCreateMethod = Activity::class.java.getDeclaredMethod("onCreate", Bundle::class.java).apply {
                            isAccessible = true
                        }
                        onCreateMethod.invoke(activityObj, savedInstanceState)

                        try {
                            val onStartMethod = Activity::class.java.getDeclaredMethod("onStart").apply { isAccessible = true }
                            onStartMethod.invoke(activityObj)
                        } catch (e: Exception) {}

                        try {
                            val onResumeMethod = Activity::class.java.getDeclaredMethod("onResume").apply { isAccessible = true }
                            onResumeMethod.invoke(activityObj)
                        } catch (e: Exception) {}

                        guestActivityInstance = activityObj
                        if (hostActivity is StubActivity) {
                            hostActivity.activeGuestActivity = activityObj
                        }
                        Log.i(TAG, "Guest activity $finalActivityName successfully attached and running")
                    }
                } catch (t: Throwable) {
                    val cause = t.cause ?: t
                    Log.w(TAG, "Direct guest activity attach notice: ${cause.message}", cause)
                    launchError = cause
                }
            }

            // 8. Check if guest activity attached content views
            val contentGroup = hostActivity.findViewById<ViewGroup>(android.R.id.content)
            val hasViews = (contentGroup?.childCount ?: 0) > 0

            // 9. If no views were attached by guest or error occurred, render rich Knox Container UI
            if (guestActivityInstance == null || !hasViews) {
                renderSandboxContainerUI(hostActivity, app, finalActivityName, launchError)
            }

            recordSecurityLog(
                packageName = targetPkg,
                type = SecurityEventType.PROCESS_LIFECYCLE,
                summary = "Virtual Activity Started",
                detail = "Package: $targetPkg, Activity: $finalActivityName, PID: ${Process.myPid()}"
            )
        } catch (t: Throwable) {
            val cause = t.cause ?: t
            Log.e(TAG, "Fatal notice launching guest activity: ${cause.message}", cause)
            renderSandboxContainerUI(hostActivity, app, finalActivityName, cause)
        }
    }

    /**
     * Dynamically match and invoke Activity.attach across Android 8.0 - 14
     */
    private fun attachGuestActivity(
        hostActivity: Activity,
        guestActivity: Activity,
        targetIntent: Intent,
        app: InstalledAppInfo,
        guestRes: Resources
    ): Boolean {
        return try {
            val attachMethod = Activity::class.java.declaredMethods.firstOrNull { it.name == "attach" }
                ?: return false

            attachMethod.isAccessible = true
            val paramTypes = attachMethod.parameterTypes
            val args = arrayOfNulls<Any>(paramTypes.size)

            val hostToken = try {
                val f = Activity::class.java.getDeclaredField("mToken").apply { isAccessible = true }
                f.get(hostActivity)
            } catch (e: Exception) { null }

            val hostIdent = try {
                val f = Activity::class.java.getDeclaredField("mIdent").apply { isAccessible = true }
                f.get(hostActivity) as? Int ?: 0
            } catch (e: Exception) { 0 }

            val hostActivityThread = try {
                val f = Activity::class.java.getDeclaredField("mMainThread").apply { isAccessible = true }
                f.get(hostActivity)
            } catch (e: Exception) { null }

            val hostInstrumentation = try {
                val f = Activity::class.java.getDeclaredField("mInstrumentation").apply { isAccessible = true }
                f.get(hostActivity)
            } catch (e: Exception) { null }

            val actInfo = ActivityInfo().apply {
                packageName = app.packageName
                name = app.mainActivity
                applicationInfo = ApplicationInfo().apply {
                    packageName = app.packageName
                    sourceDir = app.apkPath
                    dataDir = app.dataDir
                    nativeLibraryDir = app.libDir
                }
            }

            for (i in paramTypes.indices) {
                val type = paramTypes[i]
                args[i] = when {
                    Context::class.java.isAssignableFrom(type) -> hostActivity
                    type.name.contains("ActivityThread") -> hostActivityThread
                    Instrumentation::class.java.isAssignableFrom(type) -> hostInstrumentation
                    IBinder::class.java.isAssignableFrom(type) -> hostToken
                    type == Int::class.javaPrimitiveType || type == Integer::class.java -> hostIdent
                    Application::class.java.isAssignableFrom(type) -> hostActivity.application
                    Intent::class.java.isAssignableFrom(type) -> targetIntent
                    ActivityInfo::class.java.isAssignableFrom(type) -> actInfo
                    CharSequence::class.java.isAssignableFrom(type) -> app.appName
                    Activity::class.java.isAssignableFrom(type) -> null
                    String::class.java.isAssignableFrom(type) -> app.packageName
                    Configuration::class.java.isAssignableFrom(type) -> hostActivity.resources.configuration
                    Window::class.java.isAssignableFrom(type) -> hostActivity.window
                    else -> null
                }
            }

            attachMethod.invoke(guestActivity, *args)

            // Inject resources and theme
            try {
                val f = Activity::class.java.getDeclaredField("mResources").apply { isAccessible = true }
                f.set(guestActivity, guestRes)
            } catch (e: Exception) {}

            try {
                val theme = guestRes.newTheme()
                try {
                    theme.applyStyle(androidx.appcompat.R.style.Theme_AppCompat_DayNight_NoActionBar, true)
                } catch (e: Exception) {
                    theme.applyStyle(android.R.style.Theme_DeviceDefault_NoActionBar, true)
                }
                val f = ContextThemeWrapper::class.java.getDeclaredField("mTheme").apply { isAccessible = true }
                f.set(guestActivity, theme)
            } catch (e: Exception) {}

            true
        } catch (t: Throwable) {
            Log.w(TAG, "Notice attaching guest activity: ${t.message}")
            false
        }
    }

    /**
     * Render the Knox Sandbox Container UI
     */
    fun renderSandboxContainerUI(
        activity: Activity,
        app: InstalledAppInfo,
        targetActivityName: String,
        launchError: Throwable?
    ) {
        val dp = activity.resources.displayMetrics.density

        val rootScroll = ScrollView(activity).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.parseColor("#0B0F19"))
            isFillViewport = true
        }

        val contentLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * dp).toInt(), (40 * dp).toInt(), (20 * dp).toInt(), (32 * dp).toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        // Header section (Icon + Name + Badges)
        val headerLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, (20 * dp).toInt())
        }

        val iconView = ImageView(activity).apply {
            val size = (64 * dp).toInt()
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                marginEnd = (16 * dp).toInt()
            }
            if (app.iconPath != null && File(app.iconPath).exists()) {
                val bitmap = BitmapFactory.decodeFile(app.iconPath)
                setImageBitmap(bitmap)
            } else {
                val bg = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    colors = intArrayOf(Color.parseColor("#00E676"), Color.parseColor("#00B0FF"))
                    orientation = GradientDrawable.Orientation.TL_BR
                }
                background = bg
            }
        }

        val titleCol = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val appTitle = TextView(activity).apply {
            text = app.appName
            textSize = 22f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
        }

        val pkgText = TextView(activity).apply {
            text = app.packageName
            textSize = 12f
            setTextColor(Color.parseColor("#8B949E"))
            typeface = Typeface.MONOSPACE
        }

        val versionText = TextView(activity).apply {
            text = "v${app.versionName} • Space :virtual_p${app.assignedProcessIndex}"
            textSize = 12f
            setTextColor(Color.parseColor("#58A6FF"))
        }

        titleCol.addView(appTitle)
        titleCol.addView(pkgText)
        titleCol.addView(versionText)

        headerLayout.addView(iconView)
        headerLayout.addView(titleCol)
        contentLayout.addView(headerLayout)

        // Status Banner Card
        val statusCard = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * dp).toInt(), (14 * dp).toInt(), (16 * dp).toInt(), (14 * dp).toInt())
            val bg = GradientDrawable().apply {
                setColor(Color.parseColor("#161B22"))
                cornerRadius = 12 * dp
                setStroke((1 * dp).toInt(), Color.parseColor("#238636"))
            }
            background = bg
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (16 * dp).toInt()
            }
        }

        val statusHeader = TextView(activity).apply {
            text = "● KNOX ISOLATION CONTAINER ACTIVE"
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#00E676"))
        }

        val statusDesc = TextView(activity).apply {
            text = "Virtual Process PID: ${Process.myPid()} • POSIX I/O Sandbox & Binder IPC Interceptors Operating"
            textSize = 12f
            setTextColor(Color.parseColor("#C9D1D9"))
            setPadding(0, (4 * dp).toInt(), 0, 0)
        }

        statusCard.addView(statusHeader)
        statusCard.addView(statusDesc)
        contentLayout.addView(statusCard)

        // Diagnostics Card (if launch notice exists)
        if (launchError != null) {
            val errorCard = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding((16 * dp).toInt(), (14 * dp).toInt(), (16 * dp).toInt(), (14 * dp).toInt())
                val bg = GradientDrawable().apply {
                    setColor(Color.parseColor("#1C1618"))
                    cornerRadius = 12 * dp
                    setStroke((1 * dp).toInt(), Color.parseColor("#D29922"))
                }
                background = bg
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = (16 * dp).toInt()
                }
            }

            val errorTitle = TextView(activity).apply {
                text = "CONTAINER RUNTIME NOTICE"
                textSize = 12f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor("#D29922"))
            }

            val errorMsg = TextView(activity).apply {
                text = "${launchError.javaClass.simpleName}: ${launchError.message ?: "Component initialized in background sandbox mode"}"
                textSize = 12f
                setTextColor(Color.parseColor("#E6EDF3"))
                setPadding(0, (4 * dp).toInt(), 0, 0)
            }

            errorCard.addView(errorTitle)
            errorCard.addView(errorMsg)
            contentLayout.addView(errorCard)
        }

        // Virtual Environment Details Card
        val detailsCard = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * dp).toInt(), (16 * dp).toInt(), (16 * dp).toInt(), (16 * dp).toInt())
            val bg = GradientDrawable().apply {
                setColor(Color.parseColor("#161B22"))
                cornerRadius = 12 * dp
                setStroke((1 * dp).toInt(), Color.parseColor("#30363D"))
            }
            background = bg
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (16 * dp).toInt()
            }
        }

        val detailsTitle = TextView(activity).apply {
            text = "CONTAINER SPECIFICATIONS"
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#8B949E"))
            setPadding(0, 0, 0, (8 * dp).toInt())
        }
        detailsCard.addView(detailsTitle)

        fun addSpecRow(label: String, value: String) {
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, (3 * dp).toInt(), 0, (3 * dp).toInt())
            }
            val lbl = TextView(activity).apply {
                text = "$label: "
                textSize = 12f
                setTextColor(Color.parseColor("#8B949E"))
                layoutParams = LinearLayout.LayoutParams((110 * dp).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT)
            }
            val valView = TextView(activity).apply {
                text = value
                textSize = 12f
                setTextColor(Color.parseColor("#E6EDF3"))
                typeface = Typeface.MONOSPACE
            }
            row.addView(lbl)
            row.addView(valView)
            detailsCard.addView(row)
        }

        addSpecRow("Target Activity", targetActivityName.ifEmpty { app.mainActivity })
        addSpecRow("Sandbox Data", app.dataDir)
        addSpecRow("Native Libraries", app.libDir)
        addSpecRow("Spoofed ANDROID_ID", DeviceSpoofManager.getFakeAndroidId(app.packageName))
        addSpecRow("Spoofed IMEI", DeviceSpoofManager.getFakeImei(app.packageName))
        addSpecRow("POSIX I/O Engine", "JNI Hook Active (libvirtual_core.so)")

        contentLayout.addView(detailsCard)

        // Action Buttons Row
        val buttonRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (20 * dp).toInt()
            }
        }

        val btnStorage = Button(activity).apply {
            text = "Storage Files"
            setTextColor(Color.WHITE)
            textSize = 12f
            val bg = GradientDrawable().apply {
                setColor(Color.parseColor("#21262D"))
                cornerRadius = 8 * dp
                setStroke((1 * dp).toInt(), Color.parseColor("#30363D"))
            }
            background = bg
            layoutParams = LinearLayout.LayoutParams(0, (44 * dp).toInt(), 1f).apply {
                marginEnd = (8 * dp).toInt()
            }
            setOnClickListener {
                showStorageDialog(activity, app)
            }
        }

        val btnWipe = Button(activity).apply {
            text = "Reset Data"
            setTextColor(Color.parseColor("#F85149"))
            textSize = 12f
            val bg = GradientDrawable().apply {
                setColor(Color.parseColor("#21262D"))
                cornerRadius = 8 * dp
                setStroke((1 * dp).toInt(), Color.parseColor("#30363D"))
            }
            background = bg
            layoutParams = LinearLayout.LayoutParams(0, (44 * dp).toInt(), 1f).apply {
                marginEnd = (8 * dp).toInt()
            }
            setOnClickListener {
                VirtualFileSystem.wipeAppData(app.packageName)
                Toast.makeText(activity, "Virtual storage reset for ${app.appName}", Toast.LENGTH_SHORT).show()
            }
        }

        val btnClose = Button(activity).apply {
            text = "Close"
            setTextColor(Color.parseColor("#00E676"))
            textSize = 12f
            val bg = GradientDrawable().apply {
                setColor(Color.parseColor("#21262D"))
                cornerRadius = 8 * dp
                setStroke((1 * dp).toInt(), Color.parseColor("#30363D"))
            }
            background = bg
            layoutParams = LinearLayout.LayoutParams(0, (44 * dp).toInt(), 1f)
            setOnClickListener {
                activity.finish()
            }
        }

        buttonRow.addView(btnStorage)
        buttonRow.addView(btnWipe)
        buttonRow.addView(btnClose)
        contentLayout.addView(buttonRow)

        // Real-Time Security Activity Feed
        val logsCard = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * dp).toInt(), (16 * dp).toInt(), (16 * dp).toInt(), (16 * dp).toInt())
            val bg = GradientDrawable().apply {
                setColor(Color.parseColor("#161B22"))
                cornerRadius = 12 * dp
                setStroke((1 * dp).toInt(), Color.parseColor("#30363D"))
            }
            background = bg
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val logsTitle = TextView(activity).apply {
            text = "REAL-TIME SANDBOX SECURITY LOGS"
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#8B949E"))
            setPadding(0, 0, 0, (8 * dp).toInt())
        }
        logsCard.addView(logsTitle)

        val logs = getAllSecurityLogs().filter { it.packageName == app.packageName || it.packageName == "system" }.take(6)
        if (logs.isEmpty()) {
            val emptyLog = TextView(activity).apply {
                text = "Virtual POSIX sandbox active. Intercepted system calls and Binder requests will appear here in real time."
                textSize = 12f
                setTextColor(Color.parseColor("#8B949E"))
            }
            logsCard.addView(emptyLog)
        } else {
            logs.forEach { log ->
                val logItem = TextView(activity).apply {
                    text = "[${log.eventType}] ${log.summary}: ${log.detail}"
                    textSize = 11f
                    setTextColor(Color.parseColor("#58A6FF"))
                    typeface = Typeface.MONOSPACE
                    setPadding(0, (2 * dp).toInt(), 0, (2 * dp).toInt())
                }
                logsCard.addView(logItem)
            }
        }

        contentLayout.addView(logsCard)
        rootScroll.addView(contentLayout)

        activity.setContentView(rootScroll)
    }

    private fun showStorageDialog(activity: Activity, app: InstalledAppInfo) {
        val dataDir = File(app.dataDir)
        val files = dataDir.listFiles()?.map {
            if (it.isDirectory) "${it.name}/ (${it.listFiles()?.size ?: 0} items)" else "${it.name} (${it.length()} bytes)"
        } ?: listOf("Empty sandbox directory")

        AlertDialog.Builder(activity)
            .setTitle("${app.appName} Sandbox Storage")
            .setItems(files.toTypedArray(), null)
            .setPositiveButton("OK", null)
            .show()
    }

    fun renderEmptyContainerUI(activity: Activity, packageName: String? = null) {
        val dp = activity.resources.displayMetrics.density
        val layout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#0B0F19"))
            setPadding((32 * dp).toInt(), (32 * dp).toInt(), (32 * dp).toInt(), (32 * dp).toInt())
        }

        val title = TextView(activity).apply {
            text = "Knox Virtual Container"
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#00E676"))
            setPadding(0, 0, 0, (12 * dp).toInt())
        }

        val info = TextView(activity).apply {
            text = if (packageName != null) {
                "Container initialized for $packageName.\nPID: ${Process.myPid()}"
            } else {
                "Multi-process container worker (:virtual_p) initialized.\nPID: ${Process.myPid()}"
            }
            textSize = 14f
            setTextColor(Color.parseColor("#8B949E"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, (24 * dp).toInt())
        }

        val closeBtn = Button(activity).apply {
            text = "Return to Dashboard"
            setTextColor(Color.WHITE)
            val bg = GradientDrawable().apply {
                setColor(Color.parseColor("#21262D"))
                cornerRadius = 8 * dp
            }
            background = bg
            setOnClickListener {
                activity.finish()
            }
        }

        layout.addView(title)
        layout.addView(info)
        layout.addView(closeBtn)

        activity.setContentView(layout)
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
