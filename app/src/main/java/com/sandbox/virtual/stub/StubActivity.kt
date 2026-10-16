package com.sandbox.virtual.stub

import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.util.Log
import com.sandbox.virtual.core.VirtualCore

/**
 * Host pre-registered Stub Activity that passes host system validation.
 * In a multi-process container, each virtual process has assigned stub activities.
 */
open class StubActivity : Activity() {

    companion object {
        private const val TAG = "StubActivity"
        const val EXTRA_TARGET_INTENT = "_virtual_target_intent_"
        const val EXTRA_TARGET_PKG = "_virtual_target_pkg_"
        const val EXTRA_TARGET_ACTIVITY = "_virtual_target_activity_"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val targetIntent = intent?.getParcelableExtra<Intent>(EXTRA_TARGET_INTENT)
        val targetPkg = intent?.getStringExtra(EXTRA_TARGET_PKG)
        val targetActivity = intent?.getStringExtra(EXTRA_TARGET_ACTIVITY)

        Log.d(TAG, "StubActivity created in process pid=${android.os.Process.myPid()}, targetPkg=$targetPkg, targetActivity=$targetActivity")

        // Ensure Virtual Core is initialized in this guest process
        VirtualCore.ensureInitialized(applicationContext)

        if (targetIntent != null && targetActivity != null && targetPkg != null) {
            VirtualCore.launchGuestActivity(this, targetPkg, targetActivity, targetIntent, savedInstanceState)
        }
    }

    override fun onResume() {
        super.onResume()
        VirtualCore.onActivityResumed(this)
    }

    override fun onPause() {
        super.onPause()
        VirtualCore.onActivityPaused(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        VirtualCore.onActivityDestroyed(this)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
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
