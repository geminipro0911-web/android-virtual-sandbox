#ifndef NATIVE_HOOK_H
#define NATIVE_HOOK_H

#include <jni.h>
#include <string>
#include <vector>
#include <mutex>
#include <sys/types.h>
#include <sys/stat.h>
#include <fcntl.h>
#include <unistd.h>
#include <android/log.h>

#define LOG_TAG "VirtualCore_Native"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace sandbox {

struct RedirectRule {
    std::string sourcePrefix;
    std::string targetPrefix;
    bool exactMatch;
};

struct FileAccessLog {
    std::string originalPath;
    std::string redirectedPath;
    std::string operation;
    long timestampMs;
    bool blocked;
};

class IORedirectEngine {
public:
    static IORedirectEngine& getInstance();

    void initialize(const std::string& hostPkg, const std::string& sandboxRoot);
    void addRule(const std::string& sourcePrefix, const std::string& targetPrefix, bool exactMatch = false);
    std::string redirectPath(const char* path, const char* operation = "ACCESS", bool* outBlocked = nullptr);
    bool isPathIsolated(const char* path);
    void recordLog(const std::string& orig, const std::string& redir, const std::string& op, bool blocked);
    std::vector<FileAccessLog> getRecentLogs();

private:
    IORedirectEngine() = default;
    ~IORedirectEngine() = default;
    IORedirectEngine(const IORedirectEngine&) = delete;
    IORedirectEngine& operator=(const IORedirectEngine&) = delete;

    std::string hostPackageName_;
    std::string sandboxRootDir_;
    std::vector<RedirectRule> rules_;
    std::vector<FileAccessLog> logs_;
    std::mutex mutex_;
};

} // namespace sandbox

#ifdef __cplusplus
extern "C" {
#endif

// JNI bindings for com.sandbox.virtual.core.NativeBridge
JNIEXPORT void JNICALL
Java_com_sandbox_virtual_core_NativeBridge_nativeInit(
    JNIEnv* env,
    jclass clazz,
    jstring hostPkg,
    jstring sandboxRoot
);

JNIEXPORT void JNICALL
Java_com_sandbox_virtual_core_NativeBridge_nativeAddRedirectRule(
    JNIEnv* env,
    jclass clazz,
    jstring sourcePrefix,
    jstring targetPrefix
);

JNIEXPORT jstring JNICALL
Java_com_sandbox_virtual_core_NativeBridge_nativeRedirectPath(
    JNIEnv* env,
    jclass clazz,
    jstring path,
    jstring operation
);

JNIEXPORT jboolean JNICALL
Java_com_sandbox_virtual_core_NativeBridge_nativeIsPathIsolated(
    JNIEnv* env,
    jclass clazz,
    jstring path
);

JNIEXPORT jobjectArray JNICALL
Java_com_sandbox_virtual_core_NativeBridge_nativeGetAccessLogs(
    JNIEnv* env,
    jclass clazz
);

#ifdef __cplusplus
}
#endif

#endif // NATIVE_HOOK_H
