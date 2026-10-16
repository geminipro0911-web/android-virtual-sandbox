package com.sandbox.virtual.core

import android.os.Build
import android.util.Log
import java.lang.reflect.Method

object HiddenApiBypass {
    private const val TAG = "HiddenApiBypass"

    /**
     * Exempt non-SDK interface restrictions on Android 9 (API 28) through Android 14 (API 34)
     * using double-reflection on VMRuntime.
     */
    fun bypass(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return true
        }

        return try {
            val classArrayType = Class.forName("[Ljava.lang.Class;")
            val stringArrayType = Class.forName("[Ljava.lang.String;")

            val forNameMethod = Class::class.java.getDeclaredMethod("forName", String::class.java)
            val getDeclaredMethod = Class::class.java.getDeclaredMethod(
                "getDeclaredMethod",
                String::class.java,
                classArrayType
            )

            val vmRuntimeClass = forNameMethod.invoke(null, "dalvik.system.VMRuntime") as Class<*>
            val getRuntimeMethod = getDeclaredMethod.invoke(
                vmRuntimeClass,
                "getRuntime",
                null
            ) as Method
            val setHiddenApiExemptionsMethod = getDeclaredMethod.invoke(
                vmRuntimeClass,
                "setHiddenApiExemptions",
                arrayOf(stringArrayType)
            ) as Method

            val vmRuntime = getRuntimeMethod.invoke(null)
            setHiddenApiExemptionsMethod.invoke(vmRuntime, arrayOf("L"))
            Log.i(TAG, "Successfully bypassed hidden API restrictions for Android ${Build.VERSION.SDK_INT}")
            true
        } catch (t: Throwable) {
            Log.w(TAG, "Hidden API bypass warning: ${t.message}")
            false
        }
    }
}
