package com.sandbox.virtual.stub

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.res.AssetManager
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import com.sandbox.virtual.core.VirtualCore

/**
 * Host pre-registered Stub Activity that passes host system validation.
 * In a multi-process container, each virtual process has assigned stub activities (:virtual_p0 to :virtual_p9).
 * Acts as the Container Host Window that dynamically attaches guest environments and forwards lifecycles.
 */
open class StubActivity : Activity() {

    companion object {
        private const val TAG = "StubActivity"
        const val EXTRA_TARGET_INTENT = "_virtual_target_intent_"
        const val EXTRA_TARGET_PKG = "_virtual_target_pkg_"
        const val EXTRA_TARGET_ACTIVITY = "_virtual_target_activity_"
        const val EXTRA_APK_PATH = "_virtual_apk_path_"
        const val EXTRA_APP_NAME = "_virtual_app_name_"
        const val EXTRA_DATA_DIR = "_virtual_data_dir_"
        const val EXTRA_LIB_DIR = "_virtual_lib_dir_"
    }

    private var guestResources: Resources? = null
    private var guestAssetManager: AssetManager? = null
    private var guestClassLoader: ClassLoader? = null
    private var guestTheme: Resources.Theme? = null
    private var guestPackageName: String? = null

    var activeGuestActivity: Activity? = null

    fun setGuestEnvironment(
        res: Resources?,
        assets: AssetManager?,
        classLoader: ClassLoader?,
        theme: Resources.Theme?,
        packageName: String?
    ) {
        this.guestResources = res
        this.guestAssetManager = assets
        this.guestClassLoader = classLoader
        this.guestTheme = theme
        this.guestPackageName = packageName
    }

    override fun getResources(): Resources {
        return guestResources ?: super.getResources()
    }

    override fun getAssets(): AssetManager {
        return guestAssetManager ?: super.getAssets()
    }

    override fun getClassLoader(): ClassLoader {
        return guestClassLoader ?: super.getClassLoader()
    }

    override fun getTheme(): Resources.Theme {
        return guestTheme ?: super.getTheme()
    }

    override fun getPackageName(): String {
        return guestPackageName ?: super.getPackageName()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            intent?.setExtrasClassLoader(StubActivity::class.java.classLoader)
        } catch (e: Exception) {
            Log.w(TAG, "Could not setExtrasClassLoader", e)
        }

        val targetIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                intent?.getParcelableExtra(EXTRA_TARGET_INTENT, Intent::class.java)
            } catch (e: Exception) {
                null
            }
        } else {
            @Suppress("DEPRECATION")
            try {
                intent?.getParcelableExtra(EXTRA_TARGET_INTENT)
            } catch (e: Exception) {
                null
            }
        }

        var targetPkg = intent?.getStringExtra(EXTRA_TARGET_PKG)
        var targetActivity = intent?.getStringExtra(EXTRA_TARGET_ACTIVITY)

        if (targetPkg.isNullOrEmpty() && targetIntent != null) {
            targetPkg = targetIntent.getStringExtra(EXTRA_TARGET_PKG) ?: targetIntent.`package` ?: targetIntent.component?.packageName
        }
        if (targetActivity.isNullOrEmpty() && targetIntent != null) {
            targetActivity = targetIntent.getStringExtra(EXTRA_TARGET_ACTIVITY) ?: targetIntent.component?.className
        }

        Log.i(TAG, "StubActivity onCreate: pid=${android.os.Process.myPid()}, targetPkg=$targetPkg, targetActivity=$targetActivity")

        // Ensure Virtual Core is initialized in this guest process
        VirtualCore.ensureInitialized(applicationContext)

        if (!targetPkg.isNullOrEmpty()) {
            val finalTargetIntent = targetIntent ?: Intent().apply {
                if (!targetActivity.isNullOrEmpty()) {
                    component = ComponentName(targetPkg, targetActivity)
                }
                action = Intent.ACTION_MAIN
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            VirtualCore.launchGuestActivity(
                hostActivity = this,
                targetPkg = targetPkg,
                targetActivityName = targetActivity ?: "",
                targetIntent = finalTargetIntent,
                savedInstanceState = savedInstanceState
            )
        } else {
            VirtualCore.renderEmptyContainerUI(this)
        }
    }

    override fun onStart() {
        super.onStart()
        try {
            activeGuestActivity?.let { guest ->
                val method = Activity::class.java.getDeclaredMethod("onStart")
                method.isAccessible = true
                method.invoke(guest)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error forwarding onStart: ${e.message}")
        }
    }

    override fun onResume() {
        super.onResume()
        VirtualCore.onActivityResumed(this)
        try {
            activeGuestActivity?.let { guest ->
                val method = Activity::class.java.getDeclaredMethod("onResume")
                method.isAccessible = true
                method.invoke(guest)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error forwarding onResume: ${e.message}")
        }
    }

    override fun onPause() {
        super.onPause()
        VirtualCore.onActivityPaused(this)
        try {
            activeGuestActivity?.let { guest ->
                val method = Activity::class.java.getDeclaredMethod("onPause")
                method.isAccessible = true
                method.invoke(guest)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error forwarding onPause: ${e.message}")
        }
    }

    override fun onStop() {
        super.onStop()
        try {
            activeGuestActivity?.let { guest ->
                val method = Activity::class.java.getDeclaredMethod("onStop")
                method.isAccessible = true
                method.invoke(guest)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error forwarding onStop: ${e.message}")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        VirtualCore.onActivityDestroyed(this)
        try {
            activeGuestActivity?.let { guest ->
                val method = Activity::class.java.getDeclaredMethod("onDestroy")
                method.isAccessible = true
                method.invoke(guest)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error forwarding onDestroy: ${e.message}")
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        try {
            if (activeGuestActivity != null) {
                val method = Activity::class.java.getDeclaredMethod("onBackPressed")
                method.isAccessible = true
                method.invoke(activeGuestActivity)
                return
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error forwarding onBackPressed: ${e.message}")
        }
        super.onBackPressed()
    }

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        if (activeGuestActivity != null && ev != null) {
            try {
                if (activeGuestActivity!!.dispatchTouchEvent(ev)) {
                    return true
                }
            } catch (e: Exception) {
                // Ignore touch dispatch issues
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        try {
            activeGuestActivity?.onConfigurationChanged(newConfig)
        } catch (e: Exception) {
            // ignore
        }
    }

    // Concrete Stub implementations assigned to :virtual_p0 through :virtual_p9
    class P0 : StubActivity()
    class P1 : StubActivity()
    class P2 : StubActivity()
    class P3 : StubActivity()
    class P4 : StubActivity()
    class P5 : StubActivity()
    class P6 : StubActivity()
    class P7 : StubActivity()
    class P8 : StubActivity()
    class P9 : StubActivity()
}
