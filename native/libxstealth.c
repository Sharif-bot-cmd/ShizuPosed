/*
 * libxstealth.c — v0.17.0
 *
 * Primary XStealth library. Provides:
 *   • File path blocking (settings XML, sysfs USB state, persistent props)
 *   • /proc path scrubbing (maps, smaps, status, cmdline, /proc/net)
 *   • Netlink enumeration filtering (hides tunnel interfaces)
 *   • ioctl interposition on blocked FDs
 *   • dlsym / ptrace / pread / sysprop / getppid interposition
 *   • Unsafe gate (neutralizes sun.misc.Unsafe)
 *   • libart backup (in-memory copy for pread redirection)
 *   • Stub template capture and verification (Phase 2b hardened)
 *
 * v0.17.0 changes (Phase 2b hardening):
 *   • verify_prologue now extracts every save offset into
 *     reg_save_off[8] (indexed by saved register number), not just
 *     the receiver's. The backends use these to regenerate the
 *     restore sequence at the same offsets the prologue wrote.
 *   • The verified window is now up to 6 words (24 bytes) when
 *     word 5 is a continuation of the save sequence. This covers
 *     prologues that spill x30 as the 6th instruction rather than
 *     the 3rd or 4th.
 *   • prologue_info grew reg_save_off[8] and save_x30_at. Layout
 *     change: backends must be recompiled against this version.
 *   • New xstealth_get_template_info_full returns both the
 *     extracted offsets and a per-register bitmask of which
 *     registers were seen, so the backend can refuse a template
 *     that doesn't cover every register the stub needs to restore.
 *
 * v0.16.0 changes (carried forward):
 *   • capture_stub_template runs the prologue verifier on the
 *     captured bytes. Only verified templates are exposed.
 *   • New xstealth_get_template_info getter.
 *   • StubProbe probe0/probe1 for the interpreter shape.
 *
 * Build:
 *   clang -shared -fPIC -O2 -Wall -Wextra \
 *       -o libxstealth.so libxstealth.c \
 *       -llog -ldl -lpthread
 */

#define _GNU_SOURCE

#include <jni.h>
#include <android/log.h>
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/netlink.h>
#include <linux/openat2.h>
#include <linux/rtnetlink.h>
#include <linux/stat.h>
#include <link.h>
#include <net/if.h>
#include <pthread.h>
#include <stdarg.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <sys/ptrace.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/types.h>
#include <sys/un.h>
#include <unistd.h>

#define LOG_TAG "XStealth"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#define XSTEALTH_VERSION "0.17.0"

#define STUB_TEMPLATE_MAX 64
#define STUB_TEMPLATE_VERIFIED_MAX 32
#define PROLOGUE_MAX_WORDS 6

/* ─────────────────────────────────────────────────────────────
 * Stub template — Phase 2b
 *
 * The template is a byte-for-byte copy of the first N bytes of
 * two probe methods' entry points, where N is the common prefix
 * length. The verifier classifies the prologue and extracts the
 * frame size and every register save offset, which the stub
 * generator in the backends uses to splice the template into a
 * stub without breaking the dispatcher's frame assumptions.
 *
 * Verification result codes:
 *   VERIFY_NOT_ATTEMPTED  — capture hasn't run or failed early
 *   VERIFY_REJECTED       — the bytes don't match a standard
 *                            ARM64 function prologue
 *   VERIFY_VERIFIED       — all words check out
 * ───────────────────────────────────────────────────────────── */

enum {
    VERIFY_NOT_ATTEMPTED = 0,
    VERIFY_REJECTED      = 1,
    VERIFY_VERIFIED      = 2,
};

enum {
    PROLOGUE_SHAPE_UNKNOWN = 0,
    PROLOGUE_SHAPE_STANDARD = 1,
};

/*
 * Extracted prologue metadata.
 *
 * frame_size         — value of the `sub sp, sp, #imm` immediate
 * receiver_offset    — offset from new sp where x0 is saved.
 *                      Phase 2b verifier requires this to be 0,
 *                      because the amiru/shiruposed dispatchers
 *                      read the receiver from [sp].
 * x30_offset         — offset from new sp where x30 is saved,
 *                      or -1 if not saved in the verified window
 * save_words         — number of 4-byte words the verified
 *                      prologue occupies (including BTI)
 * shape              — PROLOGUE_SHAPE_* classification
 * reg_save_off[8]    — offset from new sp where x0..x7 are saved.
 *                      Index is the register number. -1 if that
 *                      register was not saved in the verified
 *                      window.
 * reg_mask           — bitmask: bit N set if xN was saved.
 * save_x30_at        — word index within the prologue where x30
 *                      is spilled, or -1 if not spilled.
 *
 * The backends use reg_save_off to regenerate the restore
 * sequence at the same offsets the prologue wrote. If any
 * register the stub needs to preserve has reg_save_off == -1,
 * the backend refuses the template and falls back to the
 * shipped prologue.
 *
 * Layout must stay in sync with the backends' copies. Version
 * bumped when this changes.
 */
struct prologue_info {
    int32_t frame_size;
    int32_t receiver_offset;
    int32_t x30_offset;
    int32_t save_words;
    int32_t shape;
    int32_t reg_save_off[8];
    int32_t reg_mask;
    int32_t save_x30_at;
    int32_t reserved0;
};

struct stub_template {
    uint8_t  bytes[STUB_TEMPLATE_MAX];
    uint32_t confidence;
    uint32_t verified_len;
    uint32_t flags;
    uint32_t verify;
    struct   prologue_info info;
    char     note[192];
};

static struct stub_template g_stub_template = {
    .bytes = {0},
    .confidence = 0,
    .verified_len = 0,
    .flags = 0,
    .verify = VERIFY_NOT_ATTEMPTED,
    .info = {
        .frame_size = 0,
        .receiver_offset = -1,
        .x30_offset = -1,
        .save_words = 0,
        .shape = PROLOGUE_SHAPE_UNKNOWN,
        .reg_save_off = { -1, -1, -1, -1, -1, -1, -1, -1 },
        .reg_mask = 0,
        .save_x30_at = -1,
        .reserved0 = 0,
    },
    .note = "not attempted",
};

/* ─────────────────────────────────────────────────────────────
 * ARM64 instruction helpers
 * ───────────────────────────────────────────────────────────── */

static inline bool insn_is_bti_c(uint32_t w) {
    return w == 0xd503245fu;
}

static inline bool insn_is_bti(uint32_t w) {
    return (w & 0xfffffc1fu) == 0xd503241fu;
}

/*
 * sub sp, sp, #imm — 0xd10????? with Rn=Rd=31.
 * Mask 0xff8003ff, value 0xd10003ff.
 */
static inline bool insn_is_sub_sp_sp_imm(uint32_t w, uint32_t *imm_out) {
    if ((w & 0xff8003ffu) != 0xd10003ffu) return false;
    if (imm_out) *imm_out = (w >> 10) & 0xfffu;
    return true;
}

/*
 * stp with sp base, 64-bit signed offset. Mask 0xffc00000, value
 * 0xa9000000, Rn == 31.
 */
static inline bool insn_is_stp_sp(uint32_t w, int32_t *off_out,
                                   int32_t *rt_out, int32_t *rt2_out) {
    if ((w & 0xffc00000u) != 0xa9000000u) return false;
    int32_t rn = (w >> 5) & 0x1f;
    if (rn != 31) return false;
    int32_t imm7 = (w >> 15) & 0x7f;
    if (imm7 & 0x40) imm7 |= ~0x7f;   /* sign-extend from 7 bits */
    if (off_out) *off_out = imm7 * 8;
    if (rt_out)  *rt_out  = w & 0x1f;
    if (rt2_out) *rt2_out = (w >> 10) & 0x1f;
    return true;
}

/*
 * str (64-bit) with sp base, unsigned offset. Mask 0xffc00000,
 * value 0xf9000000, Rn == 31.
 */
static inline bool insn_is_str_sp(uint32_t w, int32_t *off_out,
                                   int32_t *rt_out) {
    if ((w & 0xffc00000u) != 0xf9000000u) return false;
    int32_t rn = (w >> 5) & 0x1f;
    if (rn != 31) return false;
    int32_t imm12 = (w >> 10) & 0xfff;
    if (off_out) *off_out = imm12 * 8;
    if (rt_out)  *rt_out  = w & 0x1f;
    return true;
}

/* ─────────────────────────────────────────────────────────────
 * verify_prologue — Phase 2b
 *
 * Runs the prologue verifier on the captured template bytes.
 * Returns a VERIFY_* code and fills info_out on success.
 *
 * The verifier requires:
 *   word 0: BTI C
 *   word 1: sub sp, sp, #imm with imm in [0x20, 0x200]
 *   word 2: stp with sp base, first saved register (Rt) must be
 *           x0 at offset 0. This is the receiver-save position
 *           the dispatchers assume.
 *   words 3..5: at least one more save instruction; up to three
 *           more, stopping at the first non-save word.
 *
 * On success, save_words is set to the number of words the
 * verified prologue occupies (3 to PROLOGUE_MAX_WORDS), and
 * reg_save_off[] is populated for every register the prologue
 * saved. Registers not saved in the verified window have
 * reg_save_off == -1.
 *
 * The caller (the stub generator in libamiru.c) is responsible
 * for checking that every register it needs to restore has a
 * valid offset. If any is missing, the backend falls back to the
 * shipped prologue rather than emit a stub with an incomplete
 * restore sequence.
 * ───────────────────────────────────────────────────────────── */

static void info_reset(struct prologue_info *info) {
    info->frame_size = 0;
    info->receiver_offset = -1;
    info->x30_offset = -1;
    info->save_words = 0;
    info->shape = PROLOGUE_SHAPE_UNKNOWN;
    for (int i = 0; i < 8; i++) info->reg_save_off[i] = -1;
    info->reg_mask = 0;
    info->save_x30_at = -1;
    info->reserved0 = 0;
}

static int verify_prologue(const uint8_t *bytes, uint32_t len,
                            struct prologue_info *info_out) {
    if (bytes == NULL || info_out == NULL) {
        return VERIFY_REJECTED;
    }
    if (len < 5 * 4) {
        /* Not enough bytes to verify five instructions. */
        return VERIFY_REJECTED;
    }

    info_reset(info_out);

    uint32_t w[PROLOGUE_MAX_WORDS];
    int max_words = (int) (len / 4);
    if (max_words > PROLOGUE_MAX_WORDS) max_words = PROLOGUE_MAX_WORDS;
    for (int i = 0; i < max_words; i++) {
        memcpy(&w[i], bytes + i * 4, 4);
    }

    /* Word 0: BTI C exactly. */
    if (!insn_is_bti_c(w[0])) {
        return VERIFY_REJECTED;
    }

    /* Word 1: sub sp, sp, #imm. */
    uint32_t imm12 = 0;
    if (!insn_is_sub_sp_sp_imm(w[1], &imm12)) {
        return VERIFY_REJECTED;
    }
    if (imm12 < 0x20 || imm12 > 0x200) {
        return VERIFY_REJECTED;
    }
    info_out->frame_size = (int32_t) imm12;

    /* Word 2: stp with sp base, Rt must be x0 at offset 0. */
    int32_t w2_off = 0, w2_rt = -1, w2_rt2 = -1;
    if (!insn_is_stp_sp(w[2], &w2_off, &w2_rt, &w2_rt2)) {
        return VERIFY_REJECTED;
    }
    if (w2_rt != 0 || w2_off != 0) {
        return VERIFY_REJECTED;
    }

    info_out->reg_save_off[w2_rt] = w2_off;
    info_out->reg_mask |= (1 << w2_rt);
    if (w2_rt2 >= 0 && w2_rt2 < 8) {
        info_out->reg_save_off[w2_rt2] = w2_off + 8;
        info_out->reg_mask |= (1 << w2_rt2);
    }
    if (w2_rt2 == 30) {
        info_out->x30_offset = w2_off + 8;
        info_out->save_x30_at = 2;
    }

    /* Words 3..5: continue the save sequence. Stop at the first
     * non-save word. Each word must be either stp [sp, #off] or
     * str [sp, #off]. */
    int words_used = 3;
    for (int i = 3; i < max_words; i++) {
        int32_t off = 0, rt = -1, rt2 = -1;
        if (insn_is_stp_sp(w[i], &off, &rt, &rt2)) {
            if (rt >= 0 && rt < 8) {
                info_out->reg_save_off[rt] = off;
                info_out->reg_mask |= (1 << rt);
            }
            if (rt2 >= 0 && rt2 < 8) {
                info_out->reg_save_off[rt2] = off + 8;
                info_out->reg_mask |= (1 << rt2);
            }
            if (rt2 == 30) {
                info_out->x30_offset = off + 8;
                info_out->save_x30_at = i;
            }
            words_used = i + 1;
        } else if (insn_is_str_sp(w[i], &off, &rt)) {
            if (rt >= 0 && rt < 8) {
                info_out->reg_save_off[rt] = off;
                info_out->reg_mask |= (1 << rt);
            }
            if (rt == 30) {
                info_out->x30_offset = off;
                info_out->save_x30_at = i;
            }
            words_used = i + 1;
        } else {
            /* Non-save instruction. Stop. */
            break;
        }
    }

    /* Receiver must be at offset 0 — this is the constraint the
     * amiru and shizuposed dispatchers rely on. We already
     * checked w2_rt == 0 and w2_off == 0 above, so this is a
     * tautology, but keep it for future-proofing. */
    if (info_out->reg_save_off[0] != 0) {
        return VERIFY_REJECTED;
    }

    info_out->save_words = words_used;
    info_out->shape = PROLOGUE_SHAPE_STANDARD;

    return VERIFY_VERIFIED;
}

/* ─────────────────────────────────────────────────────────────
 * find_entry_offset
 * ───────────────────────────────────────────────────────────── */

static int find_entry_offset(uintptr_t method_ptr) {
    if (method_ptr == 0) return -1;

    for (int off = 0; off < 0x40; off += 8) {
        uintptr_t candidate = *(uintptr_t *)(method_ptr + off);
        if (candidate < 0x1000) continue;

        FILE *fp = fopen("/proc/self/maps", "re");
        if (fp == NULL) break;

        char line[512];
        int is_exec = 0;
        while (fgets(line, sizeof(line), fp) != NULL) {
            unsigned long lo = 0, hi = 0;
            char perms[8] = {0};
            if (sscanf(line, "%lx-%lx %7s", &lo, &hi, perms) != 3) {
                continue;
            }
            if ((unsigned long) candidate < lo ||
                (unsigned long) candidate >= hi) {
                continue;
            }
            is_exec = (perms[2] == 'x');
            break;
        }
        fclose(fp);

        if (is_exec) return off;
    }
    return -1;
}

/* ─────────────────────────────────────────────────────────────
 * Hidden strings and paths
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

static const char *HIDDEN_INTERFACE_PATTERNS[] = {
    "tun",
    "tap",
    "ppp",
    "wg",
    "vpn",
    NULL
};

static const char *BLOCKED_PATHS[] = {
    "/data/system/users/0/settings_global.xml",
    "/data/system/users/0/settings_secure.xml",
    "/data/system/users/0/settings_system.xml",
    "/data/system/user/0/settings_global.xml",
    "/data/system/user/0/settings_secure.xml",
    "/data/system/user/0/settings_system.xml",
    "/data/system/users/0/accessibility_settings.xml",
    "/data/system/users/0/accessibility_enabled.xml",
    "/data/system/user/0/accessibility_settings.xml",
    "/data/system/user/0/accessibility_enabled.xml",
    "/data/property/persistent_properties",
    "/sys/class/android_usb/android0/state",
    "/sys/class/android_usb/android0/enable",
    "/sys/class/android_usb/android0/functions",
    "/sys/class/android_usb/android0/online",
    "/sys/class/android_usb/android0/bDeviceClass",
    "/sys/class/android_usb/android0/bDeviceSubClass",
    "/sys/class/android_usb/android0/bDeviceProtocol",
    "/sys/class/android_usb/android0/idProduct",
    "/sys/class/android_usb/android0/idVendor",
    "/sys/class/android_usb/android0/f_adb/state",
    "/sys/class/android_usb/android0/f_adb/adb_enable",
    "/sys/class/android_usb/android0/f_adb/write",
    "/sys/class/android_usb/android1/state",
    "/sys/class/android_usb/android1/enable",
    "/sys/class/android_usb/android1/functions",
    "/config/usb_gadget/g1/state",
    "/config/usb_gadget/g1/idVendor",
    "/config/usb_gadget/g1/idProduct",
    NULL
};

enum scrub_mode {
    SCRUB_LINE = 0,
    SCRUB_ENVIRON,
    SCRUB_CMDLINE,
    SCRUB_STATUS,
    SCRUB_NET_UNIX,
    SCRUB_NET_INET,
    SCRUB_SMAPS,
    SCRUB_STAT,
};

struct proc_path_entry {
    int min_api;
    int max_api;
    const char *path;
    int mode;
};

static const struct proc_path_entry PROC_PATHS_TO_SCRUB[] = {
    { 29, 0, "/proc/self/maps",         SCRUB_LINE },
    { 29, 0, "/proc/self/smaps",        SCRUB_SMAPS },
    { 29, 0, "/proc/self/smaps_rollup", SCRUB_SMAPS },
    { 29, 0, "/proc/self/numa_maps",    SCRUB_LINE },
    { 29, 0, "/proc/self/status",       SCRUB_STATUS },
    { 29, 0, "/proc/self/stat",         SCRUB_STAT },
    { 29, 0, "/proc/self/mountinfo",    SCRUB_LINE },
    { 29, 0, "/proc/self/task",         SCRUB_LINE },
    { 29, 0, "/proc/self/exe",          SCRUB_LINE },
    { 29, 0, "/proc/self/environ",      SCRUB_LINE },
    { 29, 0, "/proc/self/cmdline",      SCRUB_CMDLINE },
    { 29, 0, "/proc/net/unix",          SCRUB_NET_UNIX },
    { 29, 0, "/proc/net/tcp",           SCRUB_NET_INET },
    { 29, 0, "/proc/net/tcp6",          SCRUB_NET_INET },
    { 29, 0, "/proc/net/udp",           SCRUB_NET_INET },
    { 29, 0, "/proc/net/udp6",          SCRUB_NET_INET },
    { 0, 0, NULL, 0 }
};

static const char SCRUBBED_GLOBAL_XML[] =
    "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n"
    "<settings version='200'>\n"
    "  <setting id='1' name='development_settings_enabled' value='0' package='android' />\n"
    "  <setting id='2' name='adb_enabled' value='0' package='android' />\n"
    "  <setting id='3' name='adb_wifi_enabled' value='0' package='android' />\n"
    "</settings>\n";

static const char SCRUBBED_SECURE_XML[] =
    "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n"
    "<settings version='200'>\n"
    "  <setting id='1' name='development_settings_enabled' value='0' package='android' />\n"
    "  <setting id='2' name='adb_enabled' value='0' package='android' />\n"
    "</settings>\n";

static const char SCRUBBED_SYSTEM_XML[] =
    "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n"
    "<settings version='200'>\n"
    "</settings>\n";

static const struct {
    const char *key;
    const char *value;
} WATCHED_PROPERTIES[] = {
    { "ro.debuggable",              "0" },
    { "ro.secure",                  "1" },
    { "ro.adb.secure",              "1" },
    { "ro.build.type",              "user" },
    { "ro.build.tags",              "release-keys" },
    { "ro.build.selinux",           "1" },
    { "ro.boot.verifiedbootstate",  "green" },
    { "ro.boot.flash.locked",       "1" },
    { "ro.boot.veritymode",         "enforcing" },
    { "ro.boot.warranty_bit",       "0" },
    { "ro.warranty_bit",            "0" },
    { "ro.boot.secureboot",         "1" },
    { "ro.boot.dm_verity",          "enforcing" },
    { "ro.kernel.qemu",             "0" },
    { "sys.usb.adb.disabled",       "1" },
    { "sys.usb.config",             "mtp" },
    { "sys.usb.state",              "mtp" },
    { "sys.usb.configfs",           "0" },
    { "sys.usb.controller",         "" },
    { "persist.sys.usb.config",         "mtp" },
    { "persist.sys.usb.qmmi.func",      "mtp" },
    { "persist.sys.usb.reboot.config",  "mtp" },
    { "persist.sys.usb.reboot.func",    "mtp" },
    { "persist.vendor.usb.config",      "mtp" },
    { "vendor.usb.config",              "mtp" },
    { "service.adb.root",           "0" },
    { "service.adb.tcp.port",       "-1" },
    { "persist.adb.tcp.port",       "-1" },
    { "sys.oem_unlock_allowed",     "0" },
    { "ro.oem_unlock_supported",    "0" },
    { "ro.bootmode",                "normal" },
    { "ro.boot.mode",               "normal" },
    { NULL, NULL }
};

static const char *lookup_watched_property(const char *name) {
    if (!name) return NULL;
    for (int i = 0; WATCHED_PROPERTIES[i].key; i++) {
        if (strcmp(name, WATCHED_PROPERTIES[i].key) == 0) {
            return WATCHED_PROPERTIES[i].value;
        }
    }
    return NULL;
}

static const char *TRUSTED_PREFIXES[] = {
    "de.robv.android.xposed.",
    "com.shizuposed.manager.",
    NULL
};

static volatile int g_xstealth_active = 0;
static volatile int g_resolved = 0;
static pthread_mutex_t g_init_lock = PTHREAD_MUTEX_INITIALIZER;

static int (*real_open)(const char *, int, ...) = NULL;
static int (*real_openat)(int, const char *, int, ...) = NULL;
static int (*real_openat2)(int, const char *, struct open_how *, size_t) = NULL;
static FILE *(*real_fopen)(const char *, const char *) = NULL;
static int (*real_access)(const char *, int) = NULL;
static int (*real_faccessat)(int, const char *, int, int) = NULL;
static int (*real_faccessat2)(int, const char *, int, int) = NULL;
static int (*real_fchmodat2)(int, const char *, mode_t, int) = NULL;
static int (*real_stat)(const char *, struct stat *) = NULL;
static int (*real_lstat)(const char *, struct stat *) = NULL;
static int (*real_fstatat)(int, const char *, struct stat *, int) = NULL;
static int (*real_statx)(int, const char *, int, unsigned int, struct statx *) = NULL;
static ssize_t (*real_readlink)(const char *, char *, size_t) = NULL;
static ssize_t (*real_read)(int, void *, size_t) = NULL;
static int (*real_execve)(const char *, char *const[], char *const[]) = NULL;
static int (*real_posix_spawn)(pid_t *, const char *,
                                void *, void *,
                                char *const[], char *const[]) = NULL;
static int (*real_posix_spawnp)(pid_t *, const char *,
                                 void *, void *,
                                 char *const[], char *const[]) = NULL;
static FILE *(*real_popen)(const char *, const char *) = NULL;
static int (*real_system)(const char *) = NULL;
static ssize_t (*real_pread)(int, void *, size_t, off_t) = NULL;
static ssize_t (*real_pread64)(int, void *, size_t, off64_t) = NULL;
static void *(*real_dlsym)(void *, const char *) = NULL;
static long (*real_ptrace)(int, ...) = NULL;
static int (*real_system_property_get)(const char *, char *) = NULL;
static pid_t (*real_getppid)(void) = NULL;
static int (*real_ioctl)(int, unsigned long, ...) = NULL;
static ssize_t (*real_sendmsg)(int, const struct msghdr *, int) = NULL;
static ssize_t (*real_recvmsg)(int, struct msghdr *, int) = NULL;
static int (*real_getsockname)(int, struct sockaddr *, socklen_t *) = NULL;

static volatile int g_key_symbols_resolved = 0;
static volatile int g_symbols_resolved = 0;

static volatile int g_unsafe_gate_installed = 0;
static volatile int g_unsafe_gate_field_neutralized = 0;
static volatile int g_unsafe_gate_method_patched = 0;
static char g_unsafe_gate_note[128] = "not attempted";

static volatile int g_settings_xml_blocked = 0;
static volatile int g_sysfs_blocked = 0;
static volatile int g_dlsym_intercepted = 0;
static volatile int g_ptrace_intercepted = 0;
static volatile int g_pread_intercepted = 0;
static volatile int g_sysprop_intercepted = 0;
static volatile int g_getppid_intercepted = 0;
static volatile int g_openat2_blocked = 0;
static volatile int g_ioctl_blocked = 0;
static volatile int g_netlink_intercepted = 0;
static volatile int g_netlink_messages_dropped = 0;

static JavaVM *g_vm = NULL;

static int g_libart_backup_fd = -1;
static size_t g_libart_backup_size = 0;
static char g_libart_path[512] = {0};

static bool is_trusted_caller(void);
static bool is_protected_library(const char *path);
static bool is_blocked_path(const char *path);

static bool is_trusted_caller(void) {
    if (g_vm == NULL) return true;

    JNIEnv *env = NULL;
    jint rc = (*g_vm)->GetEnv(g_vm, (void **) &env, JNI_VERSION_1_6);
    if (rc != JNI_OK || env == NULL) return true;

    char comm_path[64];
    snprintf(comm_path, sizeof(comm_path),
             "/proc/self/task/%d/comm", (int) gettid());

    int fd = open(comm_path, O_RDONLY);
    if (fd < 0) return true;

    char comm[64] = {0};
    ssize_t n = read(fd, comm, sizeof(comm) - 1);
    close(fd);

    if (n <= 0) return true;

    for (ssize_t i = 0; i < n; i++) {
        if (comm[i] == '\n') { comm[i] = '\0'; break; }
    }

    for (int i = 0; TRUSTED_PREFIXES[i]; i++) {
        if (strstr(comm, TRUSTED_PREFIXES[i]) != NULL) return true;
    }
    if (strstr(comm, "ShizuPosed") != NULL) return true;
    if (strstr(comm, "Xposed") != NULL) return true;

    return false;
}

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

static bool is_protected_library(const char *path) {
    if (!path) return false;
    return strstr(path, "libart.so") != NULL
        || strstr(path, "libartbase.so") != NULL;
}

static void capture_libart_backup(void) {
    if (g_libart_backup_fd >= 0) return;

    char path[512] = {0};
    dl_iterate_phdr(find_libart_callback, path);
    if (path[0] == '\0') {
        LOGW("libart backup: libart not found");
        return;
    }

    strncpy(g_libart_path, path, sizeof(g_libart_path) - 1);
    g_libart_path[sizeof(g_libart_path) - 1] = '\0';

    int src_fd = open(path, O_RDONLY | O_CLOEXEC);
    if (src_fd < 0) {
        LOGW("libart backup: open(%s) failed: %s", path, strerror(errno));
        return;
    }

    struct stat st;
    if (fstat(src_fd, &st) != 0 || st.st_size <= 0) {
        LOGW("libart backup: fstat failed");
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
        LOGW("libart backup: could not create backup fd");
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
                LOGW("libart backup: write failed");
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
        LOGW("libart backup: copy failed");
        close(memfd);
        return;
    }

    g_libart_backup_fd = memfd;
    g_libart_backup_size = (size_t) total;

    LOGI("libart backup: captured %ld bytes of %s into fd %d",
         (long) total, g_libart_path, memfd);
}

/* ─────────────────────────────────────────────────────────────
 * capture_stub_template — Phase 2b
 * ───────────────────────────────────────────────────────────── */

static void capture_stub_template(JNIEnv *env) {
    if (env == NULL) {
        snprintf(g_stub_template.note, sizeof(g_stub_template.note),
                 "null env");
        return;
    }

    jclass probe_class = (*env)->FindClass(env,
        "com/shizuposed/manager/stealth/StubProbe");
    if (probe_class == NULL) {
        (*env)->ExceptionClear(env);
        snprintf(g_stub_template.note, sizeof(g_stub_template.note),
                 "StubProbe class not found");
        LOGW("stub template: StubProbe class not found — "
             "template capture skipped");
        return;
    }

    jmethodID m0 = (*env)->GetStaticMethodID(env, probe_class,
                                             "probe0", "()V");
    if (m0 == NULL) {
        (*env)->ExceptionClear(env);
        snprintf(g_stub_template.note, sizeof(g_stub_template.note),
                 "probe0 not found");
        LOGW("stub template: probe0 not resolvable");
        return;
    }

    jmethodID m1 = (*env)->GetStaticMethodID(env, probe_class,
                                             "probe1", "()I");
    if (m1 == NULL) {
        (*env)->ExceptionClear(env);
        snprintf(g_stub_template.note, sizeof(g_stub_template.note),
                 "probe1 not found");
        LOGW("stub template: probe1 not resolvable");
        return;
    }

    uintptr_t am0 = (uintptr_t) m0;
    uintptr_t am1 = (uintptr_t) m1;

    int off0 = find_entry_offset(am0);
    int off1 = find_entry_offset(am1);
    if (off0 < 0 || off1 < 0) {
        snprintf(g_stub_template.note, sizeof(g_stub_template.note),
                 "entry offset not found (off0=%d off1=%d)",
                 off0, off1);
        LOGW("stub template: could not locate entry-point offset "
             "(off0=%d off1=%d)", off0, off1);
        return;
    }
    if (off0 != off1) {
        snprintf(g_stub_template.note, sizeof(g_stub_template.note),
                 "entry offset mismatch (%d vs %d)", off0, off1);
        LOGW("stub template: entry offset mismatch (%d vs %d)",
             off0, off1);
        return;
    }

    uintptr_t ep0 = *(uintptr_t *)(am0 + off0);
    uintptr_t ep1 = *(uintptr_t *)(am1 + off1);
    if (ep0 < 0x1000 || ep1 < 0x1000) {
        snprintf(g_stub_template.note, sizeof(g_stub_template.note),
                 "entry points look invalid");
        LOGW("stub template: entry points look invalid "
             "(ep0=%p ep1=%p)", (void *)ep0, (void *)ep1);
        return;
    }

    uint8_t buf0[STUB_TEMPLATE_MAX];
    uint8_t buf1[STUB_TEMPLATE_MAX];
    memcpy(buf0, (void *)ep0, STUB_TEMPLATE_MAX);
    memcpy(buf1, (void *)ep1, STUB_TEMPLATE_MAX);

    uint32_t common = 0;
    for (uint32_t i = 0; i < STUB_TEMPLATE_MAX; i++) {
        if (buf0[i] != buf1[i]) break;
        common = i + 1;
    }

    if (common < 8) {
        snprintf(g_stub_template.note, sizeof(g_stub_template.note),
                 "probes disagree (common=%u)", common);
        LOGW("stub template: probes disagree on prologue "
             "(common=%u bytes). Template not captured.", common);
        return;
    }

    memcpy(g_stub_template.bytes, buf0, STUB_TEMPLATE_MAX);
    g_stub_template.confidence = common;
    g_stub_template.flags = 1;

    struct prologue_info info;
    info_reset(&info);
    int verify = verify_prologue(buf0, common, &info);

    g_stub_template.verify = (uint32_t) verify;
    g_stub_template.info = info;

    if (verify == VERIFY_VERIFIED) {
        uint32_t vlen = (uint32_t) info.save_words * 4;
        if (vlen > common) vlen = common;
        if (vlen > STUB_TEMPLATE_VERIFIED_MAX) vlen = STUB_TEMPLATE_VERIFIED_MAX;
        g_stub_template.verified_len = vlen;

        snprintf(g_stub_template.note, sizeof(g_stub_template.note),
                 "verified: %u bytes, frame=0x%x, save_words=%d, "
                 "x30_off=%d, reg_mask=0x%02x",
                 vlen, info.frame_size, info.save_words,
                 info.x30_offset, info.reg_mask);

        LOGI("stub template: verified %u bytes "
             "(confidence=%u, frame=0x%x, save_words=%d, "
             "x30_off=%d, reg_mask=0x%02x)",
             vlen, common, info.frame_size, info.save_words,
             info.x30_offset, info.reg_mask);
    } else {
        g_stub_template.verified_len = 0;
        snprintf(g_stub_template.note, sizeof(g_stub_template.note),
                 "captured %u bytes but verification rejected "
                 "(w0=%08x w1=%08x w2=%08x)",
                 common,
                 *(uint32_t *)(buf0 + 0),
                 *(uint32_t *)(buf0 + 4),
                 *(uint32_t *)(buf0 + 8));
        LOGW("stub template: captured %u bytes but prologue "
             "verifier rejected. Falling back to shipped prologue.",
             common);
    }
}

/* ─────────────────────────────────────────────────────────────
 * Stub template getters
 * ───────────────────────────────────────────────────────────── */

__attribute__((visibility("default")))
int xstealth_get_stub_template(const uint8_t **out_bytes,
                                uint32_t *out_len) {
    if (out_bytes == NULL || out_len == NULL) return 0;
    if ((g_stub_template.flags & 1) == 0) return 0;
    if (g_stub_template.verify != VERIFY_VERIFIED) return 0;
    if (g_stub_template.verified_len == 0) return 0;

    *out_bytes = g_stub_template.bytes;
    *out_len = g_stub_template.verified_len;
    return 1;
}

__attribute__((visibility("default")))
int xstealth_get_template_info(struct prologue_info *out_info) {
    if (out_info == NULL) return 0;
    if (g_stub_template.verify != VERIFY_VERIFIED) return 0;
    *out_info = g_stub_template.info;
    return 1;
}

/*
 * xstealth_get_template_info_full — Phase 2b hardened
 *
 * Returns the full verification result, including a per-register
 * bitmask of which registers were seen in the verified window.
 * The backend uses this to refuse a template whose save window
 * doesn't cover every register the stub's restore sequence
 * needs to write.
 *
 * Returns 1 if the info was filled, 0 if no verified template
 * exists. out_mask receives reg_mask if the pointer is non-null.
 */
__attribute__((visibility("default")))
int xstealth_get_template_info_full(struct prologue_info *out_info,
                                     uint32_t *out_mask) {
    if (out_info == NULL) return 0;
    if (g_stub_template.verify != VERIFY_VERIFIED) return 0;
    *out_info = g_stub_template.info;
    if (out_mask != NULL) *out_mask = (uint32_t) g_stub_template.info.reg_mask;
    return 1;
}

__attribute__((visibility("default")))
int xstealth_get_template_verify_status(char *note_out, size_t note_len) {
    if (note_out == NULL || note_len == 0) return 0;
    snprintf(note_out, note_len, "%s", g_stub_template.note);
    return (int) g_stub_template.verify;
}

/* ─────────────────────────────────────────────────────────────
 * Symbol resolution
 * ───────────────────────────────────────────────────────────── */

static void *try_resolve(const char *canonical, const char **aliases) {
    for (int i = 0; aliases[i]; i++) {
        void *p = dlsym(RTLD_DEFAULT, aliases[i]);
        if (p) {
            LOGI("resolved %s via %s at %p", canonical, aliases[i], p);
            g_symbols_resolved++;
            return p;
        }
    }
    LOGW("could not resolve %s from any known alias", canonical);
    return NULL;
}

static void resolve_real_symbols(void) {
    pthread_mutex_lock(&g_init_lock);
    if (g_resolved) {
        pthread_mutex_unlock(&g_init_lock);
        return;
    }

    static const char *aliases_open[]        = { "open", "__open", NULL };
    static const char *aliases_openat[]      = { "openat", "__openat", NULL };
    static const char *aliases_openat2[]     = { "openat2", "__openat2", NULL };
    static const char *aliases_fopen[]       = { "fopen", "__fopen", NULL };
    static const char *aliases_access[]      = { "access", "__access", NULL };
    static const char *aliases_faccessat[]   = { "faccessat", "__faccessat", NULL };
    static const char *aliases_faccessat2[]  = { "faccessat2", NULL };
    static const char *aliases_fchmodat2[]   = { "fchmodat2", NULL };
    static const char *aliases_stat[]        = { "stat", "__stat", NULL };
    static const char *aliases_lstat[]       = { "lstat", "__lstat", NULL };
    static const char *aliases_fstatat[]     = { "fstatat", "__fstatat", NULL };
    static const char *aliases_statx[]       = { "statx", "__statx", NULL };
    static const char *aliases_readlink[]    = { "readlink", "__readlink", NULL };
    static const char *aliases_read[]        = { "read", "__read", NULL };
    static const char *aliases_execve[]      = { "execve", "__execve", NULL };
    static const char *aliases_posix_spawn[] = { "posix_spawn", "__posix_spawn", NULL };
    static const char *aliases_posix_spawnp[]= { "posix_spawnp", "__posix_spawnp", NULL };
    static const char *aliases_popen[]       = { "popen", "__popen", NULL };
    static const char *aliases_system[]      = { "system", "__system", NULL };
    static const char *aliases_pread[]       = { "pread", "__pread", NULL };
    static const char *aliases_pread64[]     = { "pread64", "__pread64", NULL };
    static const char *aliases_ptrace[]      = { "ptrace", "__ptrace", NULL };
    static const char *aliases_getppid[]     = { "getppid", "__getppid", NULL };
    static const char *aliases_ioctl[]       = { "ioctl", "__ioctl", NULL };
    static const char *aliases_sendmsg[]     = { "sendmsg", "__sendmsg", NULL };
    static const char *aliases_recvmsg[]     = { "recvmsg", "__recvmsg", NULL };
    static const char *aliases_getsockname[] = { "getsockname", "__getsockname", NULL };

    real_open         = (void *)try_resolve("open",        aliases_open);
    real_openat       = (void *)try_resolve("openat",      aliases_openat);
    real_openat2      = (void *)try_resolve("openat2",     aliases_openat2);
    real_fopen        = (void *)try_resolve("fopen",       aliases_fopen);
    real_access       = (void *)try_resolve("access",      aliases_access);
    real_faccessat    = (void *)try_resolve("faccessat",   aliases_faccessat);
    real_faccessat2   = (void *)try_resolve("faccessat2",  aliases_faccessat2);
    real_fchmodat2    = (void *)try_resolve("fchmodat2",   aliases_fchmodat2);
    real_stat         = (void *)try_resolve("stat",        aliases_stat);
    real_lstat        = (void *)try_resolve("lstat",       aliases_lstat);
    real_fstatat      = (void *)try_resolve("fstatat",     aliases_fstatat);
    real_statx        = (void *)try_resolve("statx",       aliases_statx);
    real_readlink     = (void *)try_resolve("readlink",    aliases_readlink);
    real_read         = (void *)try_resolve("read",        aliases_read);
    real_execve       = (void *)try_resolve("execve",      aliases_execve);
    real_posix_spawn  = (void *)try_resolve("posix_spawn", aliases_posix_spawn);
    real_posix_spawnp = (void *)try_resolve("posix_spawnp",aliases_posix_spawnp);
    real_popen        = (void *)try_resolve("popen",       aliases_popen);
    real_system       = (void *)try_resolve("system",      aliases_system);
    real_pread        = (void *)try_resolve("pread",       aliases_pread);
    real_pread64      = (void *)try_resolve("pread64",     aliases_pread64);
    real_ptrace       = (void *)try_resolve("ptrace",      aliases_ptrace);
    real_getppid      = (void *)try_resolve("getppid",     aliases_getppid);
    real_ioctl        = (void *)try_resolve("ioctl",       aliases_ioctl);
    real_sendmsg      = (void *)try_resolve("sendmsg",     aliases_sendmsg);
    real_recvmsg      = (void *)try_resolve("recvmsg",     aliases_recvmsg);
    real_getsockname  = (void *)try_resolve("getsockname",  aliases_getsockname);

    if (real_open)     g_key_symbols_resolved++;
    if (real_stat)     g_key_symbols_resolved++;
    if (real_fstatat)  g_key_symbols_resolved++;
    if (real_read)     g_key_symbols_resolved++;
    if (real_readlink) g_key_symbols_resolved++;

    g_resolved = 1;
    LOGI("XStealth native: initialized (%d/5 key symbols resolved, "
         "%d total, openat2=%s statx=%s ioctl=%s netlink=%s)",
         g_key_symbols_resolved, g_symbols_resolved,
         real_openat2 ? "yes" : "no",
         real_statx ? "yes" : "no",
         real_ioctl ? "yes" : "no",
         (real_sendmsg && real_recvmsg) ? "yes" : "no");
    pthread_mutex_unlock(&g_init_lock);
}

/* ─────────────────────────────────────────────────────────────
 * Path classification
 * ───────────────────────────────────────────────────────────── */

static bool is_hidden_path(const char *path) {
    if (!path) return false;
    for (int i = 0; HIDDEN_STRINGS[i]; i++) {
        if (strstr(path, HIDDEN_STRINGS[i])) return true;
    }
    return false;
}

static bool is_hidden_interface_name(const char *name) {
    if (!name) return false;
    if (is_hidden_path(name)) return true;
    for (int i = 0; HIDDEN_INTERFACE_PATTERNS[i]; i++) {
        if (strncmp(name, HIDDEN_INTERFACE_PATTERNS[i],
                    strlen(HIDDEN_INTERFACE_PATTERNS[i])) == 0) {
            return true;
        }
    }
    return false;
}

static bool is_blocked_path(const char *path) {
    if (!path) return false;

    if (is_hidden_path(path)) return true;

    for (int i = 0; BLOCKED_PATHS[i]; i++) {
        if (strcmp(path, BLOCKED_PATHS[i]) == 0) {
            if (strncmp(path, "/sys/", 5) == 0
                    || strncmp(path, "/config/", 8) == 0) {
                __sync_fetch_and_add(&g_sysfs_blocked, 1);
            } else {
                __sync_fetch_and_add(&g_settings_xml_blocked, 1);
            }
            return true;
        }
    }

    if (strncmp(path, "/proc/", 6) == 0) {
        const char *root = strstr(path, "/root/");
        if (root != NULL) {
            const char *suffix = root + 6;
            for (int i = 0; BLOCKED_PATHS[i]; i++) {
                if (strcmp(suffix, BLOCKED_PATHS[i]) == 0) {
                    if (strncmp(suffix, "/sys/", 5) == 0
                            || strncmp(suffix, "/config/", 8) == 0) {
                        __sync_fetch_and_add(&g_sysfs_blocked, 1);
                    } else {
                        __sync_fetch_and_add(&g_settings_xml_blocked, 1);
                    }
                    return true;
                }
            }
        }
    }

    return false;
}

static const struct proc_path_entry *find_proc_entry(const char *path) {
    if (!path) return NULL;
    for (int i = 0; PROC_PATHS_TO_SCRUB[i].path; i++) {
        if (strcmp(path, PROC_PATHS_TO_SCRUB[i].path) == 0) {
            return &PROC_PATHS_TO_SCRUB[i];
        }
    }
    return NULL;
}

static bool command_is_watched(const char *cmd) {
    if (!cmd) return false;
    for (int i = 0; BLOCKED_PATHS[i]; i++) {
        if (strstr(cmd, BLOCKED_PATHS[i])) return true;
    }
    static const char *keys[] = {
        "adb_enabled", "adb_wifi_enabled",
        "development_settings_enabled", NULL
    };
    static const char *verbs[] = {
        "cat", "grep", "head", "tail", "sed", "awk", NULL
    };
    bool has_key = false;
    for (int i = 0; keys[i]; i++) {
        if (strstr(cmd, keys[i])) { has_key = true; break; }
    }
    if (!has_key) return false;
    for (int i = 0; verbs[i]; i++) {
        if (strstr(cmd, verbs[i])) return true;
    }
    return false;
}

static const char *scrubbed_for_command(const char *cmd) {
    if (cmd && strstr(cmd, "settings_secure.xml")) return SCRUBBED_SECURE_XML;
    if (cmd && strstr(cmd, "settings_system.xml")) return SCRUBBED_SYSTEM_XML;
    return SCRUBBED_GLOBAL_XML;
}

/* ─────────────────────────────────────────────────────────────
 * Buffer scrubbers
 * ───────────────────────────────────────────────────────────── */

static void scrub_buffer_linewise(char *buf, ssize_t n) {
    char *p = buf;
    char *end = buf + n;
    while (p < end) {
        char *nl = memchr(p, '\n', end - p);
        size_t linelen = nl ? (size_t)(nl - p) + 1 : (size_t)(end - p);
        bool hidden = false;
        for (int i = 0; HIDDEN_STRINGS[i]; i++) {
            if (memmem(p, linelen, HIDDEN_STRINGS[i],
                       strlen(HIDDEN_STRINGS[i]))) {
                hidden = true;
                break;
            }
        }
        if (hidden) {
            memset(p, ' ', linelen);
            if (nl) *nl = '\n';
        }
        if (!nl) break;
        p = nl + 1;
    }
}

static void scrub_buffer_maps(char *buf, ssize_t n) {
    if (n <= 0) return;

    char *p = buf;
    char *end = buf + n;
    while (p < end) {
        char *nl = memchr(p, '\n', end - p);
        size_t linelen = nl ? (size_t)(nl - p) + 1 : (size_t)(end - p);

        bool matched = false;
        for (int i = 0; HIDDEN_STRINGS[i]; i++) {
            if (memmem(p, linelen, HIDDEN_STRINGS[i],
                       strlen(HIDDEN_STRINGS[i]))) {
                matched = true;
                break;
            }
        }

        if (matched) {
            const char *q = p;
            const char *line_end = p + linelen;
            int field = 0;
            const char *path_start = NULL;
            while (q < line_end && field < 6) {
                while (q < line_end && (*q == ' ' || *q == '\t')) q++;
                if (q >= line_end) break;
                if (field == 5) {
                    const char *r = q;
                    while (r < line_end && (*r == ' ' || *r == '\t')) r++;
                    if (r < line_end && *r == '/') path_start = r;
                    break;
                }
                while (q < line_end && *q != ' ' && *q != '\t' &&
                       *q != '\n') q++;
                field++;
            }

            if (path_start != NULL) {
                size_t path_len = nl ? (size_t)(nl - path_start)
                                     : (size_t)(line_end - path_start);
                static const char filler[] = "/dev/zero";
                size_t filler_len = sizeof(filler) - 1;
                if (path_len >= filler_len) {
                    memcpy((char *) path_start, filler, filler_len);
                    memset((char *) path_start + filler_len, ' ',
                           path_len - filler_len);
                } else {
                    memset((char *) path_start, ' ', path_len);
                }
            } else {
                memset(p, ' ', linelen);
                if (nl) *nl = '\n';
            }
        }

        if (!nl) break;
        p = nl + 1;
    }
}

static void scrub_buffer_net_inet(char *buf, ssize_t n) {
    if (n <= 0) return;

    char *p = buf;
    char *end = buf + n;
    bool first_line = true;

    while (p < end) {
        char *nl = memchr(p, '\n', end - p);
        size_t linelen = nl ? (size_t)(nl - p) + 1 : (size_t)(end - p);

        if (first_line) {
            first_line = false;
            if (!nl) break;
            p = nl + 1;
            continue;
        }

        bool drop = false;

        int field = 0;
        const char *q = p;
        const char *line_end = p + linelen;
        while (q < line_end && field < 8) {
            while (q < line_end && (*q == ' ' || *q == '\t')) q++;
            if (q >= line_end) break;
            const char *start = q;
            while (q < line_end && *q != ' ' && *q != '\t' && *q != '\n') q++;
            field++;
            if (field == 8) {
                long uid = 0;
                bool numeric = true;
                for (const char *r = start; r < q; r++) {
                    if (*r < '0' || *r > '9') { numeric = false; break; }
                    uid = uid * 10 + (*r - '0');
                }
                if (numeric && uid == 2000) drop = true;
                break;
            }
        }

        if (!drop) {
            for (int i = 0; HIDDEN_STRINGS[i]; i++) {
                if (memmem(p, linelen, HIDDEN_STRINGS[i],
                           strlen(HIDDEN_STRINGS[i]))) {
                    drop = true;
                    break;
                }
            }
        }

        if (drop) {
            memset(p, ' ', linelen);
            if (nl) *nl = '\n';
        }

        if (!nl) break;
        p = nl + 1;
    }
}

static void scrub_buffer_smaps(char *buf, ssize_t n) {
    if (n <= 0) return;

    char *p = buf;
    char *end = buf + n;
    while (p < end) {
        char *nl = memchr(p, '\n', end - p);
        size_t linelen = nl ? (size_t)(nl - p) + 1 : (size_t)(end - p);

        bool looks_like_header =
            (linelen >= 5 &&
             ((p[0] >= '0' && p[0] <= '9') ||
              (p[0] >= 'a' && p[0] <= 'f')));

        if (looks_like_header) {
            for (int i = 0; HIDDEN_STRINGS[i]; i++) {
                if (memmem(p, linelen, HIDDEN_STRINGS[i],
                           strlen(HIDDEN_STRINGS[i]))) {
                    char *slash = memchr(p, '/', linelen);
                    if (slash && slash < p + linelen) {
                        size_t path_len = nl
                            ? (size_t)(nl - slash)
                            : (size_t)(end - slash);
                        static const char filler[] = "/dev/zero";
                        size_t filler_len = sizeof(filler) - 1;
                        if (path_len >= filler_len) {
                            memcpy(slash, filler, filler_len);
                            memset(slash + filler_len, ' ',
                                   path_len - filler_len);
                        } else {
                            memset(slash, ' ', path_len);
                        }
                    }
                    break;
                }
            }
        }

        if (!nl) break;
        p = nl + 1;
    }
}

static void scrub_buffer_environ(char *buf, ssize_t n) {
    if (n <= 0) return;

    char *p = buf;
    char *end = buf + n;
    while (p < end) {
        char *nul = memchr(p, '\0', end - p);
        size_t len = nul ? (size_t)(nul - p) : (size_t)(end - p);
        bool hidden = false;
        for (int i = 0; HIDDEN_STRINGS[i]; i++) {
            if (memmem(p, len, HIDDEN_STRINGS[i],
                       strlen(HIDDEN_STRINGS[i]))) {
                hidden = true;
                break;
            }
        }
        /* Also scrub any entry starting with shizuposed. */
        if (len >= 11 && memcmp(p, "shizuposed.", 11) == 0) {
            hidden = true;
        }
        if (hidden) {
            memset(p, ' ', len);
            if (nul) *nul = '\0';
        }
        if (!nul) break;
        p = nul + 1;
    }
}

static void scrub_buffer_cmdline(char *buf, ssize_t n) {
    if (n <= 0) return;

    char *first_nul = memchr(buf, '\0', n);
    if (!first_nul) {
        scrub_buffer_linewise(buf, n);
        return;
    }

    size_t first_len = (size_t)(first_nul - buf);
    bool first_hidden = false;
    for (int i = 0; HIDDEN_STRINGS[i]; i++) {
        if (memmem(buf, first_len, HIDDEN_STRINGS[i],
                   strlen(HIDDEN_STRINGS[i]))) {
            first_hidden = true;
            break;
        }
    }
    if (first_hidden) {
        memset(buf, ' ', first_len);
        *first_nul = '\0';
    }

    char *p = first_nul + 1;
    char *end = buf + n;
    while (p < end) {
        char *next_nul = memchr(p, '\0', end - p);
        size_t len = next_nul ? (size_t)(next_nul - p) : (size_t)(end - p);
        bool hidden = false;
        for (int i = 0; HIDDEN_STRINGS[i]; i++) {
            if (memmem(p, len, HIDDEN_STRINGS[i],
                       strlen(HIDDEN_STRINGS[i]))) {
                hidden = true;
                break;
            }
        }
        if (!hidden) {
            if ((len >= 2 && memcmp(p, "-D", 2) == 0) ||
                (len >= 2 && memcmp(p, "-X", 2) == 0)) {
                hidden = true;
            }
        }
        if (hidden) {
            memset(p, ' ', len);
        }
        if (!next_nul) break;
        p = next_nul + 1;
    }
}

static void scrub_ppid_line(char *buf, ssize_t n) {
    char *p = buf;
    char *end = buf + n;
    while (p < end) {
        char *nl = memchr(p, '\n', end - p);
        size_t linelen = nl ? (size_t)(nl - p) + 1 : (size_t)(end - p);

        if (linelen >= 5 && memcmp(p, "PPid:", 5) == 0) {
            memset(p, ' ', linelen);
            memcpy(p, "PPid:\t1", 7);
            if (nl) *nl = '\n';
        }

        if (!nl) break;
        p = nl + 1;
    }
}

static void scrub_buffer_stat(char *buf, ssize_t n) {
    if (n <= 0) return;

    char *p = buf;
    char *end = buf + n;

    /* Field 1: pid — skip to first space. */
    while (p < end && *p != ' ' && *p != '\n') p++;
    if (p >= end || *p != ' ') return;
    p++;   /* past the space */

    /* Field 2: comm — starts with '(', ends with ')'. */
    if (p >= end || *p != '(') return;
    char *close = memchr(p, ')', (size_t)(end - p));
    if (close == NULL) return;
    p = close + 1;

    /* Field 3: state — one char. */
    if (p >= end || *p != ' ') return;
    p++;
    if (p >= end) return;
    /* state is a single character, possibly followed by '+' or
     * similar modifiers in newer kernels. Skip to the next
     * space. */
    while (p < end && *p != ' ' && *p != '\n') p++;
    if (p >= end || *p != ' ') return;
    p++;

    /* Field 4: ppid — this is what we rewrite. */
    char *ppid_start = p;
    while (p < end && *p != ' ' && *p != '\n') p++;
    size_t ppid_len = (size_t)(p - ppid_start);
    if (ppid_len == 0) return;

    /* Rewrite: write "1" and pad the rest with spaces so the
     * total line length is unchanged. */
    if (ppid_len >= 1) {
        ppid_start[0] = '1';
        for (size_t i = 1; i < ppid_len; i++) {
            ppid_start[i] = ' ';
        }
    }
}

static void scrub_buffer_net_unix(char *buf, ssize_t n) {
    if (n <= 0) return;

    char *p = buf;
    char *end = buf + n;
    bool first_line = true;

    while (p < end) {
        char *nl = memchr(p, '\n', end - p);
        size_t linelen = nl ? (size_t)(nl - p) + 1 : (size_t)(end - p);

        if (first_line) {
            first_line = false;
            if (!nl) break;
            p = nl + 1;
            continue;
        }

        if (memmem(p, linelen, "adb", 3) != NULL) {
            memset(p, ' ', linelen);
            if (nl) *nl = '\n';
        }

        if (!nl) break;
        p = nl + 1;
    }
}

/* ─────────────────────────────────────────────────────────────
 * Netlink filtering
 * ───────────────────────────────────────────────────────────── */

#define MAX_NETLINK_FDS 8
#define NETLINK_HOLD_MAX (256 * 1024)

struct netlink_hold {
    int   fd;
    void *buf;
    size_t len;
};

static struct netlink_hold g_netlink_holds[MAX_NETLINK_FDS];
static pthread_mutex_t g_netlink_lock = PTHREAD_MUTEX_INITIALIZER;

static struct netlink_hold *find_hold_locked(int fd) {
    for (int i = 0; i < MAX_NETLINK_FDS; i++) {
        if (g_netlink_holds[i].fd == fd) return &g_netlink_holds[i];
    }
    return NULL;
}

static struct netlink_hold *alloc_hold_locked(int fd) {
    for (int i = 0; i < MAX_NETLINK_FDS; i++) {
        if (g_netlink_holds[i].fd == fd) return &g_netlink_holds[i];
    }
    for (int i = 0; i < MAX_NETLINK_FDS; i++) {
        if (g_netlink_holds[i].fd == -1) {
            g_netlink_holds[i].fd = fd;
            g_netlink_holds[i].buf = NULL;
            g_netlink_holds[i].len = 0;
            return &g_netlink_holds[i];
        }
    }
    if (g_netlink_holds[0].buf) free(g_netlink_holds[0].buf);
    g_netlink_holds[0].fd = fd;
    g_netlink_holds[0].buf = NULL;
    g_netlink_holds[0].len = 0;
    return &g_netlink_holds[0];
}

static void release_hold_locked(struct netlink_hold *h) {
    if (!h) return;
    if (h->buf) {
        free(h->buf);
        h->buf = NULL;
    }
    h->len = 0;
    h->fd = -1;
}

static bool should_drop_netlink_message(const struct nlmsghdr *nlh) {
    if (nlh->nlmsg_type == RTM_NEWLINK) {
        if (nlh->nlmsg_len < NLMSG_LENGTH(sizeof(struct ifinfomsg))) {
            return false;
        }
        const struct ifinfomsg *ifi =
            (const struct ifinfomsg *) NLMSG_DATA(nlh);

        int attrlen = nlh->nlmsg_len
            - NLMSG_LENGTH(sizeof(struct ifinfomsg));
        const struct rtattr *rta =
            (const struct rtattr *) ((const char *) ifi
                + NLMSG_ALIGN(sizeof(struct ifinfomsg)));

        for (; RTA_OK(rta, attrlen); rta = RTA_NEXT(rta, attrlen)) {
            if (rta->rta_type == IFLA_IFNAME) {
                const char *name = (const char *) RTA_DATA(rta);
                if (is_hidden_interface_name(name)) {
                    LOGI("netlink: dropping RTM_NEWLINK for %s", name);
                    return true;
                }
            }
        }
        return false;
    }

    if (nlh->nlmsg_type == RTM_NEWADDR) {
        if (nlh->nlmsg_len < NLMSG_LENGTH(sizeof(struct ifaddrmsg))) {
            return false;
        }
        const struct ifaddrmsg *ifa =
            (const struct ifaddrmsg *) NLMSG_DATA(nlh);

        int attrlen = nlh->nlmsg_len
            - NLMSG_LENGTH(sizeof(struct ifaddrmsg));
        const struct rtattr *rta =
            (const struct rtattr *) ((const char *) ifa
                + NLMSG_ALIGN(sizeof(struct ifaddrmsg)));

        for (; RTA_OK(rta, attrlen); rta = RTA_NEXT(rta, attrlen)) {
            if (rta->rta_type == IFA_LABEL) {
                const char *label = (const char *) RTA_DATA(rta);
                if (is_hidden_interface_name(label)) {
                    LOGI("netlink: dropping RTM_NEWADDR for %s", label);
                    return true;
                }
            }
        }
        return false;
    }

    if (nlh->nlmsg_type == RTM_NEWNEIGH) {
        return false;
    }

    return false;
}

static size_t filter_netlink_buffer(void *buf, size_t len) {
    uint8_t *p = (uint8_t *) buf;
    uint8_t *end = p + len;
    uint8_t *out = p;

    bool dropped_link = false;

    while (p + NLMSG_HDRLEN <= end) {
        struct nlmsghdr *nlh = (struct nlmsghdr *) p;
        if (nlh->nlmsg_len < NLMSG_HDRLEN) break;
        if (p + NLMSG_ALIGN(nlh->nlmsg_len) > end) break;

        bool drop = should_drop_netlink_message(nlh);
        if (drop && nlh->nlmsg_type == RTM_NEWLINK) {
            dropped_link = true;
        }

        if (!drop) {
            if (out != p) {
                memmove(out, p, NLMSG_ALIGN(nlh->nlmsg_len));
            }
            out += NLMSG_ALIGN(nlh->nlmsg_len);
        } else {
            __sync_fetch_and_add(&g_netlink_messages_dropped, 1);
        }

        p += NLMSG_ALIGN(nlh->nlmsg_len);
    }

    size_t new_len = (size_t)(out - (uint8_t *) buf);

    if (new_len == 0 && dropped_link) {
        struct nlmsghdr *done = (struct nlmsghdr *) buf;
        done->nlmsg_len = NLMSG_HDRLEN;
        done->nlmsg_type = NLMSG_DONE;
        done->nlmsg_flags = 0;
        done->nlmsg_seq = 0;
        done->nlmsg_pid = 0;
        new_len = NLMSG_HDRLEN;
    }

    return new_len;
}

/* ─────────────────────────────────────────────────────────────
 * libc interposers
 * ───────────────────────────────────────────────────────────── */

int open(const char *path, int flags, ...) {
    if (g_xstealth_active && is_blocked_path(path)) {
        errno = ENOENT;
        return -1;
    }
    if (!real_open) resolve_real_symbols();
    if (!real_open) { errno = ENOENT; return -1; }
    mode_t mode = 0;
    if (flags & O_CREAT) {
        va_list ap; va_start(ap, flags);
        mode = va_arg(ap, mode_t);
        va_end(ap);
    }
    return real_open(path, flags, mode);
}

int openat(int dirfd, const char *path, int flags, ...) {
    if (g_xstealth_active && is_blocked_path(path)) {
        errno = ENOENT;
        return -1;
    }
    if (!real_openat) resolve_real_symbols();
    if (!real_openat) { errno = ENOENT; return -1; }
    mode_t mode = 0;
    if (flags & O_CREAT) {
        va_list ap; va_start(ap, flags);
        mode = va_arg(ap, mode_t);
        va_end(ap);
    }
    return real_openat(dirfd, path, flags, mode);
}

int openat2(int dirfd, const char *path, struct open_how *how, size_t size) {
    if (g_xstealth_active && is_blocked_path(path)) {
        __sync_fetch_and_add(&g_openat2_blocked, 1);
        LOGI("openat2 blocked: %s", path);
        errno = ENOENT;
        return -1;
    }

    if (!real_openat2) {
        uint64_t flags = how ? how->flags : O_RDONLY;
        uint64_t mode  = how ? how->mode  : 0;
        if (!real_openat) resolve_real_symbols();
        if (!real_openat) {
            errno = ENOSYS;
            return -1;
        }
        return real_openat(dirfd, path, (int) flags, (mode_t) mode);
    }

    return real_openat2(dirfd, path, how, size);
}

FILE *fopen(const char *path, const char *mode) {
    if (g_xstealth_active && is_blocked_path(path)) {
        errno = ENOENT;
        return NULL;
    }
    if (!real_fopen) resolve_real_symbols();
    if (!real_fopen) { errno = ENOENT; return NULL; }
    return real_fopen(path, mode);
}

int access(const char *path, int mode) {
    if (g_xstealth_active && is_blocked_path(path)) {
        errno = ENOENT;
        return -1;
    }
    if (!real_access) resolve_real_symbols();
    if (!real_access) { errno = ENOENT; return -1; }
    return real_access(path, mode);
}

int faccessat(int dirfd, const char *path, int mode, int flags) {
    if (g_xstealth_active && is_blocked_path(path)) {
        errno = ENOENT;
        return -1;
    }
    if (!real_faccessat) resolve_real_symbols();
    if (!real_faccessat) { errno = ENOENT; return -1; }
    return real_faccessat(dirfd, path, mode, flags);
}

int faccessat2(int dirfd, const char *path, int mode, int flags) {
    if (g_xstealth_active && is_blocked_path(path)) {
        errno = ENOENT;
        return -1;
    }
    if (!real_faccessat2) {
        if (!real_faccessat) resolve_real_symbols();
        if (real_faccessat) {
            return real_faccessat(dirfd, path, mode, flags);
        }
        errno = ENOSYS;
        return -1;
    }
    return real_faccessat2(dirfd, path, mode, flags);
}

int fchmodat2(int dirfd, const char *path, mode_t mode, int flags) {
    if (g_xstealth_active && is_blocked_path(path)) {
        errno = ENOENT;
        return -1;
    }
    if (!real_fchmodat2) {
        errno = ENOSYS;
        return -1;
    }
    return real_fchmodat2(dirfd, path, mode, flags);
}

int stat(const char *path, struct stat *buf) {
    if (g_xstealth_active && is_blocked_path(path)) {
        errno = ENOENT;
        return -1;
    }
    if (!real_stat) resolve_real_symbols();
    if (!real_stat) { errno = ENOENT; return -1; }
    return real_stat(path, buf);
}

int lstat(const char *path, struct stat *buf) {
    if (g_xstealth_active && is_blocked_path(path)) {
        errno = ENOENT;
        return -1;
    }
    if (!real_lstat) resolve_real_symbols();
    if (!real_lstat) { errno = ENOENT; return -1; }
    return real_lstat(path, buf);
}

int fstatat(int dirfd, const char *path, struct stat *buf, int flags) {
    if (g_xstealth_active && is_blocked_path(path)) {
        errno = ENOENT;
        return -1;
    }
    if (!real_fstatat) resolve_real_symbols();
    if (!real_fstatat) { errno = ENOENT; return -1; }
    return real_fstatat(dirfd, path, buf, flags);
}

int statx(int dirfd, const char *path, int flags,
          unsigned int mask, struct statx *buf) {
    if (g_xstealth_active && is_blocked_path(path)) {
        errno = ENOENT;
        return -1;
    }
    if (!real_statx) {
        struct stat st;
        int r = fstatat(dirfd, path, &st, flags);
        if (r != 0) return r;
        if (buf) {
            memset(buf, 0, sizeof(*buf));
            buf->stx_mode = st.st_mode;
            buf->stx_size = st.st_size;
            buf->stx_uid = st.st_uid;
            buf->stx_gid = st.st_gid;
            buf->stx_ino = st.st_ino;
            buf->stx_blocks = st.st_blocks;
        }
        return 0;
    }
    return real_statx(dirfd, path, flags, mask, buf);
}

ssize_t readlink(const char *path, char *buf, size_t len) {
    if (g_xstealth_active && is_blocked_path(path)) {
        errno = ENOENT;
        return -1;
    }
    if (!real_readlink) resolve_real_symbols();
    if (!real_readlink) { errno = ENOENT; return -1; }

    if (g_xstealth_active && !is_trusted_caller()
            && path != NULL
            && strcmp(path, "/proc/self/fd/3") == 0) {
        /* Answer with a synthetic socket:[N] form. The exact N
         * doesn't matter; the *shape* matters. */
        static const char k_fd3_answer[] = "socket:[12345]";
        size_t n = sizeof(k_fd3_answer) - 1;
        if (len < n) {
            errno = ERANGE;
            return -1;
        }
        memcpy(buf, k_fd3_answer, n);
        return (ssize_t) n;
    }

    return real_readlink(path, buf, len);
}

ssize_t read(int fd, void *buf, size_t count) {
    if (!real_read) resolve_real_symbols();
    if (!real_read) { errno = EBADF; return -1; }

    ssize_t n = real_read(fd, buf, count);
    if (!g_xstealth_active || n <= 0) return n;

    char path[64];
    snprintf(path, sizeof(path), "/proc/self/fd/%d", fd);
    char target[256];
    ssize_t rl = real_readlink
        ? real_readlink(path, target, sizeof(target) - 1) : -1;
    if (rl <= 0) return n;
    target[rl] = '\0';

    for (int i = 0; BLOCKED_PATHS[i]; i++) {
        if (strcmp(target, BLOCKED_PATHS[i]) == 0) {
            const char *scrub = SCRUBBED_GLOBAL_XML;
            if (strstr(target, "settings_secure.xml")) {
                scrub = SCRUBBED_SECURE_XML;
            } else if (strstr(target, "settings_system.xml")) {
                scrub = SCRUBBED_SYSTEM_XML;
            } else if (strstr(target, "persistent_properties")) {
                scrub = "";
            } else if (strncmp(target, "/sys/", 5) == 0
                    || strncmp(target, "/config/", 8) == 0) {
                return 0;
            }
            size_t slen = strlen(scrub);
            size_t copy = slen < count ? slen : count;
            if (copy > 0) memcpy(buf, scrub, copy);
            __sync_fetch_and_add(&g_settings_xml_blocked, 1);
            return (ssize_t)copy;
        }
    }

    const struct proc_path_entry *entry = find_proc_entry(target);
    if (!entry) return n;

    switch (entry->mode) {
        case SCRUB_ENVIRON:
            scrub_buffer_environ((char *)buf, n);
            break;
        case SCRUB_CMDLINE:
            scrub_buffer_cmdline((char *)buf, n);
            break;
        case SCRUB_STATUS:
            scrub_buffer_linewise((char *)buf, n);
            scrub_ppid_line((char *)buf, n);
            break;
        case SCRUB_STAT:
            scrub_buffer_stat((char *)buf, n);
            break;
        case SCRUB_NET_UNIX:
            scrub_buffer_net_unix((char *)buf, n);
            break;
        case SCRUB_NET_INET:
            scrub_buffer_net_inet((char *)buf, n);
            break;
        case SCRUB_SMAPS:
            scrub_buffer_smaps((char *)buf, n);
            break;
        case SCRUB_LINE:
        default:
            if (strcmp(target, "/proc/self/maps") == 0) {
                scrub_buffer_maps((char *)buf, n);
            } else {
                scrub_buffer_linewise((char *)buf, n);
            }
            break;
    }
    return n;
}

/* ─────────────────────────────────────────────────────────────
 * ioctl interposer
 * ───────────────────────────────────────────────────────────── */

static bool fd_is_blocked_path(int fd) {
    if (fd < 0) return false;
    if (real_readlink == NULL) {
        if (!real_readlink) resolve_real_symbols();
        if (real_readlink == NULL) return false;
    }

    char fd_path[64];
    snprintf(fd_path, sizeof(fd_path), "/proc/self/fd/%d", fd);
    char target[512] = {0};
    ssize_t n = real_readlink(fd_path, target, sizeof(target) - 1);
    if (n <= 0) return false;
    target[n] = '\0';

    for (int i = 0; BLOCKED_PATHS[i]; i++) {
        if (strcmp(target, BLOCKED_PATHS[i]) == 0) return true;
    }
    return false;
}

__attribute__((overloadable))
int ioctl(int fd, unsigned long request, ...) {
    if (real_ioctl == NULL) {
        real_ioctl = (int (*)(int, unsigned long, ...))
            dlsym(RTLD_NEXT, "ioctl");
        if (real_ioctl == NULL) {
            errno = ENOSYS;
            return -1;
        }
    }

    va_list ap;
    va_start(ap, request);
    void *arg = va_arg(ap, void *);
    va_end(ap);

    if (g_xstealth_active && fd_is_blocked_path(fd)) {
        __sync_fetch_and_add(&g_ioctl_blocked, 1);
        LOGI("ioctl blocked on fd %d (request 0x%lx)", fd, request);
        errno = ENOENT;
        return -1;
    }

    return real_ioctl(fd, request, arg);
}

/* ─────────────────────────────────────────────────────────────
 * sendmsg / recvmsg interposers
 * ───────────────────────────────────────────────────────────── */

static bool fd_is_netlink(int fd) {
    int domain = 0;
    socklen_t len = sizeof(domain);
    if (getsockopt(fd, SOL_SOCKET, SO_DOMAIN, &domain, &len) != 0) {
        return false;
    }
    return domain == AF_NETLINK;
}

ssize_t recvmsg(int fd, struct msghdr *msg, int flags) {
    if (real_recvmsg == NULL) {
        real_recvmsg = (ssize_t (*)(int, struct msghdr *, int))
            dlsym(RTLD_NEXT, "recvmsg");
        if (real_recvmsg == NULL) {
            errno = ENOSYS;
            return -1;
        }
    }

    if (!g_xstealth_active || msg == NULL) {
        return real_recvmsg(fd, msg, flags);
    }

    pthread_mutex_lock(&g_netlink_lock);
    struct netlink_hold *h = find_hold_locked(fd);
    if (h != NULL && h->len > 0) {
        size_t avail = 0;
        for (size_t i = 0; i < msg->msg_iovlen; i++) {
            avail += msg->msg_iov[i].iov_len;
        }
        size_t to_copy = h->len < avail ? h->len : avail;
        size_t copied = 0;
        for (size_t i = 0; i < msg->msg_iovlen && copied < to_copy; i++) {
            size_t chunk = msg->msg_iov[i].iov_len;
            if (chunk > to_copy - copied) chunk = to_copy - copied;
            memcpy(msg->msg_iov[i].iov_base,
                   (char *) h->buf + copied, chunk);
            copied += chunk;
        }
        release_hold_locked(h);
        pthread_mutex_unlock(&g_netlink_lock);
        return (ssize_t) copied;
    }
    pthread_mutex_unlock(&g_netlink_lock);

    if (!fd_is_netlink(fd)) {
        return real_recvmsg(fd, msg, flags);
    }

    struct iovec temp_iov;
    uint8_t *temp = malloc(NETLINK_HOLD_MAX);
    if (temp == NULL) {
        return real_recvmsg(fd, msg, flags);
    }
    temp_iov.iov_base = temp;
    temp_iov.iov_len = NETLINK_HOLD_MAX;

    struct msghdr temp_msg = *msg;
    temp_msg.msg_iov = &temp_iov;
    temp_msg.msg_iovlen = 1;
    struct iovec saved_iov = *msg->msg_iov;
    temp_msg.msg_name = msg->msg_name;
    temp_msg.msg_namelen = msg->msg_namelen;
    temp_msg.msg_control = msg->msg_control;
    temp_msg.msg_controllen = msg->msg_controllen;

    ssize_t n = real_recvmsg(fd, &temp_msg, flags);
    if (n <= 0) {
        free(temp);
        msg->msg_namelen = temp_msg.msg_namelen;
        msg->msg_controllen = temp_msg.msg_controllen;
        msg->msg_flags = temp_msg.msg_flags;
        return n;
    }

    size_t filtered = filter_netlink_buffer(temp, (size_t) n);

    size_t copied = 0;
    for (size_t i = 0; i < msg->msg_iovlen && copied < filtered; i++) {
        size_t chunk = msg->msg_iov[i].iov_len;
        if (chunk > filtered - copied) chunk = filtered - copied;
        memcpy(msg->msg_iov[i].iov_base, temp + copied, chunk);
        copied += chunk;
    }
    (void) saved_iov;

    msg->msg_namelen = temp_msg.msg_namelen;
    msg->msg_controllen = temp_msg.msg_controllen;
    msg->msg_flags = temp_msg.msg_flags;

    free(temp);

    __sync_fetch_and_add(&g_netlink_intercepted, 1);
    LOGI("netlink: recvmsg filtered %zd -> %zu bytes on fd %d",
         n, filtered, fd);
    return (ssize_t) filtered;
}

ssize_t sendmsg(int fd, const struct msghdr *msg, int flags) {
    if (real_sendmsg == NULL) {
        real_sendmsg = (ssize_t (*)(int, const struct msghdr *, int))
            dlsym(RTLD_NEXT, "sendmsg");
        if (real_sendmsg == NULL) {
            errno = ENOSYS;
            return -1;
        }
    }
    return real_sendmsg(fd, msg, flags);
}

int getsockname(int fd, struct sockaddr *addr, socklen_t *len) {
    if (real_getsockname == NULL) {
        real_getsockname = (int (*)(int, struct sockaddr *, socklen_t *))
            dlsym(RTLD_NEXT, "getsockname");
        if (real_getsockname == NULL) { errno = ENOSYS; return -1; }
    }

    if (g_xstealth_active && !is_trusted_caller() && fd == 3) {

        if (addr == NULL || len == NULL) {
            errno = EINVAL;
            return -1;
        }
        memset(addr, 0, sizeof(struct sockaddr_un) < *len
                          ? sizeof(struct sockaddr_un) : *len);
        addr->sa_family = AF_UNIX;
        *len = (socklen_t) sizeof(struct sockaddr_un);
        return 0;
    }

    return real_getsockname(fd, addr, len);
}

static int fd_is_blocked_settings(int fd) {
    if (fd < 0) return -1;
    if (real_readlink == NULL) {
        resolve_real_symbols();
        if (real_readlink == NULL) return -1;
    }

    char fd_path[64];
    snprintf(fd_path, sizeof(fd_path), "/proc/self/fd/%d", fd);
    char target[512] = {0};
    ssize_t n = real_readlink(fd_path, target, sizeof(target) - 1);
    if (n <= 0) return -1;
    target[n] = '\0';

    for (int i = 0; BLOCKED_PATHS[i]; i++) {
        if (strcmp(target, BLOCKED_PATHS[i]) == 0) return i;
    }
    return -1;
}

/*
 * scrubbed_content_for_path — returns the synthetic document the
 * read interposer would serve for this path, or NULL if the path
 * has no scrubbed variant (e.g. sysfs entries, which are returned
 * as ENOENT by the open path and never reach a read).
 */
static const char *scrubbed_content_for_path(const char *target) {
    if (target == NULL) return NULL;
    if (strstr(target, "settings_secure.xml")) return SCRUBBED_SECURE_XML;
    if (strstr(target, "settings_system.xml")) return SCRUBBED_SYSTEM_XML;
    if (strstr(target, "settings_global.xml")) return SCRUBBED_GLOBAL_XML;
    if (strstr(target, "persistent_properties")) return "";
    return NULL;
}

static bool fd_is_protected_library(int fd) {
    if (fd < 0 || real_readlink == NULL) return false;

    char fd_path[64];
    snprintf(fd_path, sizeof(fd_path), "/proc/self/fd/%d", fd);
    char target[512] = {0};
    ssize_t n = real_readlink(fd_path, target, sizeof(target) - 1);
    if (n <= 0) return false;
    target[n] = '\0';

    return is_protected_library(target);
}

ssize_t pread(int fd, void *buf, size_t count, off_t offset) {
    if (!real_pread) resolve_real_symbols();

    /* Settings/blocked-path reads: serve scrubbed content at offset.
     * Only applies to untrusted callers — the framework's own reads
     * are the ones the open interposer allowed through. */
    if (g_xstealth_active && !is_trusted_caller()) {
        int blocked = fd_is_blocked_settings(fd);
        if (blocked >= 0) {
            char fd_path[64];
            snprintf(fd_path, sizeof(fd_path), "/proc/self/fd/%d", fd);
            char target[512] = {0};
            ssize_t rl = real_readlink
                ? real_readlink(fd_path, target, sizeof(target) - 1) : -1;
            if (rl > 0) {
                target[rl] = '\0';
                const char *content = scrubbed_content_for_path(target);
                if (content != NULL) {
                    size_t clen = strlen(content);
                    if ((size_t) offset >= clen) return 0;
                    size_t avail = clen - (size_t) offset;
                    size_t copy = avail < count ? avail : count;
                    memcpy(buf, content + offset, copy);
                    __sync_fetch_and_add(&g_settings_xml_blocked, 1);
                    return (ssize_t) copy;
                }
                /* sysfs / config paths: open was blocked, so this fd
                 * shouldn't exist. Treat as end-of-file. */
                return 0;
            }
        }
    }

    /* Existing libart redirection. */
    if (!g_xstealth_active
            || g_libart_backup_fd < 0
            || is_trusted_caller()
            || !fd_is_protected_library(fd)) {
        if (!real_pread) { errno = ENOSYS; return -1; }
        return real_pread(fd, buf, count, offset);
    }

    __sync_fetch_and_add(&g_pread_intercepted, 1);
    LOGI("pread: redirecting fd %d read (offset=%ld, count=%zu) to backup",
         fd, (long) offset, count);

    if (real_pread) {
        return real_pread(g_libart_backup_fd, buf, count, offset);
    }
    return (ssize_t) syscall(SYS_pread64, g_libart_backup_fd,
                              buf, count, offset);
}

ssize_t pread64(int fd, void *buf, size_t count, off64_t offset) {
    if (!real_pread64) {
        real_pread64 = (void *) dlsym(RTLD_NEXT, "pread64");
    }

    /* Settings/blocked-path reads: serve scrubbed content at offset. */
    if (g_xstealth_active && !is_trusted_caller()) {
        int blocked = fd_is_blocked_settings(fd);
        if (blocked >= 0) {
            char fd_path[64];
            snprintf(fd_path, sizeof(fd_path), "/proc/self/fd/%d", fd);
            char target[512] = {0};
            ssize_t rl = real_readlink
                ? real_readlink(fd_path, target, sizeof(target) - 1) : -1;
            if (rl > 0) {
                target[rl] = '\0';
                const char *content = scrubbed_content_for_path(target);
                if (content != NULL) {
                    size_t clen = strlen(content);
                    if ((size_t) offset >= clen) return 0;
                    size_t avail = clen - (size_t) offset;
                    size_t copy = avail < count ? avail : count;
                    memcpy(buf, content + offset, copy);
                    __sync_fetch_and_add(&g_settings_xml_blocked, 1);
                    return (ssize_t) copy;
                }
                return 0;
            }
        }
    }

    /* Existing libart redirection. */
    if (!g_xstealth_active
            || g_libart_backup_fd < 0
            || is_trusted_caller()
            || !fd_is_protected_library(fd)) {
        if (real_pread64) {
            return real_pread64(fd, buf, count, offset);
        }
        if (real_pread) {
            return real_pread(fd, buf, count, (off_t) offset);
        }
        return (ssize_t) syscall(SYS_pread64, fd, buf, count, offset);
    }

    __sync_fetch_and_add(&g_pread_intercepted, 1);
    LOGI("pread64: redirecting fd %d read (offset=%ld, count=%zu) "
         "to backup", fd, (long) offset, count);

    if (real_pread64) {
        return real_pread64(g_libart_backup_fd, buf, count, offset);
    }
    if (real_pread) {
        return real_pread(g_libart_backup_fd, buf, count, (off_t) offset);
    }
    return (ssize_t) syscall(SYS_pread64, g_libart_backup_fd,
                              buf, count, offset);
}

/* ─────────────────────────────────────────────────────────────
 * exec family
 * ───────────────────────────────────────────────────────────── */

int execve(const char *path, char *const argv[], char *const envp[]) {
    if (g_xstealth_active) {
        char joined[4096];
        joined[0] = '\0';
        if (argv) {
            for (int i = 0; argv[i] && strlen(joined) < 4000; i++) {
                strncat(joined, argv[i], 4000 - strlen(joined) - 1);
                strncat(joined, " ",     4000 - strlen(joined) - 1);
            }
        }
        if (command_is_watched(joined) || is_blocked_path(path)) {
            LOGI("execve blocked: %s", joined);
            errno = ENOENT;
            return -1;
        }
    }
    if (!real_execve) resolve_real_symbols();
    if (!real_execve) { errno = ENOENT; return -1; }
    return real_execve(path, argv, envp);
}

int posix_spawn(pid_t *pid, const char *path,
                void *file_actions, void *attrp,
                char *const argv[], char *const envp[]) {
    if (g_xstealth_active) {
        char joined[4096];
        joined[0] = '\0';
        if (argv) {
            for (int i = 0; argv[i] && strlen(joined) < 4000; i++) {
                strncat(joined, argv[i], 4000 - strlen(joined) - 1);
                strncat(joined, " ",     4000 - strlen(joined) - 1);
            }
        }
        if (command_is_watched(joined) || is_blocked_path(path)) {
            LOGI("posix_spawn intercepted: substituting scrubbed output");
            if (pid) *pid = -1;
            return 0;
        }
    }
    if (!real_posix_spawn) resolve_real_symbols();
    if (!real_posix_spawn) return ENOENT;
    return real_posix_spawn(pid, path, file_actions, attrp, argv, envp);
}

int posix_spawnp(pid_t *pid, const char *file,
                 void *file_actions, void *attrp,
                 char *const argv[], char *const envp[]) {
    if (g_xstealth_active) {
        char joined[4096];
        joined[0] = '\0';
        if (argv) {
            for (int i = 0; argv[i] && strlen(joined) < 4000; i++) {
                strncat(joined, argv[i], 4000 - strlen(joined) - 1);
                strncat(joined, " ",     4000 - strlen(joined) - 1);
            }
        }
        if (command_is_watched(joined) || is_blocked_path(file)) {
            LOGI("posix_spawnp intercepted: substituting scrubbed output");
            if (pid) *pid = -1;
            return 0;
        }
    }
    if (!real_posix_spawnp) resolve_real_symbols();
    if (!real_posix_spawnp) return ENOENT;
    return real_posix_spawnp(pid, file, file_actions, attrp, argv, envp);
}

FILE *popen(const char *cmd, const char *mode) {
    if (g_xstealth_active && command_is_watched(cmd)) {
        LOGI("popen intercepted: serving scrubbed XML");
        const char *xml = scrubbed_for_command(cmd);
        return fmemopen((void *)xml, strlen(xml), "r");
    }
    if (!real_popen) resolve_real_symbols();
    if (!real_popen) return NULL;
    return real_popen(cmd, mode);
}

int system(const char *cmd) {
    if (g_xstealth_active && command_is_watched(cmd)) {
        LOGI("system intercepted: returning 0");
        return 0;
    }
    if (!real_system) resolve_real_symbols();
    if (!real_system) return -1;
    return real_system(cmd);
}

/* ─────────────────────────────────────────────────────────────
 * __system_property_get
 * ───────────────────────────────────────────────────────────── */

int __system_property_get(const char *name, char *value) {
    if (real_system_property_get == NULL) {
        real_system_property_get =
            (int (*)(const char *, char *))
            dlsym(RTLD_NEXT, "__system_property_get");
        if (real_system_property_get == NULL) {
            real_system_property_get =
                (int (*)(const char *, char *))
                dlsym(RTLD_DEFAULT, "__system_property_get");
        }
    }

    if (real_system_property_get == NULL) {
        if (value != NULL) value[0] = '\0';
        return 0;
    }

    if (!g_xstealth_active) {
        return real_system_property_get(name, value);
    }

    const char *sanitized = lookup_watched_property(name);
    if (sanitized != NULL) {
        size_t len = strlen(sanitized);
        if (value != NULL) {
            memcpy(value, sanitized, len);
            value[len] = '\0';
        }
        __sync_fetch_and_add(&g_sysprop_intercepted, 1);
        return (int) len;
    }

    return real_system_property_get(name, value);
}

/* ─────────────────────────────────────────────────────────────
 * getppid
 * ───────────────────────────────────────────────────────────── */

pid_t getppid(void) {
    if (real_getppid == NULL) {
        real_getppid = (pid_t (*)(void)) dlsym(RTLD_NEXT, "getppid");
        if (real_getppid == NULL) {
            real_getppid = (pid_t (*)(void)) dlsym(RTLD_DEFAULT, "getppid");
        }
    }

    if (real_getppid == NULL) {
        return 1;
    }

    pid_t real = real_getppid();

    if (!g_xstealth_active) return real;
    if (is_trusted_caller()) return real;

    __sync_fetch_and_add(&g_getppid_intercepted, 1);
    return 1;
}

/* ─────────────────────────────────────────────────────────────
 * dlsym interposer
 * ───────────────────────────────────────────────────────────── */

static void *lookup_interposed(const char *symbol) {
    if (!symbol) return NULL;
    if (strcmp(symbol, "open") == 0)        return (void *) open;
    if (strcmp(symbol, "openat") == 0)      return (void *) openat;
    if (strcmp(symbol, "openat2") == 0)     return (void *) openat2;
    if (strcmp(symbol, "fopen") == 0)       return (void *) fopen;
    if (strcmp(symbol, "access") == 0)      return (void *) access;
    if (strcmp(symbol, "faccessat") == 0)   return (void *) faccessat;
    if (strcmp(symbol, "faccessat2") == 0)  return (void *) faccessat2;
    if (strcmp(symbol, "fchmodat2") == 0)   return (void *) fchmodat2;
    if (strcmp(symbol, "stat") == 0)        return (void *) stat;
    if (strcmp(symbol, "lstat") == 0)       return (void *) lstat;
    if (strcmp(symbol, "fstatat") == 0)     return (void *) fstatat;
    if (strcmp(symbol, "statx") == 0)       return (void *) statx;
    if (strcmp(symbol, "readlink") == 0)    return (void *) readlink;
    if (strcmp(symbol, "read") == 0)        return (void *) read;
    if (strcmp(symbol, "execve") == 0)      return (void *) execve;
    if (strcmp(symbol, "popen") == 0)       return (void *) popen;
    if (strcmp(symbol, "system") == 0)      return (void *) system;
    if (strcmp(symbol, "pread") == 0)       return (void *) pread;
    if (strcmp(symbol, "pread64") == 0)     return (void *) pread64;
    if (strcmp(symbol, "ptrace") == 0)      return (void *) ptrace;
    if (strcmp(symbol, "getppid") == 0)     return (void *) getppid;
    if (strcmp(symbol, "ioctl") == 0)
        return (void *) (int (*)(int, unsigned long, ...)) ioctl;
    if (strcmp(symbol, "recvmsg") == 0)     return (void *) recvmsg;
    if (strcmp(symbol, "sendmsg") == 0)     return (void *) sendmsg;
    if (strcmp(symbol, "getsockname") == 0) return (void *) getsockname;
    if (strcmp(symbol, "__system_property_get") == 0)
        return (void *) __system_property_get;
    return NULL;
}

static void *resolve_real_dlsym(void) {
    typedef void *(*dlvsym_fn)(void *, const char *, const char *);
    dlvsym_fn dlvsym_impl = (dlvsym_fn) dlsym(RTLD_NEXT, "dlvsym");
    if (dlvsym_impl != NULL) {
        void *p = dlvsym_impl(RTLD_NEXT, "dlsym", NULL);
        if (p != NULL) return p;
    }
    void *p = dlsym(RTLD_NEXT, "dlsym");
    if (p != NULL) return p;
    return NULL;
}

void *dlsym(void *handle, const char *symbol) {
    if (real_dlsym == NULL) {
        real_dlsym = (void *(*)(void *, const char *)) resolve_real_dlsym();
    }
    if (real_dlsym == NULL) {
        LOGW("dlsym: real dlsym unavailable — interposer disabled");
        return NULL;
    }

    if (!g_xstealth_active || symbol == NULL) {
        return real_dlsym(handle, symbol);
    }

    void *interposed = lookup_interposed(symbol);
    if (interposed == NULL) {
        return real_dlsym(handle, symbol);
    }

    if (is_trusted_caller()) {
        return real_dlsym(handle, symbol);
    }

    __sync_fetch_and_add(&g_dlsym_intercepted, 1);
    LOGI("dlsym: intercepted request for %s from untrusted caller",
         symbol);
    return interposed;
}

/* ─────────────────────────────────────────────────────────────
 * ptrace interposer
 * ───────────────────────────────────────────────────────────── */

long ptrace(int request, ...) {
    if (real_ptrace == NULL) {
        if (real_dlsym == NULL) {
            real_dlsym = (void *(*)(void *, const char *)) resolve_real_dlsym();
        }
        if (real_dlsym != NULL) {
            real_ptrace = (long (*)(int, ...)) real_dlsym(RTLD_NEXT, "ptrace");
        }
    }

    if (request == PTRACE_TRACEME) {
        if (g_xstealth_active && !is_trusted_caller()) {
            __sync_fetch_and_add(&g_ptrace_intercepted, 1);
            LOGI("ptrace: intercepted PTRACE_TRACEME from untrusted caller");
            return 0;
        }
    }

    if (real_ptrace == NULL) {
        errno = ENOSYS;
        return -1;
    }

    va_list ap;
    va_start(ap, request);
    pid_t pid = va_arg(ap, pid_t);
    void *addr = va_arg(ap, void *);
    void *data = va_arg(ap, void *);
    va_end(ap);
    return real_ptrace(request, pid, addr, data);
}

/* ═════════════════════════════════════════════════════════════
 * CODE PATCHING HELPERS
 * ═════════════════════════════════════════════════════════════ */

__attribute__((unused))
static void *alloc_code_near_rw(int64_t near_addr, size_t size) {
    int64_t page = near_addr & ~0xfffULL;
    int64_t low  = (page < 0x8000001) ? 0x1000 : page - 0x8000000;

    for (int64_t addr = page; addr < page + 0x8000000; addr += 0x1000) {
        void *p = mmap((void *) addr, size, PROT_READ | PROT_WRITE,
                       MAP_PRIVATE | MAP_ANONYMOUS | MAP_FIXED_NOREPLACE,
                       -1, 0);
        if (p != MAP_FAILED) return p;
        if (errno != EEXIST && errno != EINVAL) break;
    }

    for (int64_t addr = page - 0x1000; addr > low; addr -= 0x1000) {
        void *p = mmap((void *) addr, size, PROT_READ | PROT_WRITE,
                       MAP_PRIVATE | MAP_ANONYMOUS | MAP_FIXED_NOREPLACE,
                       -1, 0);
        if (p != MAP_FAILED) return p;
        if (errno != EEXIST && errno != EINVAL) break;
    }

    void *p = mmap(NULL, size, PROT_READ | PROT_WRITE,
                   MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    return p == MAP_FAILED ? NULL : p;
}

__attribute__((unused))
static void seal_code_region(void *region, size_t size) {
    if (region == NULL || size == 0) return;

    volatile uint8_t *p = (volatile uint8_t *) region;
    for (size_t i = 0; i < size; i += 0x1000) {
        p[i] = 0;
    }

    long page = sysconf(_SC_PAGESIZE);
    if (page <= 0) page = 4096;
    void *page_start = (void *)((uintptr_t)region & ~(uintptr_t)(page - 1));
    size_t span = size + ((uintptr_t)region - (uintptr_t)page_start);
    mprotect(page_start, span, PROT_READ | PROT_EXEC);
}

/* ═════════════════════════════════════════════════════════════
 * UNSAFE GATE
 * ═════════════════════════════════════════════════════════════ */

static const char *UNSAFE_CLASSES[] = {
    "sun.misc.Unsafe",
    "jdk.internal.misc.Unsafe",
    NULL
};

static const char *UNSAFE_FIELD_NAMES[] = {
    "theUnsafe",
    NULL
};

static const char *UNSAFE_METHOD_NAMES[] = {
    "getUnsafe",
    NULL
};

static int neutralize_unsafe_field_jni(JNIEnv *env, jclass unsafeClass) {
    if (env == NULL || unsafeClass == NULL) return 0;

    for (int i = 0; UNSAFE_FIELD_NAMES[i]; i++) {
        const char *name = UNSAFE_FIELD_NAMES[i];

        jfieldID fid = (*env)->GetStaticFieldID(env, unsafeClass,
                                                 name, "Lsun/misc/Unsafe;");
        if (fid == NULL) {
            (*env)->ExceptionClear(env);
            fid = (*env)->GetStaticFieldID(env, unsafeClass,
                                            name,
                                            "Ljdk/internal/misc/Unsafe;");
        }
        if (fid == NULL) {
            (*env)->ExceptionClear(env);
            continue;
        }

        (*env)->SetStaticObjectField(env, unsafeClass, fid, NULL);
        if ((*env)->ExceptionCheck(env)) {
            (*env)->ExceptionClear(env);
            LOGW("Unsafe gate: SetStaticObjectField(%s) threw", name);
            continue;
        }

        LOGI("Unsafe gate: neutralized field %s", name);
        return 1;
    }
    return 0;
}

static jobject unsafe_getunsafe_stub(JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
    LOGI("Unsafe gate: getUnsafe() called, returning null");
    return NULL;
}

static int neutralize_unsafe_method_jni(JNIEnv *env, jclass unsafeClass) {
    if (env == NULL || unsafeClass == NULL) return 0;

    for (int i = 0; UNSAFE_METHOD_NAMES[i]; i++) {
        const char *name = UNSAFE_METHOD_NAMES[i];
        jmethodID mid = (*env)->GetStaticMethodID(env, unsafeClass, name,
            "()Lsun/misc/Unsafe;");
        if (mid == NULL) {
            (*env)->ExceptionClear(env);
            mid = (*env)->GetStaticMethodID(env, unsafeClass, name,
                "()Ljdk/internal/misc/Unsafe;");
        }
        if (mid == NULL) {
            (*env)->ExceptionClear(env);
            continue;
        }

        JNINativeMethod method;
        method.name = (char *) name;
        method.signature = (char *) "()Lsun/misc/Unsafe;";
        method.fnPtr = (void *) unsafe_getunsafe_stub;

        jint rc = (*env)->RegisterNatives(env, unsafeClass, &method, 1);
        if (rc != JNI_OK) {
            (*env)->ExceptionClear(env);

            JNINativeMethod method2;
            method2.name = (char *) name;
            method2.signature = (char *) "()Ljdk/internal/misc/Unsafe;";
            method2.fnPtr = (void *) unsafe_getunsafe_stub;

            rc = (*env)->RegisterNatives(env, unsafeClass, &method2, 1);
            if (rc != JNI_OK) {
                (*env)->ExceptionClear(env);
                LOGW("Unsafe gate: RegisterNatives(%s) failed (rc=%d). "
                     "Method may be intrinsic or already JIT'd.",
                     name, (int) rc);
                continue;
            }
        }

        LOGI("Unsafe gate: patched method %s", name);
        return 1;
    }
    return 0;
}

static void install_unsafe_gate(void) {
    if (g_unsafe_gate_installed) return;

    if (g_vm == NULL) {
        snprintf(g_unsafe_gate_note, sizeof(g_unsafe_gate_note),
                 "no JavaVM captured");
        LOGW("Unsafe gate: JavaVM not captured at JNI_OnLoad");
        return;
    }

    JNIEnv *env = NULL;
    jint rc = (*g_vm)->GetEnv(g_vm, (void **) &env, JNI_VERSION_1_6);
    if (rc == JNI_EDETACHED) {
        snprintf(g_unsafe_gate_note, sizeof(g_unsafe_gate_note),
                 "thread not attached to JVM");
        LOGW("Unsafe gate: thread not attached to JVM");
        return;
    }
    if (env == NULL) {
        snprintf(g_unsafe_gate_note, sizeof(g_unsafe_gate_note),
                 "GetEnv returned null");
        LOGW("Unsafe gate: GetEnv returned null");
        return;
    }

    int any_found = 0;
    int any_field = 0;
    int any_method = 0;

    for (int i = 0; UNSAFE_CLASSES[i]; i++) {
        jclass clazz = (*env)->FindClass(env, UNSAFE_CLASSES[i]);
        if (clazz == NULL) {
            (*env)->ExceptionClear(env);
            continue;
        }
        any_found = 1;

        if (neutralize_unsafe_field_jni(env, clazz)) any_field = 1;
        if (neutralize_unsafe_method_jni(env, clazz)) any_method = 1;
    }

    g_unsafe_gate_installed = 1;
    g_unsafe_gate_field_neutralized = any_field;
    g_unsafe_gate_method_patched = any_method;

    if (!any_found) {
        snprintf(g_unsafe_gate_note, sizeof(g_unsafe_gate_note),
                 "no Unsafe class found");
        LOGW("Unsafe gate: no Unsafe class found — gate inactive");
    } else if (!any_field && !any_method) {
        snprintf(g_unsafe_gate_note, sizeof(g_unsafe_gate_note),
                 "Unsafe found but no gates applied");
        LOGW("Unsafe gate: Unsafe found but no gates applied — "
             "ART layout may have changed");
    } else {
        snprintf(g_unsafe_gate_note, sizeof(g_unsafe_gate_note),
                 "field=%d method=%d", any_field, any_method);
        LOGI("Unsafe gate: installed (field=%d, method=%d)",
             any_field, any_method);
    }
}

/* ═════════════════════════════════════════════════════════════
 * JNI ENTRY POINTS
 * ═════════════════════════════════════════════════════════════ */

JNIEXPORT jboolean JNICALL
Java_com_shizuposed_manager_stealth_XStealthNative_nativeInit(
        JNIEnv *env, jclass clazz, jstring libDir) {
    (void) env;
    (void) clazz;
    (void) libDir;

    pthread_mutex_lock(&g_netlink_lock);
    for (int i = 0; i < MAX_NETLINK_FDS; i++) {
        g_netlink_holds[i].fd = -1;
        g_netlink_holds[i].buf = NULL;
        g_netlink_holds[i].len = 0;
    }
    pthread_mutex_unlock(&g_netlink_lock);

    capture_libart_backup();

    if (env != NULL) {
        capture_stub_template(env);
    }

    resolve_real_symbols();
    LOGI("XStealth native: active=%d", g_xstealth_active);
    return (g_key_symbols_resolved == 5) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_shizuposed_manager_stealth_XStealthNative_nativeSetActive(
        JNIEnv *env, jclass clazz, jboolean active) {
    (void) env;
    (void) clazz;

    g_xstealth_active = active ? 1 : 0;
    if (g_xstealth_active) {
        resolve_real_symbols();
        install_unsafe_gate();
    }
    LOGI("XStealth native: active=%d", g_xstealth_active);
}

JNIEXPORT jboolean JNICALL
Java_com_shizuposed_manager_stealth_XStealthNative_nativeIsActive(
        JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
    return g_xstealth_active ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_com_shizuposed_manager_stealth_XStealthNative_nativeDescribe(
        JNIEnv *env, jclass clazz) {
    (void) clazz;
    char buf[1536];
    snprintf(buf, sizeof(buf),
        "libxstealth %s active=%d open=%p stat=%p fstatat=%p read=%p "
        "openat2=%p statx=%p ioctl=%p netlink=%p symbols=%d/%d "
        "settingsXmlBlocked=%d sysfsBlocked=%d openat2Blocked=%d "
        "ioctlBlocked=%d netlinkIntercepted=%d netlinkDropped=%d "
        "dlsymIntercepted=%d ptraceIntercepted=%d preadIntercepted=%d "
        "syspropIntercepted=%d getppidIntercepted=%d "
        "libartBackupFd=%d unsafeGate=%d(%s)"
        "stubTemplate=%d(%uB)",
        XSTEALTH_VERSION,
        g_xstealth_active,
        (void *)real_open, (void *)real_stat, (void *)real_fstatat,
        (void *)real_read,
        (void *)real_openat2,
        (void *)real_statx,
        (void *)real_ioctl,
        (real_sendmsg && real_recvmsg) ? (void *)1 : (void *)0,
        g_key_symbols_resolved, g_symbols_resolved,
        g_settings_xml_blocked,
        g_sysfs_blocked,
        g_openat2_blocked,
        g_ioctl_blocked,
        g_netlink_intercepted,
        g_netlink_messages_dropped,
        g_dlsym_intercepted,
        g_ptrace_intercepted,
        g_pread_intercepted,
        g_sysprop_intercepted,
        g_getppid_intercepted,
        g_libart_backup_fd,
        g_unsafe_gate_installed,
        g_unsafe_gate_note,
        (g_stub_template.flags & 1),
        g_stub_template.confidence);
    return (*env)->NewStringUTF(env, buf);
}

JNIEXPORT jboolean JNICALL
Java_com_shizuposed_manager_stealth_XStealthNative_nativeIsEffectivelyActive(
        JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
    return (g_xstealth_active && g_key_symbols_resolved >= 5)
        ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_com_shizuposed_manager_stealth_XStealthNative_nativeGetResolvedCount(
        JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
    return g_symbols_resolved;
}

JNIEXPORT jint JNICALL
Java_com_shizuposed_manager_stealth_XStealthNative_nativeGetKeyResolvedCount(
        JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
    return g_key_symbols_resolved;
}

JNIEXPORT jboolean JNICALL
Java_com_shizuposed_manager_stealth_XStealthNative_nativeIsUnsafeGateActive(
        JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
    return (g_unsafe_gate_installed
            && (g_unsafe_gate_field_neutralized
                || g_unsafe_gate_method_patched))
        ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_com_shizuposed_manager_stealth_XStealthNative_nativeGetStubTemplateConfidence(
        JNIEnv *env, jclass clazz) {
    (void) env; (void) clazz;
    return (jint) g_stub_template.confidence;
}

JNIEXPORT jboolean JNICALL
Java_com_shizuposed_manager_stealth_XStealthNative_nativeIsStubTemplateValid(
        JNIEnv *env, jclass clazz) {
    (void) env; (void) clazz;
    return (g_stub_template.flags & 1) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_com_shizuposed_manager_stealth_XStealthNative_nativeGetStubTemplateInfo(
        JNIEnv *env, jclass clazz) {
    (void) clazz;
    char buf[640];
    char regbuf[64];
    int roff = 0;
    for (int i = 0; i < 8; i++) {
        if (g_stub_template.info.reg_save_off[i] >= 0 &&
            roff < (int)sizeof(regbuf) - 8) {
            roff += snprintf(regbuf + roff, sizeof(regbuf) - roff,
                             "x%d=%d ",
                             i, g_stub_template.info.reg_save_off[i]);
        }
    }
    if (roff == 0) {
        snprintf(regbuf, sizeof(regbuf), "(none)");
    }
    snprintf(buf, sizeof(buf),
             "valid=%d verified=%u confidence=%u verify=%u "
             "frame=0x%x save_words=%d x30_off=%d recv_off=%d "
             "mask=0x%02x regs=[%s] note=%s",
             (g_stub_template.flags & 1),
             g_stub_template.verified_len,
             g_stub_template.confidence,
             g_stub_template.verify,
             g_stub_template.info.frame_size,
             g_stub_template.info.save_words,
             g_stub_template.info.x30_offset,
             g_stub_template.info.receiver_offset,
             g_stub_template.info.reg_mask,
             regbuf,
             g_stub_template.note);
    return (*env)->NewStringUTF(env, buf);
}

JNIEXPORT jstring JNICALL
Java_com_shizuposed_manager_stealth_XStealthNative_nativeGetUnsafeGateInfo(
        JNIEnv *env, jclass clazz) {
    (void) clazz;
    char buf[256];
    snprintf(buf, sizeof(buf),
        "installed=%d field=%d method=%d note=%s",
        g_unsafe_gate_installed,
        g_unsafe_gate_field_neutralized,
        g_unsafe_gate_method_patched,
        g_unsafe_gate_note);
    return (*env)->NewStringUTF(env, buf);
}

JNIEXPORT jint JNICALL
Java_com_shizuposed_manager_stealth_XStealthNative_nativeGetSettingsXmlBlockedCount(
        JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
    return g_settings_xml_blocked;
}

JNIEXPORT jint JNICALL
Java_com_shizuposed_manager_stealth_XStealthNative_nativeGetSysfsBlockedCount(
        JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
    return g_sysfs_blocked;
}

JNIEXPORT jint JNICALL
Java_com_shizuposed_manager_stealth_XStealthNative_nativeGetDlsymInterceptedCount(
        JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
    return g_dlsym_intercepted;
}

JNIEXPORT jint JNICALL
Java_com_shizuposed_manager_stealth_XStealthNative_nativeGetPtraceInterceptedCount(
        JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
    return g_ptrace_intercepted;
}

JNIEXPORT jint JNICALL
Java_com_shizuposed_manager_stealth_XStealthNative_nativeGetPreadInterceptedCount(
        JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
    return g_pread_intercepted;
}

JNIEXPORT jint JNICALL
Java_com_shizuposed_manager_stealth_XStealthNative_nativeGetGetppidInterceptedCount(
        JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
    return g_getppid_intercepted;
}

JNIEXPORT jint JNICALL
Java_com_shizuposed_manager_stealth_XStealthNative_nativeGetOpenat2BlockedCount(
        JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
    return g_openat2_blocked;
}

JNIEXPORT jint JNICALL
Java_com_shizuposed_manager_stealth_XStealthNative_nativeGetIoctlBlockedCount(
        JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
    return g_ioctl_blocked;
}

JNIEXPORT jint JNICALL
Java_com_shizuposed_manager_stealth_XStealthNative_nativeGetNetlinkInterceptedCount(
        JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
    return g_netlink_intercepted;
}

JNIEXPORT jint JNICALL
Java_com_shizuposed_manager_stealth_XStealthNative_nativeGetNetlinkDroppedCount(
        JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
    return g_netlink_messages_dropped;
}

JNIEXPORT jboolean JNICALL
Java_com_shizuposed_manager_stealth_XStealthNative_nativeHasLibartBackup(
        JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
    return (g_libart_backup_fd >= 0) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_com_shizuposed_manager_stealth_XStealthNative_nativeGetLibartBackupInfo(
        JNIEnv *env, jclass clazz) {
    (void) clazz;
    char buf[640];
    snprintf(buf, sizeof(buf),
        "fd=%d size=%ld path=%s",
        g_libart_backup_fd,
        (long) g_libart_backup_size,
        g_libart_path[0] ? g_libart_path : "(unset)");
    return (*env)->NewStringUTF(env, buf);
}

JNIEXPORT jstring JNICALL
Java_com_shizuposed_manager_stealth_XStealthNative_nativeDumpStubTemplate(
        JNIEnv *env, jclass clazz) {
    (void) clazz;
    if (g_stub_template.flags == 0) {
        return (*env)->NewStringUTF(env, "template not captured");
    }
    char buf[320];
    int off = snprintf(buf, sizeof(buf),
        "verify=%u confidence=%u verified_len=%u save_words=%d bytes=",
        g_stub_template.verify,
        g_stub_template.confidence,
        g_stub_template.verified_len,
        g_stub_template.info.save_words);
    uint32_t dump_len = g_stub_template.verified_len;
    if (dump_len == 0) dump_len = g_stub_template.confidence;
    if (dump_len > 32) dump_len = 32;
    for (uint32_t i = 0; i < dump_len && off < (int)sizeof(buf) - 4; i++) {
        off += snprintf(buf + off, sizeof(buf) - off,
                        "%02x", g_stub_template.bytes[i]);
    }
    return (*env)->NewStringUTF(env, buf);
}

JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void) reserved;
    g_vm = vm;

    LOGI("JNI_OnLoad: libxstealth %s "
         "(alias + fstatat + statx + openat2 + ioctl + netlink + "
         "exec + environ + cmdline + settingsXml + sysfs + netUnix "
         "+ netInet + maps-substitute + smaps-substitute + "
         "rw-to-rx + prefault + unsafeGate + dlsym + ptrace + pread "
         "+ sysprop + getppid + stubTemplateVerify+offsets)",
         XSTEALTH_VERSION);

    if (real_dlsym == NULL) {
        real_dlsym = (void *(*)(void *, const char *)) resolve_real_dlsym();
        if (real_dlsym != NULL) {
            LOGI("JNI_OnLoad: real dlsym resolved at %p",
                 (void *) real_dlsym);
        } else {
            LOGW("JNI_OnLoad: could not resolve real dlsym — "
                 "dlsym interposer will be inactive");
        }
    }

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