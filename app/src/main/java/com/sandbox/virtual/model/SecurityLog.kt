package com.sandbox.virtual.model

import java.io.Serializable
import java.util.UUID

enum class SecurityEventType {
    PERMISSION_INTERCEPTED,
    FILE_ACCESS_REDIRECTED,
    FILE_ACCESS_BLOCKED,
    DEVICE_ID_SPOOFED,
    IPC_INTERCEPTED,
    PROCESS_LIFECYCLE
}

data class SecurityLog(
    val id: String = UUID.randomUUID().toString(),
    val packageName: String,
    val eventType: SecurityEventType,
    val summary: String,
    val detail: String,
    val timestamp: Long = System.currentTimeMillis(),
    val blocked: Boolean = false
) : Serializable
