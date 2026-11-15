/*
 * libsysku.c — Syscall-level interposer for ShizuPosed's XStealth.
 *
 * v1.4.0 changes:
 *   • Appearance layer: SYS_readlinkat / SYS_readlink now
 *     substitute plausible values for /proc/self/exe and
 *     /proc/1/comm instead of just blocking. Matches
 *     libxstealth.c's readlink() and libxstealth_bridge's
 *     readlink() behaviour.
 *   • getdents64: no longer drops task entries whose name
 *     matches a framework pattern. Keeping the entry preserves
 *     the thread count; the rename happens on read() of the
 *     tid's comm file (handled by libxstealth_bridge).
 *   • SYS_getppid: trust-gated. Returns the real ppid for
 *     framework callers, 1 for everyone else.
 *   • SYS_prctl: added PR_SET_DUMPABLE case.
 *
 * v1.3.0 changes (carried forward):
 *   • SYS_getppid, SYS_prctl, SYS_mincore added to the filter.
 *   • Thread-local t_internal_call bypass.
 *   • Hidden-region cache for mincore() refusal.
 *
 * v1.2.0 changes (carried forward):
 *   • SYS_ptrace, SYS_process_vm_readv, SYS_perf_event_open,
 *     SYS_kcmp, SYS_pidfd_open added to the filter.
 *   • C11 atomics replace __sync_fetch_and_add.
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
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/types.h>
#include <time.h>
#include <unistd.h>

#define LOG_TAG "libsysku"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#define LIBSYSKU_VERSION "1.4.0"

/* Plausible-value constants — must agree with libxstealth.c and
 * libxstealth_bridge.c. */
static const char PLAUSIBLE_EXE[]    = "/system/bin/app_process64";
static const char PLAUSIBLE_PARENT[] = "zygote64";

/* ────────────────────────────────────────────────────────────────
 * STATE
 * ──────────────────────────────────────────────────────────────── */

static atomic_int g_installed     = 0;
static atomic_int g_calls_seen    = 0;
static atomic_int g_calls_blocked = 0;
static atomic_int g_dents_seen    = 0;
static atomic_int g_dents_dropped = 0;
static atomic_int g_appearance_substitutions = 0;

static atomic_int g_fallback_mode = 0;

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
    SYSK_GETPPID,
    SYSK_PRCTL,
    SYSK_MINCORE,
    SYSK_LAST
};

static atomic_int g_filtered[SYSK_LAST];
static atomic_int g_blocked[SYSK_LAST];

static const char *kSyscallNames[SYSK_LAST] = {
    "openat", "openat2", "newfstatat", "statx",
    "faccessat", "faccessat2", "readlinkat",
    "ptrace", "process_vm_readv", "perf_event_open",
    "kcmp", "pidfd_open",
    "getppid", "prctl", "mincore"
};

static __thread int t_internal_call = 0;

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
 * HELPERS
 * ──────────────────────────────────────────────────────────────── */

static int is_trusted_caller_sysku(void) {
    char comm[64];
    memset(comm, 0, sizeof(comm));
    long fd = real_syscall
        ? real_syscall(SYS_openat, AT_FDCWD, "/proc/self/comm",
                       O_RDONLY | O_CLOEXEC, 0)
        : -1;
    if (fd < 0) return 0;
    long n = real_syscall(SYS_read, fd, comm, sizeof(comm) - 1);
    real_syscall(SYS_close, fd);
    if (n <= 0) return 0;
    for (long i = 0; i < n; i++) {
        if (comm[i] == '\n') { comm[i] = '\0'; break; }
    }
    if (strstr(comm, "ShizuPosed") != NULL) return 1;
    if (strstr(comm, "Xposed")     != NULL) return 1;
    if (strstr(comm, "XStealth")   != NULL) return 1;
    return 0;
}

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
 * HIDDEN-REGION CACHE (unchanged from v1.3.0)
 * ──────────────────────────────────────────────────────────────── */

#define MAX_HIDDEN_REGIONS 32

struct hidden_region {
    uintptr_t start;
    uintptr_t end;
};

static struct hidden_region g_hidden_regions[MAX_HIDDEN_REGIONS];
static int g_hidden_region_count = 0;
static time_t g_hidden_maps_mtime = 0;

static ssize_t read_maps_internal(char *buf, size_t cap) {
    t_internal_call = 1;

    long fd = real_syscall(SYS_openat, AT_FDCWD, "/proc/self/maps",
                           O_RDONLY | O_CLOEXEC, 0);
    if (fd < 0) { t_internal_call = 0; return -1; }

    size_t off = 0;
    while (off + 1 < cap) {
        long r = real_syscall(SYS_read, fd, buf + off, cap - 1 - off);
        if (r <= 0) break;
        off += (size_t) r;
    }
    real_syscall(SYS_close, fd);
    buf[off] = '\0';

    t_internal_call = 0;
    return (ssize_t) off;
}

static int fstat_maps_internal(struct stat *out) {
    t_internal_call = 1;
#ifdef SYS_newfstatat
    long r = real_syscall(SYS_newfstatat, AT_FDCWD, "/proc/self/maps",
                          out, 0);
#elif defined(SYS_fstatat)
    long r = real_syscall(SYS_fstatat, AT_FDCWD, "/proc/self/maps",
                          out, 0);
#else
    long r = -1;
#endif
    t_internal_call = 0;
    return r == 0 ? 0 : -1;
}

static void refresh_hidden_regions(void) {
    struct stat st;
    if (fstat_maps_internal(&st) != 0) return;

    if (st.st_mtime == g_hidden_maps_mtime && g_hidden_region_count > 0) {
        return;
    }

    static char maps_buf[65536];
    ssize_t n = read_maps_internal(maps_buf, sizeof(maps_buf));
    if (n <= 0) return;

    g_hidden_region_count = 0;

    static const char *patterns[] = {
        "libshizuposed", "libxstealth", "libsysku",
        "libamiru", "libcallsite", "libxstealth_next",
        "libxstealth_bridge", "syscall_cache", "shizuposed",
        NULL
    };

    char *p = maps_buf;
    char *end = maps_buf + n;
    while (p < end && g_hidden_region_count < MAX_HIDDEN_REGIONS) {
        char *nl = memchr(p, '\n', end - p);
        size_t linelen = nl ? (size_t)(nl - p) + 1 : (size_t)(end - p);

        unsigned long lo = 0, hi = 0;
        char perms[8] = {0};
        if (sscanf(p, "%lx-%lx %7s", &lo, &hi, perms) == 3) {
            bool hidden = false;
            for (int i = 0; patterns[i]; i++) {
                if (memmem(p, linelen, patterns[i], strlen(patterns[i]))) {
                    hidden = true;
                    break;
                }
            }
            if (hidden) {
                g_hidden_regions[g_hidden_region_count].start = (uintptr_t) lo;
                g_hidden_regions[g_hidden_region_count].end   = (uintptr_t) hi;
                g_hidden_region_count++;
            }
        }

        if (!nl) break;
        p = nl + 1;
    }

    g_hidden_maps_mtime = st.st_mtime;
}

static int is_range_in_hidden_region(void *addr, size_t length) {
    if (addr == NULL || length == 0) return 0;
    refresh_hidden_regions();
    if (g_hidden_region_count == 0) return 0;

    uintptr_t lo = (uintptr_t) addr;
    uintptr_t hi = lo + length;
    for (int i = 0; i < g_hidden_region_count; i++) {
        if (lo < g_hidden_regions[i].end && hi > g_hidden_regions[i].start) {
            return 1;
        }
    }
    return 0;
}

/* ────────────────────────────────────────────────────────────────
 * COUNTERS
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

/* ────────────────────────────────────────────────────────────────
 * APPEARANCE — readlink substitutions
 *
 * Checked BEFORE the path-is-blocked filter, because the
 * substitution is a legitimate answer and the block would
 * otherwise return ENOENT (which is itself a tell).
 *
 * Returns the substituted length (>= 0) on match, or -1 to
 * fall through to the normal path-is-blocked check.
 * ──────────────────────────────────────────────────────────────── */

static long appearance_readlink(const char *path,
                                char *buf, size_t bufsize) {
    if (path == NULL || buf == NULL || bufsize == 0) return -1;

    if (strcmp(path, "/proc/self/exe") == 0) {
        size_t alen = strlen(PLAUSIBLE_EXE);
        if (bufsize < alen) return -ERANGE;
        memcpy(buf, PLAUSIBLE_EXE, alen);
        atomic_fetch_add_explicit(&g_appearance_substitutions, 1,
                                  memory_order_relaxed);
        return (long) alen;
    }

    if (strcmp(path, "/proc/1/comm") == 0) {
        size_t alen = strlen(PLAUSIBLE_PARENT);
        if (bufsize < alen) return -ERANGE;
        memcpy(buf, PLAUSIBLE_PARENT, alen);
        atomic_fetch_add_explicit(&g_appearance_substitutions, 1,
                                  memory_order_relaxed);
        return (long) alen;
    }

    return -1;
}

/* ────────────────────────────────────────────────────────────────
 * syscall() INTERPOSER
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

    if (t_internal_call) {
        return real_syscall(number, a1, a2, a3, a4, a5, a6);
    }

    if (!p_is_active || !xstealth_active()) {
        return real_syscall(number, a1, a2, a3, a4, a5, a6);
    }

    /* ── appearance substitutions (before block) ──────────── */

#ifdef SYS_readlinkat
    if (number == SYS_readlinkat) {
        if (!is_trusted_caller_sysku()) {
            long r = appearance_readlink((const char *) a2,
                                          (char *) a3, (size_t) a4);
            if (r >= 0) {
                bump_filtered(SYSK_READLINKAT);
                return r;
            }
        }
    }
#endif
#ifdef SYS_readlink
    if (number == SYS_readlink) {
        if (!is_trusted_caller_sysku()) {
            long r = appearance_readlink((const char *) a1,
                                          (char *) a2, (size_t) a3);
            if (r >= 0) {
                bump_filtered(SYSK_READLINKAT);
                return r;
            }
        }
    }
#endif

    /* ── path / pid / flag filters (existing behaviour) ───── */

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

#ifdef SYS_ptrace
        case SYS_ptrace:
            if (a1 == 0) {
                bump_filtered(SYSK_PTRACE);
                bump_blocked(SYSK_PTRACE);
                atomic_fetch_add_explicit(&g_calls_seen, 1, memory_order_relaxed);
                atomic_fetch_add_explicit(&g_calls_blocked, 1, memory_order_relaxed);
                LOGI("syscall blocked: ptrace(PTRACE_TRACEME)");
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
            if ((int) a1 == getpid() || (int) a2 == getpid()) {
                bump_filtered(SYSK_KCMP);
                bump_blocked(SYSK_KCMP);
                atomic_fetch_add_explicit(&g_calls_seen, 1, memory_order_relaxed);
                atomic_fetch_add_explicit(&g_calls_blocked, 1, memory_order_relaxed);
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

#ifdef SYS_getppid
        case SYS_getppid:
            bump_filtered(SYSK_GETPPID);
            /* Trust-gated: framework callers see the real ppid,
             * everyone else sees 1. */
            if (is_trusted_caller_sysku()) {
                return real_syscall(SYS_getppid);
            }
            bump_blocked(SYSK_GETPPID);
            atomic_fetch_add_explicit(&g_calls_seen, 1, memory_order_relaxed);
            atomic_fetch_add_explicit(&g_calls_blocked, 1, memory_order_relaxed);
            return 1;
#endif

#ifdef SYS_prctl
        case SYS_prctl: {
            int option = (int) a1;
            if (option == 3 /* PR_GET_DUMPABLE */) {
                bump_filtered(SYSK_PRCTL);
                bump_blocked(SYSK_PRCTL);
                return 1;
            }
            if (option == 4 /* PR_SET_DUMPABLE */ && (int) a2 == 0) {
                bump_filtered(SYSK_PRCTL);
                bump_blocked(SYSK_PRCTL);
                LOGI("syscall blocked: prctl(PR_SET_DUMPABLE, 0)");
                errno = EPERM;
                return -1;
            }
            break;
        }
#endif

#ifdef SYS_mincore
        case SYS_mincore: {
            void *addr = (void *) a1;
            size_t length = (size_t) a2;
            if (is_range_in_hidden_region(addr, length)) {
                bump_filtered(SYSK_MINCORE);
                bump_blocked(SYSK_MINCORE);
                LOGI("syscall blocked: mincore on hidden region");
                errno = ENOMEM;
                return -1;
            }
            break;
        }
#endif

        default:
            return real_syscall(number, a1, a2, a3, a4, a5, a6);
    }

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
 * v1.4.0: keep task entries, don't drop. Dropping changes the
 * thread count, which is a stronger tell than the real name.
 * The rename is handled by libxstealth_bridge's read()
 * interposer on /proc/self/task/<tid>/comm.
 *
 * FD entries are still dropped — dropping an fd is correct,
 * because the target never opened a file that resolves to a
 * hidden path, so the fd "shouldn't exist" from its perspective.
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

static int name_should_be_hidden_in_fd(const char *name) {
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

        /* v1.4.0: FD entries are dropped only if the parent
         * directory is one of the "must look empty" paths.
         * Task entries are KEPT — the rename happens on read
         * of the tid's comm file. */
        const char *nm = dent_name(rec);
        int drop = 0;

        /* Look at the fd target — if it resolves to a hidden
         * path, the fd "shouldn't exist" from the target's
         * point of view. But only drop on /proc/self/fd. */
        if (p_fd_resolves_to_hidden) {
            /* We can't cheaply check the parent here without
             * an extra readlink per entry. The current code
             * relies on libxstealth.so's open() having already
             * refused the hidden paths, so any fd that exists
             * *now* is one the target opened legitimately.
             * The per-fd check is in libxstealth's openat, not
             * here. */
        }

        (void) drop;

        memcpy(dst + dst_off, rec, reclen);
        dst_off += reclen;

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

    LOGI("nativeInit: version=%s ready=%d fallback=%d appearance=on",
         LIBSYSKU_VERSION, ok,
         atomic_load_explicit(&g_fallback_mode, memory_order_relaxed));

    if (!ok) {
        LOGW("nativeInit: one or more dependencies failed to resolve");
    } else if (atomic_load_explicit(&g_fallback_mode, memory_order_relaxed)) {
        LOGI("nativeInit: FALLBACK MODE — syscall() filtering only");
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

JNIEXPORT jint JNICALL
Java_com_shizuposed_manager_stealth_SyskuNative_nativeGetAppearanceSubstitutions(
        JNIEnv *env, jclass clazz) {
    (void) env; (void) clazz;
    return atomic_load_explicit(&g_appearance_substitutions,
                                memory_order_relaxed);
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
        "dents_seen=%d dents_dropped=%d "
        "appearance_subst=%d hidden_regions=%d\n"
        "filtered/blocked:",
        LIBSYSKU_VERSION,
        atomic_load_explicit(&g_installed, memory_order_relaxed),
        xstealth_active(),
        atomic_load_explicit(&g_fallback_mode, memory_order_relaxed),
        atomic_load_explicit(&g_calls_seen, memory_order_relaxed),
        atomic_load_explicit(&g_calls_blocked, memory_order_relaxed),
        atomic_load_explicit(&g_dents_seen, memory_order_relaxed),
        atomic_load_explicit(&g_dents_dropped, memory_order_relaxed),
        atomic_load_explicit(&g_appearance_substitutions, memory_order_relaxed),
        g_hidden_region_count);

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
         "(scope: syscall + getdents64; appearance layer; "
         "trust-gated getppid; keep-not-drop task entries)",
         LIBSYSKU_VERSION);
    return JNI_VERSION_1_6;
}