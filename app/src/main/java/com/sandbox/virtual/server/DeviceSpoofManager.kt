package com.sandbox.virtual.server

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.sandbox.virtual.core.VirtualCore
import com.sandbox.virtual.model.SecurityEventType
import java.security.MessageDigest
import java.util.Random

object DeviceSpoofManager {
    private const val TAG = "DeviceSpoofManager"
    private const val PREFS_NAME = "device_spoof_prefs"

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        Log.i(TAG, "DeviceSpoofManager initialized")
    }

    fun getFakeAndroidId(packageName: String): String {
        val key = "android_id_$packageName"
        var id = prefs.getString(key, null)
        if (id == null) {
            val md = MessageDigest.getInstance("SHA-256")
            val hash = md.digest("$packageName-android-id-${System.currentTimeMillis()}".toByteArray())
            id = hash.take(8).joinToString("") { "%02x".format(it) }
            prefs.edit().putString(key, id).apply()
        }
        VirtualCore.recordSecurityLog(
            packageName = packageName,
            type = SecurityEventType.DEVICE_ID_SPOOFED,
            summary = "Spoofed ANDROID_ID",
            detail = "Returned fake ID: $id"
        )
        return id
    }

    fun getFakeImei(packageName: String): String {
        val key = "imei_$packageName"
        var imei = prefs.getString(key, null)
        if (imei == null) {
            val random = Random(packageName.hashCode().toLong())
            val sb = StringBuilder("86")
            for (i in 0 until 12) {
                sb.append(random.nextInt(10))
            }
            // Luhn check digit
            sb.append(calculateLuhnCheckDigit(sb.toString()))
            imei = sb.toString()
            prefs.edit().putString(key, imei).apply()
        }
        VirtualCore.recordSecurityLog(
            packageName = packageName,
            type = SecurityEventType.DEVICE_ID_SPOOFED,
            summary = "Spoofed Telephony IMEI",
            detail = "Returned fake IMEI: $imei"
        )
        return imei
    }

    fun getFakeMacAddress(packageName: String): String {
        val key = "mac_$packageName"
        var mac = prefs.getString(key, null)
        if (mac == null) {
            val random = Random(packageName.hashCode().toLong() xor 0xABCDE)
            mac = String.format(
                "02:%02X:%02X:%02X:%02X:%02X",
                random.nextInt(256),
                random.nextInt(256),
                random.nextInt(256),
                random.nextInt(256),
                random.nextInt(256)
            )
            prefs.edit().putString(key, mac).apply()
        }
        VirtualCore.recordSecurityLog(
            packageName = packageName,
            type = SecurityEventType.DEVICE_ID_SPOOFED,
            summary = "Spoofed WiFi MAC Address",
            detail = "Returned fake MAC: $mac"
        )
        return mac
    }

    fun getFakeSerial(packageName: String): String {
        val key = "serial_$packageName"
        var serial = prefs.getString(key, null)
        if (serial == null) {
            val md = MessageDigest.getInstance("MD5")
            val hash = md.digest("$packageName-serial".toByteArray())
            serial = "VSB" + hash.take(6).joinToString("") { "%02X".format(it) }
            prefs.edit().putString(key, serial).apply()
        }
        VirtualCore.recordSecurityLog(
            packageName = packageName,
            type = SecurityEventType.DEVICE_ID_SPOOFED,
            summary = "Spoofed Hardware Serial",
            detail = "Returned fake Serial: $serial"
        )
        return serial
    }

    fun getFakePhoneNumber(): String = "+15550192834"

    fun getFakeImsi(): String = "310260000000000"

    fun resetSpoofedIds(packageName: String) {
        prefs.edit()
            .remove("android_id_$packageName")
            .remove("imei_$packageName")
            .remove("mac_$packageName")
            .remove("serial_$packageName")
            .apply()
    }

    private fun calculateLuhnCheckDigit(number: String): Int {
        var sum = 0
        var alternate = true
        for (i in number.length - 1 downTo 0) {
            var n = number[i].digitToInt()
            if (alternate) {
                n *= 2
                if (n > 9) n = (n % 10) + 1
            }
            sum += n
            alternate = !alternate
        }
        return (10 - (sum % 10)) % 10
    }
}
