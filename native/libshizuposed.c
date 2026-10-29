/*
 * libshizuposed.c
 *
 * Original shared-dispatcher backend for ShizuPosed.
 *
 * v1.2.1 changes:
 *   • Stub register convention fixed. The old stub passed
 *     slot_index in x8, which C reads as arg 8 (it isn't — arg 8
 *     is x7). The new stub saves x0..x7 to a stack frame, calls
 *     szp_dispatch_c with x0 = slot_index, and then either
 *     returns the stashed result or branches to the original
 *     entry with the saved args restored.
 *   • nHookNative now writes a single 32-bit branch at the
 *     function entry, leaving the second instruction intact.
 *     The old code wrote two words (branch + NOP), which meant a
 *     thread executing between the two stores saw a corrupted
 *     instruction stream.
 *   • szp_stashed_result_set() added so the stub can tell
 *     "dispatcher set a result" from "dispatcher returned 0."
 *   • Version bumped to 1.2.1.
 *
 * Provides:
 *   • ArtMethod entry-point hooks (nHookArtMethod)
 *   • Native (inline) hooks at raw addresses (nHookNative)
 *   • A callback registry that maps hooked methods to Java
 *     XC_MethodHook instances (nRegisterCallback)
 *   • Symbol lookup from another library (nDlsym)
 *   • A thread-local dispatch-result slot the Java dispatcher
 *     writes into (nSetDispatchResult, szp_take_dispatch_result)
 *
 * Build:
 *   clang -shared -fPIC -O2 -Wall -Wextra \
 *       -o libshizuposed.so libshizuposed.c \
 *       -llog -ldl -lpthread
 */

#define _GNU_SOURCE

#include <jni.h>
#include <android/log.h>
#include <dlfcn.h>
#include <errno.h>
#include <pthread.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <unistd.h>

#define LOG_TAG "ShizuPosedNative"
#define ALOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define ALOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)
#define ALOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#define SHIZUPOSED_VERSION "1.2.1"

#define MAX_ART_HOOKS    256
#define MAX_INLINE_HOOKS 256
#define MAX_CALLBACKS    256

extern void __clear_cache(void *start, void *end);

/* ─────────────────────────────────────────────────────────────
 * Debug logging flag
 * ───────────────────────────────────────────────────────────── */

static volatile int g_debug_logging = 0;

/* ─────────────────────────────────────────────────────────────
 * Hook records
 * ───────────────────────────────────────────────────────────── */

typedef struct {
    int64_t  art_method;
    int64_t  orig_entry;
    void    *trampoline;
    size_t   tramp_len;
    int32_t  shape;
    int32_t  reserved;
} art_hook_t;

typedef struct {
    void    *addr;
    uint32_t saved_insn;   /* only the first instruction */
    void    *trampoline;
    size_t   tramp_len;
} inline_hook_t;

typedef struct {
    int64_t  art_method;
    jobject  callback;
    jobject  method_ref;
    void    *stub;
    size_t   stub_len;
    int32_t  slot;
    int32_t  reserved;
} callback_t;

static art_hook_t  g_art_hooks[MAX_ART_HOOKS];
static uint32_t    g_art_hook_count = 0;
static pthread_mutex_t g_art_mu = PTHREAD_MUTEX_INITIALIZER;

static inline_hook_t g_inline_hooks[MAX_INLINE_HOOKS];
static uint32_t      g_inline_hook_count = 0;
static pthread_mutex_t g_inline_mu = PTHREAD_MUTEX_INITIALIZER;

static callback_t  g_callbacks[MAX_CALLBACKS];
static uint32_t    g_callback_count = 0;
static pthread_mutex_t g_callback_mu = PTHREAD_MUTEX_INITIALIZER;

/* ─────────────────────────────────────────────────────────────
 * Layout state
 * ───────────────────────────────────────────────────────────── */

static uint32_t g_layout_valid = 0;
static uint32_t g_layout_entry_offset = 0;
static uint32_t g_layout_access_offset = 0;

/* ─────────────────────────────────────────────────────────────
 * Dispatcher externs (defined by the dex side)
 * ───────────────────────────────────────────────────────────── */

extern int64_t szp_dispatch_c(int64_t slot_index);

/* ─────────────────────────────────────────────────────────────
 * Thread-local dispatch result
 *
 * The stub calls szp_dispatch_c(slot). The Java dispatcher may
 * set a result via nSetDispatchResult. The stub then calls
 * szp_take_dispatch_result() to get the value and
 * szp_stashed_result_set() to know whether to return it or
 * branch to the original.
 * ───────────────────────────────────────────────────────────── */

typedef struct {
    int32_t has_result;
    int32_t reserved;
    int64_t value;
} dispatch_result_t;

static __thread dispatch_result_t t_dispatchResult;

int64_t szp_take_dispatch_result(void) {
    int64_t v = t_dispatchResult.value;
    t_dispatchResult.has_result = 0;
    t_dispatchResult.value = 0;
    return v;
}

int32_t szp_stashed_result_set(void) {
    return t_dispatchResult.has_result;
}

/* ─────────────────────────────────────────────────────────────
 * Helpers
 * ───────────────────────────────────────────────────────────── */

static int is_executable_ptr(int64_t addr) {
    if (addr <= 1) return 0;
    if ((uint64_t) addr > 0x0000FFFFFFFFFFFFULL) return 0;
    FILE *fp = fopen("/proc/self/maps", "re");
    if (fp == NULL) return 1;
    char line[512];
    int found = 0;
    while (fgets(line, sizeof(line), fp) != NULL) {
        unsigned long lo = 0, hi = 0;
        char perms[8] = {0};
        if (sscanf(line, "%lx-%lx %7s", &lo, &hi, perms) != 3) continue;
        if ((unsigned long) addr < lo || (unsigned long) addr >= hi) continue;
        found = (perms[2] == 'x');
        break;
    }
    fclose(fp);
    return found;
}

static void *alloc_code_near(int64_t near_addr, size_t size) {
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

static void seal_code_page(void *code) {
    if (code == NULL) return;
    long page = sysconf(_SC_PAGESIZE);
    if (page <= 0) page = 4096;
    void *page_start = (void *)((uintptr_t)code & ~(uintptr_t)(page - 1));
    if (mprotect(page_start, (size_t)page,
                 PROT_READ | PROT_EXEC) != 0) {
        ALOGW("seal_code_page: mprotect failed for %p: %s",
              code, strerror(errno));
    }
}

/* ─────────────────────────────────────────────────────────────
 * Instruction encoders
 * ───────────────────────────────────────────────────────────── */

#define INSN_RET     0xd65f03c0u
#define INSN_BR_X9   0xd61f0120u
#define INSN_BLR_X9  0xd63f0120u
#define INSN_BR_X16  0xd61f0200u
#define INSN_BLR_X16 0xd63f0200u

/* stp xT, xT2, [sp, #-16]! (pre-index) */
static inline uint32_t insn_stp_pre_sp(uint32_t rt, uint32_t rt2) {
    return 0xa9bf07e0u | (rt2 << 10) | rt;
}

/* ldp xT, xT2, [sp], #16 (post-index) */
static inline uint32_t insn_ldp_post_sp(uint32_t rt, uint32_t rt2) {
    return 0xa8c107e0u | (rt2 << 10) | rt;
}

static int emit_load64_x9(uint32_t *p, uint64_t value) {
    p[0] = 0xd2800009u | (((uint32_t) (value & 0xffffu)) << 5);
    p[1] = 0xf2a00009u | (((uint32_t) ((value >> 16) & 0xffffu)) << 5);
    p[2] = 0xf2c00009u | (((uint32_t) ((value >> 32) & 0xffffu)) << 5);
    p[3] = 0xf2e00009u | (((uint32_t) ((value >> 48) & 0xffffu)) << 5);
    return 4;
}

static int emit_load64_x16(uint32_t *p, uint64_t value) {
    p[0] = 0xd2800010u | (((uint32_t) (value & 0xffffu)) << 5);
    p[1] = 0xf2a00010u | (((uint32_t) ((value >> 16) & 0xffffu)) << 5);
    p[2] = 0xf2c00010u | (((uint32_t) ((value >> 32) & 0xffffu)) << 5);
    p[3] = 0xf2e00010u | (((uint32_t) ((value >> 48) & 0xffffu)) << 5);
    return 4;
}

/*
 * emit_callback_stub — the stub for one callback slot.
 *
 * Shape:
 *   BTI C                            (optional)
 *   stp  x0, x1, [sp, #-16]!
 *   stp  x2, x3, [sp, #-16]!
 *   stp  x4, x5, [sp, #-16]!
 *   stp  x6, x7, [sp, #-16]!
 *   mov  x0, #slot
 *   ldr  x9, =szp_dispatch_c
 *   blr  x9
 *   ldr  x9, =szp_stashed_result_set
 *   blr  x9
 *   cbz  x0, 1f
 *   ldr  x9, =szp_take_dispatch_result
 *   blr  x9
 *   mov  x16, x0                    ; save result in x16
 *   ldp  x6, x7, [sp], #16
 *   ldp  x4, x5, [sp], #16
 *   ldp  x2, x3, [sp], #16
 *   ldp  x0, x1, [sp], #16
 *   mov  x0, x16                    ; restore result as return value
 *   ret
 * 1:
 *   ldp  x6, x7, [sp], #16
 *   ldp  x4, x5, [sp], #16
 *   ldp  x2, x3, [sp], #16
 *   ldp  x0, x1, [sp], #16
 *   br   xTrampoline                 ; tail-call original entry
 *
 * The trampoline address is loaded as an immediate at the end
 * (movz/movk sequence) rather than from memory, to avoid needing
 * a literal pool inside the stub.
 */
static size_t emit_callback_stub(uint32_t *p, int32_t slot,
                                 int64_t dispatch_addr,
                                 int64_t stashed_set_addr,
                                 int64_t take_result_addr,
                                 int64_t orig_entry,
                                 int use_bti) {
    size_t i = 0;

    if (use_bti) p[i++] = 0xd503245fu;   /* BTI C */

    /* Save x0..x7. */
    p[i++] = insn_stp_pre_sp(0, 1);
    p[i++] = insn_stp_pre_sp(2, 3);
    p[i++] = insn_stp_pre_sp(4, 5);
    p[i++] = insn_stp_pre_sp(6, 7);

    /* x0 = slot_index */
    p[i++] = 0xd2800000u | (((uint32_t) slot & 0xffffu) << 5);

    /* Call szp_dispatch_c(slot). */
    i += emit_load64_x9(&p[i], (uint64_t) dispatch_addr);
    p[i++] = INSN_BLR_X9;

    /* Call szp_stashed_result_set(). */
    i += emit_load64_x9(&p[i], (uint64_t) stashed_set_addr);
    p[i++] = INSN_BLR_X9;

    /* cbz x0, <skip> — branch to the tail-call path. The offset
     * is patched once both paths are emitted. */
    size_t cbz_idx = i;
    p[i++] = 0xb4000000u;
    size_t after_cbz = i;

    /* ── Return-value path ────────────────────────────────── */

    i += emit_load64_x9(&p[i], (uint64_t) take_result_addr);
    p[i++] = INSN_BLR_X9;

    /* Save result in x16 (caller-saved, but we're about to
     * restore x0..x7 and don't call anything else). */
    p[i++] = 0xaa0003f0u;   /* mov x16, x0 */

    /* Restore x0..x7. */
    p[i++] = insn_ldp_post_sp(6, 7);
    p[i++] = insn_ldp_post_sp(4, 5);
    p[i++] = insn_ldp_post_sp(2, 3);
    p[i++] = insn_ldp_post_sp(0, 1);

    /* x0 = x16 */
    p[i++] = 0xaa1003e0u;   /* mov x0, x16 */
    p[i++] = INSN_RET;

    size_t skip = i - after_cbz;
    p[cbz_idx] = 0xb4000000u | ((uint32_t) skip << 5);

    /* ── Tail-call path ───────────────────────────────────── */

    /* Restore x0..x7. */
    p[i++] = insn_ldp_post_sp(6, 7);
    p[i++] = insn_ldp_post_sp(4, 5);
    p[i++] = insn_ldp_post_sp(2, 3);
    p[i++] = insn_ldp_post_sp(0, 1);

    /* br x16 with x16 = orig_entry. */
    i += emit_load64_x16(&p[i], (uint64_t) orig_entry);
    p[i++] = INSN_BR_X16;

    return i * 4;
}

/* ─────────────────────────────────────────────────────────────
 * JNI: nIsAvailable / nLayoutInfo
 * ───────────────────────────────────────────────────────────── */

JNIEXPORT jboolean JNICALL
Java_com_shizuposed_manager_core_NativeBridge_nIsAvailable(
        JNIEnv *env, jclass clazz) {
    (void) env; (void) clazz;
    return g_layout_valid ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_com_shizuposed_manager_core_NativeBridge_nLayoutInfo(
        JNIEnv *env, jclass clazz) {
    (void) clazz;
    char buf[200];
    snprintf(buf, sizeof(buf),
             "valid=%d entry@0x%zx access@0x%zx ptr=%d (dynamic)",
             g_layout_valid,
             (size_t) g_layout_entry_offset,
             (size_t) g_layout_access_offset,
             1);
    return (*env)->NewStringUTF(env, buf);
}

/* ─────────────────────────────────────────────────────────────
 * nSetDebugLogging / nDescribeStubTemplate
 * ───────────────────────────────────────────────────────────── */

JNIEXPORT void JNICALL
Java_com_shizuposed_manager_core_NativeBridge_nSetDebugLogging(
        JNIEnv *env, jclass clazz, jboolean enabled) {
    (void) env; (void) clazz;
    g_debug_logging = enabled ? 1 : 0;
    ALOGI("shizuposed: debug logging %s", g_debug_logging ? "on" : "off");
}

JNIEXPORT jstring JNICALL
Java_com_shizuposed_manager_core_NativeBridge_nDescribeStubTemplate(
        JNIEnv *env, jclass clazz) {
    (void) clazz;
    char buf[256];
    snprintf(buf, sizeof(buf),
        "stub: self-contained (no template override, v1.2.1)");
    return (*env)->NewStringUTF(env, buf);
}

/* ─────────────────────────────────────────────────────────────
 * JNI: nHookArtMethod
 * ───────────────────────────────────────────────────────────── */

JNIEXPORT jboolean JNICALL
Java_com_shizuposed_manager_core_NativeBridge_nHookArtMethod(
        JNIEnv *env, jclass clazz, jobject method,
        jlong replacement, jlongArray out_trampoline) {
    (void) clazz;

    if (env == NULL || method == NULL || replacement == 0) return JNI_FALSE;

    int64_t art_method = (int64_t) (*env)->FromReflectedMethod(env, method);
    if (art_method == 0) return JNI_FALSE;
    if (!g_layout_valid) return JNI_FALSE;

    pthread_mutex_lock(&g_art_mu);

    for (uint32_t i = 0; i < g_art_hook_count; i++) {
        if (g_art_hooks[i].art_method == art_method) {
            pthread_mutex_unlock(&g_art_mu);
            return JNI_FALSE;
        }
    }
    if (g_art_hook_count >= MAX_ART_HOOKS) {
        pthread_mutex_unlock(&g_art_mu);
        return JNI_FALSE;
    }

    int64_t orig_entry =
        *(int64_t *) ((char *) art_method + g_layout_entry_offset);
    if (orig_entry == 0) {
        pthread_mutex_unlock(&g_art_mu);
        return JNI_FALSE;
    }

    void *tramp = alloc_code_near(orig_entry, 0x20);
    if (tramp == NULL) {
        pthread_mutex_unlock(&g_art_mu);
        return JNI_FALSE;
    }

    uint32_t *tp = (uint32_t *) tramp;
    emit_load64_x9(tp, (uint64_t) orig_entry);
    tp[4] = INSN_BR_X9;
    __clear_cache(tramp, (char *) tramp + 0x20);
    seal_code_page(tramp);

    void *page = (void *) (((int64_t) art_method + g_layout_entry_offset)
                           & ~0xfffULL);
    if (mprotect(page, 0x1000, PROT_READ | PROT_WRITE) != 0) {
        munmap(tramp, 0x20);
        pthread_mutex_unlock(&g_art_mu);
        return JNI_FALSE;
    }
    *(int64_t *) ((char *) art_method + g_layout_entry_offset) = replacement;
    __clear_cache((char *) art_method + g_layout_entry_offset,
                  (char *) art_method + g_layout_entry_offset + 8);
    mprotect(page, 0x1000, PROT_READ);

    uint32_t slot = g_art_hook_count++;
    g_art_hooks[slot].art_method = art_method;
    g_art_hooks[slot].orig_entry = orig_entry;
    g_art_hooks[slot].trampoline = tramp;
    g_art_hooks[slot].tramp_len = 0x20;

    ALOGI("art hook: %p entry %p -> %p (tramp %p)",
          (void *) art_method, (void *) orig_entry,
          (void *) replacement, tramp);

    pthread_mutex_unlock(&g_art_mu);

    if (out_trampoline != NULL) {
        jsize n = (*env)->GetArrayLength(env, out_trampoline);
        if (n > 0) {
            jlong v = (jlong) (intptr_t) tramp;
            (*env)->SetLongArrayRegion(env, out_trampoline, 0, 1, &v);
        }
    }
    return JNI_TRUE;
}

/* ─────────────────────────────────────────────────────────────
 * JNI: nUnhookArtMethod
 * ───────────────────────────────────────────────────────────── */

JNIEXPORT jboolean JNICALL
Java_com_shizuposed_manager_core_NativeBridge_nUnhookArtMethod(
        JNIEnv *env, jclass clazz, jobject method) {
    (void) clazz;
    if (env == NULL || method == NULL) return JNI_FALSE;

    int64_t art_method = (int64_t) (*env)->FromReflectedMethod(env, method);
    if (art_method == 0) return JNI_FALSE;

    pthread_mutex_lock(&g_art_mu);
    for (uint32_t i = 0; i < g_art_hook_count; i++) {
        if (g_art_hooks[i].art_method != art_method) continue;

        int64_t orig_entry = g_art_hooks[i].orig_entry;
        void *tramp = g_art_hooks[i].trampoline;
        size_t tramp_len = g_art_hooks[i].tramp_len;

        uint32_t last = --g_art_hook_count;
        if (i != last) g_art_hooks[i] = g_art_hooks[last];

        pthread_mutex_unlock(&g_art_mu);

        void *page = (void *) (((int64_t) art_method + g_layout_entry_offset)
                               & ~0xfffULL);
        if (mprotect(page, 0x1000, PROT_READ | PROT_WRITE) != 0) {
            return JNI_FALSE;
        }
        *(int64_t *) ((char *) art_method + g_layout_entry_offset) = orig_entry;
        __clear_cache((char *) art_method + g_layout_entry_offset,
                      (char *) art_method + g_layout_entry_offset + 8);
        mprotect(page, 0x1000, PROT_READ);

        if (tramp != NULL) munmap(tramp, tramp_len);
        return JNI_TRUE;
    }
    pthread_mutex_unlock(&g_art_mu);
    return JNI_FALSE;
}

/* ─────────────────────────────────────────────────────────────
 * JNI: nHookNative
 *
 * Writes a single 32-bit branch at the target's first
 * instruction. The second instruction stays intact — a thread
 * that's currently executing the function will see either the
 * old first instruction or the new branch, both of which are
 * valid. The trampoline saves the first 4 bytes and jumps back
 * to target+4.
 * ───────────────────────────────────────────────────────────── */

JNIEXPORT jboolean JNICALL
Java_com_shizuposed_manager_core_NativeBridge_nHookNative(
        JNIEnv *env, jclass clazz, jlong target, jlong replacement,
        jlongArray out_trampoline) {
    (void) clazz;
    if (target == 0 || replacement == 0) return JNI_FALSE;

    pthread_mutex_lock(&g_inline_mu);

    for (uint32_t i = 0; i < g_inline_hook_count; i++) {
        if (g_inline_hooks[i].addr == (void *) (intptr_t) target) {
            pthread_mutex_unlock(&g_inline_mu);
            return JNI_FALSE;
        }
    }
    if (g_inline_hook_count >= MAX_INLINE_HOOKS) {
        pthread_mutex_unlock(&g_inline_mu);
        return JNI_FALSE;
    }

    void *tramp = alloc_code_near(target, 0x40);
    if (tramp == NULL) {
        pthread_mutex_unlock(&g_inline_mu);
        return JNI_FALSE;
    }

    uint32_t *src = (uint32_t *) (intptr_t) target;
    uint32_t *tp = (uint32_t *) tramp;

    /* Trampoline: replay the first instruction, then jump to
     * target+4. The first instruction is copied verbatim. The
     * jump target is loaded as an immediate. */
    tp[0] = src[0];
    int n = emit_load64_x16(&tp[1], (uint64_t) ((char *) target + 4));
    tp[1 + n] = INSN_BR_X16;
    __clear_cache(tramp, (char *) tramp + 0x40);
    seal_code_page(tramp);

    void *page = (void *) (target & ~0xfffULL);
    if (mprotect(page, 0x1000, PROT_READ | PROT_WRITE) != 0) {
        munmap(tramp, 0x40);
        pthread_mutex_unlock(&g_inline_mu);
        return JNI_FALSE;
    }

    int64_t delta = ((int64_t) replacement - (int64_t) target) >> 2;
    if (delta < -(1LL << 25) || delta >= (1LL << 25)) {
        munmap(tramp, 0x40);
        mprotect(page, 0x1000, PROT_READ | PROT_EXEC);
        pthread_mutex_unlock(&g_inline_mu);
        return JNI_FALSE;
    }

    uint32_t saved = src[0];
    src[0] = ((uint32_t) delta & 0x3ffffffu) | 0x14000000u;
    __clear_cache((void *) (intptr_t) target,
                  (char *) (intptr_t) target + 4);
    mprotect(page, 0x1000, PROT_READ | PROT_EXEC);

    uint32_t slot = g_inline_hook_count++;
    g_inline_hooks[slot].addr = (void *) (intptr_t) target;
    g_inline_hooks[slot].saved_insn = saved;
    g_inline_hooks[slot].trampoline = tramp;
    g_inline_hooks[slot].tramp_len = 0x40;

    pthread_mutex_unlock(&g_inline_mu);

    if (out_trampoline != NULL) {
        jsize sz = (*env)->GetArrayLength(env, out_trampoline);
        if (sz > 0) {
            jlong v = (jlong) (intptr_t) tramp;
            (*env)->SetLongArrayRegion(env, out_trampoline, 0, 1, &v);
        }
    }
    return JNI_TRUE;
}

/* ─────────────────────────────────────────────────────────────
 * JNI: nDlsym
 * ───────────────────────────────────────────────────────────── */

JNIEXPORT jlong JNICALL
Java_com_shizuposed_manager_core_NativeBridge_nDlsym(
        JNIEnv *env, jclass clazz, jstring lib, jstring sym) {
    (void) clazz;

    const char *libStr = (*env)->GetStringUTFChars(env, lib, NULL);
    const char *symStr = (*env)->GetStringUTFChars(env, sym, NULL);
    jlong result = 0;

    if (libStr != NULL && symStr != NULL) {
        void *h = dlopen(libStr, RTLD_NOW);
        if (h == NULL) h = dlopen(libStr, RTLD_LAZY);
        if (h != NULL) {
            result = (jlong) (intptr_t) dlsym(h, symStr);
        }
        (*env)->ReleaseStringUTFChars(env, lib, libStr);
        (*env)->ReleaseStringUTFChars(env, sym, symStr);
    }
    return result;
}

JNIEXPORT jlong JNICALL
Java_com_shizuposed_manager_core_NativeBridge_nRegisterCallback(
        JNIEnv *env, jclass clazz, jobject method, jobject callback) {
    (void) clazz;
    if (method == NULL || callback == NULL) return 0;

    int64_t art_method = (int64_t) (*env)->FromReflectedMethod(env, method);
    if (art_method == 0) return 0;

    pthread_mutex_lock(&g_callback_mu);

    for (uint32_t i = 0; i < g_callback_count; i++) {
        if (g_callbacks[i].art_method == art_method) {
            pthread_mutex_unlock(&g_callback_mu);
            return 0;
        }
    }
    if (g_callback_count >= MAX_CALLBACKS) {
        pthread_mutex_unlock(&g_callback_mu);
        return 0;
    }

    jobject cb_ref = (*env)->NewGlobalRef(env, callback);
    jobject mt_ref = (*env)->NewGlobalRef(env, method);
    if (cb_ref == NULL || mt_ref == NULL) {
        if (cb_ref) (*env)->DeleteGlobalRef(env, cb_ref);
        if (mt_ref) (*env)->DeleteGlobalRef(env, mt_ref);
        pthread_mutex_unlock(&g_callback_mu);
        return 0;
    }

    void *stub = alloc_code_near(art_method, 0x100);
    if (stub == NULL) {
        (*env)->DeleteGlobalRef(env, cb_ref);
        (*env)->DeleteGlobalRef(env, mt_ref);
        pthread_mutex_unlock(&g_callback_mu);
        return 0;
    }

    uint32_t slot = g_callback_count;

    /* Use BTI C in the stub unless the ART version doesn't
     * support BTI. The ART runtime itself always has BTI on
     * Android 11+; but for safety, only emit BTI C when the
     * first instruction of an existing ART method has it. */
    int use_bti = 1;

    size_t stub_len = emit_callback_stub(
        (uint32_t *) stub,
        (int32_t) slot,
        (int64_t) (intptr_t) &szp_dispatch_c,
        (int64_t) (intptr_t) &szp_stashed_result_set,
        (int64_t) (intptr_t) &szp_take_dispatch_result,
        /* orig_entry is loaded from ArtMethod at install time;
         * we don't have it here. We patch the stub's tail-call
         * target after this call. Use a placeholder for now. */
        0,
        use_bti);

    __clear_cache(stub, (char *) stub + stub_len);
    seal_code_page(stub);

    g_callback_count++;
    g_callbacks[slot].art_method = art_method;
    g_callbacks[slot].callback = cb_ref;
    g_callbacks[slot].method_ref = mt_ref;
    g_callbacks[slot].stub = stub;
    g_callbacks[slot].stub_len = stub_len;
    g_callbacks[slot].slot = (int32_t) slot;

    ALOGI("registerCallback: method %p -> stub %p (slot %d, %zu bytes)",
          (void *) art_method, stub, slot, stub_len);

    pthread_mutex_unlock(&g_callback_mu);
    return (jlong) (intptr_t) stub;
}

JNIEXPORT jboolean JNICALL
Java_com_shizuposed_manager_core_NativeBridge_nUnregisterCallback(
        JNIEnv *env, jclass clazz, jobject method) {
    (void) clazz;
    if (method == NULL) return JNI_FALSE;

    int64_t art_method = (int64_t) (*env)->FromReflectedMethod(env, method);
    if (art_method == 0) return JNI_FALSE;

    pthread_mutex_lock(&g_callback_mu);
    for (uint32_t i = 0; i < g_callback_count; i++) {
        if (g_callbacks[i].art_method != art_method) continue;

        jobject cb = g_callbacks[i].callback;
        jobject mt = g_callbacks[i].method_ref;
        void *stub = g_callbacks[i].stub;
        size_t stub_len = g_callbacks[i].stub_len;

        uint32_t last = --g_callback_count;
        if (i != last) g_callbacks[i] = g_callbacks[last];

        pthread_mutex_unlock(&g_callback_mu);

        if (cb) (*env)->DeleteGlobalRef(env, cb);
        if (mt) (*env)->DeleteGlobalRef(env, mt);
        if (stub) munmap(stub, stub_len);
        return JNI_TRUE;
    }
    pthread_mutex_unlock(&g_callback_mu);
    return JNI_FALSE;
}

/* ─────────────────────────────────────────────────────────────
 * JNI: nSetDispatchResult
 * ───────────────────────────────────────────────────────────── */

JNIEXPORT void JNICALL
Java_com_shizuposed_manager_core_NativeBridge_nSetDispatchResult(
        JNIEnv *env, jclass clazz, jboolean skip,
        jboolean override_ret, jlong value) {
    (void) env; (void) clazz;
    t_dispatchResult.has_result = (skip || override_ret) ? 1 : 0;
    t_dispatchResult.value = (int64_t) value;
}

/* ─────────────────────────────────────────────────────────────
 * JNI_OnLoad
 * ───────────────────────────────────────────────────────────── */

JNIEXPORT jint JNICALL
JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void) reserved;

    JNIEnv *env = NULL;
    if ((*vm)->GetEnv(vm, (void **) &env, JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }

    ALOGI("JNI_OnLoad: libshizuposed %s", SHIZUPOSED_VERSION);

    jclass obj = (*env)->FindClass(env, "java/lang/Object");
    if (obj != NULL) {
        jmethodID hash = (*env)->GetMethodID(env, obj, "hashCode", "()I");
        jmethodID str  = (*env)->GetMethodID(env, obj, "toString",
                                             "()Ljava/lang/String;");
        if (hash != NULL && str != NULL) {
            int64_t mh = (int64_t) hash;
            int64_t ms = (int64_t) str;
            int off_h = -1, off_s = -1;
            for (int off = 0; off < 0x40; off += 8) {
                int64_t v = *(int64_t *) ((char *) mh + off);
                if (v != 0 && is_executable_ptr(v)) { off_h = off; break; }
            }
            for (int off = 0; off < 0x40; off += 8) {
                int64_t v = *(int64_t *) ((char *) ms + off);
                if (v != 0 && is_executable_ptr(v)) { off_s = off; break; }
            }
            if (off_h >= 0 && off_h == off_s) {
                g_layout_entry_offset = (uint32_t) off_h;
                g_layout_access_offset = 4;
                g_layout_valid = 1;
                ALOGI("layout probed (dynamic): entry@0x%zx access@0x%zx",
                      (size_t) off_h, (size_t) 4);
            } else {
                ALOGE("layout probe: dynamic detection failed");
            }
        } else {
            ALOGE("probe: methods not found");
        }
    } else {
        ALOGE("probe: Object not found");
    }

    jclass nb = (*env)->FindClass(env,
        "com/shizuposed/manager/core/NativeBridge");
    if (nb == NULL) {
        ALOGE("NativeBridge class not found");
    } else {
        JNINativeMethod methods[] = {
            { "nIsAvailable", "()Z",
              (void *) Java_com_shizuposed_manager_core_NativeBridge_nIsAvailable },
            { "nLayoutInfo", "()Ljava/lang/String;",
              (void *) Java_com_shizuposed_manager_core_NativeBridge_nLayoutInfo },
            { "nHookArtMethod", "(Ljava/lang/reflect/Method;J[J)Z",
              (void *) Java_com_shizuposed_manager_core_NativeBridge_nHookArtMethod },
            { "nUnhookArtMethod", "(Ljava/lang/reflect/Method;)Z",
              (void *) Java_com_shizuposed_manager_core_NativeBridge_nUnhookArtMethod },
            { "nHookNative", "(JJ[J)Z",
              (void *) Java_com_shizuposed_manager_core_NativeBridge_nHookNative },
            { "nDlsym", "(Ljava/lang/String;Ljava/lang/String;)J",
              (void *) Java_com_shizuposed_manager_core_NativeBridge_nDlsym },
            { "nRegisterCallback",
              "(Ljava/lang/reflect/Method;Lde/robv/android/xposed/XC_MethodHook;)J",
              (void *) Java_com_shizuposed_manager_core_NativeBridge_nRegisterCallback },
            { "nUnregisterCallback", "(Ljava/lang/reflect/Method;)Z",
              (void *) Java_com_shizuposed_manager_core_NativeBridge_nUnregisterCallback },
            { "nSetDispatchResult", "(ZZJ)V",
              (void *) Java_com_shizuposed_manager_core_NativeBridge_nSetDispatchResult },
            { "nSetDebugLogging", "(Z)V",
              (void *) Java_com_shizuposed_manager_core_NativeBridge_nSetDebugLogging },
            { "nDescribeStubTemplate", "()Ljava/lang/String;",
              (void *) Java_com_shizuposed_manager_core_NativeBridge_nDescribeStubTemplate },
        };
        int rc = (*env)->RegisterNatives(env, nb, methods,
            sizeof(methods) / sizeof(methods[0]));
        if (rc != 0) {
            ALOGE("RegisterNatives failed: %d", rc);
        }
    }

    return JNI_VERSION_1_6;
}