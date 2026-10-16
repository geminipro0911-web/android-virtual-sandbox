package com.sandbox.virtual.stub

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Pre-registered Stub BroadcastReceiver to forward broadcasts to guest receivers.
 */
class StubReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "StubReceiver"
        const val EXTRA_GUEST_ACTION = "_virtual_guest_action_"
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        Log.d(TAG, "StubReceiver received intent: ${intent?.action}")
    }
}
