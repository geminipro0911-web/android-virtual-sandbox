package com.sandbox.virtual.ui

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sandbox.virtual.core.ProcessManager
import com.sandbox.virtual.core.VirtualCore
import com.sandbox.virtual.model.InstalledAppInfo
import com.sandbox.virtual.model.SecurityLog
import com.sandbox.virtual.server.DeviceSpoofManager
import com.sandbox.virtual.server.VirtualActivityManagerService
import com.sandbox.virtual.server.VirtualFileSystem
import com.sandbox.virtual.server.VirtualPackageManagerService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

enum class SandboxScreen {
    DASHBOARD,
    APP_DETAIL,
    SECURITY_LOGS
}

data class HostAppItem(
    val packageName: String,
    val appName: String,
    val sourceDir: String
)

class SandboxViewModel : ViewModel() {

    private val _installedApps = MutableStateFlow<List<InstalledAppInfo>>(emptyList())
    val installedApps: StateFlow<List<InstalledAppInfo>> = _installedApps.asStateFlow()

    private val _securityLogs = MutableStateFlow<List<SecurityLog>>(emptyList())
    val securityLogs: StateFlow<List<SecurityLog>> = _securityLogs.asStateFlow()

    private val _selectedApp = MutableStateFlow<InstalledAppInfo?>(null)
    val selectedApp: StateFlow<InstalledAppInfo?> = _selectedApp.asStateFlow()

    private val _currentScreen = MutableStateFlow(SandboxScreen.DASHBOARD)
    val currentScreen: StateFlow<SandboxScreen> = _currentScreen.asStateFlow()

    private val _isInstalling = MutableStateFlow(false)
    val isInstalling: StateFlow<Boolean> = _isInstalling.asStateFlow()

    private val _installProgress = MutableStateFlow("")
    val installProgress: StateFlow<String> = _installProgress.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    private val _hostApps = MutableStateFlow<List<HostAppItem>>(emptyList())
    val hostApps: StateFlow<List<HostAppItem>> = _hostApps.asStateFlow()

    init {
        refreshApps()
        startLogCollector()
    }

    fun refreshApps() {
        viewModelScope.launch(Dispatchers.IO) {
            val apps = VirtualPackageManagerService.getAllInstalledApps()
            val ctx = VirtualCore.getContext()
            apps.forEach { app ->
                app.isRunning = ProcessManager.isAppRunning(ctx, app.packageName)
            }
            _installedApps.value = apps
            val current = _selectedApp.value
            if (current != null) {
                _selectedApp.value = apps.find { it.packageName == current.packageName }
            }
        }
    }

    private fun startLogCollector() {
        viewModelScope.launch(Dispatchers.IO) {
            while (true) {
                _securityLogs.value = VirtualCore.getAllSecurityLogs()
                delay(2000)
            }
        }
    }

    fun selectApp(app: InstalledAppInfo?) {
        _selectedApp.value = app
        if (app != null) {
            _currentScreen.value = SandboxScreen.APP_DETAIL
        }
    }

    fun navigateTo(screen: SandboxScreen) {
        _currentScreen.value = screen
    }

    fun clearStatusMessage() {
        _statusMessage.value = null
    }

    fun installApkFromUri(uri: Uri, context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            _isInstalling.value = true
            _installProgress.value = "Reading APK file..."

            try {
                val tempFile = File(context.cacheDir, "install_temp_${System.currentTimeMillis()}.apk")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(tempFile).use { output ->
                        input.copyTo(output)
                    }
                }

                _installProgress.value = "Parsing manifest and verifying signatures..."
                delay(400)

                _installProgress.value = "Extracting native shared libraries (.so)..."
                delay(300)

                _installProgress.value = "Configuring POSIX I/O sandbox boundaries..."
                val result = VirtualPackageManagerService.installPackage(tempFile)
                tempFile.delete()

                if (result.isSuccess) {
                    val app = result.getOrNull()!!
                    _statusMessage.value = "Successfully installed ${app.appName} into Sandbox!"
                    refreshApps()
                } else {
                    _statusMessage.value = "Install failed: ${result.exceptionOrNull()?.message}"
                }
            } catch (t: Throwable) {
                Log.e("SandboxViewModel", "Installation exception", t)
                _statusMessage.value = "Install failed: ${t.message}"
            } finally {
                _isInstalling.value = false
                _installProgress.value = ""
            }
        }
    }

    fun loadHostApps(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            val pm = context.packageManager
            val installed = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            val list = mutableListOf<HostAppItem>()

            for (info in installed) {
                // Allow any app that has a launcher intent and is not our own host app
                if (info.packageName != context.packageName && pm.getLaunchIntentForPackage(info.packageName) != null) {
                    val name = pm.getApplicationLabel(info).toString()
                    list.add(HostAppItem(info.packageName, name, info.sourceDir))
                }
            }
            _hostApps.value = list.sortedBy { it.appName }
        }
    }

    fun cloneApp(packageName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _isInstalling.value = true
            _installProgress.value = "Cloning $packageName into virtual container..."

            val result = VirtualPackageManagerService.cloneHostApp(packageName)
            if (result.isSuccess) {
                _statusMessage.value = "Successfully cloned $packageName!"
                refreshApps()
            } else {
                _statusMessage.value = "Clone failed: ${result.exceptionOrNull()?.message}"
            }
            _isInstalling.value = false
            _installProgress.value = ""
        }
    }

    fun launchApp(packageName: String, context: Context) {
        val success = VirtualActivityManagerService.launchApp(context, packageName)
        if (!success) {
            _statusMessage.value = "Failed to launch $packageName"
        }
        refreshApps()
    }

    fun forceStop(packageName: String, context: Context) {
        VirtualActivityManagerService.forceStop(context, packageName)
        _statusMessage.value = "Force stopped $packageName"
        refreshApps()
    }

    fun wipeData(packageName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val wiped = VirtualFileSystem.wipeAppData(packageName)
            if (wiped) {
                _statusMessage.value = "Sandbox data wiped for $packageName"
            } else {
                _statusMessage.value = "Failed to wipe data for $packageName"
            }
            refreshApps()
        }
    }

    fun uninstall(packageName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            VirtualPackageManagerService.uninstallPackage(packageName)
            _statusMessage.value = "Uninstalled $packageName"
            _selectedApp.value = null
            _currentScreen.value = SandboxScreen.DASHBOARD
            refreshApps()
        }
    }

    fun regenerateSpoofedIds(packageName: String) {
        DeviceSpoofManager.resetSpoofedIds(packageName)
        _statusMessage.value = "Regenerated fake hardware identifiers for $packageName"
        refreshApps()
    }

    fun clearAllLogs() {
        VirtualCore.clearSecurityLogs()
        _securityLogs.value = emptyList()
    }
}
