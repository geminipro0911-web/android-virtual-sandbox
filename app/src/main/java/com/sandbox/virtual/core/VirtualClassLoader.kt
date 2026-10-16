package com.sandbox.virtual.core

import android.content.Context
import android.util.Log
import com.sandbox.virtual.server.VirtualFileSystem
import dalvik.system.DexClassLoader
import java.io.File
import java.util.concurrent.ConcurrentHashMap

object VirtualClassLoader {
    private const val TAG = "VirtualClassLoader"
    private val classLoaderMap = ConcurrentHashMap<String, DexClassLoader>()

    fun getOrCreateClassLoader(context: Context, packageName: String, apkPath: String): DexClassLoader {
        return classLoaderMap.getOrPut(packageName) {
            val optDir = File(VirtualFileSystem.getDataDir(packageName), "code_cache")
            val libDir = VirtualFileSystem.getLibDir(packageName)

            if (!optDir.exists()) optDir.mkdirs()
            if (!libDir.exists()) libDir.mkdirs()

            Log.i(TAG, "Creating DexClassLoader for $packageName with APK=$apkPath, libDir=${libDir.absolutePath}")

            DexClassLoader(
                apkPath,
                optDir.absolutePath,
                libDir.absolutePath,
                context.classLoader
            )
        }
    }

    fun removeClassLoader(packageName: String) {
        classLoaderMap.remove(packageName)
    }
}
