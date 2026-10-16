#include "native_hook.h"
#include <chrono>
#include <algorithm>
#include <cstring>
#include <dlfcn.h>

namespace sandbox {

IORedirectEngine& IORedirectEngine::getInstance() {
    static IORedirectEngine instance;
    return instance;
}

void IORedirectEngine::initialize(const std::string& hostPkg, const std::string& sandboxRoot) {
    std::lock_guard<std::mutex> lock(mutex_);
    hostPackageName_ = hostPkg;
    sandboxRootDir_ = sandboxRoot;

    // Ensure trailing slash for directory boundaries
    if (!sandboxRootDir_.empty() && sandboxRootDir_.back() != '/') {
        sandboxRootDir_ += '/';
    }

    rules_.clear();
    logs_.clear();

    LOGI("IORedirectEngine initialized: host=%s, sandboxRoot=%s",
         hostPackageName_.c_str(), sandboxRootDir_.c_str());
}

void IORedirectEngine::addRule(const std::string& sourcePrefix, const std::string& targetPrefix, bool exactMatch) {
    std::lock_guard<std::mutex> lock(mutex_);
    rules_.push_back({sourcePrefix, targetPrefix, exactMatch});
    LOGI("Added I/O redirect rule: %s -> %s (exact=%d)",
         sourcePrefix.c_str(), targetPrefix.c_str(), exactMatch);
}

std::string IORedirectEngine::redirectPath(const char* path, const char* operation, bool* outBlocked) {
    if (!path) {
        if (outBlocked) *outBlocked = true;
        return "";
    }

    std::string orig(path);
    std::lock_guard<std::mutex> lock(mutex_);

    // Security check: block guest attempts to access host private data or sensitive root partitions
    bool blocked = false;
    if (orig.rfind("/data/system", 0) == 0 ||
        orig.rfind("/data/misc", 0) == 0 ||
        orig.rfind("/proc/kallsyms", 0) == 0 ||
        (orig.rfind("/data/data/", 0) == 0 &&
         orig.find(hostPackageName_) != std::string::npos &&
         orig.find("/virtual/") == std::string::npos)) {
        blocked = true;
        LOGW("BLOCKED unauthorized host file access: %s [%s]", orig.c_str(), operation);
        recordLog(orig, "[ACCESS_DENIED]", operation, true);
        if (outBlocked) *outBlocked = true;
        return "";
    }

    std::string redirected = orig;

    // Match registered rules
    for (const auto& rule : rules_) {
        if (rule.exactMatch) {
            if (orig == rule.sourcePrefix) {
                redirected = rule.targetPrefix;
                break;
            }
        } else {
            if (orig.rfind(rule.sourcePrefix, 0) == 0) {
                redirected = rule.targetPrefix + orig.substr(rule.sourcePrefix.length());
                break;
            }
        }
    }

    if (redirected != orig) {
        LOGI("Redirected I/O [%s]: %s -> %s", operation, orig.c_str(), redirected.c_str());
        recordLog(orig, redirected, operation, false);
    } else {
        // Record non-redirected standard access
        recordLog(orig, orig, operation, false);
    }

    if (outBlocked) *outBlocked = false;
    return redirected;
}

bool IORedirectEngine::isPathIsolated(const char* path) {
    if (!path) return false;
    std::string str(path);
    std::lock_guard<std::mutex> lock(mutex_);
    for (const auto& rule : rules_) {
        if (str.rfind(rule.sourcePrefix, 0) == 0) {
            return true;
        }
    }
    return false;
}

void IORedirectEngine::recordLog(const std::string& orig, const std::string& redir, const std::string& op, bool blocked) {
    long now = std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::system_clock::now().time_since_epoch()).count();

    if (logs_.size() >= 150) {
        logs_.erase(logs_.begin());
    }
    logs_.push_back({orig, redir, op, now, blocked});
}

std::vector<FileAccessLog> IORedirectEngine::getRecentLogs() {
    std::lock_guard<std::mutex> lock(mutex_);
    return logs_;
}

} // namespace sandbox

// POSIX System Call Interceptors
extern "C" {

using sandbox::IORedirectEngine;

JNIEXPORT void JNICALL
Java_com_sandbox_virtual_core_NativeBridge_nativeInit(
    JNIEnv* env,
    jclass clazz,
    jstring hostPkg,
    jstring sandboxRoot
) {
    const char* c_host = env->GetStringUTFChars(hostPkg, nullptr);
    const char* c_root = env->GetStringUTFChars(sandboxRoot, nullptr);

    IORedirectEngine::getInstance().initialize(c_host ? c_host : "", c_root ? c_root : "");

    if (c_host) env->ReleaseStringUTFChars(hostPkg, c_host);
    if (c_root) env->ReleaseStringUTFChars(sandboxRoot, c_root);
}

JNIEXPORT void JNICALL
Java_com_sandbox_virtual_core_NativeBridge_nativeAddRedirectRule(
    JNIEnv* env,
    jclass clazz,
    jstring sourcePrefix,
    jstring targetPrefix
) {
    const char* c_src = env->GetStringUTFChars(sourcePrefix, nullptr);
    const char* c_tgt = env->GetStringUTFChars(targetPrefix, nullptr);

    if (c_src && c_tgt) {
        IORedirectEngine::getInstance().addRule(c_src, c_tgt, false);
    }

    if (c_src) env->ReleaseStringUTFChars(sourcePrefix, c_src);
    if (c_tgt) env->ReleaseStringUTFChars(targetPrefix, c_tgt);
}

JNIEXPORT jstring JNICALL
Java_com_sandbox_virtual_core_NativeBridge_nativeRedirectPath(
    JNIEnv* env,
    jclass clazz,
    jstring path,
    jstring operation
) {
    if (!path) return nullptr;

    const char* c_path = env->GetStringUTFChars(path, nullptr);
    const char* c_op = operation ? env->GetStringUTFChars(operation, nullptr) : "ACCESS";

    bool blocked = false;
    std::string redir = IORedirectEngine::getInstance().redirectPath(c_path, c_op ? c_op : "ACCESS", &blocked);

    if (c_path) env->ReleaseStringUTFChars(path, c_path);
    if (c_op && operation) env->ReleaseStringUTFChars(operation, c_op);

    if (blocked) {
        return env->NewStringUTF("");
    }
    return env->NewStringUTF(redir.c_str());
}

JNIEXPORT jboolean JNICALL
Java_com_sandbox_virtual_core_NativeBridge_nativeIsPathIsolated(
    JNIEnv* env,
    jclass clazz,
    jstring path
) {
    if (!path) return JNI_FALSE;
    const char* c_path = env->GetStringUTFChars(path, nullptr);
    bool isolated = IORedirectEngine::getInstance().isPathIsolated(c_path);
    env->ReleaseStringUTFChars(path, c_path);
    return isolated ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jobjectArray JNICALL
Java_com_sandbox_virtual_core_NativeBridge_nativeGetAccessLogs(
    JNIEnv* env,
    jclass clazz
) {
    std::vector<sandbox::FileAccessLog> logs = IORedirectEngine::getInstance().getRecentLogs();

    jclass strClass = env->FindClass("java/lang/String");
    jobjectArray array = env->NewObjectArray((jsize)logs.size(), strClass, nullptr);

    for (size_t i = 0; i < logs.size(); ++i) {
        // Format: [timestamp]|[operation]|[blocked]|[originalPath]|[redirectedPath]
        char buffer[2048];
        snprintf(buffer, sizeof(buffer), "%ld|%s|%d|%s|%s",
                 logs[i].timestampMs,
                 logs[i].operation.c_str(),
                 logs[i].blocked ? 1 : 0,
                 logs[i].originalPath.c_str(),
                 logs[i].redirectedPath.c_str());
        jstring item = env->NewStringUTF(buffer);
        env->SetObjectArrayElement(array, (jsize)i, item);
        env->DeleteLocalRef(item);
    }

    return array;
}

} // extern "C"
