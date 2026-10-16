/*
 * libshizuposed.c
 *
 * Original shared-dispatcher backend for ShizuPosed.
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
 * Stub template: at JNI_OnLoad, the backend dlopens libxstealth.so
 * (RTLD_NOLOAD) and resolves xstealth_get_stub_template() and
 * xstealth_get_template_info(). If the primary library captured
 * and verified a template, the first N words of every callback
 * stub are a byte-for-byte copy of what ART itself emits at a
 * legitimate entry point (BTI C plus the standard frame setup).
 *
 * The callback stub is a leaf that immediately branches to the
 * dispatcher, so only the prologue is templated — the dispatcher
 * is responsible for restoring the frame and returning. This is
 * different from libamiru.c, where the stub has a full
 * prologue + tail and both need to be shape-consistent.
 *
 * Debug logging: gated behind a JNI toggle, off by default.
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

#define SHIZUPOSED_VERSION "1.2.0-dynamic"

#define MAX_ART_HOOKS    256
#define MAX_INLINE_HOOKS 256
#define MAX_CALLBACKS    256

extern void __clear_cache(void *start, void *end);

/* ─────────────────────────────────────────────────────────────
 * Prologue info — must match libxstealth.c's struct layout.
 * ───────────────────────────────────────────────────────────── */

enum {
    PROLOGUE_SHAPE_UNKNOWN = 0,
    PROLOGUE_SHAPE_STANDARD = 1,
};

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

typedef int (*get_stub_template_fn)(const uint8_t **out_bytes,
                                    uint32_t *out_len);
typedef int (*get_template_info_fn)(struct prologue_info *out_info);

/* ─────────────────────────────────────────────────────────────
 * Debug logging flag — Phase 2b
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
    uint64_t saved[2];
    void    *trampoline;
    size_t   tramp_len;
} inline_hook_t;

typedef struct {
    int64_t  art_method;
    jobject  callback;
    jobject  method_ref;
    void    *stub;
    size_t   stub_len;
    int64_t  reserved_a;
    int32_t  reserved_b;
    int32_t  reserved_c;
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

extern int64_t szp_dispatch_c(int64_t art_method);

/* ─────────────────────────────────────────────────────────────
 * Stub template state — Phase 2b
 * ───────────────────────────────────────────────────────────── */

static const uint8_t *g_stub_template_bytes = NULL;
static uint32_t       g_stub_template_len = 0;
static struct prologue_info g_template_info = {
    .frame_size = 0,
    .receiver_offset = -1,
    .x30_offset = -1,
    .save_words = 0,
    .shape = PROLOGUE_SHAPE_UNKNOWN,
};
static int g_template_available = 0;

static void load_stub_template(void) {
    if (g_template_available) return;

    void *h = dlopen("libxstealth.so", RTLD_NOLOAD | RTLD_NOW);
    if (h == NULL) {
        if (g_debug_logging) {
            ALOGW("stub template: libxstealth.so not loaded — "
                  "using shipped stub shape");
        }
        return;
    }

    get_stub_template_fn get_bytes = (get_stub_template_fn)
        dlsym(h, "xstealth_get_stub_template");
    if (get_bytes == NULL) {
        if (g_debug_logging) {
            ALOGW("stub template: byte getter not exported — "
                  "using shipped stub shape");
        }
        return;
    }

    const uint8_t *bytes = NULL;
    uint32_t len = 0;
    if (!get_bytes(&bytes, &len) || bytes == NULL || len == 0) {
        if (g_debug_logging) {
            ALOGW("stub template: getter returned no verified bytes — "
                  "using shipped stub shape");
        }
        return;
    }

    get_template_info_fn get_info = (get_template_info_fn)
        dlsym(h, "xstealth_get_template_info");

    struct prologue_info info = {0};
    info.frame_size = 0;
    info.receiver_offset = -1;
    info.x30_offset = -1;
    info.save_words = 0;
    info.shape = PROLOGUE_SHAPE_UNKNOWN;
    for (int i = 0; i < 8; i++) info.reg_save_off[i] = -1;
    info.reg_mask = 0;
    info.save_x30_at = -1;
    info.reserved0 = 0;

    int have_info = 0;
    if (get_info != NULL && get_info(&info) &&
        info.shape == PROLOGUE_SHAPE_STANDARD &&
        info.frame_size > 0 && info.save_words >= 2) {
        have_info = 1;
    }

    g_stub_template_bytes = bytes;
    g_stub_template_len = len;
    if (have_info) {
        g_template_info = info;
        g_template_available = 1;
        ALOGI("stub template: verified %u bytes "
              "(frame=0x%x, save_words=%d, x30_off=%d)",
              len, info.frame_size, info.save_words, info.x30_offset);
    } else {
        g_template_info.shape = PROLOGUE_SHAPE_UNKNOWN;
        g_template_available = 1;
        if (g_debug_logging) {
            ALOGW("stub template: no verification metadata — "
                  "only substituting the first instruction");
        }
    }
}

/* ─────────────────────────────────────────────────────────────
 * Helpers
 * ───────────────────────────────────────────────────────────── */

static int is_executable_ptr(int64_t addr) {
    if (addr <= 1 || addr > 0xfffffffffffeULL) return 0;
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
 * Thread-local dispatch result
 * ───────────────────────────────────────────────────────────── */

typedef struct {
    int32_t skip;
    int32_t override_ret;
    int64_t ret_value;
} dispatch_result_t;

static __thread dispatch_result_t t_dispatchResult;

int64_t szp_take_dispatch_result(void) {
    int64_t v = t_dispatchResult.ret_value;
    t_dispatchResult.skip = 0;
    t_dispatchResult.override_ret = 0;
    t_dispatchResult.ret_value = 0;
    return v;
}

int64_t szp_dispatch_entry(int64_t trampoline,
                           int64_t a2, int64_t a3, int64_t a4,
                           int64_t a5, int64_t a6, int64_t a7,
                           int64_t a8) {
    int64_t result = szp_dispatch_c(a8);
    if (result != 0) {
        return a2;
    }

    typedef int64_t (*fn_t)(int64_t, int64_t, int64_t, int64_t,
                            int64_t, int64_t, int64_t, int64_t);
    return ((fn_t) trampoline)(a2, a2, a3, a4, a5, a6, a7, a8);
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
 * nSetDebugLogging / nDescribeStubTemplate — Phase 2b
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
    if (!g_template_available) {
        return (*env)->NewStringUTF(env,
            "template: not available (using shipped prologue)");
    }
    snprintf(buf, sizeof(buf),
        "template: %u bytes, shape=%d, frame=0x%x, "
        "save_words=%d, x30_off=%d, recv_off=%d",
        g_stub_template_len,
        g_template_info.shape,
        g_template_info.frame_size,
        g_template_info.save_words,
        g_template_info.x30_offset,
        g_template_info.receiver_offset);
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
    tp[0] = (((uint32_t) (orig_entry >>  0) & 0xffffu) << 5) | 0xd2800009u;
    tp[1] = (((uint32_t) (orig_entry >> 16) & 0xffffu) << 5) | 0xf2a00009u;
    tp[2] = (((uint32_t) (orig_entry >> 32) & 0xffffu) << 5) | 0xf2c00009u;
    tp[3] = (((uint32_t) (orig_entry >> 48) & 0xffffu) << 5) | 0xf2e00009u;
    tp[4] = 0xd61f0120;  /* br x9 */
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

    pthread_mutex_lock(&g_callback_mu);
    for (uint32_t i = 0; i < g_callback_count; i++) {
        if (g_callbacks[i].art_method == art_method) {
            g_callbacks[i].stub = tramp;
            g_callbacks[i].stub_len = 0x20;
            break;
        }
    }
    pthread_mutex_unlock(&g_callback_mu);

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
    tp[0] = src[0];
    tp[1] = src[1];
    tp[2] = 0x58000050;
    tp[3] = 0xd61f0200;
    *(int64_t *) ((char *) tramp + 0x10) =
        (int64_t) (intptr_t) ((char *) target + 8);
    __clear_cache(tramp, (char *) tramp + 0x40);
    seal_code_page(tramp);

    void *page = (void *) (target & ~0xfffULL);
    if (mprotect(page, 0x1000,
                 PROT_READ | PROT_WRITE | PROT_EXEC) != 0) {
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
    src[0] = ((uint32_t) delta & 0x3ffffffu) | 0x14000000u;
    src[1] = 0xd503201f;
    __clear_cache((void *) (intptr_t) target,
                  (char *) (intptr_t) target + 8);
    mprotect(page, 0x1000, PROT_READ | PROT_EXEC);

    uint32_t slot = g_inline_hook_count++;
    g_inline_hooks[slot].addr = (void *) (intptr_t) target;
    g_inline_hooks[slot].saved[0] = tp[0];
    g_inline_hooks[slot].saved[1] = tp[1];
    g_inline_hooks[slot].trampoline = tramp;
    g_inline_hooks[slot].tramp_len = 0x40;

    pthread_mutex_unlock(&g_inline_mu);

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

    void *stub = alloc_code_near(art_method, 0x20);
    if (stub == NULL) {
        (*env)->DeleteGlobalRef(env, cb_ref);
        (*env)->DeleteGlobalRef(env, mt_ref);
        pthread_mutex_unlock(&g_callback_mu);
        return 0;
    }

    uint32_t *sp = (uint32_t *) stub;
    int off = 0;
    int used_template = 0;

    /* Phase 2b: splice the verified prologue if available. */
    if (g_template_available
            && g_template_info.shape == PROLOGUE_SHAPE_STANDARD
            && g_template_info.save_words > 0
            && g_stub_template_len >=
                (uint32_t) (g_template_info.save_words * 4)) {
        /* Check that the prologue fits: 5 words + movz + b = 7. */
        if (g_template_info.save_words + 2 <= 8) {
            for (int w = 0; w < g_template_info.save_words; w++) {
                uint32_t word;
                memcpy(&word, g_stub_template_bytes + w * 4, 4);
                sp[off++] = word;
            }
            used_template = 1;
        }
    }

    if (!used_template) {
        /* Fallback: optional BTI C, then movz + branch. */
        uint32_t first_word = 0;
        if (g_stub_template_bytes != NULL && g_stub_template_len >= 4) {
            memcpy(&first_word, g_stub_template_bytes, 4);
        }
        if (first_word == 0xd503245fu) {
            sp[off++] = first_word;
        }
    }

    sp[off++] = (((uint32_t) (g_callback_count & 0xffffu)) << 5) |
                0xd2800008u;

    int64_t delta = ((int64_t) (intptr_t) &szp_dispatch_entry
                     - (int64_t) (intptr_t) (sp + off)) >> 2;
    sp[off++] = ((uint32_t) delta & 0x3ffffffu) | 0x14000000u;

    __clear_cache(stub, (char *) stub + off * 4);
    seal_code_page(stub);

    uint32_t slot = g_callback_count++;
    g_callbacks[slot].art_method = art_method;
    g_callbacks[slot].callback = cb_ref;
    g_callbacks[slot].method_ref = mt_ref;
    g_callbacks[slot].stub = stub;
    g_callbacks[slot].stub_len = 0x20;

    ALOGI("registerCallback: method %p -> stub %p (%s, %d words)",
          (void *) art_method, stub,
          used_template ? "verified template" : "shipped",
          off);

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
    t_dispatchResult.skip = skip ? 1 : 0;
    t_dispatchResult.override_ret = override_ret ? 1 : 0;
    t_dispatchResult.ret_value = (int64_t) value;
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

    load_stub_template();

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