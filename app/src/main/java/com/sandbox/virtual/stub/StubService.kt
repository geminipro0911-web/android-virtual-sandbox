package com.sandbox.virtual.stub

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log

/**
 * Pre-registered Stub Service across isolated processes.
 */
open class StubService : Service() {

    companion object {
        private const val TAG = "StubService"
        const val EXTRA_TARGET_SERVICE = "_virtual_target_service_"
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "StubService created in process pid=${android.os.Process.myPid()}")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val targetService = intent?.getStringExtra(EXTRA_TARGET_SERVICE)
        Log.d(TAG, "StubService onStartCommand: target=$targetService")
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    // Concrete Stub implementations assigned to :virtual_p0 through :virtual_p4
    class P0 : StubService()
    class P1 : StubService()
    class P2 : StubService()
    class P3 : StubService()
    class P4 : StubService()
}
