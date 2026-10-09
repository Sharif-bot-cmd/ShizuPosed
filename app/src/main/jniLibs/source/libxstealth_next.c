#include <jni.h>
#include <android/log.h>
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/openat2.h>
#include <linux/stat.h>
#include <link.h>
#include <pthread.h>
#include <stdarg.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/types.h>
#include <unistd.h>

#define LOG_TAG "XStealthNativeNext"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#define NEXT_VERSION "0.16.0"

#ifndef XSTEALTH_ENABLE_MMAP_GUARD
#define XSTEALTH_ENABLE_MMAP_GUARD 1
#endif

/* ─────────────────────────────────────────────────────────────
 * Syscall numbers (arm64)
 * ───────────────────────────────────────────────────────────── */

#define NR_IOCTL        29
#define NR_FACCESSAT    48
#define NR_GETPPID      173
#define NR_FACCESSAT2   439
#define NR_FCHMODAT2    452
#define NR_OPENAT       56
#define NR_PREAD64      67
#define NR_READLINKAT   78
#define NR_NEWFSTATAT   79
#define NR_STATX        291
#define NR_OPENAT2      437

/* ─────────────────────────────────────────────────────────────
 * Hidden strings
 * ───────────────────────────────────────────────────────────── */

static const char *HIDDEN_STRINGS[] = {
    "syscall_cache",
    "shizuposed",
    "libshizuposed",
    "libamiru",
    "libxstealth",
    "ShizuPosedXStealth",
    "XStealthNative",
    "XStealthNativeNext",
    "XStealthBridge",
    NULL
};

/* ─────────────────────────────────────────────────────────────
 * Settings XML paths
 * ───────────────────────────────────────────────────────────── */

static const char *SETTINGS_XML_PATHS[] = {
    "/data/system/users/0/settings_global.xml",
    "/data/system/users/0/settings_secure.xml",
    "/data/system/users/0/settings_system.xml",
    "/data/system/user/0/settings_global.xml",
    "/data/system/user/0/settings_secure.xml",
    "/data/system/user/0/settings_system.xml",
    NULL
};

static const char *OTHER_BLOCKED_PATHS[] = {
    "/data/property/persistent_properties",
    "/data/system/users/0/accessibility_settings.xml",
    "/data/system/users/0/accessibility_enabled.xml",
    "/data/system/user/0/accessibility_settings.xml",
    "/data/system/user/0/accessibility_enabled.xml",
    "/sys/class/android_usb/android0/state",
    "/sys/class/android_usb/android0/enable",
    "/sys/class/android_usb/android0/functions",
    "/sys/class/android_usb/android0/online",
    "/sys/class/android_usb/android0/idProduct",
    "/sys/class/android_usb/android0/idVendor",
    "/sys/class/android_usb/android0/f_adb/state",
    "/sys/class/android_usb/android0/f_adb/adb_enable",
    "/config/usb_gadget/g1/state",
    NULL
};

static bool is_hidden_path(const char *path) {
    if (!path) return false;
    for (int i = 0; HIDDEN_STRINGS[i]; i++) {
        if (strstr(path, HIDDEN_STRINGS[i])) return true;
    }
    return false;
}

static bool is_blocked_path(const char *path) {
    if (!path) return false;

    if (is_hidden_path(path)) return true;

    for (int i = 0; SETTINGS_XML_PATHS[i]; i++) {
        if (strcmp(path, SETTINGS_XML_PATHS[i]) == 0) return true;
    }
    for (int i = 0; OTHER_BLOCKED_PATHS[i]; i++) {
        if (strcmp(path, OTHER_BLOCKED_PATHS[i]) == 0) return true;
    }

    /* /proc/<pid>/root/<canonical> alias. */
    if (strncmp(path, "/proc/", 6) == 0) {
        const char *root = strstr(path, "/root/");
        if (root != NULL) {
            const char *suffix = root + 6;
            for (int i = 0; SETTINGS_XML_PATHS[i]; i++) {
                if (strcmp(suffix, SETTINGS_XML_PATHS[i]) == 0) return true;
            }
            for (int i = 0; OTHER_BLOCKED_PATHS[i]; i++) {
                if (strcmp(suffix, OTHER_BLOCKED_PATHS[i]) == 0) return true;
            }
        }
    }

    return false;
}

static bool is_protected_library(const char *path) {
    if (!path) return false;
    return strstr(path, "libart.so") != NULL
        || strstr(path, "libartbase.so") != NULL;
}

/* ─────────────────────────────────────────────────────────────
 * Alias tables
 * ───────────────────────────────────────────────────────────── */

static const char *ALIASES_openat[]   = { "openat", "__openat", NULL };
static const char *ALIASES_stat[]     = { "stat", "__stat", NULL };
static const char *ALIASES_access[]   = { "access", "__access", NULL };
static const char *ALIASES_readlink[] = { "readlink", "__readlink", NULL };
static const char *ALIASES_fstatat[]  = { "fstatat", "__fstatat",
                                            "newfstatat", NULL };
static const char *ALIASES_syscall[]  = { "syscall", "__syscall", NULL };
static const char *ALIASES_getppid[]  = { "getppid", "__getppid", NULL };

/* ─────────────────────────────────────────────────────────────
 * Real wrapper pointers
 * ───────────────────────────────────────────────────────────── */

static long   (*next_openat)(int, const char *, int, ...) = NULL;
static int    (*next_stat)(const char *, void *) = NULL;
static int    (*next_access)(const char *, int) = NULL;
static ssize_t(*next_readlink)(const char *, char *, size_t) = NULL;
static int    (*next_fstatat)(int, const char *, void *, int) = NULL;
static long   (*next_syscall)(long, ...) = NULL;
static pid_t  (*next_getppid)(void) = NULL;

/* ─────────────────────────────────────────────────────────────
 * Forward declarations
 * ───────────────────────────────────────────────────────────── */

long    xstealth_next_openat_handler(int dirfd, const char *path,
                                      int flags, ...);
int     xstealth_next_stat_handler(const char *path, void *buf);
int     xstealth_next_access_handler(const char *path, int mode);
ssize_t xstealth_next_readlink_handler(const char *path,
                                        char *buf, size_t len);
int     xstealth_next_fstatat_handler(int dirfd, const char *path,
                                       void *buf, int flags);
int     xstealth_next_getppid_handler(void);
long    xstealth_next_syscall_handler(long number, ...);
long    xstealth_next_dispatch_handler(long number, ...);

static bool is_trusted_caller(void);

/* ─────────────────────────────────────────────────────────────
 * Patch records
 * ───────────────────────────────────────────────────────────── */

#define MAX_PATCHES 16

struct patch_record {
    void *stub_addr;                 /* address the branch was written at */
    void *handler;
    const char *name;
    uint32_t saved_bytes;            /* original 4 bytes at stub_addr   */
    uint32_t saved_svc;              /* the original SVC word, if scanner-patched */
    uint32_t orig_prot;              /* original mprotect prot for page */
    uint32_t shape;                  /* SHAPE_* or hop count for scanner */
} __attribute__((packed));

static struct patch_record g_patches[MAX_PATCHES];
static volatile int g_patch_count = 0;

static volatile int g_next_active = 0;
static pthread_mutex_t g_next_lock = PTHREAD_MUTEX_INITIALIZER;

static const char *g_strategy_used = NULL;

/* ─────────────────────────────────────────────────────────────
 * libart backup
 * ───────────────────────────────────────────────────────────── */

static int g_libart_backup_fd = -1;
static size_t g_libart_backup_size = 0;
static char g_libart_path[512] = {0};

/* ─────────────────────────────────────────────────────────────
 * Counters
 * ───────────────────────────────────────────────────────────── */

static volatile int g_pread64_intercepted = 0;
static volatile int g_openat2_intercepted = 0;
static volatile int g_ioctl_intercepted = 0;
static volatile int g_statx_intercepted = 0;
static volatile int g_faccessat2_intercepted = 0;
static volatile int g_fchmodat2_intercepted = 0;
static volatile int g_getppid_intercepted = 0;

/* ─────────────────────────────────────────────────────────────
 * ARM64 instruction helpers
 * ───────────────────────────────────────────────────────────── */

static inline bool is_arm64_bti(uint32_t insn) {
    return (insn & 0xFFFFFC1F) == 0xD503241F;
}

static inline bool is_arm64_svc(uint32_t insn) {
    return (insn & 0xFFE0001F) == 0xD4000001;
}

static inline bool is_arm64_b(uint32_t insn) {
    return (insn & 0xFC000000) == 0x14000000;
}

static inline bool is_arm64_bl(uint32_t insn) {
    return (insn & 0xFC000000) == 0x94000000;
}

static inline bool is_arm64_br(uint32_t insn) {
    return (insn & 0xFFFFFC1F) == 0xD61F0000;
}

static inline bool is_arm64_blr(uint32_t insn) {
    return (insn & 0xFFFFFC1F) == 0xD63F0000;
}

static inline bool is_arm64_ret(uint32_t insn) {
    return (insn & 0xFFFFFC1F) == 0xD65F0000;
}

/*
 * MOVZ/MOVK Xd, #imm16 with Rd == x8.
 */
static inline bool is_arm64_mov_x8_imm(uint32_t w) {
    if ((w & 0xff800000u) != 0xd2800000u
            && (w & 0xff800000u) != 0xf2800000u) {
        return false;
    }
    return (w & 0x1fu) == 8u;
}

/*
 * ADRP x16, <page>.
 */
static inline bool is_arm64_adrp_x16(uint32_t w) {
    if ((w & 0x9f000000u) != 0x90000000u) return false;
    return (w & 0x1fu) == 16u;
}

/*
 * LDR x16, [x16, #imm].
 */
static inline bool is_arm64_ldr_x16_x16(uint32_t w) {
    if ((w & 0xffc00000u) != 0xf9400000u) return false;
    if ((w & 0x1fu) != 16u) return false;
    if (((w >> 5) & 0x1fu) != 16u) return false;
    return true;
}

/*
 * BR x16.
 */
static inline bool is_arm64_br_x16(uint32_t w) {
    return w == 0xd61f0200u;
}

enum {
    SHAPE_UNKNOWN   = 0,
    SHAPE_BTI_MOV   = 1,
    SHAPE_BTI_SVC   = 2,
    SHAPE_BRANCH    = 3,
    SHAPE_BTI_ADRP  = 4,
    SHAPE_MOV_SVC   = 5,
    SHAPE_PLT       = 6,
    /* SHAPE_SCANNED is a marker, not a shape. Records patched via
     * the scanner carry their hop count in this field instead. */
};

static const char *shape_name(int s) {
    switch (s) {
        case SHAPE_BTI_MOV:  return "bti-mov-w8";
        case SHAPE_BTI_SVC:  return "bti-svc";
        case SHAPE_BRANCH:   return "branch";
        case SHAPE_BTI_ADRP: return "bti-adrp";
        case SHAPE_MOV_SVC:  return "mov-svc";
        case SHAPE_PLT:      return "plt";
        default:             return "unknown";
    }
}

extern void __clear_cache(void *begin, void *end);

/* ─────────────────────────────────────────────────────────────
 * Low-level patching helper
 * ───────────────────────────────────────────────────────────── */

static bool patch_word_at(int idx, void *patch_addr, void *handler,
                            const char *name) {
    intptr_t delta = (intptr_t)handler - (intptr_t)patch_addr;
    if (delta & 0x3) {
        LOGW("patch_word_at(%s): branch out of range (unaligned)",
             name);
        return false;
    }
    int64_t off = (int64_t)(delta >> 2);
    if (off < -(1LL << 25) || off >= (1LL << 25)) {
        LOGW("patch_word_at(%s): branch out of range", name);
        return false;
    }
    uint32_t branch = 0x14000000u | (uint32_t)(off & 0x03FFFFFFu);

    long page = sysconf(_SC_PAGESIZE);
    if (page <= 0) page = 4096;

    void *page_start = (void *)((uintptr_t)patch_addr
                                 & ~(uintptr_t)(page - 1));
    size_t span = (size_t)page * 2;

    uint32_t orig_prot = PROT_READ | PROT_EXEC;
    {
        FILE *f = fopen("/proc/self/maps", "r");
        if (f != NULL) {
            char line[512];
            uintptr_t want = (uintptr_t)patch_addr;
            while (fgets(line, sizeof(line), f) != NULL) {
                unsigned long lo = 0, hi = 0;
                char perms[8] = {0};
                if (sscanf(line, "%lx-%lx %7s", &lo, &hi, perms) != 3) {
                    continue;
                }
                if (want >= lo && want < hi) {
                    orig_prot = 0;
                    if (perms[0] == 'r') orig_prot |= PROT_READ;
                    if (perms[1] == 'w') orig_prot |= PROT_WRITE;
                    if (perms[2] == 'x') orig_prot |= PROT_EXEC;
                    break;
                }
            }
            fclose(f);
        }
    }

    if (mprotect(page_start, span,
                 PROT_READ | PROT_WRITE | PROT_EXEC) != 0) {
        LOGW("patch_word_at(%s): mprotect failed (W^X?)", name);
        return false;
    }

    volatile uint32_t pre = *(volatile uint32_t *)patch_addr;
    (void)pre;

    struct patch_record *r = &g_patches[idx];
    r->stub_addr = patch_addr;
    r->handler = handler;
    r->name = name;
    r->shape = SHAPE_UNKNOWN;
    r->saved_svc = 0;
    r->orig_prot = orig_prot;
    r->saved_bytes = *(uint32_t *)patch_addr;

    *(uint32_t *)patch_addr = branch;
    __clear_cache(patch_addr, (uint8_t *)patch_addr + 4);

    mprotect(page_start, span, (int)orig_prot);
    return true;
}

/* ═════════════════════════════════════════════════════════════
 * RUNTIME STUB SCANNER
 *
 * Instead of classifying a stub against a fixed list of shapes,
 * scan for the SVC instruction and patch it. Every ARM64 syscall
 * stub ends with SVC #0; the instructions before it prepare the
 * syscall number in x8. Patching the SVC routes the syscall
 * through the handler regardless of how the stub is shaped.
 * ═════════════════════════════════════════════════════════════ */

#define SCAN_MAX_HOPS  4
#define SCAN_MAX_WORDS 32

struct resolved_stub {
    uint32_t *svc_addr;     /* address of the SVC instruction */
    uint32_t *stub_entry;   /* the original symbol, for logging */
    int       hops;         /* how many B trampolines followed */
    int       svc_offset;   /* words from stub entry to SVC */
};

/*
 * find_stub_svc — scan a linear stub for its SVC.
 *
 * Returns the address of the SVC, or NULL if the stub doesn't
 * reach an SVC by fall-through within SCAN_MAX_WORDS. A branch
 * of any kind (B, BL, BR, BLR, RET) terminates the linear scan
 * and returns NULL — the caller decides whether to follow it.
 */
static uint32_t *find_stub_svc(uint32_t *stub, int max_words) {
    uint32_t *p = stub;
    for (int i = 0; i < max_words; i++) {
        uint32_t w = *p;

        if (is_arm64_svc(w)) {
            return p;
        }

        if (is_arm64_b(w) || is_arm64_bl(w)
                || is_arm64_br(w) || is_arm64_blr(w)
                || is_arm64_ret(w)) {
            return NULL;
        }

        p++;
    }
    return NULL;
}

/*
 * resolve_stub_svc — follow B trampolines to find a stub's SVC.
 *
 * Some bionic versions route every syscall stub through a shared
 * implementation via a single B instruction. This follows up to
 * SCAN_MAX_HOPS branches. If a shared implementation is reached
 * (a stub that isn't a compiled wrapper), the strategy fails —
 * the caller falls through to syscall-dispatch, which patches
 * the shared syscall() entry instead.
 *
 * Returns 1 on success, 0 on failure.
 */
static int resolve_stub_svc(void *symbol, struct resolved_stub *out) {
    if (!symbol || !out) return 0;

    uint32_t *stub  = (uint32_t *)symbol;
    uint32_t *entry = stub;

    for (int hop = 0; hop < SCAN_MAX_HOPS; hop++) {
        uint32_t *svc = find_stub_svc(stub, SCAN_MAX_WORDS);

        if (svc != NULL) {
            out->svc_addr   = svc;
            out->stub_entry = entry;
            out->hops       = hop;
            out->svc_offset = (int)(svc - entry);
            return 1;
        }

        /* No SVC by fall-through. Follow a single B if present. */
        int off = is_arm64_bti(stub[0]) ? 1 : 0;
        uint32_t w0 = stub[off];

        if (!is_arm64_b(w0)) {
            /* Not a simple trampoline. Refuse. */
            return 0;
        }

        /* B <offset> — sign-extend the 26-bit immediate and shift
         * by 2 to get the byte offset. */
        int64_t imm = (int64_t)(w0 & 0x03FFFFFFu);
        if (imm & (1LL << 25)) {
            imm |= ~((1LL << 26) - 1);
        }
        imm <<= 2;
        stub = (uint32_t *)((uintptr_t)(stub + off) + imm);
    }

    return 0;
}

/*
 * patch_stub_by_svc — scanner-based patching.
 *
 * Resolves the symbol to its SVC, saves the original SVC word in
 * the patch record (so a handler can later invoke it via a
 * trampoline), and patches the SVC to branch to the handler.
 *
 * The handler is entered with the same register state the SVC
 * would have seen. It can return a synthetic value directly, or
 * (for the fall-through case) call the saved SVC through the
 * helper xstealth_next_call_saved_svc().
 */
static bool patch_stub_by_svc(int idx, void *handler,
                                const char *name,
                                const char **aliases) {
    void *symbol = NULL;
    for (int i = 0; aliases[i]; i++) {
        void *p = dlsym(RTLD_DEFAULT, aliases[i]);
        if (p) { symbol = p; break; }
    }
    if (!symbol) {
        LOGW("patch_stub_by_svc(%s): symbol not found", name);
        return false;
    }

    struct resolved_stub stub;
    memset(&stub, 0, sizeof(stub));
    if (!resolve_stub_svc(symbol, &stub)) {
        LOGW("patch_stub_by_svc(%s): no SVC reachable (entry %p)",
             name, symbol);
        return false;
    }

    uint32_t saved_svc = *stub.svc_addr;

    if (!patch_word_at(idx, stub.svc_addr, handler, name)) {
        return false;
    }

    g_patches[idx].saved_svc = saved_svc;
    g_patches[idx].shape = (uint32_t)stub.hops;   /* hop count */

    LOGI("patch_stub_by_svc(%s): SVC at %p+%d (entry %p, hops=%d, "
         "saved=%08x)",
         name, stub.stub_entry, stub.svc_offset, symbol, stub.hops,
         saved_svc);
    return true;
}

/* ═════════════════════════════════════════════════════════════
 * CLASSIFIER-BASED PATCHING (fallback)
 *
 * Retained from v0.15.0. Used when the scanner can't find an SVC
 * (e.g. a stub shape it can't follow). Patches at a hardcoded
 * offset based on the shape.
 * ═════════════════════════════════════════════════════════════ */

static bool patch_stub_classified(int idx, void *handler,
                                    const char *name,
                                    const char **aliases) {
    void *stub = NULL;
    for (int i = 0; aliases[i]; i++) {
        void *p = dlsym(RTLD_DEFAULT, aliases[i]);
        if (p) { stub = p; break; }
    }
    if (!stub) {
        LOGW("patch_stub_classified(%s): symbol not found", name);
        return false;
    }

    uint32_t *w = (uint32_t *)stub;
    uint32_t w0 = w[0];
    uint32_t w1 = w[1];
    uint32_t w2 = w[2];
    uint32_t w3 = w[3];
    int shape = SHAPE_UNKNOWN;
    int patch_off = 0;

    if (is_arm64_bti(w0)) {
        patch_off = 4;
        if (is_arm64_svc(w1)) {
            shape = SHAPE_BTI_SVC;
        } else if (is_arm64_b(w1)) {
            shape = SHAPE_BRANCH;
        } else if (is_arm64_mov_x8_imm(w1)) {
            shape = SHAPE_BTI_MOV;
        } else if (is_arm64_adrp_x16(w1)) {
            shape = SHAPE_BTI_ADRP;
        } else {
            shape = SHAPE_BTI_MOV;
        }
    } else if (is_arm64_svc(w0)) {
        shape = SHAPE_BTI_SVC;
        patch_off = 0;
    } else if (is_arm64_b(w0)) {
        shape = SHAPE_BRANCH;
        patch_off = 0;
    } else if (is_arm64_mov_x8_imm(w0)) {
        shape = SHAPE_MOV_SVC;
        patch_off = 0;
    } else if (is_arm64_adrp_x16(w0) && is_arm64_ldr_x16_x16(w1)
            && is_arm64_br_x16(w2)) {
        shape = SHAPE_PLT;
        patch_off = 0;
    }

    if (shape == SHAPE_UNKNOWN) {
        LOGW("patch_stub_classified(%s): unrecognized prologue at %p: "
             "%08x %08x %08x %08x  refusing to patch",
             name, stub, w0, w1, w2, w3);
        return false;
    }

    if (!patch_word_at(idx, (uint8_t *)stub + patch_off, handler, name)) {
        return false;
    }

    g_patches[idx].shape = (uint32_t)shape;
    LOGI("patch_stub_classified(%s): patched at %p+%d "
         "(resolved=%s, shape=%s)",
         name, stub, patch_off, name, shape_name(shape));
    return true;
}

/* ═════════════════════════════════════════════════════════════
 * STRATEGY 1a — stub-inline-by-svc (scanner)
 * ═════════════════════════════════════════════════════════════ */

static int strategy_stub_inline_by_svc_apply(void) {
    next_openat   = (void *)dlsym(RTLD_DEFAULT, "openat");
    next_stat     = (void *)dlsym(RTLD_DEFAULT, "stat");
    next_access   = (void *)dlsym(RTLD_DEFAULT, "access");
    next_readlink = (void *)dlsym(RTLD_DEFAULT, "readlink");
    next_fstatat  = (void *)dlsym(RTLD_DEFAULT, "fstatat");
    next_syscall  = (void *)dlsym(RTLD_DEFAULT, "syscall");
    next_getppid  = (void *)dlsym(RTLD_DEFAULT, "getppid");

    int n = 0;
    if (patch_stub_by_svc(0, (void *)xstealth_next_openat_handler,
                           "openat", ALIASES_openat))   n++;
    if (patch_stub_by_svc(1, (void *)xstealth_next_stat_handler,
                           "stat", ALIASES_stat))       n++;
    if (patch_stub_by_svc(2, (void *)xstealth_next_access_handler,
                           "access", ALIASES_access))   n++;
    if (patch_stub_by_svc(3, (void *)xstealth_next_readlink_handler,
                           "readlink", ALIASES_readlink)) n++;
    if (patch_stub_by_svc(4, (void *)xstealth_next_fstatat_handler,
                           "fstatat", ALIASES_fstatat)) n++;
    if (patch_stub_by_svc(5, (void *)xstealth_next_syscall_handler,
                           "syscall", ALIASES_syscall)) n++;
    if (patch_stub_by_svc(6, (void *)xstealth_next_getppid_handler,
                           "getppid", ALIASES_getppid)) n++;

    return n;
}

/* ═════════════════════════════════════════════════════════════
 * STRATEGY 1b — stub-inline-classified (hardcoded shapes)
 * ═════════════════════════════════════════════════════════════ */

static int strategy_stub_inline_classified_apply(void) {
    next_openat   = (void *)dlsym(RTLD_DEFAULT, "openat");
    next_stat     = (void *)dlsym(RTLD_DEFAULT, "stat");
    next_access   = (void *)dlsym(RTLD_DEFAULT, "access");
    next_readlink = (void *)dlsym(RTLD_DEFAULT, "readlink");
    next_fstatat  = (void *)dlsym(RTLD_DEFAULT, "fstatat");
    next_syscall  = (void *)dlsym(RTLD_DEFAULT, "syscall");
    next_getppid  = (void *)dlsym(RTLD_DEFAULT, "getppid");

    int n = 0;
    if (patch_stub_classified(0, (void *)xstealth_next_openat_handler,
                               "openat", ALIASES_openat))   n++;
    if (patch_stub_classified(1, (void *)xstealth_next_stat_handler,
                               "stat", ALIASES_stat))       n++;
    if (patch_stub_classified(2, (void *)xstealth_next_access_handler,
                               "access", ALIASES_access))   n++;
    if (patch_stub_classified(3, (void *)xstealth_next_readlink_handler,
                               "readlink", ALIASES_readlink)) n++;
    if (patch_stub_classified(4, (void *)xstealth_next_fstatat_handler,
                               "fstatat", ALIASES_fstatat)) n++;
    if (patch_stub_classified(5, (void *)xstealth_next_syscall_handler,
                               "syscall", ALIASES_syscall)) n++;
    if (patch_stub_classified(6, (void *)xstealth_next_getppid_handler,
                               "getppid", ALIASES_getppid)) n++;

    return n;
}

/* ─────────────────────────────────────────────────────────────
 * STRATEGY 2 — syscall dispatcher patching
 * ───────────────────────────────────────────────────────────── */

static int strategy_syscall_dispatch_apply(void) {
    if (!next_syscall) {
        next_syscall = (void *)dlsym(RTLD_DEFAULT, "syscall");
    }
    if (!next_syscall) {
        LOGW("syscall-dispatch: syscall symbol not found");
        return 0;
    }

    void *entry = (void *)next_syscall;
    uint32_t *w = (uint32_t *)entry;

    void *patch_addr = entry;
    if (is_arm64_bti(w[0])) {
        patch_addr = (uint8_t *)entry + 4;
    }

    uint32_t first = *(uint32_t *)patch_addr;
    if (is_arm64_b(first)) {
        LOGW("syscall-dispatch: syscall already patched");
        return 0;
    }

    if (!patch_word_at(7, patch_addr,
                        (void *)xstealth_next_dispatch_handler,
                        "syscall-dispatch")) {
        return 0;
    }

    LOGI("syscall-dispatch: patched syscall at %p "
         "(first word was %08x)", entry, first);
    return 1;
}

/* ─────────────────────────────────────────────────────────────
 * STRATEGY 3 — PLT/GOT rewriting (not implemented)
 * ───────────────────────────────────────────────────────────── */

static int strategy_plt_got_apply(void) {
    LOGW("plt-got: strategy not implemented — skipping");
    return 0;
}

/* ─────────────────────────────────────────────────────────────
 * install_all_patches — the strategy ladder
 *
 *   1. stub-inline-by-svc        (scanner, no shape knowledge)
 *   2. stub-inline-classified    (hardcoded shapes, fallback)
 *   3. syscall-dispatch          (shared syscall() entry)
 *   4. plt-got                   (not implemented)
 *
 * The scanner runs first because it handles new bionic layouts
 * without a code change. The classifier runs only if the scanner
 * can't resolve a stub. The dispatch strategy runs only if both
 * per-stub strategies fail.
 * ───────────────────────────────────────────────────────────── */

static void install_all_patches(void) {
    pthread_mutex_lock(&g_next_lock);

    if (g_patch_count > 0) {
        pthread_mutex_unlock(&g_next_lock);
        return;
    }

    LOGI("install_all_patches: trying strategy stub-inline-by-svc");
    int n0 = strategy_stub_inline_by_svc_apply();
    if (n0 > 0) {
        g_patch_count = n0;
        g_strategy_used = "stub-inline-by-svc";
        LOGI("install_all_patches: strategy stub-inline-by-svc "
             "applied %d patch(es)", n0);
        pthread_mutex_unlock(&g_next_lock);
        return;
    }
    LOGW("install_all_patches: strategy stub-inline-by-svc "
         "applied 0 patches");

    LOGI("install_all_patches: trying strategy stub-inline-classified");
    int n1 = strategy_stub_inline_classified_apply();
    if (n1 > 0) {
        g_patch_count = n1;
        g_strategy_used = "stub-inline-classified";
        LOGI("install_all_patches: strategy stub-inline-classified "
             "applied %d patch(es)", n1);
        pthread_mutex_unlock(&g_next_lock);
        return;
    }
    LOGW("install_all_patches: strategy stub-inline-classified "
         "applied 0 patches");

    LOGI("install_all_patches: trying strategy syscall-dispatch");
    int n2 = strategy_syscall_dispatch_apply();
    if (n2 > 0) {
        g_patch_count = n2;
        g_strategy_used = "syscall-dispatch";
        LOGI("install_all_patches: strategy syscall-dispatch applied "
             "%d patch(es)", n2);
        pthread_mutex_unlock(&g_next_lock);
        return;
    }
    LOGW("install_all_patches: strategy syscall-dispatch applied 0 patches");

    LOGI("install_all_patches: trying strategy plt-got");
    int n3 = strategy_plt_got_apply();
    if (n3 > 0) {
        g_patch_count = n3;
        g_strategy_used = "plt-got";
        LOGI("install_all_patches: strategy plt-got applied %d patch(es)",
             n3);
        pthread_mutex_unlock(&g_next_lock);
        return;
    }

    LOGW("install_all_patches: no strategy succeeded. "
         "Next engine will be ineffective.");
    pthread_mutex_unlock(&g_next_lock);
}

/* ─────────────────────────────────────────────────────────────
 * uninstall_all_patches
 * ───────────────────────────────────────────────────────────── */

static void uninstall_all_patches(void) {
    pthread_mutex_lock(&g_next_lock);
    for (int i = 0; i < g_patch_count; i++) {
        struct patch_record *r = &g_patches[i];
        if (!r->stub_addr) continue;

        long page = sysconf(_SC_PAGESIZE);
        if (page <= 0) page = 4096;
        void *page_start = (void *)((uintptr_t)r->stub_addr
                                     & ~(uintptr_t)(page - 1));
        size_t span = (size_t)page * 2;

        if (mprotect(page_start, span,
                     PROT_READ | PROT_WRITE | PROT_EXEC) != 0) {
            LOGW("unpatch_stub(%s): mprotect failed", r->name);
            continue;
        }

        *(uint32_t *)r->stub_addr = r->saved_bytes;
        __clear_cache(r->stub_addr, (uint8_t *)r->stub_addr + 4);

        mprotect(page_start, span, (int)r->orig_prot);
        LOGI("uninstall: restored %s", r->name);
    }
    g_patch_count = 0;
    g_strategy_used = NULL;
    pthread_mutex_unlock(&g_next_lock);
}

/* ═════════════════════════════════════════════════════════════
 * MMAP GUARD
 * ═════════════════════════════════════════════════════════════ */

#if XSTEALTH_ENABLE_MMAP_GUARD

static void *(*real_mmap)(void *, size_t, int, int, int, off_t) = NULL;
static void *(*real_mmap64)(void *, size_t, int, int, int, off64_t) = NULL;
static ssize_t (*real_readlink_mmap)(const char *, char *, size_t) = NULL;

static volatile int g_mmap_intercepted = 0;
static volatile int g_mmap_guard_installed = 0;
static char g_mmap_guard_note[128] = "not attempted";

static int find_libart_callback(struct dl_phdr_info *info,
                                 size_t size, void *data) {
    (void) size;
    if (info == NULL || info->dlpi_name == NULL) return 0;
    if (strstr(info->dlpi_name, "libart.so") == NULL) return 0;

    char *dst = (char *) data;
    strncpy(dst, info->dlpi_name, 511);
    dst[511] = '\0';
    return 1;
}

static void capture_libart_backup(void) {
    if (g_libart_backup_fd >= 0) return;

    char path[512] = {0};
    dl_iterate_phdr(find_libart_callback, path);
    if (path[0] == '\0') {
        snprintf(g_mmap_guard_note, sizeof(g_mmap_guard_note),
                 "libart not found");
        LOGW("mmap guard: libart not found via dl_iterate_phdr");
        return;
    }

    strncpy(g_libart_path, path, sizeof(g_libart_path) - 1);
    g_libart_path[sizeof(g_libart_path) - 1] = '\0';

    int src_fd = open(path, O_RDONLY | O_CLOEXEC);
    if (src_fd < 0) {
        snprintf(g_mmap_guard_note, sizeof(g_mmap_guard_note),
                 "open failed errno=%d", errno);
        LOGW("mmap guard: open(%s) failed: %s", path, strerror(errno));
        return;
    }

    struct stat st;
    if (fstat(src_fd, &st) != 0 || st.st_size <= 0) {
        snprintf(g_mmap_guard_note, sizeof(g_mmap_guard_note),
                 "fstat failed");
        LOGW("mmap guard: fstat(%s) failed", path);
        close(src_fd);
        return;
    }

    int memfd = -1;
#ifdef SYS_memfd_create
    memfd = (int) syscall(SYS_memfd_create, "xstealth_libart", 0);
#endif
    if (memfd < 0) {
        char tmp_path[128];
        snprintf(tmp_path, sizeof(tmp_path),
                 "/data/local/tmp/.xstealth_libart_%d", getpid());
        memfd = open(tmp_path, O_RDWR | O_CREAT | O_TRUNC | O_CLOEXEC, 0600);
        if (memfd >= 0) {
            unlink(tmp_path);
        }
    }
    if (memfd < 0) {
        snprintf(g_mmap_guard_note, sizeof(g_mmap_guard_note),
                 "backup fd creation failed");
        LOGW("mmap guard: could not create backup fd");
        close(src_fd);
        return;
    }

    char buf[65536];
    ssize_t n;
    off_t total = 0;
    while ((n = read(src_fd, buf, sizeof(buf))) > 0) {
        ssize_t written = 0;
        while (written < n) {
            ssize_t w = write(memfd, buf + written, n - written);
            if (w <= 0) {
                snprintf(g_mmap_guard_note,
                         sizeof(g_mmap_guard_note),
                         "write failed");
                LOGW("mmap guard: write to backup failed");
                close(memfd);
                close(src_fd);
                return;
            }
            written += w;
        }
        total += n;
    }

    close(src_fd);

    if (n < 0 || total <= 0) {
        snprintf(g_mmap_guard_note, sizeof(g_mmap_guard_note),
                 "copy failed");
        LOGW("mmap guard: copy failed, total=%ld", (long) total);
        close(memfd);
        return;
    }

    g_libart_backup_fd = memfd;
    g_libart_backup_size = (size_t) total;

    snprintf(g_mmap_guard_note, sizeof(g_mmap_guard_note),
             "backup captured %ld bytes", (long) total);
    LOGI("mmap guard: captured %ld bytes of %s into fd %d",
         (long) total, g_libart_path, memfd);
}

static int maybe_substitute_fd(int fd) {
    if (!g_next_active) return fd;
    if (g_libart_backup_fd < 0) return fd;
    if (fd < 0) return fd;
    if (is_trusted_caller()) return fd;

    char fd_path[64];
    snprintf(fd_path, sizeof(fd_path), "/proc/self/fd/%d", fd);
    char target[512] = {0};

    ssize_t n;
    if (real_readlink_mmap != NULL) {
        n = real_readlink_mmap(fd_path, target, sizeof(target) - 1);
    } else {
        n = readlink(fd_path, target, sizeof(target) - 1);
    }
    if (n <= 0) return fd;
    target[n] = '\0';

    if (!is_protected_library(target)) return fd;

    __sync_fetch_and_add(&g_mmap_intercepted, 1);
    LOGI("mmap guard: substituting backup fd for %s (original fd %d)",
         target, fd);
    return g_libart_backup_fd;
}

static int maybe_refuse_blocked_mmap(int fd) {
    if (!g_next_active) return 0;
    if (fd < 0) return 0;

    char fd_path[64];
    snprintf(fd_path, sizeof(fd_path), "/proc/self/fd/%d", fd);
    char target[512] = {0};

    ssize_t n;
    if (real_readlink_mmap != NULL) {
        n = real_readlink_mmap(fd_path, target, sizeof(target) - 1);
    } else {
        n = readlink(fd_path, target, sizeof(target) - 1);
    }
    if (n <= 0) return 0;
    target[n] = '\0';

    if (is_blocked_path(target)) {
        __sync_fetch_and_add(&g_mmap_intercepted, 1);
        LOGI("mmap guard: refusing mmap of blocked path %s (fd %d)",
             target, fd);
        errno = EACCES;
        return 1;
    }

    if (strcmp(target, "/proc/self/maps") == 0
            || strcmp(target, "/proc/self/smaps") == 0
            || strcmp(target, "/proc/self/smaps_rollup") == 0
            || strcmp(target, "/proc/self/numa_maps") == 0) {
        if (!is_trusted_caller()) {
            __sync_fetch_and_add(&g_mmap_intercepted, 1);
            LOGI("mmap guard: refusing mmap of %s (untrusted caller)",
                 target);
            errno = EACCES;
            return 1;
        }
    }

    return 0;
}

void *mmap(void *addr, size_t length, int prot, int flags,
           int fd, off_t offset) {
    if (real_mmap == NULL) {
        real_mmap = (void *) dlsym(RTLD_NEXT, "mmap");
        if (real_mmap == NULL) {
            long r = syscall(SYS_mmap, addr, length, prot, flags,
                             fd, offset);
            return (void *) r;
        }
    }

    if (maybe_refuse_blocked_mmap(fd)) {
        return MAP_FAILED;
    }

    int effective_fd = maybe_substitute_fd(fd);
    return real_mmap(addr, length, prot, flags, effective_fd, offset);
}

void *mmap64(void *addr, size_t length, int prot, int flags,
             int fd, off64_t offset) {
    if (real_mmap64 == NULL) {
        real_mmap64 = (void *) dlsym(RTLD_NEXT, "mmap64");
        if (real_mmap64 == NULL) {
            return mmap(addr, length, prot, flags, fd, (off_t) offset);
        }
    }

    if (maybe_refuse_blocked_mmap(fd)) {
        return MAP_FAILED;
    }

    int effective_fd = maybe_substitute_fd(fd);
    return real_mmap64(addr, length, prot, flags, effective_fd, offset);
}

#endif /* XSTEALTH_ENABLE_MMAP_GUARD */

/* ═════════════════════════════════════════════════════════════
 * Handlers
 * ═════════════════════════════════════════════════════════════ */

long xstealth_next_openat_handler(int dirfd, const char *path,
                                    int flags, ...) {
    if (g_next_active && is_blocked_path(path)) {
        errno = ENOENT;
        return -1;
    }
    if (!next_openat) { errno = ENOENT; return -1; }

    if (flags & O_CREAT) {
        va_list ap;
        va_start(ap, flags);
        mode_t mode = va_arg(ap, mode_t);
        va_end(ap);
        return next_openat(dirfd, path, flags, mode);
    }
    return next_openat(dirfd, path, flags);
}

int xstealth_next_stat_handler(const char *path, void *buf) {
    if (g_next_active && is_blocked_path(path)) {
        errno = ENOENT;
        return -1;
    }
    if (!next_stat) { errno = ENOENT; return -1; }
    return next_stat(path, buf);
}

int xstealth_next_access_handler(const char *path, int mode) {
    if (g_next_active && is_blocked_path(path)) {
        errno = ENOENT;
        return -1;
    }
    if (!next_access) { errno = ENOENT; return -1; }
    return next_access(path, mode);
}

ssize_t xstealth_next_readlink_handler(const char *path,
                                        char *buf, size_t len) {
    if (g_next_active && is_blocked_path(path)) {
        errno = ENOENT;
        return -1;
    }
    if (!next_readlink) { errno = ENOENT; return -1; }
    return next_readlink(path, buf, len);
}

int xstealth_next_fstatat_handler(int dirfd, const char *path,
                                    void *buf, int flags) {
    if (g_next_active && is_blocked_path(path)) {
        errno = ENOENT;
        return -1;
    }
    if (!next_fstatat) { errno = ENOENT; return -1; }
    return next_fstatat(dirfd, path, buf, flags);
}

int xstealth_next_getppid_handler(void) {
    if (!g_next_active) {
        if (!next_getppid) return 1;
        return next_getppid();
    }
    if (is_trusted_caller()) {
        if (!next_getppid) return 1;
        return next_getppid();
    }
    __sync_fetch_and_add(&g_getppid_intercepted, 1);
    return 1;
}

/* ─────────────────────────────────────────────────────────────
 * is_trusted_caller — check whether the current thread belongs
 * to the framework.
 * ───────────────────────────────────────────────────────────── */

static bool is_trusted_caller(void) {
    char comm_path[64];
    snprintf(comm_path, sizeof(comm_path),
             "/proc/self/task/%d/comm", (int) syscall(SYS_gettid));

    int fd = open(comm_path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return true;

    char comm[64] = {0};
    ssize_t n = read(fd, comm, sizeof(comm) - 1);
    close(fd);

    if (n <= 0) return true;

    for (ssize_t i = 0; i < n; i++) {
        if (comm[i] == '\n') { comm[i] = '\0'; break; }
    }

    if (strstr(comm, "ShizuPosed") != NULL) return true;
    if (strstr(comm, "Xposed") != NULL) return true;
    return false;
}

/* ─────────────────────────────────────────────────────────────
 * pread64 syscall handler.
 * ───────────────────────────────────────────────────────────── */

static long handle_pread64(int fd, void *buf, size_t count, off_t offset) {
    if (!g_next_active
            || g_libart_backup_fd < 0
            || is_trusted_caller()) {
        return next_syscall(NR_PREAD64, fd, buf, count, offset);
    }

    char fd_path[64];
    snprintf(fd_path, sizeof(fd_path), "/proc/self/fd/%d", fd);
    char target[512] = {0};

    ssize_t rl = -1;
    if (next_readlink) {
        rl = next_readlink(fd_path, target, sizeof(target) - 1);
    }
    if (rl <= 0) {
        return next_syscall(NR_PREAD64, fd, buf, count, offset);
    }
    target[rl] = '\0';

    if (!is_protected_library(target)) {
        return next_syscall(NR_PREAD64, fd, buf, count, offset);
    }

    __sync_fetch_and_add(&g_pread64_intercepted, 1);
    LOGI("pread64 syscall: redirecting fd %d read of %s "
         "(offset=%ld, count=%zu) to backup",
         fd, target, (long) offset, count);

    return next_syscall(NR_PREAD64, g_libart_backup_fd,
                         buf, count, offset);
}

/* ─────────────────────────────────────────────────────────────
 * getppid syscall handler.
 * ───────────────────────────────────────────────────────────── */

static long handle_getppid(void) {
    long real = next_syscall(NR_GETPPID);
    if (!g_next_active) return real;
    if (is_trusted_caller()) return real;
    __sync_fetch_and_add(&g_getppid_intercepted, 1);
    return 1;
}

/* ─────────────────────────────────────────────────────────────
 * openat2 syscall handler.
 * ───────────────────────────────────────────────────────────── */

static long handle_openat2(int dirfd, const char *path,
                             void *how, size_t size) {
    if (g_next_active && path && is_blocked_path(path)) {
        __sync_fetch_and_add(&g_openat2_intercepted, 1);
        LOGI("openat2 syscall blocked: %s", path);
        errno = ENOENT;
        return -1;
    }
    return next_syscall(NR_OPENAT2, dirfd, path, how, size);
}

/* ─────────────────────────────────────────────────────────────
 * ioctl syscall handler.
 * ───────────────────────────────────────────────────────────── */

static long handle_ioctl(int fd, unsigned long request, void *arg) {
    if (!g_next_active) {
        return next_syscall(NR_IOCTL, fd, request, arg);
    }

    if (fd >= 0 && next_readlink) {
        char fd_path[64];
        snprintf(fd_path, sizeof(fd_path), "/proc/self/fd/%d", fd);
        char target[512] = {0};
        ssize_t rl = next_readlink(fd_path, target, sizeof(target) - 1);
        if (rl > 0) {
            target[rl] = '\0';
            if (is_blocked_path(target)) {
                __sync_fetch_and_add(&g_ioctl_intercepted, 1);
                LOGI("ioctl syscall blocked on fd %d -> %s "
                     "(request 0x%lx)", fd, target, request);
                errno = ENOENT;
                return -1;
            }
        }
    }

    return next_syscall(NR_IOCTL, fd, request, arg);
}

/* ─────────────────────────────────────────────────────────────
 * statx syscall handler.
 * ───────────────────────────────────────────────────────────── */

static long handle_statx(int dirfd, const char *path,
                          int flags, unsigned int mask,
                          struct statx *buf) {
    if (g_next_active && path && is_blocked_path(path)) {
        __sync_fetch_and_add(&g_statx_intercepted, 1);
        errno = ENOENT;
        return -1;
    }
    return next_syscall(NR_STATX, dirfd, path, flags, mask, buf);
}

/* ─────────────────────────────────────────────────────────────
 * faccessat2 syscall handler.
 * ───────────────────────────────────────────────────────────── */

static long handle_faccessat2(int dirfd, const char *path,
                                int mode, int flags) {
    if (g_next_active && path && is_blocked_path(path)) {
        __sync_fetch_and_add(&g_faccessat2_intercepted, 1);
        errno = ENOENT;
        return -1;
    }
    return next_syscall(NR_FACCESSAT2, dirfd, path, mode, flags);
}

/* ─────────────────────────────────────────────────────────────
 * fchmodat2 syscall handler.
 * ───────────────────────────────────────────────────────────── */

static long handle_fchmodat2(int dirfd, const char *path,
                               mode_t mode, int flags) {
    if (g_next_active && path && is_blocked_path(path)) {
        __sync_fetch_and_add(&g_fchmodat2_intercepted, 1);
        errno = ENOENT;
        return -1;
    }
    return next_syscall(NR_FCHMODAT2, dirfd, path,
                         (unsigned long) mode,
                         (unsigned long) flags);
}

long xstealth_next_syscall_handler(long number, ...) {
    if (!next_syscall) {
        errno = ENOSYS;
        return -1;
    }

    va_list ap;
    va_start(ap, number);

    long ret;

    switch (number) {
        case NR_IOCTL: {
            int fd            = va_arg(ap, int);
            unsigned long req = va_arg(ap, unsigned long);
            void *arg         = va_arg(ap, void *);
            va_end(ap);

            ret = handle_ioctl(fd, req, arg);
            break;
        }

        case NR_FACCESSAT: {
            int dirfd        = va_arg(ap, int);
            const char *path = va_arg(ap, const char *);
            int mode         = va_arg(ap, int);
            int flags        = va_arg(ap, int);
            va_end(ap);

            if (g_next_active && is_blocked_path(path)) {
                errno = ENOENT;
                return -1;
            }
            ret = next_syscall(NR_FACCESSAT, dirfd, path, mode, flags);
            break;
        }

        case NR_FACCESSAT2: {
            int dirfd        = va_arg(ap, int);
            const char *path = va_arg(ap, const char *);
            int mode         = va_arg(ap, int);
            int flags        = va_arg(ap, int);
            va_end(ap);

            ret = handle_faccessat2(dirfd, path, mode, flags);
            break;
        }

        case NR_FCHMODAT2: {
            int dirfd        = va_arg(ap, int);
            const char *path = va_arg(ap, const char *);
            mode_t mode      = (mode_t) va_arg(ap, unsigned long);
            int flags        = va_arg(ap, int);
            va_end(ap);

            ret = handle_fchmodat2(dirfd, path, mode, flags);
            break;
        }

        case NR_OPENAT: {
            int dirfd        = va_arg(ap, int);
            const char *path = va_arg(ap, const char *);
            int flags        = va_arg(ap, int);
            int mode         = va_arg(ap, int);
            va_end(ap);

            if (g_next_active && is_blocked_path(path)) {
                errno = ENOENT;
                return -1;
            }
            ret = next_syscall(NR_OPENAT, dirfd, path, flags, mode);
            break;
        }

        case NR_OPENAT2: {
            int dirfd        = va_arg(ap, int);
            const char *path = va_arg(ap, const char *);
            void *how        = va_arg(ap, void *);
            size_t size      = va_arg(ap, size_t);
            va_end(ap);

            ret = handle_openat2(dirfd, path, how, size);
            break;
        }

        case NR_PREAD64: {
            int fd           = va_arg(ap, int);
            void *buf        = va_arg(ap, void *);
            size_t count     = va_arg(ap, size_t);
            off_t offset     = va_arg(ap, off_t);
            va_end(ap);

            ret = handle_pread64(fd, buf, count, offset);
            break;
        }

        case NR_READLINKAT: {
            int dirfd        = va_arg(ap, int);
            const char *path = va_arg(ap, const char *);
            char *buf        = va_arg(ap, char *);
            size_t bufsiz    = va_arg(ap, size_t);
            va_end(ap);

            if (path && strcmp(path, "/proc/self/fd/") == 0) {
                ret = next_syscall(NR_READLINKAT, dirfd, path,
                                    buf, bufsiz);
                break;
            }
            if (g_next_active && is_blocked_path(path)) {
                errno = ENOENT;
                return -1;
            }
            ret = next_syscall(NR_READLINKAT, dirfd, path, buf, bufsiz);
            break;
        }

        case NR_NEWFSTATAT: {
            int dirfd        = va_arg(ap, int);
            const char *path = va_arg(ap, const char *);
            void *buf        = va_arg(ap, void *);
            int flags        = va_arg(ap, int);
            va_end(ap);

            if (g_next_active && is_blocked_path(path)) {
                errno = ENOENT;
                return -1;
            }
            ret = next_syscall(NR_NEWFSTATAT, dirfd, path, buf, flags);
            break;
        }

        case NR_STATX: {
            int dirfd           = va_arg(ap, int);
            const char *path    = va_arg(ap, const char *);
            int flags           = va_arg(ap, int);
            unsigned int mask   = va_arg(ap, unsigned int);
            struct statx *buf   = va_arg(ap, struct statx *);
            va_end(ap);

            ret = handle_statx(dirfd, path, flags, mask, buf);
            break;
        }

        case NR_GETPPID: {
            va_end(ap);
            ret = handle_getppid();
            break;
        }

        default:
            va_end(ap);
            ret = next_syscall(number, 0, 0, 0, 0, 0, 0);
            break;
    }

    return ret;
}

long xstealth_next_dispatch_handler(long number, ...) {
    if (!next_syscall) {
        errno = ENOSYS;
        return -1;
    }

    va_list ap;
    va_start(ap, number);

    long ret;

    switch (number) {
        case NR_IOCTL: {
            int fd            = va_arg(ap, int);
            unsigned long req = va_arg(ap, unsigned long);
            void *arg         = va_arg(ap, void *);
            va_end(ap);
            ret = handle_ioctl(fd, req, arg);
            break;
        }
        case NR_FACCESSAT: {
            int dirfd        = va_arg(ap, int);
            const char *path = va_arg(ap, const char *);
            int mode         = va_arg(ap, int);
            int flags        = va_arg(ap, int);
            va_end(ap);
            if (g_next_active && is_blocked_path(path)) {
                errno = ENOENT;
                return -1;
            }
            ret = next_syscall(NR_FACCESSAT, dirfd, path, mode, flags);
            break;
        }
        case NR_FACCESSAT2: {
            int dirfd        = va_arg(ap, int);
            const char *path = va_arg(ap, const char *);
            int mode         = va_arg(ap, int);
            int flags        = va_arg(ap, int);
            va_end(ap);
            ret = handle_faccessat2(dirfd, path, mode, flags);
            break;
        }
        case NR_FCHMODAT2: {
            int dirfd        = va_arg(ap, int);
            const char *path = va_arg(ap, const char *);
            mode_t mode      = (mode_t) va_arg(ap, unsigned long);
            int flags        = va_arg(ap, int);
            va_end(ap);
            ret = handle_fchmodat2(dirfd, path, mode, flags);
            break;
        }
        case NR_OPENAT: {
            int dirfd        = va_arg(ap, int);
            const char *path = va_arg(ap, const char *);
            int flags        = va_arg(ap, int);
            int mode         = va_arg(ap, int);
            va_end(ap);
            if (g_next_active && is_blocked_path(path)) {
                errno = ENOENT;
                return -1;
            }
            ret = next_syscall(NR_OPENAT, dirfd, path, flags, mode);
            break;
        }
        case NR_OPENAT2: {
            int dirfd        = va_arg(ap, int);
            const char *path = va_arg(ap, const char *);
            void *how        = va_arg(ap, void *);
            size_t size      = va_arg(ap, size_t);
            va_end(ap);
            ret = handle_openat2(dirfd, path, how, size);
            break;
        }
        case NR_PREAD64: {
            int fd           = va_arg(ap, int);
            void *buf        = va_arg(ap, void *);
            size_t count     = va_arg(ap, size_t);
            off_t offset     = va_arg(ap, off_t);
            va_end(ap);
            ret = handle_pread64(fd, buf, count, offset);
            break;
        }
        case NR_READLINKAT: {
            int dirfd        = va_arg(ap, int);
            const char *path = va_arg(ap, const char *);
            char *buf        = va_arg(ap, char *);
            size_t bufsiz    = va_arg(ap, size_t);
            va_end(ap);
            if (path && strcmp(path, "/proc/self/fd/") == 0) {
                ret = next_syscall(NR_READLINKAT, dirfd, path,
                                    buf, bufsiz);
                break;
            }
            if (g_next_active && is_blocked_path(path)) {
                errno = ENOENT;
                return -1;
            }
            ret = next_syscall(NR_READLINKAT, dirfd, path, buf, bufsiz);
            break;
        }
        case NR_NEWFSTATAT: {
            int dirfd        = va_arg(ap, int);
            const char *path = va_arg(ap, const char *);
            void *buf        = va_arg(ap, void *);
            int flags        = va_arg(ap, int);
            va_end(ap);
            if (g_next_active && is_blocked_path(path)) {
                errno = ENOENT;
                return -1;
            }
            ret = next_syscall(NR_NEWFSTATAT, dirfd, path, buf, flags);
            break;
        }
        case NR_STATX: {
            int dirfd           = va_arg(ap, int);
            const char *path    = va_arg(ap, const char *);
            int flags           = va_arg(ap, int);
            unsigned int mask   = va_arg(ap, unsigned int);
            struct statx *buf   = va_arg(ap, struct statx *);
            va_end(ap);
            ret = handle_statx(dirfd, path, flags, mask, buf);
            break;
        }
        case NR_GETPPID: {
            va_end(ap);
            ret = handle_getppid();
            break;
        }
        default:
            va_end(ap);
            ret = next_syscall(number, 0, 0, 0, 0, 0, 0);
            break;
    }
    return ret;
}

/* ─────────────────────────────────────────────────────────────
 * JNI entry points
 * ───────────────────────────────────────────────────────────── */

JNIEXPORT jboolean JNICALL
Java_com_shizuposed_manager_stealth_XStealthNativeNext_nativeInit(
        JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;

#if XSTEALTH_ENABLE_MMAP_GUARD
    real_readlink_mmap = (ssize_t (*)(const char *, char *, size_t))
        dlsym(RTLD_NEXT, "readlink");
    capture_libart_backup();
    g_mmap_guard_installed = (g_libart_backup_fd >= 0) ? 1 : 0;
#endif

    install_all_patches();
    LOGI("XStealth Next: initialized (strategy=%s)",
         g_strategy_used ? g_strategy_used : "none");
    return (g_patch_count > 0) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_shizuposed_manager_stealth_XStealthNativeNext_nativeSetActive(
        JNIEnv *env, jclass clazz, jboolean active) {
    (void) env;
    (void) clazz;

    if (active && g_patch_count == 0) {
        install_all_patches();
    }
    if (!active && g_patch_count > 0) {
        uninstall_all_patches();
    }
    g_next_active = active ? 1 : 0;

    if (g_next_active && g_patch_count == 0) {
        LOGW("nativeSetActive(true): no patches applied; Next inactive");
    } else if (g_next_active) {
        LOGI("XStealth Next: active (strategy=%s)",
             g_strategy_used ? g_strategy_used : "unknown");
    } else {
        LOGI("XStealth Next: inactive");
    }
}

JNIEXPORT jboolean JNICALL
Java_com_shizuposed_manager_stealth_XStealthNativeNext_nativeIsActive(
        JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
    return g_next_active ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_com_shizuposed_manager_stealth_XStealthNativeNext_nativeDescribe(
        JNIEnv *env, jclass clazz) {
    (void) clazz;
    char buf[1024];
    int off = snprintf(buf, sizeof(buf),
        "libxstealth_next %s active=%d strategy=%s patches=%d "
        "mmapGuard=%d mmapIntercepted=%d pread64Intercepted=%d "
        "openat2Intercepted=%d ioctlIntercepted=%d "
        "statxIntercepted=%d faccessat2Intercepted=%d "
        "fchmodat2Intercepted=%d getppidIntercepted=%d [",
        NEXT_VERSION,
        g_next_active,
        g_strategy_used ? g_strategy_used : "none",
        g_patch_count,
#if XSTEALTH_ENABLE_MMAP_GUARD
        g_mmap_guard_installed,
        g_mmap_intercepted,
#else
        0, 0,
#endif
        g_pread64_intercepted,
        g_openat2_intercepted,
        g_ioctl_intercepted,
        g_statx_intercepted,
        g_faccessat2_intercepted,
        g_fchmodat2_intercepted,
        g_getppid_intercepted
    );
    for (int i = 0; i < g_patch_count && off < (int)sizeof(buf) - 16; i++) {
        off += snprintf(buf + off, sizeof(buf) - off,
            "%s%s", i ? "," : "", g_patches[i].name);
    }
    snprintf(buf + off, sizeof(buf) - off, "]");
    return (*env)->NewStringUTF(env, buf);
}

JNIEXPORT jboolean JNICALL
Java_com_shizuposed_manager_stealth_XStealthNativeNext_nativeIsEffectivelyActive(
        JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
    return (g_next_active && g_patch_count > 0) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_com_shizuposed_manager_stealth_XStealthNativeNext_nativeGetPatchCount(
        JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
    return g_patch_count;
}

JNIEXPORT jstring JNICALL
Java_com_shizuposed_manager_stealth_XStealthNativeNext_nativeGetStrategy(
        JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
    return (*env)->NewStringUTF(env,
        g_strategy_used ? g_strategy_used : "none");
}

JNIEXPORT jboolean JNICALL
Java_com_shizuposed_manager_stealth_XStealthNativeNext_nativeIsMmapGuardActive(
        JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
#if XSTEALTH_ENABLE_MMAP_GUARD
    return (g_mmap_guard_installed && g_libart_backup_fd >= 0)
        ? JNI_TRUE : JNI_FALSE;
#else
    return JNI_FALSE;
#endif
}

JNIEXPORT jint JNICALL
Java_com_shizuposed_manager_stealth_XStealthNativeNext_nativeGetMmapInterceptedCount(
        JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
#if XSTEALTH_ENABLE_MMAP_GUARD
    return g_mmap_intercepted;
#else
    return 0;
#endif
}

JNIEXPORT jstring JNICALL
Java_com_shizuposed_manager_stealth_XStealthNativeNext_nativeGetMmapGuardInfo(
        JNIEnv *env, jclass clazz) {
    (void) clazz;
    char buf[384];
#if XSTEALTH_ENABLE_MMAP_GUARD
    snprintf(buf, sizeof(buf),
        "enabled=1 installed=%d fd=%d size=%ld path=%s note=%s",
        g_mmap_guard_installed,
        g_libart_backup_fd,
        (long) g_libart_backup_size,
        g_libart_path[0] ? g_libart_path : "(unset)",
        g_mmap_guard_note);
#else
    snprintf(buf, sizeof(buf),
        "enabled=0 installed=0 fd=-1 size=0 path=(unset) "
        "note=compiled out");
#endif
    return (*env)->NewStringUTF(env, buf);
}

JNIEXPORT jint JNICALL
Java_com_shizuposed_manager_stealth_XStealthNativeNext_nativeGetPread64InterceptedCount(
        JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
    return g_pread64_intercepted;
}

JNIEXPORT jint JNICALL
Java_com_shizuposed_manager_stealth_XStealthNativeNext_nativeGetOpenat2InterceptedCount(
        JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
    return g_openat2_intercepted;
}

JNIEXPORT jint JNICALL
Java_com_shizuposed_manager_stealth_XStealthNativeNext_nativeGetIoctlInterceptedCount(
        JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
    return g_ioctl_intercepted;
}

JNIEXPORT jint JNICALL
Java_com_shizuposed_manager_stealth_XStealthNativeNext_nativeGetStatxInterceptedCount(
        JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
    return g_statx_intercepted;
}

JNIEXPORT jint JNICALL
Java_com_shizuposed_manager_stealth_XStealthNativeNext_nativeGetFaccessat2InterceptedCount(
        JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
    return g_faccessat2_intercepted;
}

JNIEXPORT jint JNICALL
Java_com_shizuposed_manager_stealth_XStealthNativeNext_nativeGetFchmodat2InterceptedCount(
        JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
    return g_fchmodat2_intercepted;
}

JNIEXPORT jint JNICALL
Java_com_shizuposed_manager_stealth_XStealthNativeNext_nativeGetGetppidInterceptedCount(
        JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
    return g_getppid_intercepted;
}

/* ─────────────────────────────────────────────────────────────
 * JNI_OnLoad
 * ───────────────────────────────────────────────────────────── */

JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void) vm;
    (void) reserved;

#if XSTEALTH_ENABLE_MMAP_GUARD
    LOGI("JNI_OnLoad: libxstealth_next %s "
         "(runtime scanner + classifier fallback + dispatch + "
         "getppid + mmapGuard)", NEXT_VERSION);
#else
    LOGI("JNI_OnLoad: libxstealth_next %s "
         "(runtime scanner + classifier fallback + dispatch + "
         "getppid, mmapGuard disabled)", NEXT_VERSION);
#endif

    int api = 0;
    void *handle = dlopen("libandroid.so", RTLD_NOLOAD | RTLD_NOW);
    if (handle) {
        int (*get_api)(void) = (int (*)(void))dlsym(handle,
            "android_get_device_api_level");
        if (get_api) api = get_api();
        dlclose(handle);
    }
    if (api > 0) {
        LOGI("JNI_OnLoad: api=%d", api);
    } else {
        LOGI("JNI_OnLoad: api=unknown");
    }
    return JNI_VERSION_1_6;
}