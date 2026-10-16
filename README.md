# Knox Virtual Sandbox Engine

A high-performance Android Virtual Container Engine capable of installing, isolating, and executing third-party guest APK files without root privileges and without modifying the host operating system.

![Android 14 Compatible](https://img.shields.io/badge/Target_SDK-34_(Android_14)-blue.svg)
![Min SDK](https://img.shields.io/badge/Min_SDK-26_(Android_8.0)-green.svg)
![Language](https://img.shields.io/badge/Language-Kotlin_%2B_C%2B%2B17-orange.svg)
![UI](https://img.shields.io/badge/UI-Jetpack_Compose_Material_3-purple.svg)

---

## Technical Architecture Overview

```
+--------------------------------------------------------------------------+
|                     Knox Virtual Sandbox Engine                          |
+--------------------------------------------------------------------------+
|  [Jetpack Compose Material 3 UI]                                         |
|  - Sandbox Dashboard (Status, App Cards, Instance Badges)                |
|  - APK Installer Flow (Storage Picker & Host App Cloner)                 |
|  - Per-App Control Panel (Process kill, Data wipe, Device Spoofing)      |
|  - Real-Time Security Monitor (POSIX I/O logs, Permission Interceptions) |
+--------------------------------------------------------------------------+
|  [Virtual Core Framework Layer (Kotlin)]                                 |
|  - Multi-Process Engine (:virtual_p0 to :virtual_p9)                      |
|  - Pre-registered Stub Components (StubActivity, StubService, Provider)  |
|  - Dynamic Proxy ServiceManager Hooks (IPackageManager, IActivityManager)|
|  - InstrumentationProxy & ActivityThread.mH Handler Callback Unwrapping   |
|  - DexClassLoader Sandbox Isolation per Guest Application                |
+--------------------------------------------------------------------------+
|  [Fake System Services & Device Spoofing]                                |
|  - VirtualPackageManagerService (VPMS): APK Parsing & Signature Db       |
|  - VirtualActivityManagerService (VAMS): Intent redirection & lifecycle  |
|  - VirtualAppOpsService: Mocked permissions (Contacts, SMS, Media)        |
|  - DeviceSpoofManager: Dynamic IMEI, ANDROID_ID, MAC & Serial Masking    |
+--------------------------------------------------------------------------+
|  [Native File System Isolation (C++17 / JNI)]                            |
|  - CMake / POSIX System Call Interception (open, openat, stat, access)   |
|  - I/O Redirection Engine (/data/data/<pkg> -> /virtual/data/user/0/...) |
|  - Strict Boundary Containment & Host File Protection                    |
+--------------------------------------------------------------------------+
```

---

## Key Features

1. **Multi-Process Virtual Isolation:**
   - Pre-registered Stub Activities (`StubActivity$P0` through `StubActivity$P9`) declared across independent process boundaries `:virtual_p0` to `:virtual_p9`.
   - Complete runtime separation between host and guest environments.

2. **Binder IPC & Dynamic Proxy Interception:**
   - Intercepts `android.os.ServiceManager.sCache` and `IActivityManagerSingleton`.
   - Replaces system services with sandboxed virtual proxies.
   - Transparently routes component calls to the virtual package database.

3. **Dual-Layer Activity Launch Routing:**
   - Intercepts `Instrumentation.execStartActivity` to wrap target guest intents into host-approved `StubActivity` envelopes.
   - Intercepts `ActivityThread.mH` handler callbacks (`LAUNCH_ACTIVITY` and `EXECUTE_TRANSACTION`) to safely unwrap intents before component creation.

4. **POSIX I/O File Redirection Engine (C++17):**
   - Redirects all `/data/data/<guest_pkg>/`, `/data/user/0/<guest_pkg>/`, and `/sdcard/` file operations to `/data/data/<host_pkg>/virtual/data/user/0/<guest_pkg>/`.
   - Prevents guest APKs from accessing host private directories or files belonging to other applications.

5. **Hardware Fingerprint & Device Spoofing:**
   - Generates and persists unique, valid `ANDROID_ID`, `IMEI`, `MAC Address`, and `Serial Number` per guest package to prevent device fingerprinting.

6. **Virtual AppOps & Security Auditing:**
   - Intercepts dangerous permission checks (`READ_CONTACTS`, `READ_SMS`, `ACCESS_FINE_LOCATION`, `READ_MEDIA_*`) and grants sandbox safe modes to keep apps operational without exposing real user data.
   - Comprehensive real-time event monitor logging all file redirections and permission checks.

---

## Building the Project

### Prerequisites
- JDK 17
- Android SDK (API 34)
- Android NDK (`25.1.8937393` or higher)
- CMake `3.22.1`

### Build via Gradle
```bash
# Build Debug APK
./gradlew assembleDebug --stacktrace
```

The resulting APK will be generated at:
`app/build/outputs/apk/debug/app-debug.apk`

---

## Project Structure Deliverables
```
.
├── .github/workflows/android-build.yml   # CI/CD Automated build pipeline
├── app/
│   ├── build.gradle.kts                 # AGP 8.4, Compose, NDK & CMake
│   ├── proguard-rules.pro               # Stub & reflection preservation
│   └── src/main/
│       ├── AndroidManifest.xml          # Multi-process & Stub definitions
│       ├── cpp/
│       │   ├── CMakeLists.txt           # Native build configuration
│       │   ├── native_hook.h            # POSIX hook & redirect structures
│       │   └── io_redirect.cpp          # C++ I/O isolation & JNI bindings
│       ├── java/com/sandbox/virtual/
│       │   ├── VirtualApplication.kt
│       │   ├── core/
│       │   │   ├── VirtualCore.kt
│       │   │   ├── BinderHookManager.kt
│       │   │   ├── InstrumentationProxy.kt
│       │   │   ├── HCallbackHook.kt
│       │   │   ├── VirtualClassLoader.kt
│       │   │   ├── ProcessManager.kt
│       │   │   └── NativeBridge.kt
│       │   ├── server/
│       │   │   ├── VirtualPackageManagerService.kt
│       │   │   ├── VirtualActivityManagerService.kt
│       │   │   ├── DeviceSpoofManager.kt
│       │   │   ├── VirtualAppOpsService.kt
│       │   │   └── VirtualFileSystem.kt
│       │   ├── stub/
│       │   │   ├── StubActivity.kt
│       │   │   ├── StubService.kt
│       │   │   ├── StubContentProvider.kt
│       │   │   └── StubReceiver.kt
│       │   ├── model/
│       │   │   ├── InstalledAppInfo.kt
│       │   │   ├── SecurityLog.kt
│       │   │   └── VirtualProcessRecord.kt
│       │   └── ui/
│       │       ├── MainActivity.kt
│       │       ├── SandboxDashboardScreen.kt
│       │       ├── AppDetailScreen.kt
│       │       ├── SecurityLogScreen.kt
│       │       ├── SandboxViewModel.kt
│       │       └── theme/
│       └── res/
└── gradle/wrapper/
```
