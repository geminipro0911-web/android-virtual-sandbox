package com.sandbox.virtual.model

data class VirtualProcessRecord(
    val processIndex: Int,
    val processName: String,
    var pid: Int = 0,
    var activePackage: String? = null,
    var startTimeMs: Long = 0L,
    var isAlive: Boolean = false
)
