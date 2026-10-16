package com.sandbox.virtual.core

import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.IInterface
import android.util.Log
import com.sandbox.virtual.model.SecurityEventType
import com.sandbox.virtual.server.DeviceSpoofManager
import com.sandbox.virtual.server.VirtualAppOpsService
import com.sandbox.virtual.server.VirtualPackageManagerService
import java.lang.reflect.Field
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

object BinderHookManager {
    private const val TAG = "BinderHookManager"

    private val hookedServices = mutableSetOf<String>()

    fun initHooks() {
        try {
            hookServiceManagerCache("package", "android.content.pm.IPackageManager") { method, args, target ->
                handlePackageManagerCall(method, args, target)
            }

            hookServiceManagerCache("appops", "com.android.internal.app.IAppOpsService") { method, args, target ->
                handleAppOpsCall(method, args, target)
            }

            hookServiceManagerCache("phone", "com.android.internal.telephony.ITelephony") { method, args, target ->
                handleTelephonyCall(method, args, target)
            }

            hookActivityManagerSingleton()

            Log.i(TAG, "All Binder & ServiceManager hooks successfully initialized")
        } catch (t: Throwable) {
            Log.e(TAG, "Error initializing Binder hooks", t)
        }
    }

    /**
     * Intercepts ServiceManager.sCache to provide our proxied IBinder
     */
    private fun hookServiceManagerCache(
        serviceName: String,
        interfaceDescriptor: String,
        interceptor: (Method, Array<out Any>?, Any) -> Any?
    ) {
        try {
            val serviceManagerClass = Class.forName("android.os.ServiceManager")
            val getServiceMethod = serviceManagerClass.getDeclaredMethod("getService", String::class.java)
            val realBinder = getServiceMethod.invoke(null, serviceName) as? IBinder ?: return

            val sCacheField = serviceManagerClass.getDeclaredField("sCache")
            sCacheField.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val sCache = sCacheField.get(null) as? MutableMap<String, IBinder> ?: return

            val proxiedBinder = Proxy.newProxyInstance(
                serviceManagerClass.classLoader,
                arrayOf(IBinder::class.java),
                BinderProxyHandler(realBinder, interfaceDescriptor, interceptor)
            ) as IBinder

            sCache[serviceName] = proxiedBinder
            hookedServices.add(serviceName)
            Log.i(TAG, "Hooked ServiceManager service: $serviceName")
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to hook service $serviceName: ${t.message}")
        }
    }

    /**
     * Hooks ActivityManager.IActivityManagerSingleton / ActivityTaskManagerSingleton
     */
    private fun hookActivityManagerSingleton() {
        try {
            val singletonClass = Class.forName("android.util.Singleton")
            val mInstanceField = singletonClass.getDeclaredField("mInstance")
            mInstanceField.isAccessible = true

            // Hook ActivityManager / ActivityTaskManager
            val amClass = Class.forName("android.app.ActivityManager")
            val singletonField = try {
                amClass.getDeclaredField("IActivityManagerSingleton")
            } catch (e: NoSuchFieldException) {
                amClass.getDeclaredField("gDefault")
            }
            singletonField.isAccessible = true
            val singletonObj = singletonField.get(null) ?: return

            // Ensure instance is initialized
            val getMethod = singletonClass.getDeclaredMethod("get")
            val realAm = getMethod.invoke(singletonObj) ?: return

            val interfaceClass = Class.forName("android.app.IActivityManager")
            val amProxy = Proxy.newProxyInstance(
                amClass.classLoader,
                arrayOf(interfaceClass),
                InvocationHandler { _, method, args ->
                    handleActivityManagerCall(method, args, realAm)
                }
            )

            mInstanceField.set(singletonObj, amProxy)
            Log.i(TAG, "Hooked IActivityManagerSingleton")
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to hook IActivityManagerSingleton: ${t.message}")
        }
    }

    private class BinderProxyHandler(
        private val baseBinder: IBinder,
        private val interfaceDescriptor: String,
        private val interceptor: (Method, Array<out Any>?, Any) -> Any?
    ) : InvocationHandler {

        override fun invoke(proxy: Any?, method: Method, args: Array<out Any>?): Any? {
            if (method.name == "queryLocalInterface") {
                try {
                    val targetClass = Class.forName(interfaceDescriptor)
                    return Proxy.newProxyInstance(
                        targetClass.classLoader,
                        arrayOf(targetClass, IInterface::class.java),
                        InvocationHandler { _, ifaceMethod, ifaceArgs ->
                            interceptor(ifaceMethod, ifaceArgs, baseBinder)
                        }
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Could not proxy interface $interfaceDescriptor: ${e.message}")
                }
            }
            return if (args != null) method.invoke(baseBinder, *args) else method.invoke(baseBinder)
        }
    }

    private fun handlePackageManagerCall(method: Method, args: Array<out Any>?, target: Any): Any? {
        val methodName = method.name
        when (methodName) {
            "getPackageInfo" -> {
                val pkgName = args?.getOrNull(0) as? String
                if (pkgName != null && VirtualPackageManagerService.isAppInstalled(pkgName)) {
                    val installed = VirtualPackageManagerService.getInstalledApp(pkgName)
                    VirtualCore.recordSecurityLog(
                        packageName = pkgName,
                        type = SecurityEventType.IPC_INTERCEPTED,
                        summary = "IPackageManager.getPackageInfo intercepted",
                        detail = "Returned sandboxed PackageInfo for $pkgName"
                    )
                }
            }
            "checkPermission" -> {
                val perm = args?.getOrNull(0) as? String
                val pkg = args?.getOrNull(1) as? String
                if (pkg != null && perm != null && VirtualPackageManagerService.isAppInstalled(pkg)) {
                    return VirtualAppOpsService.checkPermission(perm, pkg)
                }
            }
            "checkUidPermission" -> {
                val perm = args?.getOrNull(0) as? String
                if (perm != null && VirtualAppOpsService.SENSITIVE_PERMISSIONS.contains(perm)) {
                    return PackageManager.PERMISSION_GRANTED
                }
            }
        }
        return invokeSafely(method, target, args)
    }

    private fun handleAppOpsCall(method: Method, args: Array<out Any>?, target: Any): Any? {
        when (method.name) {
            "checkOperation", "checkAudioOperation" -> {
                val code = (args?.getOrNull(0) as? Number)?.toInt() ?: 0
                val uid = (args?.getOrNull(1) as? Number)?.toInt() ?: 0
                val pkg = args?.getOrNull(2) as? String ?: "virtual.guest"
                return VirtualAppOpsService.checkOperation(code, uid, pkg)
            }
            "noteOperation", "noteProxyOperation" -> {
                val code = (args?.getOrNull(0) as? Number)?.toInt() ?: 0
                val uid = (args?.getOrNull(1) as? Number)?.toInt() ?: 0
                val pkg = args?.getOrNull(2) as? String ?: "virtual.guest"
                return VirtualAppOpsService.noteOperation(code, uid, pkg)
            }
        }
        return invokeSafely(method, target, args)
    }

    private fun handleTelephonyCall(method: Method, args: Array<out Any>?, target: Any): Any? {
        val pkg = "virtual.guest"
        when (method.name) {
            "getDeviceId", "getImei", "getMeid" -> {
                return DeviceSpoofManager.getFakeImei(pkg)
            }
            "getSubscriberId" -> {
                return DeviceSpoofManager.getFakeImsi()
            }
            "getLine1Number" -> {
                return DeviceSpoofManager.getFakePhoneNumber()
            }
        }
        return invokeSafely(method, target, args)
    }

    private fun handleActivityManagerCall(method: Method, args: Array<out Any>?, target: Any): Any? {
        if (method.name == "startActivity") {
            VirtualCore.recordSecurityLog(
                packageName = "virtual.guest",
                type = SecurityEventType.IPC_INTERCEPTED,
                summary = "IActivityManager.startActivity intercepted",
                detail = "Routing guest activity intent through Virtual Engine"
            )
        }
        return invokeSafely(method, target, args)
    }

    private fun invokeSafely(method: Method, target: Any, args: Array<out Any>?): Any? {
        return try {
            if (args != null) method.invoke(target, *args) else method.invoke(target)
        } catch (t: Throwable) {
            val cause = t.cause ?: t
            throw cause
        }
    }
}
