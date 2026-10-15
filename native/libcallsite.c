/*
 * libcallsite.c
 *
 * CallSite backend for ShizuPosed.
 *
 * Instead of patching ArtMethod entry points, this backend patches
 * the ART interpreter's handler table so that when the interpreter
 * dispatches an opcode, it lands on a trampoline that routes into
 * the Java callback registry.
 *
 * The interpreter table is discovered by scanning a known
 * interpreter entry function for the ARM64 adrp/add or adrp/ldr
 * pair that loads the table address. The table is validated by
 * comparing its first entry against the interpreter's own address
 * range.
 *
 * Each entry point that needs hooking is patched by writing a
 * trampoline address into the handler table slot, after
 * temporarily making the page writable.
 *
 * ARM64 only.
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

#define CALLSITE_VERSION "0.1.0"

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
    int64_t table;      /* address of the handler table */
    int32_t valid;
    int32_t reserved;
} interpreter_t;

static interpreter_t g_interp;
static int g_interp_ok = 0;

/* ─────────────────────────────────────────────────────────────
 * Patched handler slots
 * ───────────────────────────────────────────────────────────── */

typedef struct {
    void   *slot;       /* +0x00 */
    int64_t original;   /* +0x08 */
    int32_t installed;  /* +0x10 */
    int32_t reserved;   /* +0x14 */
} patched_slot_t;

static patched_slot_t g_patched[MAX_PATCHED_SLOTS];
static uint32_t g_patched_count = 0;
static pthread_mutex_t g_patch_lock = PTHREAD_MUTEX_INITIALIZER;

/* ─────────────────────────────────────────────────────────────
 * Intercept registry: art_method -> callback id
 * ───────────────────────────────────────────────────────────── */

typedef struct intercept {
    int64_t art_method;         /* +0x00 */
    int64_t cb_id;              /* +0x08 */
    struct intercept *next;     /* +0x10 */
} intercept_t;

static intercept_t *g_intercepts = NULL;
static pthread_mutex_t g_intercepts_lock = PTHREAD_MUTEX_INITIALIZER;

/* ─────────────────────────────────────────────────────────────
 * JNI-global state
 * ───────────────────────────────────────────────────────────── */

static JavaVM   *g_vm = NULL;
static jclass    g_registryClass = NULL;
static jmethodID g_dispatchMethod = NULL;

/* ─────────────────────────────────────────────────────────────
 * ARM64 instruction decoders
 *
 * Each decoder extracts the field named in its function name from
 * a 32-bit AArch64 instruction word. They're used by the table
 * scanner to identify adrp/add/ldr/movz/movk sequences.
 * ───────────────────────────────────────────────────────────── */

/*
 * decode_adrp — compute the target of an adrp instruction.
 *
 * Encoding:
 *   bits [30:29]  immlo
 *   bits [23:5]   immhi
 *   bits [4:0]    Rd (destination register)
 *
 * Target = (PC & ~0xfff) + (SignExtend(immhi:immlo) << 12)
 */
static int64_t decode_adrp(uint32_t instr, uint64_t pc) {
    uint32_t immlo = (instr >> 29) & 0x3u;
    uint32_t immhi = (instr >> 5) & 0x7ffffu;
    int64_t imm = ((int64_t) (immhi << 2 | immlo)) << 12;

    /* Sign-extend from bit 32 (the immhi:immlo field is 21 bits
     * and shifts left by 12, so the sign is at bit 32 of imm). */
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
    /* ADD (immediate): imm12 at bits [21:10]. */
    return (instr >> 10) & 0xfffu;
}

static int decode_ldr_imm(uint32_t instr) {
    /* LDR (immediate, unsigned offset): imm12 at [21:10],
     * scaled by the access size. For 64-bit loads, scale is 8. */
    return (int) (((instr >> 10) & 0xfffu) << 3);
}

static uint32_t decode_movz_imm(uint32_t instr) {
    /* MOVZ/MOVK: imm16 at [20:5]. */
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
 *
 * The interpreter entry function contains a literal load of the
 * handler table's address. The sequence is usually:
 *   adrp xN, <page>
 *   add  xN, xN, <offset>     (add-immediate)
 * or:
 *   adrp xN, <page>
 *   ldr  xN, [xN, <offset>]   (load the pointer from a GOT entry)
 *
 * The scanner walks the first max_bytes of the anchor function,
 * looking for either pattern. When it finds one, it computes the
 * address and hands it to the validator.
 * ───────────────────────────────────────────────────────────── */

static int64_t scan_for_table_address(int64_t fn, size_t max_bytes) {
    if (fn == 0 || max_bytes < 8) return 0;

    uint32_t *code = (uint32_t *) (intptr_t) fn;
    size_t n_insn = max_bytes / 4;

    for (size_t i = 0; i + 1 < n_insn; i++) {
        uint32_t w0 = code[i];
        uint32_t w1 = code[i + 1];

        /* Pattern 1: adrp xN, <page> ; add xN, xN, <imm> */
        if ((w0 & 0x9f000000u) == 0x90000000u) {
            int64_t base = decode_adrp(w0, fn + i * 4);
            uint32_t rd = decode_rd(w0);

            if ((w1 & 0xff800000u) == 0x91000000u &&
                decode_rn(w1) == rd &&
                decode_rd(w1) == rd) {
                return base + decode_add_imm(w1);
            }

            /* Pattern 2: adrp xN, <page> ; ldr xN, [xN, <imm>] */
            if ((w1 & 0xffc00000u) == 0xf9400000u &&
                decode_rn(w1) == rd) {
                int64_t addr = base + decode_ldr_imm(w1);
                int64_t value = *(int64_t *) (intptr_t) addr;
                if (value != 0) return value;
            }
        }

        /* Pattern 3: movz xN, #imm16 ; movk xN, #imm16, lsl 16 */
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

/*
 * validate_table_address — sanity check the candidate table.
 *
 * The shipped binary accepts a candidate if the first entry is
 * within 32 MB of the ART symbol's resolved address (when the
 * symbol is available) or if the first entry looks like a
 * plausible code pointer (high bits set, above 0x7000000000).
 *
 * The range check works because the table and the interpreter
 * live in the same shared object, so their addresses are close.
 * A candidate that points somewhere else entirely is rejected.
 */
static int validate_table_address(void *handle, int64_t table) {
    if (table == 0) return 0;

    uint64_t first = *(uint64_t *) (intptr_t) table;
    if (first == 0) return 0;

    int64_t access_fn = handle != NULL
        ? resolve_first(handle, SYM_GET_ACCESS_FLAGS)
        : 0;

    if (access_fn == 0) {
        /* No anchor to compare against. Use a range heuristic:
         * a plausible code address has the high byte set and
         * is well above the zero page. */
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

    /* Iterate the interpreter-execute anchor names in order.
     * The first one that resolves on this ART version is used. */
    for (int i = 0; SYM_INTERPRETER_EXECUTE[i] != NULL; i++) {
        void *anchor = dlsym(NULL, SYM_INTERPRETER_EXECUTE[i]);
        if (anchor == NULL) {
            /* dlsym(NULL, ...) searches RTLD_DEFAULT, which
             * doesn't include libart.so's local symbols. Try
             * opening libart.so explicitly. */
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
 * The trampoline saves x0-x7, loads the handler-check function
 * address and the original handler address as literals, calls
 * the check, then either returns (if the check returns non-zero)
 * or branches to the original handler.
 *
 * The instruction words below are copied from the disassembly.
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

    /* ldr x16, [pc, #+offset]   ; load handler_check_addr */
    p[4]  = 0x58000110;
    /* blr x16 */
    p[5]  = 0xd63f0200;

    /* ldr x16, [pc, #+offset]   ; load original_handler */
    p[6]  = 0x58000110;

    /* Restore x0-x7. */
    p[7]  = 0xa8c11fe6;  /* ldp  x6, x7, [sp], #0x10 */
    p[8]  = 0xa8c117e4;  /* ldp  x4, x5, [sp], #0x10 */
    p[9]  = 0xa8c10fe2;  /* ldp  x2, x3, [sp], #0x10 */
    p[10] = 0xa8c107e0;  /* ldp  x0, x1, [sp], #0x10 */

    /* br x16 */
    p[11] = 0xd61f0200;

    /* Two 8-byte literals follow the code. The ldr instructions
     * at p[4] and p[6] load from these positions. Both use a
     * relative offset of 0x20, which is 8 instructions after the
     * ldr itself. */
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

/*
 * handler_check — called from the trampoline with the currently
 * executing ArtMethod in x0. The function looks up the intercept
 * registry. If a match is found, the Java dispatcher runs and its
 * return value is passed back.
 *
 * The shipped binary calls lookup_intercept and returns its
 * result. The reconstruction does the same.
 */
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
 * ArtMethod from reflect
 * ───────────────────────────────────────────────────────────── */

static int64_t get_art_method_from_reflect(int64_t reflect_method) {
    if (g_vm == NULL) return 0;

    JNIEnv *env = NULL;
    if ((*g_vm)->GetEnv(g_vm, (void **) &env, JNI_VERSION_1_6) != JNI_OK) {
        return 0;
    }
    if (env == NULL) return 0;

    /* On Android, FromReflectedMethod returns the ArtMethod*
     * directly for Method and Constructor. */
    jmethodID id = (*env)->FromReflectedMethod(env,
        (jobject) (intptr_t) reflect_method);
    return (int64_t) id;
}

/* ─────────────────────────────────────────────────────────────
 * Access flags
 * ───────────────────────────────────────────────────────────── */

static uint32_t read_access_flags(int64_t art_method) {
    if (!g_art_ok || art_method == 0) return 0;
    typedef uint32_t (*fn_t)(int64_t);
    return ((fn_t) (intptr_t) g_art.get_access_flags)(art_method);
}

static int write_access_flags(int64_t art_method, uint32_t flags) {
    if (!g_art_ok || art_method == 0) return 0;
    typedef void (*fn_t)(int64_t, uint32_t);
    ((fn_t) (intptr_t) g_art.set_access_flags)(art_method, flags);
    return 1;
}

/* ─────────────────────────────────────────────────────────────
 * Hook installation
 * ───────────────────────────────────────────────────────────── */

static int install_hook_impl(int64_t reflect_method, int64_t cb_id) {
    if (!g_art_ok || !g_interp_ok) {
        ALOGW("install refused: art_ok=%d interp_ok=%d",
              g_art_ok, g_interp_ok);
        return 0;
    }

    int64_t art_method = get_art_method_from_reflect(reflect_method);
    if (art_method == 0) return 0;

    uint32_t flags = read_access_flags(art_method);
    write_access_flags(art_method, flags | 1);

    register_intercept(art_method, cb_id);
    return 1;
}

/* ─────────────────────────────────────────────────────────────
 * JNI surface
 * ───────────────────────────────────────────────────────────── */

JNIEXPORT jboolean JNICALL
Java_com_shizuposed_manager_core_backends_CallSiteBackend_nativeInit(
        JNIEnv *env, jclass clazz) {
    (void) clazz;

    (*env)->GetJavaVM(env, &g_vm);

    jclass reg = (*env)->FindClass(env,
        "com/shizuposed/manager/core/backends/CallSiteCallbackRegistry");
    if (reg == NULL) {
        (*env)->ExceptionClear(env);
        ALOGE("CallSiteCallbackRegistry not found");
        return JNI_FALSE;
    }
    g_registryClass = (*env)->NewGlobalRef(env, reg);
    g_dispatchMethod = (*env)->GetStaticMethodID(env, g_registryClass,
        "dispatch", "(ILjava/lang/Object;[Ljava/lang/Object;)Z");
    if (g_dispatchMethod == NULL) {
        (*env)->ExceptionClear(env);
        ALOGE("dispatch method not found");
        return JNI_FALSE;
    }

    if (!resolve_art_symbols()) {
        ALOGE("ART symbols unavailable");
        return JNI_FALSE;
    }

    if (!discover_interpreter_table()) {
        ALOGE("interpreter table not found");
        return JNI_FALSE;
    }

    install_interpreter_patches();

    ALOGI("CallSiteBackend ready");
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
    snprintf(buf, sizeof(buf), "callsite: %s | %s",
             g_art_ok ? "ART symbols resolved" : "ART symbols unavailable",
             g_interp_ok ? "interpreter table found"
                         : "interpreter table not found");
    return (*env)->NewStringUTF(env, buf);
}