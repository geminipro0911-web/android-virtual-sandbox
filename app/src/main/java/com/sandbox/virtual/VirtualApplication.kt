package com.sandbox.virtual

import android.app.Application
import android.util.Log
import com.sandbox.virtual.core.VirtualCore

class VirtualApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        Log.i("VirtualApplication", "VirtualApplication starting in process pid=${android.os.Process.myPid()}")
        VirtualCore.initialize(this)
    }
}
