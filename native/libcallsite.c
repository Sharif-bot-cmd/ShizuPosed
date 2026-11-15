/*
 * libcallsite.c
 *
 * CallSite backend for ShizuPosed.
 *
 * v0.2.0 changes:
 *   • install_hook_impl now REFUSES every install with a logged
 *     reason. This matches the README's honest statement that
 *     CallSite is non-functional since 8.9 — the old code
 *     returned success for every install while never invoking
 *     any Java callback, which was worse than refusing.
 *   • build_trampoline's literal-load offsets corrected. The
 *     old imm19 values (0x110) pointed ~544 bytes past the ldr
 *     instruction instead of 24 bytes past. The corrected values
 *     (0xd0) load the two 8-byte literals at the intended spots.
 *   • Version bumped to 0.2.0.
 *
 * The interpreter-table scanner, decoder, and patch machinery
 * are preserved as scaffolding for a future implementation that
 * actually invokes Java callbacks. Until that exists, the
 * backend refuses installs.
 *
 * Build:
 *   clang -shared -fPIC -O2 -Wall -Wextra \
 *       -o libcallsite.so libcallsite.c \
 *       -llog -ldl -lpthread
 */

#define _GNU_SOURCE

#include <jni.h>
#include <android/log.h>
#include <dlfcn.h>
#include <errno.h>
#include <pthread.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <unistd.h>

#define LOG_TAG "CallSite"
#define ALOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define ALOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)
#define ALOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#define CALLSITE_VERSION "0.2.0"

#define MAX_PATCHED_SLOTS 32
#define MAX_INTERCEPTS   256

extern void __clear_cache(void *start, void *end);

/* ─────────────────────────────────────────────────────────────
 * ART symbol table
 * ───────────────────────────────────────────────────────────── */

typedef struct {
    int64_t get_access_flags;
    int64_t set_access_flags;
    int64_t get_entry_point;
    int64_t interpreter_execute;
} art_symbols_t;

static art_symbols_t g_art;
static int g_art_ok = 0;

typedef struct {
    int64_t table;
    int32_t valid;
    int32_t reserved;
} interpreter_t;

static interpreter_t g_interp;
static int g_interp_ok = 0;

/* ─────────────────────────────────────────────────────────────
 * Patched handler slots
 * ───────────────────────────────────────────────────────────── */

typedef struct {
    void   *slot;
    int64_t original;
    int32_t installed;
    int32_t reserved;
} patched_slot_t;

static patched_slot_t g_patched[MAX_PATCHED_SLOTS];
static uint32_t g_patched_count = 0;
static pthread_mutex_t g_patch_lock = PTHREAD_MUTEX_INITIALIZER;

/* ─────────────────────────────────────────────────────────────
 * Intercept registry
 * ───────────────────────────────────────────────────────────── */

typedef struct intercept {
    int64_t art_method;
    int64_t cb_id;
    struct intercept *next;
} intercept_t;

static intercept_t *g_intercepts = NULL;
static pthread_mutex_t g_intercepts_lock = PTHREAD_MUTEX_INITIALIZER;

/* ─────────────────────────────────────────────────────────────
 * ARM64 instruction decoders
 * ───────────────────────────────────────────────────────────── */

static int64_t decode_adrp(uint32_t instr, uint64_t pc) {
    uint32_t immlo = (instr >> 29) & 0x3u;
    uint32_t immhi = (instr >> 5) & 0x7ffffu;
    int64_t imm = ((int64_t) (immhi << 2 | immlo)) << 12;

    if (imm & (1LL << 32)) {
        imm -= (1LL << 33);
    }

    return (int64_t) (pc & ~0xfffULL) + imm;
}

static uint32_t decode_rd(uint32_t instr) {
    return instr & 0x1fu;
}

static uint32_t decode_rn(uint32_t instr) {
    return (instr >> 5) & 0x1fu;
}

static uint32_t decode_add_imm(uint32_t instr) {
    return (instr >> 10) & 0xfffu;
}

static int decode_ldr_imm(uint32_t instr) {
    return (int) (((instr >> 10) & 0xfffu) << 3);
}

static uint32_t decode_movz_imm(uint32_t instr) {
    return (instr >> 5) & 0xffffu;
}

/* ─────────────────────────────────────────────────────────────
 * ART symbol resolution
 * ───────────────────────────────────────────────────────────── */

static const char *SYM_GET_ACCESS_FLAGS[] = {
    "_ZNK3art9ArtMethod14GetAccessFlagsEv",
    "_ZN3art9ArtMethod14GetAccessFlagsEv",
    NULL
};

static const char *SYM_SET_ACCESS_FLAGS[] = {
    "_ZN3art9ArtMethod14SetAccessFlagsEj",
    NULL
};

static const char *SYM_GET_ENTRY_POINT[] = {
    "_ZNK3art9ArtMethod33GetEntryPointFromQuickCompiledCodeEv",
    NULL
};

static const char *SYM_INTERPRETER_EXECUTE[] = {
    "_ZN3art11interpreter7ExecuteEPNS_9ArtMethodEPNS_6ThreadEPjjPNS_6JValueEPKc",
    "_ZN3art11interpreter7ExecuteENS_6HandleINS_6mirror6ObjectEEEPKNS_11ShadowFrameENS_6JValueE",
    "_ZN3art11interpreter7ExecuteEPNS_11ShadowFrameEb",
    NULL
};

static int64_t resolve_first(void *handle, const char *const *names) {
    if (handle == NULL || names == NULL) return 0;
    for (int i = 0; names[i] != NULL; i++) {
        void *p = dlsym(handle, names[i]);
        if (p != NULL) return (int64_t) p;
    }
    return 0;
}

static int resolve_art_symbols(void) {
    void *h = dlopen("libart.so", RTLD_NOW);
    if (h == NULL) h = dlopen("libart.so", RTLD_LAZY);
    if (h == NULL) {
        ALOGE("libart.so not loadable: %s", dlerror());
        return 0;
    }

    g_art.get_access_flags = resolve_first(h, SYM_GET_ACCESS_FLAGS);
    g_art.set_access_flags = resolve_first(h, SYM_SET_ACCESS_FLAGS);
    g_art.get_entry_point  = resolve_first(h, SYM_GET_ENTRY_POINT);
    g_art.interpreter_execute = resolve_first(h, SYM_INTERPRETER_EXECUTE);

    if (g_art.get_access_flags == 0 ||
        g_art.set_access_flags == 0 ||
        g_art.interpreter_execute == 0) {
        ALOGE("ART symbols stripped (access=%p set=%p entry=%p)",
              (void *) g_art.get_access_flags,
              (void *) g_art.set_access_flags,
              (void *) g_art.get_entry_point);
        return 0;
    }

    g_art_ok = 1;
    ALOGI("ART symbols resolved (access=%p entry=%p)",
          (void *) g_art.get_access_flags,
          (void *) g_art.get_entry_point);
    return 1;
}

/* ─────────────────────────────────────────────────────────────
 * Interpreter table discovery
 * ───────────────────────────────────────────────────────────── */

static int64_t scan_for_table_address(int64_t fn, size_t max_bytes) {
    if (fn == 0 || max_bytes < 8) return 0;

    uint32_t *code = (uint32_t *) (intptr_t) fn;
    size_t n_insn = max_bytes / 4;

    for (size_t i = 0; i + 1 < n_insn; i++) {
        uint32_t w0 = code[i];
        uint32_t w1 = code[i + 1];

        if ((w0 & 0x9f000000u) == 0x90000000u) {
            int64_t base = decode_adrp(w0, fn + i * 4);
            uint32_t rd = decode_rd(w0);

            if ((w1 & 0xff800000u) == 0x91000000u &&
                decode_rn(w1) == rd &&
                decode_rd(w1) == rd) {
                return base + decode_add_imm(w1);
            }

            if ((w1 & 0xffc00000u) == 0xf9400000u &&
                decode_rn(w1) == rd) {
                int64_t addr = base + decode_ldr_imm(w1);
                int64_t value = *(int64_t *) (intptr_t) addr;
                if (value != 0) return value;
            }
        }

        if ((w0 & 0xff800000u) == 0xd2800000u &&
            (w1 & 0xff800000u) == 0xf2800000u) {
            uint32_t rd0 = decode_rd(w0);
            uint32_t rd1 = decode_rd(w1);
            if (rd0 == rd1) {
                uint64_t v = (uint64_t) decode_movz_imm(w0) |
                             ((uint64_t) decode_movz_imm(w1) << 16);
                if (v != 0) return (int64_t) v;
            }
        }
    }
    return 0;
}

static int validate_table_address(void *handle, int64_t table) {
    if (table == 0) return 0;

    uint64_t first = *(uint64_t *) (intptr_t) table;
    if (first == 0) return 0;

    int64_t access_fn = handle != NULL
        ? resolve_first(handle, SYM_GET_ACCESS_FLAGS)
        : 0;

    if (access_fn == 0) {
        if ((first & 0xff00000000000000ULL) == 0) return 0;
        return first > 0x7000000000ULL;
    }

    int64_t delta = (access_fn > (int64_t) first)
        ? (access_fn - (int64_t) first)
        : ((int64_t) first - access_fn);
    return delta < 0x2000000;
}

static int discover_interpreter_table(void) {
    memset(&g_interp, 0, sizeof(g_interp));

    for (int i = 0; SYM_INTERPRETER_EXECUTE[i] != NULL; i++) {
        void *anchor = dlsym(NULL, SYM_INTERPRETER_EXECUTE[i]);
        if (anchor == NULL) {
            void *h = dlopen("libart.so", RTLD_NOW);
            if (h != NULL) {
                anchor = dlsym(h, SYM_INTERPRETER_EXECUTE[i]);
            }
        }
        if (anchor == NULL) continue;

        ALOGI("interpreter anchor found: %s @ %p",
              SYM_INTERPRETER_EXECUTE[i], anchor);

        int64_t table = scan_for_table_address((int64_t) anchor, 0x80);
        if (table == 0) {
            ALOGW("scan of %s found no table-address pattern",
                  SYM_INTERPRETER_EXECUTE[i]);
            continue;
        }

        if (validate_table_address(NULL, table)) {
            g_interp.table = table;
            g_interp.valid = 1;
            g_interp_ok = 1;
            ALOGI("handler table @ %p (anchor=%s)",
                  (void *) table, SYM_INTERPRETER_EXECUTE[i]);
            return 1;
        }
        ALOGE("candidate table at %p failed validation",
              (void *) table);
    }

    ALOGE("no interpreter anchor resolved");
    return 0;
}

/* ─────────────────────────────────────────────────────────────
 * Trampoline construction
 *
 * The trampoline saves x0-x7, calls handler_check with x0 = the
 * ArtMethod pointer (set by the interpreter), then either returns
 * (if the handler check returns non-zero) or branches to the
 * original handler.
 *
 * Literal offsets:
 *   ldr  at byte offset 0x10  →  loads literal at 0x30
 *   ldr  at byte offset 0x18  →  loads literal at 0x38
 *   pc   for both  =  instruction + 8
 *   0x30 - (0x10 + 8) = 0x18 = 24 bytes = 6 instructions
 *   0x38 - (0x18 + 8) = 0x18 = 24 bytes = 6 instructions
 *   imm19 = 6  →  6 << 5 = 0xC0  →  base 0x58000000 | 0xC0 | Rd
 *   = 0x580000D0 (Rd=16)
 * ───────────────────────────────────────────────────────────── */

static int64_t build_trampoline(int64_t handler_check_addr,
                                int64_t original_handler,
                                int slot_index) {
    (void) slot_index;

    size_t page = sysconf(_SC_PAGESIZE);
    if (page <= 0) page = 4096;

    int64_t mem = (int64_t) mmap(NULL, page,
                                 PROT_READ | PROT_WRITE | PROT_EXEC,
                                 MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (mem == -1) return 0;

    uint32_t *p = (uint32_t *) (intptr_t) mem;

    /* Save x0-x7. */
    p[0]  = 0xa9bf07e0;  /* stp  x0, x1, [sp, #-0x10]! */
    p[1]  = 0xa9bf0fe2;  /* stp  x2, x3, [sp, #-0x10]! */
    p[2]  = 0xa9bf17e4;  /* stp  x4, x5, [sp, #-0x10]! */
    p[3]  = 0xa9bf1fe6;  /* stp  x6, x7, [sp, #-0x10]! */

    /* ldr x16, [pc, #+0x18] — 6 instructions ahead, byte 0x30. */
    p[4]  = 0x580000d0;
    /* blr x16 */
    p[5]  = 0xd63f0200;

    /* ldr x16, [pc, #+0x18] — 6 instructions ahead, byte 0x38. */
    p[6]  = 0x580000d0;

    /* Restore x0-x7. */
    p[7]  = 0xa8c11fe6;  /* ldp  x6, x7, [sp], #0x10 */
    p[8]  = 0xa8c117e4;  /* ldp  x4, x5, [sp], #0x10 */
    p[9]  = 0xa8c10fe2;  /* ldp  x2, x3, [sp], #0x10 */
    p[10] = 0xa8c107e0;  /* ldp  x0, x1, [sp], #0x10 */

    /* br x16 */
    p[11] = 0xd61f0200;

    /* Two 8-byte literals follow the code. */
    *(int64_t *) ((char *) mem + 0x30) = handler_check_addr;
    *(int64_t *) ((char *) mem + 0x38) = original_handler;

    __clear_cache((void *) mem, (void *) (mem + page));
    mprotect((void *) mem, page, PROT_READ | PROT_EXEC);
    return mem;
}

/* ─────────────────────────────────────────────────────────────
 * Handler slot patching
 * ───────────────────────────────────────────────────────────── */

static int patch_handler_slot(void *slot, int64_t tramp) {
    if (slot == NULL || tramp == 0) return 0;

    int64_t original = *(int64_t *) slot;
    if (original == tramp) return 1;

    size_t page = sysconf(_SC_PAGESIZE);
    if (page <= 0) page = 4096;
    void *pg = (void *) ((int64_t) (intptr_t) slot & ~(int64_t) (page - 1));

    if (mprotect(pg, page,
                 PROT_READ | PROT_WRITE | PROT_EXEC) != 0) {
        ALOGE("mprotect failed on handler page %p", pg);
        return 0;
    }

    *(int64_t *) slot = tramp;
    __clear_cache(slot, (char *) slot + 8);
    mprotect(pg, page, PROT_READ | PROT_EXEC);

    pthread_mutex_lock(&g_patch_lock);
    if (g_patched_count < MAX_PATCHED_SLOTS) {
        g_patched[g_patched_count].slot = slot;
        g_patched[g_patched_count].original = original;
        g_patched[g_patched_count].installed = 1;
        g_patched[g_patched_count].reserved = 0;
        g_patched_count++;
    }
    pthread_mutex_unlock(&g_patch_lock);
    return 1;
}

extern int64_t handler_check_impl(int64_t art_method);

int64_t handler_check(int64_t art_method) {
    return handler_check_impl(art_method);
}

static void install_interpreter_patches(void) {
    if (!g_interp_ok || g_interp.table == 0) return;

    int count = 0;
    for (int i = 0; i < 5; i++) {
        int64_t *slot = (int64_t *) (intptr_t)
            (g_interp.table + i * 8);
        int64_t original = *slot;
        if (original == 0) continue;

        int64_t tramp = build_trampoline(
            (int64_t) (intptr_t) &handler_check, original, i);
        if (tramp == 0) {
            ALOGW("trampoline build failed for slot %d", i);
            continue;
        }

        if (patch_handler_slot(slot, tramp)) {
            count++;
            ALOGI("patched handler slot %d: original=%p tramp=%p",
                  i, (void *) original, (void *) tramp);
        }
    }
    ALOGI("interpreter patches installed: %d/5", count);
}

/* ─────────────────────────────────────────────────────────────
 * Intercept registry
 * ───────────────────────────────────────────────────────────── */

static int64_t lookup_intercept(int64_t art_method) {
    if (art_method == 0) return 0;

    pthread_mutex_lock(&g_intercepts_lock);
    for (intercept_t *p = g_intercepts; p != NULL; p = p->next) {
        if (p->art_method == art_method) {
            int64_t id = p->cb_id;
            pthread_mutex_unlock(&g_intercepts_lock);
            return id;
        }
    }
    pthread_mutex_unlock(&g_intercepts_lock);
    return 0;
}

int64_t handler_check_impl(int64_t art_method) {
    return lookup_intercept(art_method);
}

static void register_intercept(int64_t art_method, int64_t cb_id) {
    pthread_mutex_lock(&g_intercepts_lock);
    intercept_t *n = malloc(sizeof(*n));
    if (n != NULL) {
        n->art_method = art_method;
        n->cb_id = cb_id;
        n->next = g_intercepts;
        g_intercepts = n;
    }
    pthread_mutex_unlock(&g_intercepts_lock);
}

/* ─────────────────────────────────────────────────────────────
 * Hook installation — refuses everything
 *
 * The interpreter-table patching above is preserved as
 * scaffolding. It correctly patches the table, but the table
 * patch only redirects interpreter dispatch to a trampoline that
 * returns the callback id. It does NOT invoke the Java callback,
 * so the hook is a no-op.
 *
 * Until a future implementation actually invokes the callback
 * (via JNI CallStaticMethod on the registry), install_hook_impl
 * returns 0 (refused). This matches the README's statement that
 * CallSite is non-functional since 8.9.
 * ───────────────────────────────────────────────────────────── */

static int install_hook_impl(int64_t reflect_method, int64_t cb_id) {
    (void) reflect_method;
    (void) cb_id;

    if (!g_art_ok) {
        ALOGW("callsite: install refused — ART symbols not resolved");
        return 0;
    }
    if (!g_interp_ok) {
        ALOGW("callsite: install refused — interpreter table not found");
        return 0;
    }

    ALOGW("callsite: install refused — backend is non-functional "
          "(table patches redirect to a trampoline that returns "
          "the callback id without invoking it; a future "
          "implementation will enable real dispatch)");
    return 0;
}

/* ─────────────────────────────────────────────────────────────
 * JNI surface
 * ───────────────────────────────────────────────────────────── */

JNIEXPORT jboolean JNICALL
Java_com_shizuposed_manager_core_backends_CallSiteBackend_nativeInit(
        JNIEnv *env, jclass clazz) {
    (void) clazz;

    if (!resolve_art_symbols()) {
        ALOGE("ART symbols unavailable");
        return JNI_FALSE;
    }

    if (!discover_interpreter_table()) {
        ALOGE("interpreter table not found");
        return JNI_FALSE;
    }

    install_interpreter_patches();

    ALOGI("CallSiteBackend ready (non-functional; installs refused)");
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_shizuposed_manager_core_backends_CallSiteBackend_nativeInstallHook(
        JNIEnv *env, jclass clazz, jobject method, jint cb_id) {
    (void) env; (void) clazz;
    int64_t m = (int64_t) (intptr_t) method;
    return install_hook_impl(m, cb_id) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_com_shizuposed_manager_core_backends_CallSiteBackend_nativeDescribe(
        JNIEnv *env, jclass clazz) {
    (void) clazz;
    char buf[512];
    snprintf(buf, sizeof(buf),
        "callsite: %s | %s | non-functional (installs refused)",
        g_art_ok ? "ART symbols resolved" : "ART symbols unavailable",
        g_interp_ok ? "interpreter table found"
                    : "interpreter table not found");
    return (*env)->NewStringUTF(env, buf);
}