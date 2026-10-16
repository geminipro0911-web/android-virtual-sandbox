package com.sandbox.virtual.server

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.res.AssetManager
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.sandbox.virtual.model.InstalledAppInfo
import java.io.File
import java.io.FileOutputStream

object VirtualPackageManagerService {
    private const val TAG = "VPMS"
    private const val DATABASE_FILE = "virtual_installed_apps.json"

    private lateinit var context: Context
    private val installedApps = mutableMapOf<String, InstalledAppInfo>()
    private val gson = Gson()

    fun init(ctx: Context) {
        context = ctx.applicationContext
        loadDatabase()
        Log.i(TAG, "VirtualPackageManagerService initialized with ${installedApps.size} apps")
    }

    private fun loadDatabase() {
        val file = File(VirtualFileSystem.getSandboxRootDir(), DATABASE_FILE)
        if (file.exists()) {
            try {
                val json = file.readText()
                val type = object : TypeToken<Map<String, InstalledAppInfo>>() {}.type
                val map: Map<String, InstalledAppInfo>? = gson.fromJson(json, type)
                if (map != null) {
                    installedApps.clear()
                    installedApps.putAll(map)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Error loading virtual apps database", t)
            }
        }
    }

    private fun saveDatabase() {
        try {
            val file = File(VirtualFileSystem.getSandboxRootDir(), DATABASE_FILE)
            val json = gson.toJson(installedApps)
            file.writeText(json)
        } catch (t: Throwable) {
            Log.e(TAG, "Error saving virtual apps database", t)
        }
    }

    /**
     * Install an APK file into the virtual container
     */
    fun installPackage(sourceApk: File): Result<InstalledAppInfo> {
        return try {
            val pm = context.packageManager
            val flags = PackageManager.GET_ACTIVITIES or
                    PackageManager.GET_SERVICES or
                    PackageManager.GET_RECEIVERS or
                    PackageManager.GET_PROVIDERS or
                    PackageManager.GET_PERMISSIONS

            val pkgInfo = pm.getPackageArchiveInfo(sourceApk.absolutePath, flags)
                ?: return Result.failure(IllegalArgumentException("Failed to parse APK archive: ${sourceApk.name}"))

            val packageName = pkgInfo.packageName
            val targetApk = VirtualFileSystem.getApkFile(packageName)

            // Copy APK to sandbox
            sourceApk.copyTo(targetApk, overwrite = true)

            // Extract native shared libraries
            val libDir = VirtualFileSystem.getLibDir(packageName)
            VirtualFileSystem.extractNativeLibraries(targetApk, libDir)

            // Setup C++ POSIX I/O isolation rules
            VirtualFileSystem.setupIsolationRules(packageName)

            // Resolve label & icon
            val appInfo = pkgInfo.applicationInfo
            appInfo.sourceDir = targetApk.absolutePath
            appInfo.publicSourceDir = targetApk.absolutePath

            var label = packageName
            var iconPath: String? = null
            try {
                val res = getResourcesForApk(targetApk)
                if (appInfo.labelRes != 0) {
                    label = res.getText(appInfo.labelRes).toString()
                } else if (appInfo.nonLocalizedLabel != null) {
                    label = appInfo.nonLocalizedLabel.toString()
                }

                if (appInfo.icon != 0) {
                    val drawable = res.getDrawable(appInfo.icon, null)
                    iconPath = saveAppIcon(packageName, drawable)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not extract full resources for $packageName: ${e.message}")
            }

            // Extract main launch activity
            val activities = pkgInfo.activities?.map { it.name } ?: emptyList()
            val mainActivity = findMainActivity(pkgInfo) ?: activities.firstOrNull() ?: ""

            val installedApp = InstalledAppInfo(
                packageName = packageName,
                appName = label,
                versionName = pkgInfo.versionName ?: "1.0",
                versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) pkgInfo.longVersionCode else pkgInfo.versionCode.toLong(),
                apkPath = targetApk.absolutePath,
                dataDir = VirtualFileSystem.getDataDir(packageName).absolutePath,
                libDir = libDir.absolutePath,
                mainActivity = mainActivity,
                iconPath = iconPath,
                installTime = System.currentTimeMillis(),
                assignedProcessIndex = installedApps.size % 10,
                requestedPermissions = pkgInfo.requestedPermissions?.toList() ?: emptyList(),
                activities = activities,
                services = pkgInfo.services?.map { it.name } ?: emptyList(),
                receivers = pkgInfo.receivers?.map { it.name } ?: emptyList(),
                providers = pkgInfo.providers?.map { it.name } ?: emptyList()
            )

            installedApps[packageName] = installedApp
            saveDatabase()
            Log.i(TAG, "Successfully installed $packageName (${installedApp.appName}) into sandbox")
            Result.success(installedApp)
        } catch (t: Throwable) {
            Log.e(TAG, "Error installing APK ${sourceApk.name}", t)
            Result.failure(t)
        }
    }

    /**
     * Clone an already installed host application into the virtual container
     */
    fun cloneHostApp(packageName: String): Result<InstalledAppInfo> {
        return try {
            val pm = context.packageManager
            val appInfo = pm.getApplicationInfo(packageName, 0)
            val hostApk = File(appInfo.sourceDir)
            installPackage(hostApk)
        } catch (t: Throwable) {
            Log.e(TAG, "Error cloning host app $packageName", t)
            Result.failure(t)
        }
    }

    fun uninstallPackage(packageName: String): Boolean {
        VirtualFileSystem.uninstallApp(packageName)
        val removed = installedApps.remove(packageName) != null
        if (removed) {
            saveDatabase()
            Log.i(TAG, "Uninstalled $packageName from virtual sandbox")
        }
        return removed
    }

    fun getInstalledApp(packageName: String): InstalledAppInfo? = installedApps[packageName]

    fun getAllInstalledApps(): List<InstalledAppInfo> = installedApps.values.toList()

    fun isAppInstalled(packageName: String): Boolean = installedApps.containsKey(packageName)

    fun resolveActivity(intent: Intent): ActivityInfo? {
        val pkg = intent.`package` ?: intent.component?.packageName
        val cls = intent.component?.className
        if (pkg != null && installedApps.containsKey(pkg)) {
            val app = installedApps[pkg]!!
            val targetClass = cls ?: app.mainActivity
            val ai = ActivityInfo()
            ai.packageName = pkg
            ai.name = targetClass
            ai.applicationInfo = ApplicationInfo().apply {
                this.packageName = pkg
                this.sourceDir = app.apkPath
                this.dataDir = app.dataDir
                this.nativeLibraryDir = app.libDir
            }
            return ai
        }
        return null
    }

    private fun findMainActivity(pkgInfo: PackageInfo): String? {
        pkgInfo.activities?.forEach { act ->
            // In most standard manifests, the launch activity is exported or standard named
            if (act.name.endsWith("MainActivity") || act.name.endsWith("SplashActivity")) {
                return act.name
            }
        }
        return pkgInfo.activities?.firstOrNull()?.name
    }

    private fun getResourcesForApk(apkFile: File): Resources {
        val assetManager = AssetManager::class.java.getDeclaredConstructor().newInstance()
        val addAssetPath = AssetManager::class.java.getDeclaredMethod("addAssetPath", String::class.java)
        addAssetPath.invoke(assetManager, apkFile.absolutePath)
        val hostRes = context.resources
        return Resources(assetManager, hostRes.displayMetrics, hostRes.configuration)
    }

    private fun saveAppIcon(packageName: String, drawable: Drawable): String? {
        return try {
            val bitmap = if (drawable is BitmapDrawable && drawable.bitmap != null) {
                drawable.bitmap
            } else {
                val b = Bitmap.createBitmap(
                    drawable.intrinsicWidth.coerceAtLeast(96),
                    drawable.intrinsicHeight.coerceAtLeast(96),
                    Bitmap.Config.ARGB_8888
                )
                val canvas = Canvas(b)
                drawable.setBounds(0, 0, canvas.width, canvas.height)
                drawable.draw(canvas)
                b
            }
            val iconFile = File(VirtualFileSystem.getAppDir(packageName), "icon.png")
            FileOutputStream(iconFile).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            iconFile.absolutePath
        } catch (e: Exception) {
            null
        }
    }
}
