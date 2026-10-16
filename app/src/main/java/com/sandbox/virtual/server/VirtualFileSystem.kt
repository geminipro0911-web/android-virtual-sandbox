package com.sandbox.virtual.server

import android.content.Context
import android.os.Build
import android.util.Log
import com.sandbox.virtual.core.NativeBridge
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.ZipFile

object VirtualFileSystem {
    private const val TAG = "VirtualFileSystem"

    private lateinit var sandboxRootDir: File
    private lateinit var virtualDataDir: File
    private lateinit var virtualAppDir: File
    private lateinit var virtualStorageDir: File

    fun init(context: Context) {
        val baseDir = context.filesDir.parentFile ?: context.filesDir
        sandboxRootDir = File(baseDir, "virtual")
        virtualDataDir = File(sandboxRootDir, "data/user/0")
        virtualAppDir = File(sandboxRootDir, "app")
        virtualStorageDir = File(sandboxRootDir, "storage/emulated/0")

        sandboxRootDir.mkdirs()
        virtualDataDir.mkdirs()
        virtualAppDir.mkdirs()
        virtualStorageDir.mkdirs()

        Log.i(TAG, "VirtualFileSystem initialized at: ${sandboxRootDir.absolutePath}")

        // Initialize Native I/O engine with root path
        NativeBridge.init(context.packageName, sandboxRootDir.absolutePath)
    }

    fun getSandboxRootDir(): File = sandboxRootDir

    fun getAppDir(packageName: String): File {
        val dir = File(virtualAppDir, packageName)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun getApkFile(packageName: String): File {
        return File(getAppDir(packageName), "base.apk")
    }

    fun getDataDir(packageName: String): File {
        val dir = File(virtualDataDir, packageName)
        if (!dir.exists()) {
            dir.mkdirs()
            File(dir, "files").mkdirs()
            File(dir, "cache").mkdirs()
            File(dir, "code_cache").mkdirs()
            File(dir, "shared_prefs").mkdirs()
            File(dir, "databases").mkdirs()
        }
        return dir
    }

    fun getLibDir(packageName: String): File {
        val dir = File(getAppDir(packageName), "lib")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun getIsolatedStorageDir(packageName: String): File {
        val dir = File(virtualStorageDir, "Android/data/$packageName")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * Set up isolation rules for a guest app in the C++ native layer
     */
    fun setupIsolationRules(packageName: String) {
        val virtualData = getDataDir(packageName).absolutePath
        val virtualStorage = getIsolatedStorageDir(packageName).absolutePath

        // Rule 1: Redirect /data/data/<pkg> -> virtual data
        NativeBridge.addRedirectRule("/data/data/$packageName", virtualData)
        // Rule 2: Redirect /data/user/0/<pkg> -> virtual data
        NativeBridge.addRedirectRule("/data/user/0/$packageName", virtualData)
        // Rule 3: Redirect /sdcard/Android/data/<pkg> -> virtual storage
        NativeBridge.addRedirectRule("/sdcard/Android/data/$packageName", virtualStorage)
        NativeBridge.addRedirectRule("/storage/emulated/0/Android/data/$packageName", virtualStorage)

        Log.i(TAG, "Isolation rules set up for $packageName")
    }

    /**
     * Extract shared libraries (.so) matching device ABI from APK into virtual lib directory
     */
    fun extractNativeLibraries(apkFile: File, libDir: File) {
        try {
            val zip = ZipFile(apkFile)
            val entries = zip.entries()
            val supportedAbis = Build.SUPPORTED_ABIS.toList()

            // Find best matching ABI in the APK
            val abiMap = mutableMapOf<String, MutableList<java.util.zip.ZipEntry>>()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.name.startsWith("lib/") && entry.name.endsWith(".so")) {
                    val parts = entry.name.split("/")
                    if (parts.size >= 3) {
                        val abi = parts[1]
                        abiMap.getOrPut(abi) { mutableListOf() }.add(entry)
                    }
                }
            }

            var chosenAbi: String? = null
            for (abi in supportedAbis) {
                if (abiMap.containsKey(abi)) {
                    chosenAbi = abi
                    break
                }
            }

            if (chosenAbi != null) {
                Log.i(TAG, "Extracting native libraries for ABI: $chosenAbi")
                val soEntries = abiMap[chosenAbi] ?: emptyList()
                for (entry in soEntries) {
                    val fileName = entry.name.substringAfterLast("/")
                    val targetSo = File(libDir, fileName)
                    zip.getInputStream(entry).use { input ->
                        FileOutputStream(targetSo).use { output ->
                            input.copyTo(output)
                        }
                    }
                    targetSo.setExecutable(true, false)
                    targetSo.setReadable(true, false)
                }
            }
            zip.close()
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to extract native libraries from ${apkFile.name}", t)
        }
    }

    /**
     * Completely wipes guest application data (reset sandbox)
     */
    fun wipeAppData(packageName: String): Boolean {
        return try {
            val dataDir = getDataDir(packageName)
            deleteRecursively(dataDir)
            dataDir.mkdirs()
            File(dataDir, "files").mkdirs()
            File(dataDir, "cache").mkdirs()
            File(dataDir, "code_cache").mkdirs()
            File(dataDir, "shared_prefs").mkdirs()
            File(dataDir, "databases").mkdirs()

            val storageDir = getIsolatedStorageDir(packageName)
            deleteRecursively(storageDir)
            storageDir.mkdirs()
            true
        } catch (t: Throwable) {
            Log.e(TAG, "Error wiping app data for $packageName", t)
            false
        }
    }

    /**
     * Completely uninstalls guest application from filesystem
     */
    fun uninstallApp(packageName: String): Boolean {
        return try {
            deleteRecursively(getAppDir(packageName))
            deleteRecursively(getDataDir(packageName))
            deleteRecursively(getIsolatedStorageDir(packageName))
            true
        } catch (t: Throwable) {
            Log.e(TAG, "Error uninstalling $packageName from virtual FS", t)
            false
        }
    }

    private fun deleteRecursively(fileOrDir: File): Boolean {
        if (!fileOrDir.exists()) return true
        if (fileOrDir.isDirectory) {
            fileOrDir.listFiles()?.forEach { deleteRecursively(it) }
        }
        return fileOrDir.delete()
    }
}
