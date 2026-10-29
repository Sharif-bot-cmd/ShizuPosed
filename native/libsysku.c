/*
 * libsysku.c — Syscall-level interposer for ShizuPosed's XStealth.
 *
 * v1.2.0 changes:
 *   • C11 atomics (<stdatomic.h>) replace __sync_fetch_and_add.
 *   • New filtered syscalls:
 *       SYS_ptrace            — closes the "call ptrace() via raw
 *                               syscall to bypass libxstealth.so's
 *                               symbol interposer" gap.
 *       SYS_process_vm_readv  — refuse reads of our own pid from
 *                               another process.
 *       SYS_perf_event_open   — refuse counter attaches to our pid.
 *       SYS_kcmp              — refuse comparisons against our pid.
 *       SYS_pidfd_open        — refuse pidfds for our pid.
 *   • Per-syscall counters visible via nativeDescribe().
 *   • Comments tightened; no functional regression vs 1.1.0.
 *
 * WHAT THIS IS
 * ------------
 * A companion to libxstealth.so that closes two classes of detection
 * path the named libc interposers can't reach: direct syscall()
 * calls, and directory enumeration via getdents64().
 *
 * MODES
 * -----
 *   FULL      — Sysku ADB module service.sh is running. Both
 *               syscall() and getdents64() filter.
 *   FALLBACK  — module not running. syscall() only. getdents64
 *               passes through unchanged.
 *
 * FALLBACK RATIONALE
 * ------------------
 * Hiding a directory entry is a more visible tell than returning
 * ENOENT for a path that shouldn't exist. Without the ADB module
 * coordinating the hidden-path list and version, libsysku filters
 * the safe half and leaves the risky half alone.
 *
 * NO ROOT NEEDED
 * --------------
 * All userspace PLT interposition. No seccomp, no ptrace, no bpf,
 * no kernel module.
 *
 * BUILD (Termux, single command):
 *
 *   clang -shared -fPIC -O2 -Wall -Wextra -s \
 *       -target aarch64-linux-android29 \
 *       -o libsysku.so libsysku.c \
 *       -llog -ldl
 *
 * ARM64 only. Android 10+. No root.
 */

#define _GNU_SOURCE

#include <android/log.h>
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <jni.h>
#include <stdatomic.h>
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

#define LIBSYSKU_VERSION "1.2.0"

/* ────────────────────────────────────────────────────────────────
 * STATE
 *
 * C11 atomics. The ints are relaxed ordering because the counters
 * are purely diagnostic — no happens-before relationship between
 * them and any other state.
 * ──────────────────────────────────────────────────────────────── */

static atomic_int g_installed        = 0;
static atomic_int g_calls_seen       = 0;
static atomic_int g_calls_blocked    = 0;
static atomic_int g_dents_seen       = 0;
static atomic_int g_dents_dropped    = 0;

/* Set by nativeInit based on moduleRunning. See MODES above. */
static atomic_int g_fallback_mode    = 0;

/* Per-syscall counters. Indexed by a small enum below. Reset
 * implicitly (static storage zeros). Used only by nativeDescribe().
 * The mapping is stable — a new entry requires bumping the enum
 * size. */
enum {
    SYSK_OPENAT = 0,
    SYSK_OPENAT2,
    SYSK_NEWFSTATAT,
    SYSK_STATX,
    SYSK_FACCESSAT,
    SYSK_FACCESSAT2,
    SYSK_READLINKAT,
    SYSK_PTRACE,
    SYSK_PROCESS_VM_READV,
    SYSK_PERF_EVENT_OPEN,
    SYSK_KCMP,
    SYSK_PIDFD_OPEN,
    SYSK_LAST
};

static atomic_int g_filtered[SYSK_LAST];
static atomic_int g_blocked[SYSK_LAST];

static const char *kSyscallNames[SYSK_LAST] = {
    "openat", "openat2", "newfstatat", "statx",
    "faccessat", "faccessat2", "readlinkat",
    "ptrace", "process_vm_readv", "perf_event_open",
    "kcmp", "pidfd_open"
};

/* ────────────────────────────────────────────────────────────────
 * RESOLVED SYMBOLS
 * ──────────────────────────────────────────────────────────────── */

static long     (*real_syscall)     (long, ...) = NULL;
static ssize_t  (*real_getdents64)  (int, void *, size_t) = NULL;

typedef int (*xstealth_path_is_blocked_fn)      (const char *);
typedef int (*xstealth_fd_resolves_to_hidden_fn)(int);
typedef int (*xstealth_is_active_fn)            (void);

static xstealth_path_is_blocked_fn        p_path_is_blocked        = NULL;
static xstealth_fd_resolves_to_hidden_fn  p_fd_resolves_to_hidden  = NULL;
static xstealth_is_active_fn              p_is_active              = NULL;

/* ────────────────────────────────────────────────────────────────
 * RESOLUTION
 * ──────────────────────────────────────────────────────────────── */

static void resolve_once(void) {
    if (atomic_load_explicit(&g_installed, memory_order_relaxed)) return;

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

    atomic_store_explicit(&g_installed, 1, memory_order_relaxed);

    LOGI("resolved: syscall=%p getdents64=%p "
         "xstealth_path_is_blocked=%p "
         "xstealth_fd_resolves_to_hidden=%p "
         "xstealth_is_active=%p",
         (void *) real_syscall, (void *) real_getdents64,
         (void *) p_path_is_blocked,
         (void *) p_fd_resolves_to_hidden,
         (void *) p_is_active);

    if (!p_path_is_blocked) {
        LOGW("libxstealth.so does not export xstealth_path_is_blocked — "
             "syscall() filtering will pass everything through. "
             "Update libxstealth.so to v0.18.0+.");
    }
    if (!p_is_active) {
        LOGW("libxstealth.so does not export xstealth_is_active — "
             "Sysku will stay inactive. Update libxstealth.so to v0.18.0+.");
    }
}

static int xstealth_active(void) {
    if (!atomic_load_explicit(&g_installed, memory_order_relaxed)) {
        resolve_once();
    }
    return p_is_active ? p_is_active() : 0;
}

/* ────────────────────────────────────────────────────────────────
 * syscall() INTERPOSER
 *
 * Two filter classes:
 *   • path-takers  — resolve path, call xstealth_path_is_blocked()
 *   • pid-takers   — compare pid arg against our own pid
 *
 * The pid-takers are new in 1.2.0. They close the "call the raw
 * syscall to bypass the symbol interposer" paths that
 * libxstealth.so's ptrace interposer doesn't reach.
 * ──────────────────────────────────────────────────────────────── */

static void bump_filtered(int idx) {
    if (idx >= 0 && idx < SYSK_LAST) {
        atomic_fetch_add_explicit(&g_filtered[idx], 1, memory_order_relaxed);
    }
}

static void bump_blocked(int idx) {
    if (idx >= 0 && idx < SYSK_LAST) {
        atomic_fetch_add_explicit(&g_blocked[idx], 1, memory_order_relaxed);
    }
}

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

    /* Fast path when Sysku is off. */
    if (!p_is_active || !xstealth_active()) {
        return real_syscall(number, a1, a2, a3, a4, a5, a6);
    }

    const char *path = NULL;
    int filter_idx = -1;
    int pid_arg    = -1;
    int is_self_pid_check = 0;

    switch (number) {
        case SYS_openat:
            path = (const char *) a2;
            filter_idx = SYSK_OPENAT;
            break;
#ifdef SYS_openat2
        case SYS_openat2:
            path = (const char *) a2;
            filter_idx = SYSK_OPENAT2;
            break;
#endif
#ifdef SYS_open
        case SYS_open:
            path = (const char *) a1;
            filter_idx = SYSK_OPENAT;
            break;
#endif
#ifdef SYS_newfstatat
        case SYS_newfstatat:
            path = (const char *) a2;
            filter_idx = SYSK_NEWFSTATAT;
            break;
#elif defined(SYS_fstatat)
        case SYS_fstatat:
            path = (const char *) a2;
            filter_idx = SYSK_NEWFSTATAT;
            break;
#endif
#ifdef SYS_statx
        case SYS_statx:
            path = (const char *) a2;
            filter_idx = SYSK_STATX;
            break;
#endif
#ifdef SYS_stat
        case SYS_stat:
            path = (const char *) a1;
            filter_idx = SYSK_NEWFSTATAT;
            break;
#endif
#ifdef SYS_lstat
        case SYS_lstat:
            path = (const char *) a1;
            filter_idx = SYSK_NEWFSTATAT;
            break;
#endif
#ifdef SYS_faccessat
        case SYS_faccessat:
            path = (const char *) a2;
            filter_idx = SYSK_FACCESSAT;
            break;
#endif
#ifdef SYS_faccessat2
        case SYS_faccessat2:
            path = (const char *) a2;
            filter_idx = SYSK_FACCESSAT2;
            break;
#endif
#ifdef SYS_access
        case SYS_access:
            path = (const char *) a1;
            filter_idx = SYSK_FACCESSAT;
            break;
#endif
#ifdef SYS_readlinkat
        case SYS_readlinkat:
            path = (const char *) a2;
            filter_idx = SYSK_READLINKAT;
            break;
#endif
#ifdef SYS_readlink
        case SYS_readlink:
            path = (const char *) a1;
            filter_idx = SYSK_READLINKAT;
            break;
#endif

        /* ── pid-takers ────────────────────────────────── */

#ifdef SYS_ptrace
        case SYS_ptrace:
            /* PTRACE_TRACEME is the only ptrace request a
             * detection probe uses to bypass libxstealth.so's
             * symbol interposer. Refuse it outright from
             * untrusted callers (that's everyone in this
             * process that isn't the framework). */
            if (a1 == 0 /* PTRACE_TRACEME */) {
                bump_filtered(SYSK_PTRACE);
                bump_blocked(SYSK_PTRACE);
                LOGI("syscall blocked: ptrace(PTRACE_TRACEME) via raw syscall");
                errno = EPERM;
                return -1;
            }
            break;
#endif

#ifdef SYS_process_vm_readv
        case SYS_process_vm_readv:
            pid_arg = (int) a1;
            is_self_pid_check = 1;
            filter_idx = SYSK_PROCESS_VM_READV;
            break;
#endif

#ifdef SYS_perf_event_open
        case SYS_perf_event_open:
            /* perf_event_open's struct has a `pid` field at
             * offset 0 (int). Read it with memcpy so we don't
             * trip on alignment. */
            if (a1 != 0) {
                int pe_pid = 0;
                memcpy(&pe_pid, (const void *) a1, sizeof(int));
                pid_arg = pe_pid;
                is_self_pid_check = 1;
                filter_idx = SYSK_PERF_EVENT_OPEN;
            }
            break;
#endif

#ifdef SYS_kcmp
        case SYS_kcmp:
            /* kcmp(pid1, pid2, ...). Refuse if either is us. */
            if ((int) a1 == getpid() || (int) a2 == getpid()) {
                bump_filtered(SYSK_KCMP);
                bump_blocked(SYSK_KCMP);
                LOGI("syscall blocked: kcmp involving our pid");
                errno = EPERM;
                return -1;
            }
            break;
#endif

#ifdef SYS_pidfd_open
        case SYS_pidfd_open:
            pid_arg = (int) a1;
            is_self_pid_check = 1;
            filter_idx = SYSK_PIDFD_OPEN;
            break;
#endif

        default:
            return real_syscall(number, a1, a2, a3, a4, a5, a6);
    }

    /* ── path check ─────────────────────────────────────── */
    if (path != NULL) {
        bump_filtered(filter_idx);
        if (p_path_is_blocked && p_path_is_blocked(path)) {
            bump_blocked(filter_idx);
            atomic_fetch_add_explicit(&g_calls_seen, 1, memory_order_relaxed);
            atomic_fetch_add_explicit(&g_calls_blocked, 1, memory_order_relaxed);
            LOGI("syscall blocked: nr=%ld path=%s", number, path);
            errno = ENOENT;
            return -1;
        }
        return real_syscall(number, a1, a2, a3, a4, a5, a6);
    }

    /* ── pid check ──────────────────────────────────────── */
    if (is_self_pid_check) {
        bump_filtered(filter_idx);
        if (pid_arg == getpid()) {
            bump_blocked(filter_idx);
            atomic_fetch_add_explicit(&g_calls_seen, 1, memory_order_relaxed);
            atomic_fetch_add_explicit(&g_calls_blocked, 1, memory_order_relaxed);
            LOGI("syscall blocked: nr=%ld targets our pid=%d",
                 number, pid_arg);
            errno = EPERM;
            return -1;
        }
    }

    return real_syscall(number, a1, a2, a3, a4, a5, a6);
}

/* ────────────────────────────────────────────────────────────────
 * getdents64 INTERPOSER
 *
 * Fallback mode: pass through unchanged.
 *
 * struct linux_dirent64 layout (ABI-frozen at 2.6.24):
 *   offset 0  : uint64 d_ino
 *   offset 8  : int64  d_off
 *   offset 16 : uint16 d_reclen
 *   offset 18 : uint8  d_type
 *   offset 19 : char   d_name[] (NUL-terminated)
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

    if (atomic_load_explicit(&g_fallback_mode, memory_order_relaxed)) {
        return real_getdents64(fd, dirp, count);
    }

    if (!p_is_active || !xstealth_active()) {
        return real_getdents64(fd, dirp, count);
    }

    if (p_fd_resolves_to_hidden && p_fd_resolves_to_hidden(fd)) {
        atomic_fetch_add_explicit(&g_dents_dropped, 1, memory_order_relaxed);
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

    atomic_fetch_add_explicit(&g_dents_seen, 1, memory_order_relaxed);

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
            atomic_fetch_add_explicit(&g_dents_dropped, 1, memory_order_relaxed);
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
 * JNI
 * ──────────────────────────────────────────────────────────────── */

JNIEXPORT jint JNICALL
Java_com_shizuposed_manager_stealth_SyskuNative_nativeInit(
        JNIEnv *env, jclass clazz, jstring shell_base,
        jboolean moduleRunning) {
    (void) env; (void) clazz; (void) shell_base;

    atomic_store_explicit(&g_fallback_mode,
        moduleRunning ? 0 : 1, memory_order_relaxed);

    resolve_once();

    int ok = (real_syscall != NULL)
          && (real_getdents64 != NULL)
          && (p_path_is_blocked != NULL)
          && (p_is_active != NULL);

    LOGI("nativeInit: version=%s ready=%d fallback=%d "
         "syscall=%p getdents64=%p "
         "path_is_blocked=%p fd_resolves=%p is_active=%p",
         LIBSYSKU_VERSION, ok,
         atomic_load_explicit(&g_fallback_mode, memory_order_relaxed),
         (void *) real_syscall, (void *) real_getdents64,
         (void *) p_path_is_blocked,
         (void *) p_fd_resolves_to_hidden,
         (void *) p_is_active);

    if (!ok) {
        LOGW("nativeInit: one or more dependencies failed to resolve — "
             "interposers may pass through unchanged");
    } else if (atomic_load_explicit(&g_fallback_mode, memory_order_relaxed)) {
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
    return atomic_load_explicit(&g_fallback_mode, memory_order_relaxed)
        ? JNI_TRUE : JNI_FALSE;
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
    return atomic_load_explicit(&g_calls_seen, memory_order_relaxed);
}

JNIEXPORT jint JNICALL
Java_com_shizuposed_manager_stealth_SyskuNative_nativeGetCallsBlocked(
        JNIEnv *env, jclass clazz) {
    (void) env; (void) clazz;
    return atomic_load_explicit(&g_calls_blocked, memory_order_relaxed);
}

JNIEXPORT jint JNICALL
Java_com_shizuposed_manager_stealth_SyskuNative_nativeGetDentsSeen(
        JNIEnv *env, jclass clazz) {
    (void) env; (void) clazz;
    return atomic_load_explicit(&g_dents_seen, memory_order_relaxed);
}

JNIEXPORT jint JNICALL
Java_com_shizuposed_manager_stealth_SyskuNative_nativeGetDentsDropped(
        JNIEnv *env, jclass clazz) {
    (void) env; (void) clazz;
    return atomic_load_explicit(&g_dents_dropped, memory_order_relaxed);
}

JNIEXPORT jstring JNICALL
Java_com_shizuposed_manager_stealth_SyskuNative_nativeDescribe(
        JNIEnv *env, jclass clazz) {
    (void) clazz;
    char buf[1536];
    int off = 0;

    off += snprintf(buf + off, sizeof(buf) - off,
        "libsysku %s ready=%d active=%d fallback=%d "
        "calls_seen=%d calls_blocked=%d "
        "dents_seen=%d dents_dropped=%d\n"
        "filtered/blocked:",
        LIBSYSKU_VERSION,
        atomic_load_explicit(&g_installed, memory_order_relaxed),
        xstealth_active(),
        atomic_load_explicit(&g_fallback_mode, memory_order_relaxed),
        atomic_load_explicit(&g_calls_seen, memory_order_relaxed),
        atomic_load_explicit(&g_calls_blocked, memory_order_relaxed),
        atomic_load_explicit(&g_dents_seen, memory_order_relaxed),
        atomic_load_explicit(&g_dents_dropped, memory_order_relaxed));

    for (int i = 0; i < SYSK_LAST && off < (int)sizeof(buf) - 32; i++) {
        int f = atomic_load_explicit(&g_filtered[i], memory_order_relaxed);
        int b = atomic_load_explicit(&g_blocked[i], memory_order_relaxed);
        if (f == 0 && b == 0) continue;
        off += snprintf(buf + off, sizeof(buf) - off,
            " %s=%d/%d", kSyscallNames[i], f, b);
    }

    return (*env)->NewStringUTF(env, buf);
}

JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void) vm; (void) reserved;
    LOGI("JNI_OnLoad: libsysku %s "
         "(scope: syscall + getdents64; fallback disables getdents64; "
         "v1.2.0 adds ptrace/process_vm_readv/perf_event_open/kcmp/pidfd_open)",
         LIBSYSKU_VERSION);
    return JNI_VERSION_1_6;
}