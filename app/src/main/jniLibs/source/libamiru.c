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

#define LOG_TAG "ShizuPosedAmiru"
#define ALOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define ALOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)
#define ALOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define AMIRU_MAX_ARGS 32
#define AMIRU_VERSION "0.4.0"
#define MAX_HOOKS 512

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
typedef int (*get_template_info_full_fn)(struct prologue_info *out_info,
                                          uint32_t *out_mask);

/* ─────────────────────────────────────────────────────────────
 * Debug logging flag
 * ───────────────────────────────────────────────────────────── */

static volatile int g_debug_logging = 0;

/* ─────────────────────────────────────────────────────────────
 * Layout globals
 * ───────────────────────────────────────────────────────────── */

struct amiru_layout {
    int32_t valid;
    int32_t entry_offset;
    int32_t access_offset;
    int32_t pointer_flag;
};

static struct amiru_layout g_layout = { 0, 0, 0, 0 };
static pthread_mutex_t g_layout_lock = PTHREAD_MUTEX_INITIALIZER;

/* ─────────────────────────────────────────────────────────────
 * Hook registry
 * ───────────────────────────────────────────────────────────── */

typedef struct {
    int64_t  art_method;              /* +0x00 */
    int64_t  orig_entry;              /* +0x08 */
    void    *stub;                    /* +0x10 */
    int32_t  arg_count;               /* +0x18 */
    int32_t  reserved0;               /* +0x1C */
    jobject  global_self;             /* +0x20 */
    int32_t  reserved1;               /* +0x28 */
    int32_t  reserved2;               /* +0x2C */
    char     arg_sigs[AMIRU_MAX_ARGS];/* +0x30 */
    int32_t  reserved3;               /* +0x50 */
} amiru_hook_t;

static amiru_hook_t g_hooks[MAX_HOOKS];
static volatile uint32_t g_hook_count = 0;
static pthread_mutex_t g_hook_lock = PTHREAD_MUTEX_INITIALIZER;

/* ─────────────────────────────────────────────────────────────
 * Stub pools
 * ───────────────────────────────────────────────────────────── */

typedef struct amiru_pool {
    void *base;
    int64_t used;
    struct amiru_pool *next;
} amiru_pool_t;

static amiru_pool_t *g_pools = NULL;
static pthread_mutex_t g_pool_lock = PTHREAD_MUTEX_INITIALIZER;

/* ─────────────────────────────────────────────────────────────
 * JNI-global state
 * ───────────────────────────────────────────────────────────── */

static JavaVM   *g_vm = NULL;
static jclass    g_dispatcher_class = NULL;
static jmethodID g_dispatch_id = NULL;

/* ─────────────────────────────────────────────────────────────
 * Stub template state
 * ───────────────────────────────────────────────────────────── */

static const uint8_t *g_stub_template_bytes = NULL;
static uint32_t       g_stub_template_len = 0;
static struct prologue_info g_template_info = {
    .frame_size = 0,
    .receiver_offset = -1,
    .x30_offset = -1,
    .save_words = 0,
    .shape = PROLOGUE_SHAPE_UNKNOWN,
    .reg_save_off = { -1, -1, -1, -1, -1, -1, -1, -1 },
    .reg_mask = 0,
    .save_x30_at = -1,
    .reserved0 = 0,
};
static int g_template_available = 0;

#define REGS_NEEDED_MASK 0xff   /* x0..x7 */

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

    get_template_info_full_fn get_info_full = (get_template_info_full_fn)
        dlsym(h, "xstealth_get_template_info_full");
    get_template_info_fn get_info = (get_template_info_fn)
        dlsym(h, "xstealth_get_template_info");

    struct prologue_info info;
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
    if (get_info_full != NULL) {
        uint32_t mask = 0;
        if (get_info_full(&info, &mask) &&
            info.shape == PROLOGUE_SHAPE_STANDARD &&
            info.frame_size > 0 && info.save_words >= 2) {
            have_info = 1;
        }
    } else if (get_info != NULL) {
        if (get_info(&info) &&
            info.shape == PROLOGUE_SHAPE_STANDARD &&
            info.frame_size > 0 && info.save_words >= 2) {
            have_info = 1;
        }
    }

    if (!have_info) {
        if (g_debug_logging) {
            ALOGW("stub template: no verification metadata — "
                  "using shipped stub shape");
        }
        return;
    }

    if ((info.reg_mask & REGS_NEEDED_MASK) != REGS_NEEDED_MASK) {
        if (g_debug_logging) {
            ALOGW("stub template: verified prologue doesn't save "
                  "every register the stub restores "
                  "(reg_mask=0x%02x needed=0x%02x) — "
                  "using shipped stub shape",
                  info.reg_mask, REGS_NEEDED_MASK);
        }
        return;
    }

    for (int i = 0; i < 8; i++) {
        if (info.reg_save_off[i] < 0) {
            if (g_debug_logging) {
                ALOGW("stub template: reg_save_off[%d] missing — "
                      "using shipped stub shape", i);
            }
            return;
        }
    }

    g_stub_template_bytes = bytes;
    g_stub_template_len = len;
    g_template_info = info;
    g_template_available = 1;

    ALOGI("stub template: verified %u bytes "
          "(frame=0x%x, save_words=%d, x30_off=%d, reg_mask=0x%02x)",
          len, info.frame_size, info.save_words,
          info.x30_offset, info.reg_mask);
}

/* ─────────────────────────────────────────────────────────────
 * Helpers
 * ───────────────────────────────────────────────────────────── */

static int64_t read_ptr_at(int64_t addr) {
    return *(int64_t *) addr;
}

static int is_executable_range(int64_t addr) {
    if (addr < 0x1000) return 0;

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

static int find_entry_offset(int64_t method_ptr) {
    for (int off = 0; off < 0x40; off += 8) {
        int64_t v = read_ptr_at(method_ptr + off);
        if (v != 0 && is_executable_range(v)) return off;
    }
    return -1;
}

/* ─────────────────────────────────────────────────────────────
 * Layout probe
 * ───────────────────────────────────────────────────────────── */

static int64_t art_method_of_class(JNIEnv *env, jclass clazz,
                                   const char *name, const char *sig) {
    jmethodID id = (*env)->GetMethodID(env, clazz, name, sig);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        return 0;
    }
    return (int64_t) id;
}

static int probe_layout(JNIEnv *env) {
    pthread_mutex_lock(&g_layout_lock);

    if (g_layout.valid) {
        pthread_mutex_unlock(&g_layout_lock);
        return 1;
    }

    jclass objectClass = (*env)->FindClass(env, "java/lang/Object");
    if (objectClass == NULL) {
        (*env)->ExceptionClear(env);
        pthread_mutex_unlock(&g_layout_lock);
        return 0;
    }

    int64_t m_hash = art_method_of_class(env, objectClass,
                                          "hashCode", "()I");
    int64_t m_str  = art_method_of_class(env, objectClass,
                                          "toString",
                                          "()Ljava/lang/String;");
    if (m_hash == 0 || m_str == 0) {
        ALOGE("probe: could not obtain ArtMethod addresses");
        pthread_mutex_unlock(&g_layout_lock);
        return 0;
    }

    int off_a = find_entry_offset(m_hash);
    int off_b = find_entry_offset(m_str);
    if (off_a < 0 || off_b < 0 || off_a != off_b) {
        ALOGE("probe: no candidate matched (hash=%d str=%d)", off_a, off_b);
        pthread_mutex_unlock(&g_layout_lock);
        return 0;
    }

    g_layout.valid = 1;
    g_layout.entry_offset = off_a;
    g_layout.access_offset = 4;
    g_layout.pointer_flag = 1;

    ALOGI("layout probed: entry@0x%x access@0x%x ptr=%d",
          off_a, 4, 1);
    pthread_mutex_unlock(&g_layout_lock);
    return 1;
}

static void *alloc_stub(void) {
    long page = sysconf(_SC_PAGESIZE);
    if (page <= 0) page = 4096;

    pthread_mutex_lock(&g_pool_lock);

    amiru_pool_t *p = g_pools;
    while (p != NULL && p->used >= 32) p = p->next;

    if (p == NULL) {
        void *base = mmap(NULL, 0x1000,
                          PROT_READ | PROT_WRITE,
                          MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
        if (base == MAP_FAILED) {
            pthread_mutex_unlock(&g_pool_lock);
            return NULL;
        }
        p = calloc(1, sizeof(*p));
        if (p == NULL) {
            munmap(base, 0x1000);
            pthread_mutex_unlock(&g_pool_lock);
            return NULL;
        }
        p->base = base;
        p->used = 0;
        p->next = g_pools;
        g_pools = p;
    }

    void *slot = (char *) p->base + (p->used * 0x80);
    p->used++;

    void *page_start = (void *)((uintptr_t)slot & ~(uintptr_t)(page - 1));
    mprotect(page_start, (size_t)page, PROT_READ | PROT_WRITE);

    memset(slot, 0, 0x80);

    pthread_mutex_unlock(&g_pool_lock);
    return slot;
}

/* ─────────────────────────────────────────────────────────────
 * Instruction encoders
 * ───────────────────────────────────────────────────────────── */

#define INSN_RET            0xd65f03c0u
#define INSN_BR_X9          0xd61f0120u
#define INSN_BLR_X9         0xd63f0120u
#define INSN_ADD_SP_1       0x910003e1u    /* add x1, sp, #0 */
#define INSN_MOV_X19_X0     0xaa0003f3u    /* mov x19, x0 */

static inline uint32_t insn_movz_x(uint32_t rd, uint32_t imm16,
                                   uint32_t shift) {
    uint32_t hw = shift / 16;
    return 0xd2800000u | (hw << 21) | ((imm16 & 0xffffu) << 5) | rd;
}

/* ldr Xt, [sp, #off] — 64-bit unsigned offset. off must be a
 * multiple of 8 in [0, 0x7fff8]. */
static inline uint32_t insn_ldr_sp(uint32_t rt, uint32_t off) {
    uint32_t imm12 = (off / 8) & 0xfffu;
    return 0xf94003e0u | (imm12 << 10) | rt;
}

/* str Xt, [sp, #off] — 64-bit unsigned offset. off must be a
 * multiple of 8 in [0, 0x7fff8]. */
static inline uint32_t insn_str_sp(uint32_t rt, uint32_t off) {
    uint32_t imm12 = (off / 8) & 0xfffu;
    return 0xf90003e0u | (imm12 << 10) | rt;
}

/* ldp Xt, Xt2, [sp, #off] — 64-bit signed offset. off must be a
 * multiple of 8 in [-512, 504]. */
static inline uint32_t insn_ldp_sp(uint32_t rt, uint32_t rt2,
                                    int32_t off) {
    int32_t imm7 = off / 8;
    uint32_t imm7u = ((uint32_t) imm7) & 0x7fu;
    return 0xa94003e0u | (imm7u << 15) | (rt2 << 10) | rt;
}

static int emit_load64_x9(uint32_t *p, uint64_t value) {
    p[0] = insn_movz_x(9, (uint32_t) (value & 0xffffu), 0);
    p[1] = 0xf2a00009u
         | (((uint32_t) ((value >> 16) & 0xffffu)) << 5);
    p[2] = 0xf2c00009u
         | (((uint32_t) ((value >> 32) & 0xffffu)) << 5);
    p[3] = 0xf2e00009u
         | (((uint32_t) ((value >> 48) & 0xffffu)) << 5);
    return 4;
}

/*
 * emit_restore — restores x0..x7 and x30 from the frame, then
 * deallocates the frame with `add sp, sp, #frame_size`.
 *
 * v0.4.0 note: this function does NOT preserve x0. Whatever was in
 * x0 before the call is overwritten by the first ldp. That is
 * intentional — the caller (emit_stub) stashes the dispatcher's
 * return value on the stack before calling emit_restore, and
 * reloads it into x0 after emit_restore returns.
 *
 * Returns the number of words written.
 */
static int emit_restore(uint32_t *p, int32_t frame_size) {
    int i = 0;

    /* x0, x1 */
    if (g_template_info.reg_save_off[1] >= 0 &&
        g_template_info.reg_save_off[1] ==
        g_template_info.reg_save_off[0] + 8) {
        p[i++] = insn_ldp_sp(0, 1,
                              g_template_info.reg_save_off[0]);
    } else {
        p[i++] = insn_ldr_sp(0, (uint32_t) g_template_info.reg_save_off[0]);
        if (g_template_info.reg_save_off[1] >= 0) {
            p[i++] = insn_ldr_sp(1, (uint32_t) g_template_info.reg_save_off[1]);
        }
    }

    /* x2, x3 */
    if (g_template_info.reg_save_off[3] >= 0 &&
        g_template_info.reg_save_off[3] ==
        g_template_info.reg_save_off[2] + 8) {
        p[i++] = insn_ldp_sp(2, 3,
                              g_template_info.reg_save_off[2]);
    } else {
        p[i++] = insn_ldr_sp(2, (uint32_t) g_template_info.reg_save_off[2]);
        p[i++] = insn_ldr_sp(3, (uint32_t) g_template_info.reg_save_off[3]);
    }

    /* x4, x5 */
    if (g_template_info.reg_save_off[5] >= 0 &&
        g_template_info.reg_save_off[5] ==
        g_template_info.reg_save_off[4] + 8) {
        p[i++] = insn_ldp_sp(4, 5,
                              g_template_info.reg_save_off[4]);
    } else {
        p[i++] = insn_ldr_sp(4, (uint32_t) g_template_info.reg_save_off[4]);
        p[i++] = insn_ldr_sp(5, (uint32_t) g_template_info.reg_save_off[5]);
    }

    /* x6, x7 */
    if (g_template_info.reg_save_off[7] >= 0 &&
        g_template_info.reg_save_off[7] ==
        g_template_info.reg_save_off[6] + 8) {
        p[i++] = insn_ldp_sp(6, 7,
                              g_template_info.reg_save_off[6]);
    } else {
        p[i++] = insn_ldr_sp(6, (uint32_t) g_template_info.reg_save_off[6]);
        p[i++] = insn_ldr_sp(7, (uint32_t) g_template_info.reg_save_off[7]);
    }

    /* x30, if the template saved it. */
    if (g_template_info.save_x30_at >= 0 &&
        g_template_info.x30_offset >= 0) {
        p[i++] = insn_ldr_sp(30, (uint32_t) g_template_info.x30_offset);
    }

    /* add sp, sp, #frame_size */
    p[i++] = 0x910003ffu | ((uint32_t) frame_size << 10);

    return i;
}

static int emit_restore_shipped(uint32_t *p) {
    int i = 0;
    p[i++] = 0xa94007e0u;    /* ldp x0, x1, [sp, #0x00] */
    p[i++] = 0xa9410fe2u;    /* ldp x2, x3, [sp, #0x10] */
    p[i++] = 0xa94217e4u;    /* ldp x4, x5, [sp, #0x20] */
    p[i++] = 0xa9431fe6u;    /* ldp x6, x7, [sp, #0x30] */
    p[i++] = 0xa9444fe8u;    /* ldp x8, x19, [sp, #0x40] */
    p[i++] = 0xf9402bfeu;    /* ldr x30, [sp, #0x50] */
    p[i++] = 0x910203ffu;    /* add sp, sp, #0x80 */
    return i;
}

static void emit_stub(void *stub,
                      int64_t slot_index,
                      int64_t dispatch_addr,
                      int64_t orig_entry) {
    uint32_t *p = (uint32_t *) stub;
    int i = 0;

    int32_t frame_size = 0x80;
    int use_template = 0;

    if (g_template_available
            && g_template_info.shape == PROLOGUE_SHAPE_STANDARD
            && g_template_info.save_words > 0
            && g_stub_template_len >=
                (uint32_t) (g_template_info.save_words * 4)
            && g_template_info.frame_size > 0) {
        frame_size = g_template_info.frame_size;
        use_template = 1;
    }

    int32_t stash_off = frame_size - 8;
    if (stash_off < 0) stash_off = 0;

    /* ── Prologue ─────────────────────────────────────────── */

    if (use_template) {
        for (int w = 0; w < g_template_info.save_words; w++) {
            uint32_t word;
            memcpy(&word, g_stub_template_bytes + w * 4, 4);
            p[i++] = word;
        }
        p[i++] = INSN_MOV_X19_X0;
    } else {
        uint32_t first_word = 0;
        if (g_stub_template_bytes != NULL && g_stub_template_len >= 4) {
            memcpy(&first_word, g_stub_template_bytes, 4);
        }
        if (first_word == 0xd503245fu) {
            p[i++] = first_word;
        }
        p[i++] = 0xd10203ffu;    /* sub  sp, sp, #0x80 */
        p[i++] = 0xa90007e0u;    /* stp  x0, x1, [sp] */
        p[i++] = 0xa9010fe2u;    /* stp  x2, x3, [sp, #0x10] */
        p[i++] = 0xa90217e4u;    /* stp  x4, x5, [sp, #0x20] */
        p[i++] = 0xa9031fe6u;    /* stp  x6, x7, [sp, #0x30] */
        p[i++] = 0xa9044fe8u;    /* stp  x8, x19, [sp, #0x40] */
        p[i++] = 0xf9002bfeu;    /* str  x30, [sp, #0x50] */
        p[i++] = INSN_MOV_X19_X0;
    }

    /* ── Dispatcher call ──────────────────────────────────── */

    p[i++] = insn_movz_x(0, (uint32_t) slot_index & 0xffffu, 0);
    p[i++] = INSN_ADD_SP_1;
    i += emit_load64_x9(&p[i], (uint64_t) dispatch_addr);
    p[i++] = INSN_BLR_X9;

    /* ── Stash the dispatcher's return value ──────────────── */

    p[i++] = insn_str_sp(0, (uint32_t) stash_off);

    /* cbz x0, <skip> — if the dispatcher returned 0, tail-call
     * the original. The branch target is patched once the return
     * path is fully emitted. */
    int cbz_idx = i;
    p[i++] = 0xb4000000u;
    int after_cbz = i;

    /* Restore x0..x7 (without add sp). */
    if (use_template) {
        /* Reuse emit_restore's body but strip the trailing add sp.
         * Rather than refactor emit_restore, we temporarily write
         * it into the buffer, note where the add sp landed, and
         * overwrite it with the ldr/ret sequence. */
        uint32_t scratch[16];
        int n = emit_restore(scratch, frame_size);
        /* The last instruction of emit_restore is the add sp. We
         * want: <rest of restore>, ldr x0, [sp, #stash_off],
         * add sp, sp, #frame_size, ret. */
        for (int k = 0; k < n - 1; k++) {
            p[i++] = scratch[k];
        }
        p[i++] = insn_ldr_sp(0, (uint32_t) stash_off);
        p[i++] = scratch[n - 1];   /* the add sp */
    } else {
        /* Shipped restore without the add sp. */
        p[i++] = 0xa94007e0u;    /* ldp x0, x1, [sp, #0x00] */
        p[i++] = 0xa9410fe2u;    /* ldp x2, x3, [sp, #0x10] */
        p[i++] = 0xa94217e4u;    /* ldp x4, x5, [sp, #0x20] */
        p[i++] = 0xa9431fe6u;    /* ldp x6, x7, [sp, #0x30] */
        p[i++] = 0xa9444fe8u;    /* ldp x8, x19, [sp, #0x40] */
        p[i++] = 0xf9402bfeu;    /* ldr x30, [sp, #0x50] */
        p[i++] = insn_ldr_sp(0, (uint32_t) stash_off);
        p[i++] = 0x910203ffu;    /* add sp, sp, #0x80 */
    }
    p[i++] = INSN_RET;

    int skip = i - after_cbz;
    p[cbz_idx] = 0xb4000000u | ((uint32_t) skip << 5);

    /* ── Tail-call original entry ─────────────────────────── */

    i += emit_load64_x9(&p[i], (uint64_t) orig_entry);
    if (use_template) {
        i += emit_restore(&p[i], frame_size);
    } else {
        i += emit_restore_shipped(&p[i]);
    }
    p[i++] = INSN_BR_X9;

    __clear_cache(stub, (char *) stub + i * 4);
}

static void seal_stub_page(void *stub) {
    if (stub == NULL) return;
    long page = sysconf(_SC_PAGESIZE);
    if (page <= 0) page = 4096;
    void *page_start = (void *)((uintptr_t)stub & ~(uintptr_t)(page - 1));
    if (mprotect(page_start, (size_t)page,
                 PROT_READ | PROT_EXEC) != 0) {
        ALOGW("seal_stub_page: mprotect failed for %p: %s",
              stub, strerror(errno));
    }
}

/* ─────────────────────────────────────────────────────────────
 * Boxed argument construction
 * ───────────────────────────────────────────────────────────── */

static jobject make_boxed_arg(JNIEnv *env,
                              char sig_char,
                              jvalue raw_value) {
    jclass clazz = NULL;
    jmethodID ctor = NULL;

    switch (sig_char) {
        case 'B':
            clazz = (*env)->FindClass(env, "java/lang/Byte");
            if (clazz == NULL) { (*env)->ExceptionClear(env); return NULL; }
            ctor = (*env)->GetMethodID(env, clazz, "<init>", "(B)V");
            if (ctor == NULL) { (*env)->ExceptionClear(env); return NULL; }
            return (*env)->NewObject(env, clazz, ctor, raw_value.b);

        case 'C':
            clazz = (*env)->FindClass(env, "java/lang/Character");
            if (clazz == NULL) { (*env)->ExceptionClear(env); return NULL; }
            ctor = (*env)->GetMethodID(env, clazz, "<init>", "(C)V");
            if (ctor == NULL) { (*env)->ExceptionClear(env); return NULL; }
            return (*env)->NewObject(env, clazz, ctor, raw_value.c);

        case 'D':
            clazz = (*env)->FindClass(env, "java/lang/Double");
            if (clazz == NULL) { (*env)->ExceptionClear(env); return NULL; }
            ctor = (*env)->GetMethodID(env, clazz, "<init>", "(D)V");
            if (ctor == NULL) { (*env)->ExceptionClear(env); return NULL; }
            return (*env)->NewObject(env, clazz, ctor, raw_value.d);

        case 'F':
            clazz = (*env)->FindClass(env, "java/lang/Float");
            if (clazz == NULL) { (*env)->ExceptionClear(env); return NULL; }
            ctor = (*env)->GetMethodID(env, clazz, "<init>", "(F)V");
            if (ctor == NULL) { (*env)->ExceptionClear(env); return NULL; }
            return (*env)->NewObject(env, clazz, ctor, raw_value.f);

        case 'I':
            clazz = (*env)->FindClass(env, "java/lang/Integer");
            if (clazz == NULL) { (*env)->ExceptionClear(env); return NULL; }
            ctor = (*env)->GetMethodID(env, clazz, "<init>", "(I)V");
            if (ctor == NULL) { (*env)->ExceptionClear(env); return NULL; }
            return (*env)->NewObject(env, clazz, ctor, raw_value.i);

        case 'J':
            clazz = (*env)->FindClass(env, "java/lang/Long");
            if (clazz == NULL) { (*env)->ExceptionClear(env); return NULL; }
            ctor = (*env)->GetMethodID(env, clazz, "<init>", "(J)V");
            if (ctor == NULL) { (*env)->ExceptionClear(env); return NULL; }
            return (*env)->NewObject(env, clazz, ctor, raw_value.j);

        case 'S':
            clazz = (*env)->FindClass(env, "java/lang/Short");
            if (clazz == NULL) { (*env)->ExceptionClear(env); return NULL; }
            ctor = (*env)->GetMethodID(env, clazz, "<init>", "(S)V");
            if (ctor == NULL) { (*env)->ExceptionClear(env); return NULL; }
            return (*env)->NewObject(env, clazz, ctor, raw_value.s);

        case 'Z':
            clazz = (*env)->FindClass(env, "java/lang/Boolean");
            if (clazz == NULL) { (*env)->ExceptionClear(env); return NULL; }
            ctor = (*env)->GetMethodID(env, clazz, "<init>", "(Z)V");
            if (ctor == NULL) { (*env)->ExceptionClear(env); return NULL; }
            return (*env)->NewObject(env, clazz, ctor, raw_value.z);

        default:
            return NULL;
    }
}

/* ─────────────────────────────────────────────────────────────
 * Dispatcher entry (native side)
 * ───────────────────────────────────────────────────────────── */

int64_t amiru_dispatch(int64_t slot_index, int64_t reg_frame) {
    if (slot_index < 0 || slot_index >= (int64_t) g_hook_count) {
        return 0;
    }

    amiru_hook_t *h = &g_hooks[slot_index];
    if (h->art_method == 0) return 0;

    JNIEnv *env = NULL;
    int attached = 0;
    jint rc = (*g_vm)->GetEnv(g_vm, (void **) &env, JNI_VERSION_1_6);
    if (rc == JNI_EDETACHED) {
        if ((*g_vm)->AttachCurrentThread(g_vm, &env, NULL) != JNI_OK) {
            return 0;
        }
        attached = 1;
    } else if (rc != JNI_OK) {
        return 0;
    }

    jobject receiver = (jobject) ((int64_t *) reg_frame)[0];

    int32_t argc = h->arg_count;
    if (argc < 0) argc = 0;
    if (argc > AMIRU_MAX_ARGS) argc = AMIRU_MAX_ARGS;

    jclass objClass = (*env)->FindClass(env, "java/lang/Object");
    if (objClass == NULL) {
        (*env)->ExceptionClear(env);
        if (attached) (*g_vm)->DetachCurrentThread(g_vm);
        return 0;
    }
    jobjectArray args = (*env)->NewObjectArray(env, argc, objClass, NULL);
    if (args == NULL) {
        (*env)->ExceptionClear(env);
        if (attached) (*g_vm)->DetachCurrentThread(g_vm);
        return 0;
    }

    for (int32_t i = 0; i < argc; i++) {
        char sig = h->arg_sigs[i];
        int64_t raw = ((int64_t *) reg_frame)[i + 1];

        jvalue v;
        memset(&v, 0, sizeof(v));
        v.j = raw;

        jobject boxed = make_boxed_arg(env, sig, v);
        if (boxed != NULL) {
            (*env)->SetObjectArrayElement(env, args, i, boxed);
            (*env)->DeleteLocalRef(env, boxed);
        }
    }

    jlong result = (*env)->CallStaticLongMethod(env, g_dispatcher_class,
                                                 g_dispatch_id,
                                                 (jint) slot_index,
                                                 receiver,
                                                 args);

    (*env)->DeleteLocalRef(env, args);

    jthrowable exc = (*env)->ExceptionOccurred(env);
    if (exc != NULL) {
        (*env)->ExceptionClear(env);
        (*env)->DeleteLocalRef(env, exc);
        if (attached) (*g_vm)->DetachCurrentThread(g_vm);
        return 0;
    }

    if (attached) (*g_vm)->DetachCurrentThread(g_vm);

    return (int64_t) result;
}

/* ─────────────────────────────────────────────────────────────
 * JNI surface
 * ───────────────────────────────────────────────────────────── */

JNIEXPORT jint JNICALL
Java_com_shizuposed_manager_core_NativeBridge_amiruSetResult(
        JNIEnv *env, jclass clazz, jint slot, jboolean skip,
        jboolean has_value, jlong value) {
    (void) clazz; (void) slot; (void) skip;
    (void) has_value; (void) value; (void) env;
    return 0;
}

JNIEXPORT void JNICALL
Java_com_shizuposed_manager_core_NativeBridge_amiruSetDebugLogging(
        JNIEnv *env, jclass clazz, jboolean enabled) {
    (void) env; (void) clazz;
    g_debug_logging = enabled ? 1 : 0;
    ALOGI("amiru: debug logging %s", g_debug_logging ? "on" : "off");
}

JNIEXPORT jstring JNICALL
Java_com_shizuposed_manager_core_NativeBridge_amiruDescribeStubTemplate(
        JNIEnv *env, jclass clazz) {
    (void) clazz;
    char buf[320];
    if (!g_template_available) {
        return (*env)->NewStringUTF(env,
            "template: not available (using shipped prologue)");
    }
    char regbuf[80];
    int roff = 0;
    for (int i = 0; i < 8; i++) {
        if (g_template_info.reg_save_off[i] >= 0 &&
            roff < (int)sizeof(regbuf) - 8) {
            roff += snprintf(regbuf + roff, sizeof(regbuf) - roff,
                             "x%d=%d ",
                             i, g_template_info.reg_save_off[i]);
        }
    }
    if (roff == 0) snprintf(regbuf, sizeof(regbuf), "(none)");
    snprintf(buf, sizeof(buf),
        "template: %u bytes, frame=0x%x, save_words=%d, "
        "x30_off=%d, reg_mask=0x%02x, regs=[%s]",
        g_stub_template_len,
        g_template_info.frame_size,
        g_template_info.save_words,
        g_template_info.x30_offset,
        g_template_info.reg_mask,
        regbuf);
    return (*env)->NewStringUTF(env, buf);
}

JNIEXPORT jint JNICALL
Java_com_shizuposed_manager_core_NativeBridge_amiruHookMethod(
        JNIEnv *env, jclass clazz,
        jobject method, jstring name, jint arg_count,
        jobjectArray arg_types, jint flags) {
    (void) clazz; (void) name; (void) flags;

    if (env == NULL) return -1;
    if (method == NULL) return -1;

    if (!g_layout.valid) {
        if (!probe_layout(env)) {
            ALOGE("amiruHookMethod: layout not valid");
            return -1;
        }
    }

    int64_t art_method = (int64_t) (*env)->FromReflectedMethod(env, method);
    if (art_method == 0) {
        ALOGE("amiruHookMethod: null ArtMethod");
        return -1;
    }

    pthread_mutex_lock(&g_hook_lock);

    if (g_hook_count >= MAX_HOOKS) {
        pthread_mutex_unlock(&g_hook_lock);
        ALOGE("amiruHookMethod: hook table full");
        return -1;
    }

    uint32_t slot = g_hook_count;

    void *stub = alloc_stub();
    if (stub == NULL) {
        pthread_mutex_unlock(&g_hook_lock);
        ALOGE("amiruHookMethod: no executable memory for stub");
        return -1;
    }

    int64_t orig_entry =
        *(int64_t *) ((char *) art_method + g_layout.entry_offset);
    if (orig_entry == 0) {
        pthread_mutex_unlock(&g_hook_lock);
        ALOGE("amiruHookMethod: null original entry");
        return -1;
    }

    amiru_hook_t *h = &g_hooks[slot];
    memset(h, 0, sizeof(*h));
    h->art_method   = art_method;
    h->orig_entry   = orig_entry;
    h->stub         = stub;
    h->arg_count    = arg_count;
    h->global_self  = arg_types
        ? (*env)->NewGlobalRef(env, arg_types)
        : NULL;

    if (arg_types != NULL && arg_count > 0) {
        int32_t n = (*env)->GetArrayLength(env, arg_types);
        for (int32_t i = 0; i < n && i < arg_count && i < AMIRU_MAX_ARGS; i++) {
            jstring s = (jstring) (*env)->GetObjectArrayElement(
                env, arg_types, i);
            if (s != NULL) {
                const char *cstr = (*env)->GetStringUTFChars(env, s, NULL);
                if (cstr != NULL && cstr[0] != '\0') {
                    h->arg_sigs[i] = cstr[0];
                }
                if (cstr) (*env)->ReleaseStringUTFChars(env, s, cstr);
                (*env)->DeleteLocalRef(env, s);
            }
        }
    }

    emit_stub(stub, (int64_t) slot,
              (int64_t) &amiru_dispatch,
              orig_entry);

    seal_stub_page(stub);

    *(int64_t *) ((char *) art_method + g_layout.entry_offset) =
        (int64_t) stub;
    __clear_cache((char *) art_method + g_layout.entry_offset,
                  (char *) art_method + g_layout.entry_offset + 8);

    g_hook_count++;

    ALOGI("hooked ArtMethod 0x%lx -> stub slot %d (orig=0x%lx, %s)",
          (unsigned long) art_method, slot,
          (unsigned long) orig_entry,
          g_template_available ? "template" : "shipped");

    pthread_mutex_unlock(&g_hook_lock);
    return (jint) slot;
}

JNIEXPORT jint JNICALL
Java_com_shizuposed_manager_core_NativeBridge_amiruUnhook(
        JNIEnv *env, jclass clazz, jint slot) {
    (void) clazz;

    if (slot < 0 || (uint32_t) slot >= g_hook_count) return -1;

    amiru_hook_t *h = &g_hooks[slot];
    if (h->art_method == 0) return -1;

    *(int64_t *) ((char *) h->art_method + g_layout.entry_offset) =
        h->orig_entry;
    __clear_cache((char *) h->art_method + g_layout.entry_offset,
                  (char *) h->art_method + g_layout.entry_offset + 8);

    if (h->global_self != NULL && env != NULL) {
        (*env)->DeleteGlobalRef(env, h->global_self);
        h->global_self = NULL;
    }
    h->art_method = 0;
    return 0;
}

JNIEXPORT jint JNICALL
JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void) reserved;

    g_vm = vm;

    JNIEnv *env = NULL;
    if ((*vm)->GetEnv(vm, (void **) &env, JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }

    jclass clazz = (*env)->FindClass(env,
        "com/shizuposed/manager/core/AmiruDispatcher");
    if (clazz != NULL) {
        g_dispatcher_class = (*env)->NewGlobalRef(env, clazz);
        g_dispatch_id = (*env)->GetStaticMethodID(env,
            g_dispatcher_class,
            "dispatch",
            "(ILjava/lang/Object;[Ljava/lang/Object;)J");
        if (g_dispatch_id == NULL) {
            (*env)->ExceptionClear(env);
        }
    } else {
        (*env)->ExceptionClear(env);
    }

    if (!probe_layout(env)) {
        ALOGW("JNI_OnLoad: layout probe failed — "
              "Amiru will refuse hooks");
    }

    load_stub_template();

    ALOGI("JNI_OnLoad: libamiru %s (Amiru for ShizuPosed, %s)",
          AMIRU_VERSION,
          g_template_available ? "template-aware+verified" :
                                 "shipped prologue");

    return JNI_VERSION_1_6;
}