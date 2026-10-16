#define _GNU_SOURCE

#include <jni.h>
#include <android/log.h>
#include <dirent.h>
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <link.h>
#include <pthread.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>
#include <sys/syscall.h>
#include <sys/types.h>

#define LOG_TAG "XStealthBridge"
#define ALOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

/* ─────────────────────────────────────────────────────────────
 * Globals
 * ───────────────────────────────────────────────────────────── */

static int (*real_dl_iterate_phdr)(int (*)(struct dl_phdr_info *, size_t, void *), void *) = NULL;

static volatile int g_bridge_active = 0;

static DIR  *(*real_opendir)(const char *)                       = NULL;
static struct dirent *(*real_readdir)(DIR *)                     = NULL;
static ssize_t (*real_readlink)(const char *, char *, size_t)    = NULL;
static char *(*real_getenv)(const char *)                        = NULL;
static int  (*real_closedir)(DIR *)                              = NULL;
static ssize_t (*real_read)(int, void *, size_t)                 = NULL;

static pthread_mutex_t g_bridge_lock = PTHREAD_MUTEX_INITIALIZER;

#define MAX_TRACKED 32
#define TRACKED_PATH_MAX 64

enum {
    TRACKED_KIND_NONE = 0,
    TRACKED_KIND_FD   = 1,
    TRACKED_KIND_TASK = 2,
    TRACKED_KIND_PROC = 3,
};

typedef struct {
    DIR    *dir;
    int32_t kind;
    char    path[TRACKED_PATH_MAX];
} tracked_slot_t;

static tracked_slot_t g_tracked[MAX_TRACKED];
static int32_t g_tracked_count = 0;

static volatile int g_proc_pid_reads_blocked = 0;
static volatile int g_proc_entries_skipped = 0;

/* Forward declaration: used in read() below, defined after. */
static int fd_is_proc_pid_file(int fd, char *pidbuf, size_t pidbufsize);

static ssize_t raw_read(int fd, void *buf, size_t count) {
    if (real_read == NULL) {
        real_read = (ssize_t (*)(int, void *, size_t))
            dlsym(RTLD_NEXT, "read");
    }
    if (real_read != NULL) {
        return real_read(fd, buf, count);
    }
    return (ssize_t) syscall(SYS_read, fd, buf, count);
}

static int raw_open(const char *path, int flags) {
    if (real_opendir == NULL) {
        real_opendir = (DIR *(*)(const char *)) dlsym(RTLD_NEXT, "opendir");
    }
    /* Use the raw syscall — the libc open() may be interposed by
     * libxstealth.so, which is fine, but we want a deterministic
     * path here. */
    return (int) syscall(SYS_openat, AT_FDCWD, path, flags, 0);
}

static int raw_close(int fd) {
    return (int) syscall(SYS_close, fd);
}

static int is_hidden_string(const char *s) {
    if (s == NULL) return 0;

    if (strstr(s, "syscall_cache")       != NULL) return 1;
    if (strstr(s, "shizuposed")          != NULL) return 1;
    if (strstr(s, "libshizuposed")       != NULL) return 1;
    if (strstr(s, "libamiru")            != NULL) return 1;
    if (strstr(s, "libxstealth")         != NULL) return 1;
    if (strstr(s, "ShizuPosedXStealth")  != NULL) return 1;
    if (strstr(s, "XStealthNative")      != NULL) return 1;
    if (strstr(s, "XStealthNativeNext")  != NULL) return 1;
    return strstr(s, "XStealthBridge") != NULL;
}

/* ─────────────────────────────────────────────────────────────
 * dl_iterate_wrapper
 * ───────────────────────────────────────────────────────────── */

struct dl_wrapper_state {
    int (*user_cb)(struct dl_phdr_info *, size_t, void *);
    void *user_data;
};

static int dl_iterate_wrapper(struct dl_phdr_info *info,
                              size_t size,
                              void *data) {
    struct dl_wrapper_state *st = (struct dl_wrapper_state *) data;

    if (st == NULL) return 0;
    int (*cb)(struct dl_phdr_info *, size_t, void *) = st->user_cb;
    if (cb == NULL) return 0;

    const char *name = info->dlpi_name;
    if (name == NULL) {
        return cb(info, size, st->user_data);
    }

    if (is_hidden_string(name)) {
        ALOGI("dl_iterate_phdr: filtered %s", name);
        return 0;
    }

    return cb(info, size, st->user_data);
}

int dl_iterate_phdr(int (*cb)(struct dl_phdr_info *, size_t, void *),
                    void *data) {
    if (real_dl_iterate_phdr == NULL) {
        real_dl_iterate_phdr = (int (*)(int (*)(struct dl_phdr_info *,
                                                 size_t, void *),
                                         void *))
            dlsym(RTLD_NEXT, "dl_iterate_phdr");
        if (real_dl_iterate_phdr == NULL) {
            return 0;
        }
    }

    if (!g_bridge_active || cb == NULL) {
        return real_dl_iterate_phdr(cb, data);
    }

    struct dl_wrapper_state st = {
        .user_cb = cb,
        .user_data = data,
    };
    return real_dl_iterate_phdr(dl_iterate_wrapper, &st);
}

/* ─────────────────────────────────────────────────────────────
 * opendir / readdir / closedir
 * ───────────────────────────────────────────────────────────── */

static int classify_dir(const char *path) {
    if (path == NULL) return TRACKED_KIND_NONE;

    if (strcmp(path, "/proc/self/fd")   == 0) return TRACKED_KIND_FD;
    if (strcmp(path, "/proc/self/task") == 0) return TRACKED_KIND_TASK;

    if (strcmp(path, "/proc") == 0) return TRACKED_KIND_PROC;
    if (strcmp(path, "/proc/") == 0) return TRACKED_KIND_PROC;

    if (strncmp(path, "/proc/", 6) != 0) return TRACKED_KIND_NONE;
    if (strncmp(path + 6, "self/", 5) != 0) return TRACKED_KIND_NONE;

    if (path[11] == 'f' && path[12] == 'd' && path[13] == '\0') {
        return TRACKED_KIND_FD;
    }
    if (strcmp(path + 11, "task") == 0) {
        return TRACKED_KIND_TASK;
    }
    return TRACKED_KIND_NONE;
}

DIR *opendir(const char *name) {
    if (real_opendir == NULL) {
        real_opendir = (DIR *(*)(const char *)) dlsym(RTLD_NEXT, "opendir");
        if (real_opendir == NULL) {
            errno = ENOSYS;
            return NULL;
        }
    }

    DIR *d = real_opendir(name);
    if (d == NULL) return NULL;
    if (name == NULL) return d;
    if (!g_bridge_active) return d;

    int kind = classify_dir(name);
    if (kind == TRACKED_KIND_NONE) return d;

    pthread_mutex_lock(&g_bridge_lock);
    if (g_tracked_count <= MAX_TRACKED - 1) {
        int i = g_tracked_count;
        g_tracked[i].dir  = d;
        g_tracked[i].kind = kind;
        strncpy(g_tracked[i].path, name, TRACKED_PATH_MAX - 1);
        g_tracked[i].path[TRACKED_PATH_MAX - 1] = '\0';
        g_tracked_count = i + 1;
    }
    pthread_mutex_unlock(&g_bridge_lock);
    return d;
}

static int read_proc_comm(const char *pid, char *buf, size_t bufsize) {
    if (pid == NULL || buf == NULL || bufsize < 2) return -1;

    char path[64];
    snprintf(path, sizeof(path), "/proc/%s/comm", pid);

    int fd = raw_open(path, O_RDONLY);
    if (fd < 0) return -1;

    memset(buf, 0, bufsize);
    ssize_t n = raw_read(fd, buf, bufsize - 1);
    raw_close(fd);

    if (n <= 0) return -1;

    for (ssize_t i = 0; i < n; i++) {
        if (buf[i] == '\n') { buf[i] = '\0'; break; }
    }
    return 0;
}

struct dirent *readdir(DIR *dirp) {
    if (real_readdir == NULL) {
        real_readdir = (struct dirent *(*)(DIR *)) dlsym(RTLD_NEXT, "readdir");
        if (real_readdir == NULL) {
            errno = ENOSYS;
            return NULL;
        }
    }

    int kind = TRACKED_KIND_NONE;
    if (dirp != NULL && g_bridge_active) {
        pthread_mutex_lock(&g_bridge_lock);
        for (int i = 0; i < g_tracked_count; i++) {
            if (g_tracked[i].dir == dirp) {
                kind = g_tracked[i].kind;
                break;
            }
        }
        pthread_mutex_unlock(&g_bridge_lock);
    }

    struct dirent *e = real_readdir(dirp);
    if (e == NULL) return NULL;

    if (kind == TRACKED_KIND_TASK) {
        char commpath[64];
        snprintf(commpath, sizeof(commpath),
                 "/proc/self/task/%s/comm", e->d_name);
        int fd = raw_open(commpath, O_RDONLY);
        if (fd >= 0) {
            char comm[64];
            memset(comm, 0, sizeof(comm));
            ssize_t n = raw_read(fd, comm, sizeof(comm) - 1);
            raw_close(fd);
            if (n >= 1 && is_hidden_string(comm)) {
                ALOGI("readdir task: filtered %s (%s)",
                      e->d_name, comm);
                return readdir(dirp);
            }
        }
    } else if (kind == TRACKED_KIND_FD) {
        char linkpath[64];
        snprintf(linkpath, sizeof(linkpath),
                 "/proc/self/fd/%s", e->d_name);
        if (real_readlink != NULL) {
            char target[512];
            ssize_t n = real_readlink(linkpath, target, 0x1ff);
            if (n >= 1) {
                target[n] = '\0';
                if (is_hidden_string(target)) {
                    ALOGI("readdir fd: filtered %s -> %s",
                          e->d_name, target);
                    return readdir(dirp);
                }
            }
        }
    } else if (kind == TRACKED_KIND_PROC) {
        if (e->d_name[0] == '.' &&
            (e->d_name[1] == '\0' ||
             (e->d_name[1] == '.' && e->d_name[2] == '\0'))) {
            return e;
        }

        int all_digits = 1;
        for (const char *p = e->d_name; *p; p++) {
            if (*p < '0' || *p > '9') { all_digits = 0; break; }
        }
        if (!all_digits) return e;

        char comm[64];
        if (read_proc_comm(e->d_name, comm, sizeof(comm)) == 0) {
            if (is_hidden_string(comm)) {
                __sync_fetch_and_add(&g_proc_entries_skipped, 1);
                ALOGI("readdir /proc: filtered pid %s (%s)",
                      e->d_name, comm);
                return readdir(dirp);
            }
        }
    }

    return e;
}

int closedir(DIR *dirp) {
    if (real_closedir == NULL) {
        real_closedir = (int (*)(DIR *)) dlsym(RTLD_NEXT, "closedir");
        if (real_closedir == NULL) {
            errno = ENOSYS;
            return -1;
        }
    }

    if (dirp != NULL && g_bridge_active) {
        pthread_mutex_lock(&g_bridge_lock);
        for (int i = 0; i < g_tracked_count; i++) {
            if (g_tracked[i].dir == dirp) {
                int last = g_tracked_count - 1;
                if (i != last) {
                    g_tracked[i] = g_tracked[last];
                }
                g_tracked_count--;
                break;
            }
        }
        pthread_mutex_unlock(&g_bridge_lock);
    }

    return real_closedir(dirp);
}

/* ─────────────────────────────────────────────────────────────
 * readlink
 * ───────────────────────────────────────────────────────────── */

ssize_t readlink(const char *path, char *buf, size_t bufsize) {
    if (real_readlink == NULL) {
        real_readlink = (ssize_t (*)(const char *, char *, size_t))
            dlsym(RTLD_NEXT, "readlink");
        if (real_readlink == NULL) {
            errno = ENOSYS;
            return -1;
        }
    }

    ssize_t n = real_readlink(path, buf, bufsize);
    if (n < 1) return n;
    if (!g_bridge_active) return n;
    if (n > 0x1ff) return n;

    char local[512];
    memcpy(local, buf, (size_t) n);
    local[n] = '\0';

    if (is_hidden_string(local)) {
        ALOGI("readlink: blocked %s -> %s", path, local);
        errno = ENOENT;
        return -1;
    }

    return n;
}

/* ─────────────────────────────────────────────────────────────
 * read
 *
 * Filters reads of /proc/<pid>/{status,cmdline,comm,maps,smaps}
 * when the PID's process name matches a hidden string. Returns
 * ENOENT for those, matching what a caller would see if the
 * process had exited between the open and the read.
 *
 * v0.3.0: maps and smaps added. Without them, a detector that
 * enumerates /proc/[pid]/maps can still find ShizuPosed's memory
 * layout even though the process name itself is hidden.
 *
 * Reads of /proc/self and /proc/thread-self are deliberately
 * not filtered here; that's libxstealth.c's job, and it needs to
 * see the real content for its own scrubbing.
 * ───────────────────────────────────────────────────────────── */

static int fd_is_proc_pid_file(int fd, char *pidbuf, size_t pidbufsize) {
    if (fd < 0 || pidbuf == NULL || pidbufsize < 2) return 0;
    if (real_readlink == NULL) return 0;

    char fd_path[64];
    snprintf(fd_path, sizeof(fd_path), "/proc/self/fd/%d", fd);
    char target[512] = {0};
    ssize_t n = real_readlink(fd_path, target, sizeof(target) - 1);
    if (n <= 0) return 0;
    target[n] = '\0';

    if (strncmp(target, "/proc/", 6) != 0) return 0;

    const char *p = target + 6;
    if (strncmp(p, "self/", 5) == 0) return 0;
    if (strncmp(p, "thread-self/", 12) == 0) return 0;

    size_t i = 0;
    while (p[i] >= '0' && p[i] <= '9' && i < pidbufsize - 1) {
        pidbuf[i] = p[i];
        i++;
    }
    pidbuf[i] = '\0';
    if (i == 0) return 0;
    if (p[i] != '/') return 0;
    p += i + 1;

    if (strcmp(p, "status")  == 0) return 1;
    if (strcmp(p, "cmdline") == 0) return 1;
    if (strcmp(p, "comm")    == 0) return 1;
    if (strcmp(p, "maps")    == 0) return 1;
    if (strcmp(p, "smaps")   == 0) return 1;
    return 0;
}

ssize_t read(int fd, void *buf, size_t count) {
    if (real_read == NULL) {
        real_read = (ssize_t (*)(int, void *, size_t))
            dlsym(RTLD_NEXT, "read");
        if (real_read == NULL) {
            errno = ENOSYS;
            return -1;
        }
    }

    if (!g_bridge_active || fd < 0 || buf == NULL || count == 0) {
        return real_read(fd, buf, count);
    }

    char pidbuf[32];
    if (!fd_is_proc_pid_file(fd, pidbuf, sizeof(pidbuf))) {
        return real_read(fd, buf, count);
    }

    char comm[64];
    if (read_proc_comm(pidbuf, comm, sizeof(comm)) == 0) {
        if (is_hidden_string(comm)) {
            __sync_fetch_and_add(&g_proc_pid_reads_blocked, 1);
            ALOGI("read /proc/%s: blocked (%s)", pidbuf, comm);
            errno = ENOENT;
            return -1;
        }
    }

    return real_read(fd, buf, count);
}

/* ─────────────────────────────────────────────────────────────
 * getenv
 * ───────────────────────────────────────────────────────────── */

char *getenv(const char *name) {
    if (real_getenv == NULL) {
        real_getenv = (char *(*)(const char *)) dlsym(RTLD_NEXT, "getenv");
        if (real_getenv == NULL) return NULL;
    }

    if (name != NULL && g_bridge_active) {
        if (strncmp(name, "shizuposed.", 11) == 0) {
            return NULL;
        }
    }

    return real_getenv(name);
}

/* ─────────────────────────────────────────────────────────────
 * JNI surface
 * ───────────────────────────────────────────────────────────── */

JNIEXPORT jboolean JNICALL
Java_com_shizuposed_manager_stealth_XStealthBridge_nativeInit(JNIEnv *env,
                                                              jclass clazz) {
    (void) env; (void) clazz;

    real_dl_iterate_phdr = (int (*)(int (*)(struct dl_phdr_info *,
                                             size_t, void *),
                                     void *))
                           dlsym(RTLD_NEXT, "dl_iterate_phdr");
    real_opendir      = (DIR *(*)(const char *))
                        dlsym(RTLD_NEXT, "opendir");
    real_readdir      = (struct dirent *(*)(DIR *))
                        dlsym(RTLD_NEXT, "readdir");
    real_closedir     = (int (*)(DIR *))
                        dlsym(RTLD_NEXT, "closedir");
    real_getenv       = (char *(*)(const char *))
                        dlsym(RTLD_NEXT, "getenv");
    real_readlink     = (ssize_t (*)(const char *, char *, size_t))
                        dlsym(RTLD_NEXT, "readlink");
    real_read         = (ssize_t (*)(int, void *, size_t))
                        dlsym(RTLD_NEXT, "read");

    int resolved = 0;
    if (real_dl_iterate_phdr) resolved++;
    if (real_opendir)         resolved++;
    if (real_readlink)        resolved++;
    if (real_readdir)         resolved++;
    if (real_closedir)        resolved++;
    if (real_getenv)          resolved++;
    if (real_read)            resolved++;

    ALOGI("XStealthBridge: initialized (%d/7 symbols resolved)", resolved);
    return resolved == 7 ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_shizuposed_manager_stealth_XStealthBridge_nativeSetActive(
        JNIEnv *env, jclass clazz, jboolean active) {
    (void) env; (void) clazz;

    g_bridge_active = active ? 1 : 0;
    if (g_bridge_active) {
        pthread_mutex_lock(&g_bridge_lock);
        g_tracked_count = 0;
        pthread_mutex_unlock(&g_bridge_lock);
    }
    ALOGI("XStealthBridge: active=%d", g_bridge_active);
}

JNIEXPORT jboolean JNICALL
Java_com_shizuposed_manager_stealth_XStealthBridge_nativeIsActive(
        JNIEnv *env, jclass clazz) {
    (void) env; (void) clazz;
    return g_bridge_active ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_com_shizuposed_manager_stealth_XStealthBridge_nativeDescribe(
        JNIEnv *env, jclass clazz) {
    (void) clazz;
    char buf[384];
    snprintf(buf, sizeof(buf),
             "libxstealth_bridge %s active=%d tracked_dirs=%d "
             "procPidReadsBlocked=%d procEntriesSkipped=%d",
             "0.3.0", g_bridge_active, g_tracked_count,
             g_proc_pid_reads_blocked,
             g_proc_entries_skipped);
    return (*env)->NewStringUTF(env, buf);
}

JNIEXPORT jint JNICALL
Java_com_shizuposed_manager_stealth_XStealthBridge_nativeGetCoverage(
        JNIEnv *env, jclass clazz) {
    (void) env; (void) clazz;

    int bits = 0;
    if (real_dl_iterate_phdr) bits |= 1;
    if (real_opendir && real_readdir && real_closedir) bits |= 2;
    if (real_getenv) bits |= 4;
    if (real_readlink) bits |= 8;
    if (real_read) bits |= 16;
    return bits;
}

JNIEXPORT jint JNICALL
Java_com_shizuposed_manager_stealth_XStealthBridge_nativeGetProcPidReadsBlockedCount(
        JNIEnv *env, jclass clazz) {
    (void) env; (void) clazz;
    return g_proc_pid_reads_blocked;
}

JNIEXPORT jint JNICALL
Java_com_shizuposed_manager_stealth_XStealthBridge_nativeGetProcEntriesSkippedCount(
        JNIEnv *env, jclass clazz) {
    (void) env; (void) clazz;
    return g_proc_entries_skipped;
}

JNIEXPORT jint JNICALL
JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void) vm; (void) reserved;

    ALOGI("JNI_OnLoad: libxstealth_bridge %s", "0.3.0");

    void *libandroid = dlopen("libandroid.so", RTLD_NOW);
    if (libandroid != NULL) {
        int (*getlevel)(void) = (int (*)(void))
            dlsym(libandroid, "android_get_device_api_level");
        if (getlevel != NULL) {
            ALOGI("JNI_OnLoad: api=%d", getlevel());
        }
        dlclose(libandroid);
    }

    return JNI_VERSION_1_6;
}