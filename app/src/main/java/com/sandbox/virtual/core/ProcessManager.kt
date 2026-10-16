package com.sandbox.virtual.core

import android.app.ActivityManager
import android.content.Context
import android.os.Process
import android.util.Log
import com.sandbox.virtual.model.SecurityEventType
import com.sandbox.virtual.model.VirtualProcessRecord

object ProcessManager {
    private const val TAG = "ProcessManager"
    const val MAX_PROCESS_COUNT = 10

    private val processRecords = mutableMapOf<Int, VirtualProcessRecord>()
    private val packageToProcess = mutableMapOf<String, Int>()

    init {
        for (i in 0 until MAX_PROCESS_COUNT) {
            processRecords[i] = VirtualProcessRecord(
                processIndex = i,
                processName = ":virtual_p$i"
            )
        }
    }

    @Synchronized
    fun allocateProcess(packageName: String): Int {
        if (packageToProcess.containsKey(packageName)) {
            val idx = packageToProcess[packageName]!!
            return idx
        }

        // Find idle process slot
        var idleSlot = -1
        for (i in 0 until MAX_PROCESS_COUNT) {
            val record = processRecords[i]!!
            if (!record.isAlive) {
                idleSlot = i
                break
            }
        }

        if (idleSlot == -1) {
            idleSlot = (0 until MAX_PROCESS_COUNT).random()
        }

        val record = processRecords[idleSlot]!!
        record.activePackage = packageName
        record.startTimeMs = System.currentTimeMillis()
        record.isAlive = true
        packageToProcess[packageName] = idleSlot

        Log.i(TAG, "Allocated process :virtual_p$idleSlot for $packageName")
        return idleSlot
    }

    fun getProcessIndex(packageName: String): Int {
        return packageToProcess[packageName] ?: 0
    }

    fun getStubActivityClass(processIndex: Int): String {
        return "com.sandbox.virtual.stub.StubActivity\$P$processIndex"
    }

    fun getStubServiceClass(processIndex: Int): String {
        val safeIndex = processIndex.coerceAtMost(4)
        return "com.sandbox.virtual.stub.StubService\$P$safeIndex"
    }

    @Synchronized
    fun onProcessStarted(processIndex: Int, pid: Int, packageName: String) {
        val record = processRecords[processIndex]
        if (record != null) {
            record.pid = pid
            record.activePackage = packageName
            record.isAlive = true
            record.startTimeMs = System.currentTimeMillis()
        }
        VirtualCore.recordSecurityLog(
            packageName = packageName,
            type = SecurityEventType.PROCESS_LIFECYCLE,
            summary = "Virtual Process Spawned",
            detail = "Assigned process :virtual_p$processIndex (PID: $pid)"
        )
    }

    @Synchronized
    fun isAppRunning(context: Context, packageName: String): Boolean {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
        val runningProcesses = am.runningAppProcesses ?: return false
        val hostPackage = context.packageName

        for (proc in runningProcesses) {
            for (i in 0 until MAX_PROCESS_COUNT) {
                if (proc.processName == "$hostPackage:virtual_p$i") {
                    if (packageToProcess[packageName] == i) {
                        return true
                    }
                }
            }
        }
        return false
    }

    @Synchronized
    fun killApp(context: Context, packageName: String): Boolean {
        val processIndex = packageToProcess[packageName] ?: return false
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
        val hostPackage = context.packageName
        val targetProcessName = "$hostPackage:virtual_p$processIndex"

        val runningProcesses = am.runningAppProcesses ?: return false
        for (proc in runningProcesses) {
            if (proc.processName == targetProcessName) {
                Log.w(TAG, "Killing process ${proc.processName} (PID: ${proc.pid})")
                Process.killProcess(proc.pid)
                val record = processRecords[processIndex]
                record?.isAlive = false
                record?.pid = 0
                record?.activePackage = null
                packageToProcess.remove(packageName)

                VirtualCore.recordSecurityLog(
                    packageName = packageName,
                    type = SecurityEventType.PROCESS_LIFECYCLE,
                    summary = "Virtual Process Force Stopped",
                    detail = "Terminated PID: ${proc.pid}"
                )
                return true
            }
        }
        packageToProcess.remove(packageName)
        return true
    }
}
