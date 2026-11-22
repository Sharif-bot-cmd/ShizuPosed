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

#define CALLSITE_VERSION "0.4.1"

#define MAX_PATCHED_SLOTS 32
#define MAX_INTERCEPTS   256

extern void __clear_cache(void *start, void *end);

/* ─────────────────────────────────────────────────────────────
 * Native-backend bridge (resolved lazily; absent is a supported
 * state).
 *
 * libshizuposed exports:
 *
 *   int64_t shizuposed_lazy_install_artmethod(int64_t art_method);
 *       → performs the entry-point replacement for an ArtMethod
 *         that already has a registered callback stub.
 *         Idempotent: if already hooked, returns the existing
 *         trampoline. Returns 0 on refusal (no layout, no
 *         registered callback, allocation failure).
 *
 *   int32_t shizuposed_unhook_artmethod(int64_t art_method);
 *       → direct-C mirror of nUnhookArtMethod, for symmetry.
 *
 * If either symbol is absent, installs are refused and
 * nativeBridgeLinked() returns false.
 *
 * Resolved lazily: CallSite may be initialized before
 * libshizuposed.so is loaded. Both install_hook_impl and
 * handler_check_impl retry the resolution when g_bridge_ok is 0.
 * ───────────────────────────────────────────────────────────── */

typedef int64_t (*lazy_install_fn)(int64_t art_method);
typedef int32_t (*unhook_artmethod_fn)(int64_t art_method);

static lazy_install_fn       g_lazy_install    = NULL;
static unhook_artmethod_fn   g_lazy_unhook     = NULL;
static int                   g_bridge_ok       = 0;
static pthread_mutex_t       g_bridge_mu       = PTHREAD_MUTEX_INITIALIZER;

static int resolve_native_bridge_locked(void) {
    if (g_bridge_ok) return 1;
    void *inst = dlsym(RTLD_DEFAULT, "shizuposed_lazy_install_artmethod");
    if (inst == NULL) {
        return 0;
    }
    void *unh = dlsym(RTLD_DEFAULT, "shizuposed_unhook_artmethod");
    g_lazy_install = (lazy_install_fn) inst;
    g_lazy_unhook  = (unhook_artmethod_fn) unh;
    g_bridge_ok = 1;
    ALOGI("native bridge resolved (install=%p unhook=%p)", inst, unh);
    return 1;
}

static int resolve_native_bridge(void) {
    if (g_bridge_ok) return 1;
    pthread_mutex_lock(&g_bridge_mu);
    int ok = resolve_native_bridge_locked();
    pthread_mutex_unlock(&g_bridge_mu);
    return ok;
}

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
 *
 * Maps ArtMethod* → cached branch target. The branch target is the
 * trampoline returned by shizuposed_lazy_install_artmethod on first
 * sighting; it is 0 until that call succeeds.
 * ───────────────────────────────────────────────────────────── */

typedef struct intercept {
    int64_t art_method;
    int64_t entry;          /* cached trampoline (0 until installed) */
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

static uint32_t decode_rd(uint32_t instr) { return instr & 0x1fu; }
static uint32_t decode_rn(uint32_t instr) { return (instr >> 5) & 0x1fu; }
static uint32_t decode_add_imm(uint32_t instr) { return (instr >> 10) & 0xfffu; }
static int decode_ldr_imm(uint32_t instr) { return (int) (((instr >> 10) & 0xfffu) << 3); }
static uint32_t decode_movz_imm(uint32_t instr) { return (instr >> 5) & 0xffffu; }

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
 * Trampoline construction (unchanged from v0.3.0)
 *
 * Layout (byte offsets):
 *   0x00  stp  x0, x1, [sp, #-0x10]!
 *   0x04  stp  x2, x3, [sp, #-0x10]!
 *   0x08  stp  x4, x5, [sp, #-0x10]!
 *   0x0c  stp  x6, x7, [sp, #-0x10]!
 *   0x10  ldr  x16, [pc, #+0x18]      → literal 0x30 (handler_check)
 *   0x14  blr  x16
 *   0x18  cbnz x0, .use_bridge         ; x0 = branch target (or 0)
 *   0x1c  ldr  x16, [pc, #+0x18]       → literal 0x38 (original)
 *   0x20  b    .restore                ; skip the branch-target load
 *   0x24  .use_bridge:
 *   0x24  mov  x16, x0
 *   0x28  .restore:
 *   0x28  ldp  x6, x7, [sp], #0x10
 *   0x2c  ldp  x4, x5, [sp], #0x10
 *   0x30  ldp  x2, x3, [sp], #0x10
 *   0x34  ldp  x0, x1, [sp], #0x10
 *   0x38  br   x16
 *   0x3c  (pad)
 *   0x30  literal: handler_check address
 *   0x38  literal: original_handler address
 *
 * Literal offsets:
 *   ldr at 0x10, pc = 0x10 + 8 = 0x18, literal at 0x30 → +0x18 → imm19=6 → 0x580000D0
 *   ldr at 0x1c, pc = 0x1c + 8 = 0x24, literal at 0x38 → +0x14 → imm19=5 → 0x580000B0
 *
 * The trampoline uses handler_check's return value:
 *   - non-zero → branch to the Native backend's trampoline
 *     (the method has just been hooked lazily)
 *   - zero     → branch to the original handler (un-hooked)
 * ───────────────────────────────────────────────────────────── */

extern int64_t handler_check_impl(int64_t art_method);
int64_t handler_check(int64_t art_method) {
    return handler_check_impl(art_method);
}

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

    /* Call handler_check(ArtMethod* in x0). */
    p[4]  = 0x580000d0;  /* ldr  x16, [pc, #+0x18] → 0x30 */
    p[5]  = 0xd63f0200;  /* blr  x16 */

    /* If x0 != 0, use x0 as the entry; else use the original. */
    p[6]  = 0xb50000e0;  /* cbnz x0, +0x1c → 0x24 (.use_bridge) */
    p[7]  = 0x580000b0;  /* ldr  x16, [pc, #+0x14] → 0x38 (original) */
    p[8]  = 0x14000002;  /* b    +0x8   → 0x28 (.restore) */
    /* .use_bridge: */
    p[9]  = 0xaa0003f0;  /* mov  x16, x0 */

    /* .restore: */
    p[10] = 0xa8c11fe6;  /* ldp  x6, x7, [sp], #0x10 */
    p[11] = 0xa8c117e4;  /* ldp  x4, x5, [sp], #0x10 */
    p[12] = 0xa8c10fe2;  /* ldp  x2, x3, [sp], #0x10 */
    p[13] = 0xa8c107e0;  /* ldp  x0, x1, [sp], #0x10 */
    p[14] = 0xd61f0200;  /* br   x16 */

    /* Literals. */
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

static int64_t lookup_entry(int64_t art_method) {
    if (art_method == 0) return 0;

    pthread_mutex_lock(&g_intercepts_lock);
    for (intercept_t *p = g_intercepts; p != NULL; p = p->next) {
        if (p->art_method == art_method) {
            int64_t entry = p->entry;
            pthread_mutex_unlock(&g_intercepts_lock);
            return entry;
        }
    }
    pthread_mutex_unlock(&g_intercepts_lock);
    return 0;
}

/*
 * The trampoline calls this. On first sighting of a hooked
 * ArtMethod, hand off to libshizuposed's direct-C lazy install;
 * that call performs the entry-point replacement exactly as
 * nHookArtMethod would, and returns the trampoline to branch to.
 * The returned trampoline is cached so subsequent hits are O(1).
 *
 * If the native bridge refuses (no layout, no registered
 * callback, already hooked by someone else, allocation failure),
 * handler_check_impl returns 0 and the trampoline falls through
 * to the original handler — same behavior as if CallSite
 * weren't here.
 *
 * No callback id is returned to nowhere: the v0.3.0 mistake was
 * that handler_check returned an id that the trampoline
 * discarded. The correct contract is "return a branch target or
 * fall through to original," and this implementation honors it.
 */
int64_t handler_check_impl(int64_t art_method) {
    if (art_method == 0) return 0;

    int64_t entry = lookup_entry(art_method);
    if (entry != 0) return entry;

    /*
     * Retry bridge resolution. libshizuposed may have loaded after
     * libcallsite; resolve_native_bridge_locked is called under
     * g_bridge_mu and only does work if g_bridge_ok == 0.
     */
    if (!g_bridge_ok && !resolve_native_bridge()) {
        return 0;
    }

    if (g_lazy_install != NULL) {
        int64_t t = g_lazy_install(art_method);
        if (t != 0) {
            pthread_mutex_lock(&g_intercepts_lock);
            for (intercept_t *p = g_intercepts; p != NULL; p = p->next) {
                if (p->art_method == art_method) { p->entry = t; break; }
            }
            pthread_mutex_unlock(&g_intercepts_lock);
            return t;
        }
    }
    return 0;
}

static void register_intercept(int64_t art_method) {
    pthread_mutex_lock(&g_intercepts_lock);
    for (intercept_t *p = g_intercepts; p != NULL; p = p->next) {
        if (p->art_method == art_method) {
            p->entry = 0;  /* re-arm for lazy install */
            pthread_mutex_unlock(&g_intercepts_lock);
            return;
        }
    }
    intercept_t *n = malloc(sizeof(*n));
    if (n != NULL) {
        n->art_method = art_method;
        n->entry = 0;
        n->next = g_intercepts;
        g_intercepts = n;
    }
    pthread_mutex_unlock(&g_intercepts_lock);
}

static void unregister_intercept(int64_t art_method) {
    pthread_mutex_lock(&g_intercepts_lock);
    intercept_t **pp = &g_intercepts;
    while (*pp != NULL) {
        if ((*pp)->art_method == art_method) {
            intercept_t *dead = *pp;
            *pp = dead->next;
            free(dead);
            break;
        }
        pp = &(*pp)->next;
    }
    pthread_mutex_unlock(&g_intercepts_lock);
}

/* ─────────────────────────────────────────────────────────────
 * Hook installation (v0.4.1)
 *
 * install_hook_impl arms the intercept without touching the
 * ArtMethod entry point. The entry-point replacement happens
 * lazily, on the first interpreted invocation, via handler_check
 * → shizuposed_lazy_install_artmethod.
 *
 * The callback itself must already have been registered with the
 * Native backend via NativeBridge.nRegisterCallback. CallSite
 * does not own a callback registry; that is the Native backend's
 * job and CallSite routes into it.
 *
 * If the native bridge is absent, install is refused; the Java
 * layer drops the registration it just made.
 * ───────────────────────────────────────────────────────────── */

static int install_hook_impl(int64_t reflect_method) {
    if (!g_art_ok) {
        ALOGW("callsite: install refused — ART symbols not resolved");
        return 0;
    }
    if (!g_interp_ok) {
        ALOGW("callsite: install refused — interpreter table not found");
        return 0;
    }
    if (!resolve_native_bridge()) {
        ALOGW("callsite: install refused — libshizuposed lazy-install "
              "bridge absent (CallSite is a deferred trigger for the "
              "Native backend; without it, installs are no-ops)");
        return 0;
    }

    register_intercept(reflect_method);

    /*
     * Do NOT call g_lazy_install here. The first interpreted
     * invocation does, via handler_check_impl. This preserves the
     * "entry point untouched until first call" property that is
     * CallSite's reason for existing.
     */
    ALOGI("callsite: armed ArtMethod %p — will install on first "
          "interpreted invocation", (void *) reflect_method);
    return 1;
}

static int uninstall_hook_impl(int64_t reflect_method) {
    unregister_intercept(reflect_method);
    if (resolve_native_bridge() && g_lazy_unhook != NULL) {
        g_lazy_unhook(reflect_method);
    }
    return 1;
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

    /*
     * Best-effort bridge resolve at init. May fail if
     * libshizuposed.so loads later; install_hook_impl and
     * handler_check_impl retry.
     */
    resolve_native_bridge();
    install_interpreter_patches();

    ALOGI("CallSiteBackend ready (v%s; native-bridge=%s)",
          CALLSITE_VERSION, g_bridge_ok ? "linked" : "absent");
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_shizuposed_manager_core_backends_CallSiteBackend_nativeInstallHook(
        JNIEnv *env, jclass clazz, jobject method) {
    (void) env; (void) clazz;
    int64_t m = (int64_t) (intptr_t) method;
    return install_hook_impl(m) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_shizuposed_manager_core_backends_CallSiteBackend_nativeUninstallHook(
        JNIEnv *env, jclass clazz, jobject method) {
    (void) env; (void) clazz;
    int64_t m = (int64_t) (intptr_t) method;
    return uninstall_hook_impl(m) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_shizuposed_manager_core_backends_CallSiteBackend_nativeBridgeLinked(
        JNIEnv *env, jclass clazz) {
    (void) env; (void) clazz;
    return resolve_native_bridge() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_com_shizuposed_manager_core_backends_CallSiteBackend_nativeDescribe(
        JNIEnv *env, jclass clazz) {
    (void) clazz;
    char buf[640];
    int bridge_ok = resolve_native_bridge();
    const char *art  = g_art_ok  ? "ART symbols resolved" : "ART symbols unavailable";
    const char *tab  = g_interp_ok ? "interpreter table found" : "interpreter table not found";
    const char *br   = bridge_ok ? "native bridge linked" : "native bridge absent";
    int n = 0;
    pthread_mutex_lock(&g_intercepts_lock);
    for (intercept_t *p = g_intercepts; p != NULL; p = p->next) n++;
    pthread_mutex_unlock(&g_intercepts_lock);
    snprintf(buf, sizeof(buf),
        "callsite: %s | %s | %s | %d armed | %s",
        art, tab, br, n,
        (g_art_ok && g_interp_ok && bridge_ok)
            ? "active (deferred via Native backend)"
            : "inactive");
    return (*env)->NewStringUTF(env, buf);
}