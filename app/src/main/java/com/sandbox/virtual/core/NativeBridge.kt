package com.sandbox.virtual.core

import android.util.Log
import com.sandbox.virtual.model.SecurityEventType
import com.sandbox.virtual.model.SecurityLog

object NativeBridge {
    private const val TAG = "NativeBridge"
    private var isLoaded = false

    init {
        try {
            System.loadLibrary("virtual_core")
            isLoaded = true
            Log.i(TAG, "Successfully loaded native library: libvirtual_core.so")
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to load libvirtual_core.so", t)
        }
    }

    fun init(hostPackageName: String, sandboxRootDir: String) {
        if (!isLoaded) return
        try {
            nativeInit(hostPackageName, sandboxRootDir)
        } catch (t: Throwable) {
            Log.e(TAG, "Error initializing native I/O redirect engine", t)
        }
    }

    fun addRedirectRule(sourcePrefix: String, targetPrefix: String) {
        if (!isLoaded) return
        try {
            nativeAddRedirectRule(sourcePrefix, targetPrefix)
        } catch (t: Throwable) {
            Log.e(TAG, "Error adding native redirect rule", t)
        }
    }

    fun redirectPath(path: String, operation: String = "ACCESS"): String {
        if (!isLoaded) return path
        return try {
            val result = nativeRedirectPath(path, operation)
            if (result.isEmpty()) "" else result
        } catch (t: Throwable) {
            Log.e(TAG, "Error in native redirectPath", t)
            path
        }
    }

    fun isPathIsolated(path: String): Boolean {
        if (!isLoaded) return false
        return try {
            nativeIsPathIsolated(path)
        } catch (t: Throwable) {
            false
        }
    }

    fun fetchNativeLogs(): List<SecurityLog> {
        if (!isLoaded) return emptyList()
        val rawList = try {
            nativeGetAccessLogs()
        } catch (t: Throwable) {
            emptyArray<String>()
        }

        val logs = mutableListOf<SecurityLog>()
        for (line in rawList) {
            try {
                // Format: [timestamp]|[operation]|[blocked]|[originalPath]|[redirectedPath]
                val parts = line.split("|")
                if (parts.size >= 5) {
                    val timestamp = parts[0].toLongOrNull() ?: System.currentTimeMillis()
                    val op = parts[1]
                    val blocked = parts[2] == "1"
                    val orig = parts[3]
                    val redir = parts[4]

                    val eventType = if (blocked) {
                        SecurityEventType.FILE_ACCESS_BLOCKED
                    } else {
                        SecurityEventType.FILE_ACCESS_REDIRECTED
                    }

                    val summary = if (blocked) {
                        "Blocked access to sensitive path"
                    } else {
                        "POSIX File I/O Redirected ($op)"
                    }

                    logs.add(
                        SecurityLog(
                            packageName = extractPackageFromPath(orig),
                            eventType = eventType,
                            summary = summary,
                            detail = "$orig -> $redir",
                            timestamp = timestamp,
                            blocked = blocked
                        )
                    )
                }
            } catch (e: Exception) {
                // Ignore malformed log lines
            }
        }
        return logs
    }

    private fun extractPackageFromPath(path: String): String {
        return if (path.contains("/data/user/0/")) {
            path.substringAfter("/data/user/0/").substringBefore("/")
        } else if (path.contains("/data/data/")) {
            path.substringAfter("/data/data/").substringBefore("/")
        } else {
            "virtual.guest"
        }
    }

    // External JNI functions
    @JvmStatic
    private external fun nativeInit(hostPkg: String, sandboxRoot: String)

    @JvmStatic
    private external fun nativeAddRedirectRule(sourcePrefix: String, targetPrefix: String)

    @JvmStatic
    private external fun nativeRedirectPath(path: String, operation: String): String

    @JvmStatic
    private external fun nativeIsPathIsolated(path: String): Boolean

    @JvmStatic
    private external fun nativeGetAccessLogs(): Array<String>
}
