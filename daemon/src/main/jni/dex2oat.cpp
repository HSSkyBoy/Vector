#include <fcntl.h>
#include <jni.h>
#include <sched.h>
#include <stdlib.h>
#include <sys/mount.h>
#include <sys/wait.h>
#include <unistd.h>

#include <string>

#include "logging.h"

// Lightweight RAII wrapper to prevent FD leaks
struct UniqueFd {
    int fd;
    explicit UniqueFd(int fd) : fd(fd) {}
    ~UniqueFd() {
        if (fd >= 0) close(fd);
    }
    operator int() const { return fd; }
};

struct JStringUtfChars {
    JNIEnv *env;
    jstring value;
    const char *chars;

    JStringUtfChars(JNIEnv *env, jstring value)
        : env(env), value(value), chars(value ? env->GetStringUTFChars(value, nullptr) : nullptr) {}

    ~JStringUtfChars() {
        if (chars != nullptr) env->ReleaseStringUTFChars(value, chars);
    }

    operator const char *() const { return chars; }
};

struct MountTarget {
    const char *source;
    const char *target;
};

static const char *resolve_wrapper_path(const char *relative_path, char *resolved_path, bool needed) {
    if (!needed) return nullptr;
    if (realpath(relative_path, resolved_path) == nullptr) {
        PLOGE("resolve realpath for %s", relative_path);
        return nullptr;
    }
    return resolved_path;
}

static void bind_mount_readonly(const char *source, const char *target) {
    if (source == nullptr || target == nullptr) return;
    mount(source, target, nullptr, MS_BIND, nullptr);
    mount(nullptr, target, nullptr, MS_BIND | MS_REMOUNT | MS_RDONLY, nullptr);
}

static void unmount_target(const char *target) {
    if (target != nullptr) umount(target);
}

extern "C" JNIEXPORT void JNICALL Java_org_matrix_vector_daemon_env_Dex2OatServer_doMountNative(
    JNIEnv *env, jobject, jboolean enabled, jstring r32, jstring d32, jstring r64, jstring d64) {
    JStringUtfChars r32p(env, r32);
    JStringUtfChars d32p(env, d32);
    JStringUtfChars r64p(env, r64);
    JStringUtfChars d64p(env, d64);

    char dex2oat32[PATH_MAX], dex2oat64[PATH_MAX];
    const char *dex2oat32p = resolve_wrapper_path("bin/dex2oat32", dex2oat32, r32p || d32p);
    const char *dex2oat64p = resolve_wrapper_path("bin/dex2oat64", dex2oat64, r64p || d64p);

    pid_t pid = fork();
    if (pid > 0) {  // Parent process
        waitpid(pid, nullptr, 0);
    } else if (pid == 0) {  // Child process
        UniqueFd ns(open("/proc/1/ns/mnt", O_RDONLY));
        if (ns >= 0) {
            setns(ns, CLONE_NEWNS);
        }

        if (enabled) {
            LOGI("Enable dex2oat wrapper");
            const MountTarget targets[] = {
                {dex2oat32p, r32p},
                {dex2oat32p, d32p},
                {dex2oat64p, r64p},
                {dex2oat64p, d64p},
            };
            for (const auto &target : targets) {
                bind_mount_readonly(target.source, target.target);
            }
            execlp("resetprop", "resetprop", "--delete", "dalvik.vm.dex2oat-flags", nullptr);
        } else {
            LOGI("Disable dex2oat wrapper");
            const char *targets[] = {r32p, d32p, r64p, d64p};
            for (const auto *target : targets) {
                unmount_target(target);
            }
            execlp("resetprop", "resetprop", "dalvik.vm.dex2oat-flags", "--inline-max-code-units=0",
                   nullptr);
        }

        PLOGE("Failed to resetprop");
        exit(1);
    }
}

static int setsockcreatecon_raw(const char *context) {
    std::string path = "/proc/self/task/" + std::to_string(gettid()) + "/attr/sockcreate";
    UniqueFd fd(open(path.c_str(), O_RDWR | O_CLOEXEC));
    if (fd < 0) return -1;

    int ret;
    if (context) {
        do {
            ret = write(fd, context, strlen(context) + 1);
        } while (ret < 0 && errno == EINTR);
    } else {
        do {
            ret = write(fd, nullptr, 0);  // clear
        } while (ret < 0 && errno == EINTR);
    }
    return ret < 0 ? -1 : 0;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_org_matrix_vector_daemon_env_Dex2OatServer_setSockCreateContext(JNIEnv *env, jclass,
                                                                     jstring contextStr) {
    const char *context = contextStr ? env->GetStringUTFChars(contextStr, nullptr) : nullptr;
    int ret = setsockcreatecon_raw(context);
    if (context) env->ReleaseStringUTFChars(contextStr, context);
    return ret == 0;
}

extern "C" JNIEXPORT jstring JNICALL
Java_org_matrix_vector_daemon_env_Dex2OatServer_getSockPath(JNIEnv *env, jobject) {
    return env->NewStringUTF("5291374ceda0aef7c5d86cd2a4f6a3ac\0");
}
