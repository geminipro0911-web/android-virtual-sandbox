package com.sandbox.virtual.core

import android.content.Intent
import android.os.Handler
import android.os.Message
import android.util.Log
import com.sandbox.virtual.stub.StubActivity
import java.lang.reflect.Field

object HCallbackHook {
    private const val TAG = "HCallbackHook"
    private const val LAUNCH_ACTIVITY = 100
    private const val EXECUTE_TRANSACTION = 159

    fun inject() {
        try {
            val activityThreadClass = Class.forName("android.app.ActivityThread")
            val currentActivityThreadMethod = activityThreadClass.getDeclaredMethod("currentActivityThread")
            currentActivityThreadMethod.isAccessible = true
            val currentActivityThread = currentActivityThreadMethod.invoke(null) ?: return

            val mHField = activityThreadClass.getDeclaredField("mH")
            mHField.isAccessible = true
            val mH = mHField.get(currentActivityThread) as? Handler ?: return

            val mCallbackField = Handler::class.java.getDeclaredField("mCallback")
            mCallbackField.isAccessible = true
            val originalCallback = mCallbackField.get(mH) as? Handler.Callback

            if (originalCallback !is VirtualHCallback) {
                mCallbackField.set(mH, VirtualHCallback(originalCallback))
                Log.i(TAG, "Successfully injected VirtualHCallback into ActivityThread.mH")
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to inject HCallbackHook", t)
        }
    }

    private class VirtualHCallback(private val originalCallback: Handler.Callback?) : Handler.Callback {
        override fun handleMessage(msg: Message): Boolean {
            try {
                if (msg.what == LAUNCH_ACTIVITY) {
                    handleLaunchActivity(msg.obj)
                } else if (msg.what == EXECUTE_TRANSACTION) {
                    handleExecuteTransaction(msg.obj)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Error in VirtualHCallback dispatch: ${t.message}")
            }
            return originalCallback?.handleMessage(msg) ?: false
        }

        private fun handleLaunchActivity(activityClientRecord: Any?) {
            if (activityClientRecord == null) return
            try {
                val intentField = activityClientRecord.javaClass.getDeclaredField("intent")
                intentField.isAccessible = true
                val intent = intentField.get(activityClientRecord) as? Intent ?: return

                unwrapIntent(intent, intentField, activityClientRecord)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to unwrap LAUNCH_ACTIVITY: ${e.message}")
            }
        }

        private fun handleExecuteTransaction(transaction: Any?) {
            if (transaction == null) return
            try {
                val getCallbacksMethod = transaction.javaClass.getDeclaredMethod("mActivityCallbacks")
                getCallbacksMethod.isAccessible = true
                val callbacks = getCallbacksMethod.invoke(transaction) as? List<*> ?: return

                for (item in callbacks) {
                    if (item != null && item.javaClass.name.contains("LaunchActivityItem")) {
                        val mIntentField = item.javaClass.getDeclaredField("mIntent")
                        mIntentField.isAccessible = true
                        val intent = mIntentField.get(item) as? Intent ?: continue

                        unwrapIntent(intent, mIntentField, item)
                        break
                    }
                }
            } catch (e: Exception) {
                // Ignore transaction reflection variants across OEMs
            }
        }

        private fun unwrapIntent(stubIntent: Intent, targetField: Field, targetObj: Any) {
            val realIntent = stubIntent.getParcelableExtra<Intent>(StubActivity.EXTRA_TARGET_INTENT)
            val realPkg = stubIntent.getStringExtra(StubActivity.EXTRA_TARGET_PKG)
            val realActivity = stubIntent.getStringExtra(StubActivity.EXTRA_TARGET_ACTIVITY)

            if (realIntent != null && realPkg != null) {
                Log.i(TAG, "Unwrapped stub intent -> Target Activity: $realActivity in $realPkg")
                targetField.set(targetObj, realIntent)
            }
        }
    }
}
