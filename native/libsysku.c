#define _GNU_SOURCE

#include <android/log.h>
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <jni.h>
#include <stdarg.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/syscall.h>
#include <sys/types.h>
#include <unistd.h>

#define LOG_TAG "libsysku"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#define LIBSYSKU_VERSION "1.1.0"

/* ────────────────────────────────────────────────────────────────
 * STATE
 * ──────────────────────────────────────────────────────────────── */

static volatile int g_installed        = 0;
static volatile int g_calls_seen       = 0;
static volatile int g_calls_blocked    = 0;
static volatile int g_dents_seen       = 0;
static volatile int g_dents_dropped    = 0;

/*
 * Fallback mode flag. Set by nativeInit based on moduleRunning.
 *
 *   0 = FULL    — syscall() and getdents64() both filter
 *   1 = FALLBACK — syscall() filters, getdents64() passes through
 *
 * The getdents64 interposer checks this and returns the real
 * result unchanged when set.
 */
static volatile int g_fallback_mode    = 0;

static long     (*real_syscall)     (long, ...) = NULL;
static ssize_t  (*real_getdents64)  (int, void *, size_t) = NULL;

typedef int (*xstealth_path_is_blocked_fn)      (const char *);
typedef int (*xstealth_fd_resolves_to_hidden_fn)(int);
typedef int (*xstealth_is_active_fn)            (void);

static xstealth_path_is_blocked_fn        p_path_is_blocked        = NULL;
static xstealth_fd_resolves_to_hidden_fn  p_fd_resolves_to_hidden  = NULL;
static xstealth_is_active_fn              p_is_active              = NULL;

/* ────────────────────────────────────────────────────────────────
 * SYMBOL RESOLUTION
 * ──────────────────────────────────────────────────────────────── */

static void resolve_once(void) {
    if (g_installed) return;

    if (!real_syscall) {
        real_syscall = (long (*)(long, ...)) dlsym(RTLD_NEXT, "syscall");
        if (!real_syscall) {
            real_syscall = (long (*)(long, ...)) dlsym(RTLD_DEFAULT, "syscall");
        }
    }
    if (!real_getdents64) {
        real_getdents64 = (ssize_t (*)(int, void *, size_t))
            dlsym(RTLD_NEXT, "getdents64");
        if (!real_getdents64) {
            real_getdents64 = (ssize_t (*)(int, void *, size_t))
                dlsym(RTLD_DEFAULT, "getdents64");
        }
    }
    if (!p_path_is_blocked) {
        p_path_is_blocked = (xstealth_path_is_blocked_fn)
            dlsym(RTLD_DEFAULT, "xstealth_path_is_blocked");
    }
    if (!p_fd_resolves_to_hidden) {
        p_fd_resolves_to_hidden = (xstealth_fd_resolves_to_hidden_fn)
            dlsym(RTLD_DEFAULT, "xstealth_fd_resolves_to_hidden");
    }
    if (!p_is_active) {
        p_is_active = (xstealth_is_active_fn)
            dlsym(RTLD_DEFAULT, "xstealth_is_active");
    }

    g_installed = 1;

    LOGI("resolved: syscall=%p getdents64=%p "
         "xstealth_path_is_blocked=%p "
         "xstealth_fd_resolves_to_hidden=%p "
         "xstealth_is_active=%p",
         (void *) real_syscall, (void *) real_getdents64,
         (void *) p_path_is_blocked,
         (void *) p_fd_resolves_to_hidden,
         (void *) p_is_active);

    if (!p_path_is_blocked) {
        LOGW("libxstealth.so does not export "
             "xstealth_path_is_blocked — syscall() filtering will "
             "pass everything through. Update libxstealth.so to "
             "v0.18.0+.");
    }
    if (!p_is_active) {
        LOGW("libxstealth.so does not export xstealth_is_active — "
             "Sysku will stay inactive. Update libxstealth.so to "
             "v0.18.0+.");
    }
}

static int xstealth_active(void) {
    if (!g_installed) resolve_once();
    return p_is_active ? p_is_active() : 0;
}

/* ────────────────────────────────────────────────────────────────
 * syscall() INTERPOSER
 *
 * Filters path-taking syscalls. Everything else passes through.
 * The #ifdef guards let the file compile on NDKs where some
 * legacy syscall constants aren't defined for 64-bit ARM.
 * ──────────────────────────────────────────────────────────────── */

long syscall(long number, ...) {
    if (!real_syscall) resolve_once();

    va_list ap;
    va_start(ap, number);
    long a1 = va_arg(ap, long);
    long a2 = va_arg(ap, long);
    long a3 = va_arg(ap, long);
    long a4 = va_arg(ap, long);
    long a5 = va_arg(ap, long);
    long a6 = va_arg(ap, long);
    va_end(ap);

    if (!real_syscall) { errno = ENOSYS; return -1; }

    if (!p_is_active || !xstealth_active() || !p_path_is_blocked) {
        return real_syscall(number, a1, a2, a3, a4, a5, a6);
    }

    const char *path = NULL;

    switch (number) {
        case SYS_openat:      path = (const char *) a2; break;
#ifdef SYS_openat2
        case SYS_openat2:     path = (const char *) a2; break;
#endif
#ifdef SYS_open
        case SYS_open:        path = (const char *) a1; break;
#endif
#ifdef SYS_newfstatat
        case SYS_newfstatat:  path = (const char *) a2; break;
#elif defined(SYS_fstatat)
        case SYS_fstatat:     path = (const char *) a2; break;
#endif
#ifdef SYS_statx
        case SYS_statx:       path = (const char *) a2; break;
#endif
#ifdef SYS_stat
        case SYS_stat:        path = (const char *) a1; break;
#endif
#ifdef SYS_lstat
        case SYS_lstat:       path = (const char *) a1; break;
#endif
#ifdef SYS_faccessat
        case SYS_faccessat:   path = (const char *) a2; break;
#endif
#ifdef SYS_faccessat2
        case SYS_faccessat2:  path = (const char *) a2; break;
#endif
#ifdef SYS_access
        case SYS_access:      path = (const char *) a1; break;
#endif
#ifdef SYS_readlinkat
        case SYS_readlinkat:  path = (const char *) a2; break;
#endif
#ifdef SYS_readlink
        case SYS_readlink:    path = (const char *) a1; break;
#endif
        default:
            return real_syscall(number, a1, a2, a3, a4, a5, a6);
    }

    if (path == NULL) {
        return real_syscall(number, a1, a2, a3, a4, a5, a6);
    }

    __sync_fetch_and_add(&g_calls_seen, 1);

    if (p_path_is_blocked(path)) {
        __sync_fetch_and_add(&g_calls_blocked, 1);
        LOGI("syscall blocked: nr=%ld path=%s", number, path);
        errno = ENOENT;
        return -1;
    }

    return real_syscall(number, a1, a2, a3, a4, a5, a6);
}

/* ────────────────────────────────────────────────────────────────
 * getdents64 INTERPOSER
 *
 * Skipped in fallback mode — returns the real getdents64
 * unchanged. Only active in FULL mode when the ADB module is
 * running and the hidden-path list is being coordinated.
 *
 * The kernel's struct linux_dirent64 layout is ABI-frozen at:
 *   offset 0  : uint64 d_ino
 *   offset 8  : int64  d_off
 *   offset 16 : uint16 d_reclen
 *   offset 18 : uint8  d_type
 *   offset 19 : char   d_name[] (NUL-terminated)
 *
 * d_reclen is the total length of the record including d_name
 * and padding. We read it from offset 16 and the name from
 * offset 19 via helpers, because a self-declared flexible-array
 * struct can't be named through a pointer in the same
 * translation unit (GCC/Clang reject it).
 * ──────────────────────────────────────────────────────────────── */

#define LINUX_DIRENT64_HEADER_SIZE 19

static inline uint16_t dent_reclen(const uint8_t *p) {
    uint16_t v;
    memcpy(&v, p + 16, sizeof(v));
    return v;
}

static inline const char *dent_name(const uint8_t *p) {
    return (const char *) (p + LINUX_DIRENT64_HEADER_SIZE);
}

static int name_should_be_hidden(const char *name) {
    if (!name || !*name) return 0;

    static const char *patterns[] = {
        "libxstealth",
        "libshizuposed",
        "libamiru",
        "libsysku",
        "libcallsite",
        "libxstealth_next",
        "libxstealth_bridge",
        "syscall_cache",
        "shizuposed",
        "shizuku",
        NULL
    };

    for (int i = 0; patterns[i]; i++) {
        if (strstr(name, patterns[i]) != NULL) return 1;
    }
    return 0;
}

ssize_t getdents64(int fd, void *dirp, size_t count) {
    if (!real_getdents64) resolve_once();
    if (!real_getdents64) { errno = ENOSYS; return -1; }

    /*
     * Fallback mode: pass through unchanged.
     *
     * Without the ADB module running, the hidden-path list may
     * be stale, and a stale list that hides the wrong entries is
     * a more visible tell than no hiding at all. The syscall()
     * interposer covers the "returns ENOENT for a path that
     * shouldn't exist" half safely; this half is left alone
     * until the module is coordinating it again.
     */
    if (g_fallback_mode) {
        return real_getdents64(fd, dirp, count);
    }

    if (!p_is_active || !xstealth_active()) {
        return real_getdents64(fd, dirp, count);
    }

    if (p_fd_resolves_to_hidden && p_fd_resolves_to_hidden(fd)) {
        __sync_fetch_and_add(&g_dents_dropped, 1);
        return 0;
    }

    void *scratch = malloc(count);
    if (!scratch) {
        return real_getdents64(fd, dirp, count);
    }

    ssize_t n = real_getdents64(fd, scratch, count);
    if (n <= 0) {
        free(scratch);
        return n;
    }

    __sync_fetch_and_add(&g_dents_seen, 1);

    uint8_t *src = (uint8_t *) scratch;
    uint8_t *dst = (uint8_t *) dirp;
    size_t src_off = 0;
    size_t dst_off = 0;

    while (src_off < (size_t) n) {
        uint8_t *rec = src + src_off;
        uint16_t reclen = dent_reclen(rec);

        if (reclen < LINUX_DIRENT64_HEADER_SIZE
                || src_off + reclen > (size_t) n) {
            memcpy(dst + dst_off, rec, (size_t) n - src_off);
            dst_off += (size_t) n - src_off;
            break;
        }

        if (name_should_be_hidden(dent_name(rec))) {
            __sync_fetch_and_add(&g_dents_dropped, 1);
        } else {
            memcpy(dst + dst_off, rec, reclen);
            dst_off += reclen;
        }

        src_off += reclen;
    }

    free(scratch);
    return (ssize_t) dst_off;
}

/* ────────────────────────────────────────────────────────────────
 * JNI ENTRY POINTS
 *
 * Class: com.shizuposed.manager.stealth.SyskuNative
 * ──────────────────────────────────────────────────────────────── */

JNIEXPORT jint JNICALL
Java_com_shizuposed_manager_stealth_SyskuNative_nativeInit(
        JNIEnv *env, jclass clazz, jstring shell_base,
        jboolean moduleRunning) {
    (void) env;
    (void) clazz;
    (void) shell_base;

    g_fallback_mode = moduleRunning ? 0 : 1;

    resolve_once();

    int ok = (real_syscall != NULL)
          && (real_getdents64 != NULL)
          && (p_path_is_blocked != NULL)
          && (p_is_active != NULL);

    LOGI("nativeInit: version=%s ready=%d fallback=%d "
         "syscall=%p getdents64=%p "
         "path_is_blocked=%p fd_resolves=%p is_active=%p",
         LIBSYSKU_VERSION, ok, g_fallback_mode,
         (void *) real_syscall, (void *) real_getdents64,
         (void *) p_path_is_blocked,
         (void *) p_fd_resolves_to_hidden,
         (void *) p_is_active);

    if (!ok) {
        LOGW("nativeInit: one or more dependencies failed to resolve — "
             "interposers may pass through unchanged");
    } else if (g_fallback_mode) {
        LOGI("nativeInit: FALLBACK MODE — syscall() filtering only; "
             "getdents64 interposer disabled until the ADB module runs");
    }

    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_shizuposed_manager_stealth_SyskuNative_nativeIsActive(
        JNIEnv *env, jclass clazz) {
    (void) env; (void) clazz;
    return xstealth_active() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_shizuposed_manager_stealth_SyskuNative_nativeIsFallbackMode(
        JNIEnv *env, jclass clazz) {
    (void) env; (void) clazz;
    return g_fallback_mode ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_com_shizuposed_manager_stealth_SyskuNative_nativeInstalledCount(
        JNIEnv *env, jclass clazz) {
    (void) env; (void) clazz;
    int n = 0;
    if (real_syscall)             n++;
    if (real_getdents64)          n++;
    if (p_path_is_blocked)        n++;
    if (p_fd_resolves_to_hidden)  n++;
    if (p_is_active)              n++;
    return n;
}

JNIEXPORT jint JNICALL
Java_com_shizuposed_manager_stealth_SyskuNative_nativeGetCallsSeen(
        JNIEnv *env, jclass clazz) {
    (void) env; (void) clazz;
    return g_calls_seen;
}

JNIEXPORT jint JNICALL
Java_com_shizuposed_manager_stealth_SyskuNative_nativeGetCallsBlocked(
        JNIEnv *env, jclass clazz) {
    (void) env; (void) clazz;
    return g_calls_blocked;
}

JNIEXPORT jint JNICALL
Java_com_shizuposed_manager_stealth_SyskuNative_nativeGetDentsSeen(
        JNIEnv *env, jclass clazz) {
    (void) env; (void) clazz;
    return g_dents_seen;
}

JNIEXPORT jint JNICALL
Java_com_shizuposed_manager_stealth_SyskuNative_nativeGetDentsDropped(
        JNIEnv *env, jclass clazz) {
    (void) env; (void) clazz;
    return g_dents_dropped;
}

JNIEXPORT jstring JNICALL
Java_com_shizuposed_manager_stealth_SyskuNative_nativeDescribe(
        JNIEnv *env, jclass clazz) {
    (void) clazz;
    char buf[640];
    snprintf(buf, sizeof(buf),
        "libsysku %s ready=%d active=%d fallback=%d "
        "syscall=%p getdents64=%p "
        "path_is_blocked=%p fd_resolves=%p is_active=%p "
        "calls_seen=%d calls_blocked=%d "
        "dents_seen=%d dents_dropped=%d",
        LIBSYSKU_VERSION,
        g_installed,
        xstealth_active(),
        g_fallback_mode,
        (void *) real_syscall,
        (void *) real_getdents64,
        (void *) p_path_is_blocked,
        (void *) p_fd_resolves_to_hidden,
        (void *) p_is_active,
        g_calls_seen,
        g_calls_blocked,
        g_dents_seen,
        g_dents_dropped);
    return (*env)->NewStringUTF(env, buf);
}

JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void) vm;
    (void) reserved;
    LOGI("JNI_OnLoad: libsysku %s "
         "(scope: syscall + getdents64; fallback mode disables getdents64)",
         LIBSYSKU_VERSION);
    return JNI_VERSION_1_6;
}