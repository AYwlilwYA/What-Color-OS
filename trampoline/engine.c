// SF 内玻璃渲染引擎：GOT 挂 BlurGenerator::generate，把申报区域的模糊输出替换为液态玻璃 SkSL 渲染
// 挂点安装 = lg_engine_try_install：maps 找 RE-ext → 解析设备 ELF 的 generate JUMP_SLOT r_offset → GOT 改写
// 引擎武装 = lg_glass_arm_engine（trampoline.cpp 门禁）：首帧真需要时跑符号+金丝雀，任一失败本会话纯转发
// 熔断三保险 = 性能门早退 / 单帧耗时连续超阈 / SF CPU 惰性自检（generate 路径内 TTL，无线程轮询）
// 参数通道 = conf 文件 TTL 惰性读（客户端下发即写，SF 侧访问时才算）
#include <dlfcn.h>
#include <fcntl.h>
#include <stdatomic.h>
#include <stdarg.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>
#ifndef LG_HOST_TEST
#include <pthread.h>
#ifdef __ANDROID__
#include <sys/system_properties.h>
#endif
#endif

#include "lginternal.h"

#define TAG_E "LG-GLASS"
#define CONF_PATH "/data/local/tmp/lg_glass.conf"
#define CODE_MAX 16384
#define KEY_MAX 96
#define CPU_CHECK_MS 5000
#define CPU_TRIP 3
#define CPU_LIMIT 0.8f
#define STAT_EVERY 100
#define INSTALL_RETRY_MAX 30

#define M_MAKE_FOR_SHADER "_ZN15SkRuntimeEffect13MakeForShaderE8SkStringRKNS_7OptionsE"
#define M_EFFECT_MKSHADER "_ZNK15SkRuntimeEffect10makeShaderE5sk_spIK6SkDataEPS0_I8SkShaderEmPK8SkMatrix"
#define M_IMG_MKSHADER "_ZNK7SkImage10makeShaderE10SkTileModeS0_RK17SkSamplingOptionsPK8SkMatrix"
#define M_DATA_EMPTY "_ZN6SkData9MakeEmptyEv"
#define M_DATA_WITH_COPY "_ZN6SkData12MakeWithCopyEPKvm"
#define M_MK_N32 "_ZN11SkImageInfo13MakeN32PremulEii"
#define M_RENDER_TARGET "_ZN10SkSurfaces12RenderTargetEP18GrRecordingContextN5skgpu8BudgetedERK11SkImageInfoi15GrSurfaceOriginPK14SkSurfacePropsbb"
#define M_GET_CANVAS "_ZN9SkSurface9getCanvasEv"
#define M_DRAW_IMAGE "_ZN8SkCanvas9drawImageEPK7SkImageffRK17SkSamplingOptionsPK7SkPaint"
#define M_DRAW_PAINT "_ZN8SkCanvas9drawPaintERK7SkPaint"
#define M_SNAPSHOT "_ZN9SkSurface17makeImageSnapshotEv"
#define M_PAINT_C1 "_ZN7SkPaintC1Ev"
#define M_PAINT_D1 "_ZN7SkPaintD1Ev"
#define M_SET_SHADER "_ZN7SkPaint9setShaderE5sk_spI8SkShaderE"
#define M_STR_C1 "_ZN8SkStringC1EPKc"
#define M_STR_D1 "_ZN8SkStringD1Ev"
#define M_GEN "_ZN7android12renderengine4skia13BlurGenerator8generateEP18GrRecordingContextj5sk_spI7SkImageERK6SkRecti"

#ifdef __aarch64__
#define LG_R_JUMP_SLOT 1026u
#else
#define LG_R_JUMP_SLOT 7u
#endif

struct lg_cfg g_cfg = {0, 0, 1, 60, 60, 500, 300, 60, 100, 10, 28.0f, 0.0f, 0.5f, 45.0f, 1.5f, 0.0f};

void lg_cfg_reset_defaults(void) {
    g_cfg = (struct lg_cfg){0, 0, 1, 60, 60, 500, 300, 60, 100, 10, 28.0f, 0.0f, 0.5f, 45.0f, 1.5f, 0.0f};
}

static _Atomic long g_calls, g_ok, g_fb;
static _Atomic long g_hit, g_reject, g_over;
static _Atomic long g_q[8];
static _Atomic int g_over_streak;
static _Atomic long g_stat_calls;
static float g_last_rect[4];
static int g_first_rect;

static float cl_def(float v, float lo, float hi, float dflt) {
    if (!(v == v)) return dflt;
    if (v < lo) return lo;
    if (v > hi) return hi;
    return v;
}

static const char *conf_path(void) {
#ifdef LG_HOST_TEST
    const char *p = getenv("LG_TEST_CONF");
    if (p && *p) return p;
#endif
    return CONF_PATH;
}

static void fb_log(const char *reason) {
    static char fb_last[48];
    static long fb_last_ms;
    long t = lg_now_ms();
    if (strcmp(reason, fb_last) == 0 && t - fb_last_ms < 60000) return;
    strncpy(fb_last, reason, sizeof(fb_last) - 1);
    fb_last_ms = t;
    lg_logf("%s-FB reason=%s", TAG_E, reason);
}

void cfg_reload(void) {
    long t = lg_now_ms();
    if (t - g_cfg.ms < 500) return;
    g_cfg.ms = t;
#ifdef __ANDROID__
    char val[PROP_VALUE_MAX] = {0};
    __system_property_get("vendor.debug.lg.glass", val);
    g_cfg.prop_on = (val[0] == '1');
#endif
    FILE *f = fopen(conf_path(), "r");
    if (f) {
        char line[256];
        if (fgets(line, sizeof(line), f)) {
            int on = g_cfg.file_on;
            double bx = g_cfg.bx, by = g_cfg.by, bw = g_cfg.bw, bh = g_cfg.bh, br = g_cfg.br;
            int to = g_cfg.timeout_ms, ot = g_cfg.overtrip;
            float rf = g_cfg.rf, ds = g_cfg.ds, hg = g_cfg.hg, ag = g_cfg.agDeg, vb = g_cfg.vb, ib = g_cfg.ib;
            sscanf(line,
                   "on=%d bx=%lf by=%lf bw=%lf bh=%lf br=%lf timeout_ms=%d overtrip=%d"
                   " rf=%f ds=%f hg=%f ag=%f vb=%f ib=%f",
                   &on, &bx, &by, &bw, &bh, &br, &to, &ot,
                   &rf, &ds, &hg, &ag, &vb, &ib);
            g_cfg.file_on = on;
            g_cfg.bx = bx;
            g_cfg.by = by;
            g_cfg.bw = bw;
            g_cfg.bh = bh;
            g_cfg.br = br;
            g_cfg.timeout_ms = to;
            g_cfg.overtrip = ot > 0 ? ot : 1;
            float nrf = cl_def(rf, 0.f, 200.f, 28.0f);
            float nds = cl_def(ds, 0.f, 1.f, 0.0f);
            float nhg = cl_def(hg, 0.f, 2.f, 0.5f);
            float nag = cl_def(ag, 0.f, 360.f, 45.0f);
            float nvb = cl_def(vb, 0.f, 3.f, 1.5f);
            float nib = cl_def(ib, 0.f, 64.f, 0.0f);
            if (nrf != g_cfg.rf || nds != g_cfg.ds || nhg != g_cfg.hg ||
                nag != g_cfg.agDeg || nvb != g_cfg.vb || nib != g_cfg.ib) {
                g_cfg.rf = nrf;
                g_cfg.ds = nds;
                g_cfg.hg = nhg;
                g_cfg.agDeg = nag;
                g_cfg.vb = nvb;
                g_cfg.ib = nib;
                lg_logf("%s-PARAM rf=%.1f ds=%.2f hg=%.2f ag=%.1f vb=%.1f ib=%.1f",
                        TAG_E, (double)g_cfg.rf, (double)g_cfg.ds, (double)g_cfg.hg,
                        (double)g_cfg.agDeg, (double)g_cfg.vb, (double)g_cfg.ib);
            }
        }
        fclose(f);
    }
}

long read_proc_self_cpu(void) {
    FILE *f = fopen("/proc/self/stat", "r");
    if (!f) return -1;
    char buf[1024];
    if (!fgets(buf, sizeof(buf), f)) {
        fclose(f);
        return -1;
    }
    fclose(f);
    char *p = strrchr(buf, ')');
    if (!p || p[1] != ' ') return -1;
    char *cur = p + 2;
    for (int i = 0; i < 11; i++) {
        cur = strchr(cur, ' ');
        if (!cur) return -1;
        cur++;
    }
    unsigned long utime = strtoul(cur, &cur, 10);
    unsigned long stime = strtoul(cur, NULL, 10);
    return (long)(utime + stime);
}

// SF CPU 惰性自检：只在 generate 路径内按 TTL 触发，超阈连续到限即熔断
static void cpu_check_lazy(void) {
    static long prev_cpu = -1, prev_ms, trips;
    long t = lg_now_ms();
    if (t - prev_ms < CPU_CHECK_MS) return;
    long cur = read_proc_self_cpu();
    if (cur < 0 || prev_cpu < 0 || t <= prev_ms) {
        prev_cpu = cur;
        prev_ms = t;
        return;
    }
    double secs = (t - prev_ms) / 1000.0;
    long clk = sysconf(_SC_CLK_TCK);
    if (clk <= 0) clk = 100;
    double frac = (double)(cur - prev_cpu) / (secs * (double)clk);
    prev_cpu = cur;
    prev_ms = t;
    if (frac > CPU_LIMIT) {
        trips++;
        lg_log_throttled("cpu", "%s-CPU frac=%.2f trips=%ld/%d", TAG_E, frac, trips, CPU_TRIP);
        if (trips >= CPU_TRIP) {
            lg_guard_trip_now("cpu");
            trips = 0;
        }
    } else {
        trips = 0;
    }
}

// 解析 so 文件里指定符号的重定位项：扫全部 SHT_RELA，优先 JUMP_SLOT 类型
int lg_engine_lookup_reloc(const char *path, const char *want,
                           unsigned long *off_out, unsigned *type_out) {
    int fd = open(path, O_RDONLY);
    if (fd < 0) return -1;
    struct stat st;
    if (fstat(fd, &st) != 0 || st.st_size < 64 || st.st_size > 512L * 1024 * 1024) {
        close(fd);
        return -1;
    }
    size_t sz = (size_t)st.st_size;
    unsigned char *b = mmap(NULL, sz, PROT_READ, MAP_PRIVATE, fd, 0);
    close(fd);
    if (b == MAP_FAILED) return -1;

    int rc = -1;
    unsigned long fb_off = 0;
    unsigned fb_type = 0;
    int have_fb = 0;
    if (memcmp(b, "\x7f""ELF", 4) == 0 && b[4] == 2 && b[5] == 1) {
        uint64_t shoff;
        uint16_t shentsize, shnum, shstrndx;
        memcpy(&shoff, b + 0x28, 8);
        memcpy(&shentsize, b + 0x3a, 2);
        memcpy(&shnum, b + 0x3c, 2);
        memcpy(&shstrndx, b + 0x3e, 2);
        if (shoff && shnum && shentsize >= 64 && shstrndx < shnum &&
            shoff + (uint64_t)shnum * shentsize <= sz) {
            for (uint16_t i = 0; i < shnum && rc != 0; i++) {
                const unsigned char *sh = b + shoff + (uint64_t)i * shentsize;
                uint32_t sh_type, sh_link;
                uint64_t sh_offset, sh_size, sh_entsize;
                memcpy(&sh_type, sh + 4, 4);
                memcpy(&sh_offset, sh + 24, 8);
                memcpy(&sh_size, sh + 32, 8);
                memcpy(&sh_link, sh + 40, 4);
                memcpy(&sh_entsize, sh + 56, 8);
                if (sh_type != 4 /* SHT_RELA */ || sh_entsize != 24) continue;
                if (sh_offset + sh_size > sz || sh_link >= shnum) continue;
                const unsigned char *symsh = b + shoff + (uint64_t)sh_link * shentsize;
                uint32_t sym_type, sym_link;
                uint64_t sym_off, sym_size, sym_entsize;
                memcpy(&sym_type, symsh + 4, 4);
                memcpy(&sym_off, symsh + 24, 8);
                memcpy(&sym_size, symsh + 32, 8);
                memcpy(&sym_link, symsh + 40, 4);
                memcpy(&sym_entsize, symsh + 56, 8);
                if ((sym_type != 11 /* SHT_DYNSYM */ && sym_type != 2 /* SHT_SYMTAB */) ||
                    sym_link >= shnum || sym_entsize != 24)
                    continue;
                if (sym_off + sym_size > sz) continue;
                const unsigned char *strsh = b + shoff + (uint64_t)sym_link * shentsize;
                uint64_t str_off, str_size;
                memcpy(&str_off, strsh + 24, 8);
                memcpy(&str_size, strsh + 32, 8);
                if (str_off + str_size > sz) continue;
                size_t nrela = (size_t)(sh_size / 24);
                for (size_t k = 0; k < nrela; k++) {
                    const unsigned char *re = b + sh_offset + k * 24;
                    uint64_t r_offset, r_info;
                    memcpy(&r_offset, re, 8);
                    memcpy(&r_info, re + 8, 8);
                    uint32_t r_sym = (uint32_t)(r_info >> 32);
                    uint32_t r_type = (uint32_t)(r_info & 0xffffffffu);
                    uint64_t syment = sym_off + (uint64_t)r_sym * sym_entsize;
                    if (r_sym == 0 || syment + 24 > sz) continue;
                    uint32_t st_name;
                    memcpy(&st_name, b + syment, 4);
                    if (str_off + st_name >= sz) continue;
                    const char *nm = (const char *)(b + str_off + st_name);
                    size_t maxn = sz - (size_t)str_off - st_name;
                    size_t l = strnlen(nm, maxn);
                    if (l == maxn || l != strlen(want) || memcmp(nm, want, l) != 0) continue;
                    if (r_type == LG_R_JUMP_SLOT) {
                        *off_out = (unsigned long)r_offset;
                        *type_out = r_type;
                        rc = 0;
                        break;
                    }
                    if (!have_fb) {
                        fb_off = (unsigned long)r_offset;
                        fb_type = r_type;
                        have_fb = 1;
                    }
                }
            }
            if (rc != 0 && have_fb) {
                *off_out = fb_off;
                *type_out = fb_type;
                rc = 0;
            }
        }
    }
    munmap(b, sz);
    return rc;
}

// 从 maps 找 RE-ext 的加载基址（file offset=0 的首段）与文件路径
int lg_engine_reext_from_maps(const char *maps_path, unsigned long *bias_out,
                              char *path_out, size_t n) {
    FILE *f = fopen(maps_path, "r");
    if (!f) return -1;
    char line[512];
    int rc = -1;
    while (fgets(line, sizeof(line), f)) {
        if (!strstr(line, "librenderengineextimpl.so")) continue;
        unsigned long start = 0, end = 0, off = 0, ino = 0;
        char perms[16], dev[16];
        char path[256];
        int nconv = sscanf(line, "%lx-%lx %15s %lx %15s %lu %255s",
                           &start, &end, perms, &off, dev, &ino, path);
        if (nconv == 7 && off == 0) {
            *bias_out = start;
            snprintf(path_out, n, "%s", path);
            rc = 0;
            break;
        }
    }
    fclose(f);
    return rc;
}

#ifndef LG_HOST_TEST

struct lg_eng_syms {
    void *make_for_shader;
    void *effect_mkshader;
    void *img_mkshader;
    void *data_empty;
    void *mk_n32;
    void *render_target;
    void *(*get_canvas)(void *surf);
    void *draw_image;
    void (*draw_paint)(void *canvas, void *paint);
    void *snapshot;
    void (*paint_c1)(void *p);
    void (*paint_d1)(void *p);
    void (*set_shader)(void *p, void *cell);
    void (*str_c1)(void *s, const char *c);
    void (*str_d1)(void *s);
    void *data_copy;
};
static struct lg_eng_syms E;

static void *g_orig;
static void *g_effect;
static char g_key[KEY_MAX];
static _Atomic int g_hook_ready;
static __thread int g_reentry;

int lg_engine_resolve_syms(void) {
    void *h = RTLD_DEFAULT;
    E.make_for_shader = (void *)dlsym(h, M_MAKE_FOR_SHADER);
    E.effect_mkshader = (void *)dlsym(h, M_EFFECT_MKSHADER);
    E.img_mkshader = (void *)dlsym(h, M_IMG_MKSHADER);
    E.data_empty = (void *)dlsym(h, M_DATA_EMPTY);
    E.mk_n32 = (void *)dlsym(h, M_MK_N32);
    E.render_target = (void *)dlsym(h, M_RENDER_TARGET);
    E.get_canvas = (void *)dlsym(h, M_GET_CANVAS);
    E.draw_image = (void *)dlsym(h, M_DRAW_IMAGE);
    E.draw_paint = (void *)dlsym(h, M_DRAW_PAINT);
    E.snapshot = (void *)dlsym(h, M_SNAPSHOT);
    E.paint_c1 = (void *)dlsym(h, M_PAINT_C1);
    E.paint_d1 = (void *)dlsym(h, M_PAINT_D1);
    E.set_shader = (void *)dlsym(h, M_SET_SHADER);
    E.str_c1 = (void *)dlsym(h, M_STR_C1);
    E.str_d1 = (void *)dlsym(h, M_STR_D1);
    E.data_copy = (void *)dlsym(h, M_DATA_WITH_COPY);
    int miss = 0;
    void **p = (void **)&E;
    for (size_t i = 0; i < sizeof(E) / sizeof(void *); i++)
        if (!p[i]) miss++;
    if (E.make_for_shader && *(const unsigned int *)E.make_for_shader != 0xd503233fu) {
        lg_logf("%s-SYM makeFs prologue mismatch %p", TAG_E, E.make_for_shader);
        miss++;
    }
    lg_logf("%s-SYMS miss=%d makeFs=%p rt=%p mk32=%p effMK=%p imgMK=%p empty=%p copy=%p",
            TAG_E, miss, (void *)E.make_for_shader, (void *)E.render_target, (void *)E.mk_n32,
            (void *)E.effect_mkshader, (void *)E.img_mkshader, (void *)E.data_empty,
            E.data_copy);
    return miss ? -1 : 0;
}

static void *get_effect(void) {
    char key[KEY_MAX];
    snprintf(key, sizeof(key), "%.1f|%.1f|%.1f|%.1f|%.1f",
             g_cfg.bx, g_cfg.by, g_cfg.bw, g_cfg.bh, g_cfg.br);
    if (g_effect && strcmp(key, g_key) == 0) return g_effect;
    if (g_effect) {
        sk_unref(g_effect);
        g_effect = NULL;
    }
    char code[CODE_MAX];
    int n = snprintf(code, sizeof(code), lg_glass_tpl(),
                     g_cfg.bx, g_cfg.by, g_cfg.bw, g_cfg.bh, g_cfg.br);
    if (n <= 0 || n >= (int)sizeof(code)) {
        fb_log("code-overflow");
        return NULL;
    }
    char strbuf[128];
    memset(strbuf, 0, sizeof(strbuf));
    E.str_c1(strbuf, code);
    unsigned long opts[2] = {0, 0};
    void *sret[2] = {0, 0};
    const void *mfs_args[2] = {strbuf, opts};
    x8_call(sret, E.make_for_shader, mfs_args, 2);
    E.str_d1(strbuf);
    if (!sret[0]) {
        static long fail_ms;
        long t = lg_now_ms();
        if (t - fail_ms > 60000) {
            fail_ms = t;
            lg_logf("%s-COMPILE fail key=%s", TAG_E, key);
        }
        return NULL;
    }
    g_effect = sret[0];
    strncpy(g_key, key, sizeof(g_key) - 1);
    lg_logf("%s-COMPILE ok key=%s", TAG_E, key);
    return g_effect;
}

// 渲染链：底图 → 玻璃块 → 新快照写回 sret（旧底图释放）
static int render_glass(void *ctx, void *img, int w, int h, void *sret) {
    void *effect = get_effect();
    if (!effect) return -1;

    char sampling[16];
    memset(sampling, 0, sizeof(sampling));
    *(int *)sampling = 1;

    void *bd[2] = {0, 0};
    const void *im_args[5] = {img, NULL, NULL, sampling, NULL};
    x8_call(bd, E.img_mkshader, im_args, 5);
    if (!bd[0]) {
        fb_log("mkshader-img");
        return -1;
    }
    float ubuf[6];
    ubuf[0] = g_cfg.rf;
    ubuf[1] = g_cfg.ds;
    ubuf[2] = g_cfg.hg;
    ubuf[3] = g_cfg.agDeg * 0.017453292519943295f;
    ubuf[4] = g_cfg.vb;
    ubuf[5] = g_cfg.ib;
    void *uni[2] = {0, 0};
    const void *ud_args[2] = {ubuf, (void *)(uintptr_t)(sizeof ubuf)};
    x8_call(uni, E.data_copy, ud_args, 2);
    if (!uni[0]) {
        sk_unref(bd[0]);
        fb_log("mkshader-uniforms");
        return -1;
    }

    void *glass[2] = {0, 0};
    const void *ef_args[5] = {effect, uni, bd, (void *)(uintptr_t)1, NULL};
    x8_call(glass, E.effect_mkshader, ef_args, 5);
    sk_unref(uni[0]);
    if (!glass[0]) {
        sk_unref(bd[0]);
        fb_log("mkshader-effect");
        return -1;
    }

    char info[32];
    memset(info, 0, sizeof(info));
    const void *n32_args[2] = {(void *)(uintptr_t)(unsigned)w, (void *)(uintptr_t)(unsigned)h};
    x8_call(info, E.mk_n32, n32_args, 2);
    void *surf[2] = {0, 0};
    const void *rt_args[8] = {ctx, NULL, info, (void *)(uintptr_t)1, NULL, NULL, NULL, NULL};
    x8_call(surf, E.render_target, rt_args, 8);
    if (!surf[0]) {
        sk_unref(bd[0]);
        sk_unref(glass[0]);
        fb_log("rendertarget");
        return -1;
    }
    void *canvas = E.get_canvas(surf[0]);
    if (!canvas) {
        sk_unref(surf[0]);
        sk_unref(bd[0]);
        sk_unref(glass[0]);
        fb_log("getcanvas");
        return -1;
    }

    char paint[512];
    memset(paint, 0, sizeof(paint));
    E.paint_c1(paint);
    E.set_shader(paint, glass);
    x8_draw_image(E.draw_image, canvas, img, 0.0f, 0.0f, sampling, NULL);
    E.draw_paint(canvas, paint);

    void *snap[2] = {0, 0};
    const void *sn_args[1] = {surf[0]};
    x8_call(snap, E.snapshot, sn_args, 1);
    E.paint_d1(paint);
    sk_unref(surf[0]);
    sk_unref(bd[0]);
    sk_unref(glass[0]);
    if (!snap[0]) {
        fb_log("snapshot");
        return -1;
    }
    sk_unref(*(void **)sret);
    *(void **)sret = snap[0];
    return 0;
}

// 原函数直通：sret x7 → x8；尾调 br 保住调用方 lr
__attribute__((naked)) static void lg_call_orig(void *fn, void *a0, void *a1, void *a2,
                                                void *a3, void *a4, void *a5, void *sret) {
    __asm__ volatile(
        "mov x8, x7\n"
        "mov x16, x0\n"
        "mov x0, x1\n"
        "mov x1, x2\n"
        "mov x2, x3\n"
        "mov x3, x4\n"
        "mov x4, x5\n"
        "mov x5, x6\n"
        "br x16\n");
}

static void lg_stat_print(void) {
    g_stat_calls = 0;
    lg_logf("%s-STAT calls=%ld ok=%ld fb=%ld err=%d hit=%ld rej=%ld over=%ld "
            "streak=%d rect=(%.0f,%.0f,%.0f,%.0f) q=[%ld,%ld,%ld,%ld,%ld]",
            TAG_E, atomic_load(&g_calls), atomic_load(&g_ok), atomic_load(&g_fb),
            lg_guard_err_count(), atomic_load(&g_hit), atomic_load(&g_reject),
            atomic_load(&g_over), atomic_load(&g_over_streak),
            g_last_rect[0], g_last_rect[1], g_last_rect[2], g_last_rect[3],
            atomic_load(&g_q[2]), atomic_load(&g_q[3]), atomic_load(&g_q[4]),
            atomic_load(&g_q[5]), atomic_load(&g_q[1]));
}

static void lg_hook_inner(void *a0, void *a1, unsigned a2, void *a3, void *a4, int a5,
                          void *sret) {
    int do_engine = 0;
    if (lg_glass_engine_alive() || lg_glass_arm_untried()) {
        cfg_reload();
        if (g_cfg.prop_on && g_cfg.file_on) do_engine = lg_glass_arm_engine();
    }

    float l = 0, t = 0, rr = 0, b = 0;
    float *r = (float *)a4;
    if (do_engine && !r) do_engine = 0;
    if (do_engine) {
        l = r[0];
        t = r[1];
        rr = r[2];
        b = r[3];
        double tx1 = g_cfg.bx + g_cfg.bw, ty1 = g_cfg.by + g_cfg.bh;
        if (rr < g_cfg.bx || l > tx1 || b < g_cfg.by || t > ty1) {
            do_engine = 0;
            atomic_fetch_add(&g_reject, 1);
        } else {
            atomic_fetch_add(&g_hit, 1);
            g_last_rect[0] = l;
            g_last_rect[1] = t;
            g_last_rect[2] = rr;
            g_last_rect[3] = b;
        }
    }

    lg_call_orig(g_orig, a0, a1, (void *)(uintptr_t)a2, a3, a4, (void *)(uintptr_t)a5, sret);
    if (!do_engine) return;

    void *img = *(void **)sret;
    if (!img) {
        atomic_fetch_add(&g_fb, 1);
        fb_log("null-img");
        return;
    }
    int w = (int)(rr - l + 0.5f), h = (int)(b - t + 0.5f);
    if (!g_first_rect) {
        g_first_rect = 1;
        lg_logf("%s-FIRST rect=(%.0f,%.0f,%.0f,%.0f) w=%d h=%d q=%d img=%p",
                TAG_E, l, t, rr, b, w, h, a5, img);
    }
    if (w < 64 || h < 64 || w > 8192 || h > 8192) {
        atomic_fetch_add(&g_fb, 1);
        fb_log("rect-range");
        return;
    }

    long t0 = lg_now_ms();
    int rc = render_glass(a1, img, w, h, sret);
    long elapsed = lg_now_ms() - t0;
    if (rc == 0) {
        atomic_fetch_add(&g_ok, 1);
        if (atomic_load(&g_ok) == 1) lg_logf("%s-RENDER-OK first", TAG_E);
    } else {
        lg_guard_note_error();
        atomic_fetch_add(&g_fb, 1);
    }
    if (g_cfg.timeout_ms <= 0 || elapsed > g_cfg.timeout_ms) {
        atomic_fetch_add(&g_over, 1);
        int streak = atomic_fetch_add(&g_over_streak, 1) + 1;
        if (streak >= g_cfg.overtrip) {
            lg_guard_trip_now("frame-timeout");
            return;
        }
    } else {
        atomic_store(&g_over_streak, 0);
    }
    cpu_check_lazy();
}

void lg_hook_worker(void *a0, void *a1, unsigned a2, void *a3, void *a4, int a5,
                    void *pad, void *sret) {
    (void)pad;
    atomic_fetch_add(&g_calls, 1);
    if (a5 >= 0 && a5 < 8) atomic_fetch_add(&g_q[a5], 1);
    if (atomic_fetch_add(&g_stat_calls, 1) + 1 >= STAT_EVERY) lg_stat_print();

    if (g_reentry) {
        lg_call_orig(g_orig, a0, a1, (void *)(uintptr_t)a2, a3, a4, (void *)(uintptr_t)a5, sret);
        return;
    }
    g_reentry = 1;
    lg_hook_inner(a0, a1, a2, a3, a4, a5, sret);
    g_reentry = 0;
}

// hook 入口：sret x8 → worker x7，尾分支保 lr
__attribute__((naked)) void lg_hook_entry(void) {
    __asm__ volatile(
        "mov x7, x8\n"
        "b lg_hook_worker\n");
}

static int try_install_once(const char **step) {
    if (atomic_load(&g_hook_ready)) {
        *step = "already";
        return 0;
    }
    unsigned long bias = 0;
    char so_path[256];
    if (lg_engine_reext_from_maps("/proc/self/maps", &bias, so_path, sizeof(so_path)) != 0) {
        *step = "no-maps";
        return -1;
    }
    unsigned long off = 0;
    unsigned type = 0;
    if (lg_engine_lookup_reloc(so_path, M_GEN, &off, &type) != 0) {
        *step = "reloc-miss";
        return -1;
    }
    uintptr_t slot_addr = (uintptr_t)bias + (uintptr_t)off;
    uintptr_t page = slot_addr & ~(uintptr_t)0xfff;
    if (mprotect((void *)page, 4096, PROT_READ | PROT_WRITE) != 0) {
        *step = "mprotect";
        return -1;
    }
    void *slot_orig = *(void **)slot_addr;
    void *expect = dlsym(RTLD_DEFAULT, M_GEN);
    void *orig = expect ? expect : slot_orig;
    if (!orig) {
        mprotect((void *)page, 4096, PROT_READ);
        *step = "slot-null";
        return -1;
    }
    if (expect && slot_orig && expect != slot_orig)
        lg_log_throttled("slotmix", "%s-HOOK warn slot=%p dlsym=%p", TAG_E, slot_orig, expect);
    g_orig = orig;
    *(void **)slot_addr = (void *)lg_hook_entry;
    mprotect((void *)page, 4096, PROT_READ);
    atomic_store(&g_hook_ready, 1);
    lg_logf("%s-HOOK ok bias=%p off=0x%lx type=%u slot=%p orig=%p entry=%p",
            TAG_E, (void *)bias, off, type, (void *)slot_addr, orig, (void *)lg_hook_entry);
    *step = "ok";
    return 0;
}

static void *install_retry_thread(void *arg) {
    (void)arg;
    const char *step = "?";
    for (int i = 1; i <= INSTALL_RETRY_MAX && !atomic_load(&g_hook_ready); i++) {
        sleep(1);
        if (try_install_once(&step) == 0) {
            lg_logf("%s-HOOK ok after retry i=%d", TAG_E, i);
            return NULL;
        }
        if (i == 1 || i == 5 || i == 10 || i == INSTALL_RETRY_MAX)
            lg_log_throttled("hookretry", "%s-HOOK retry i=%d step=%s", TAG_E, i, step);
    }
    if (!atomic_load(&g_hook_ready))
        lg_logf("%s-HOOK give-up step=%s（本会话无玻璃，纯转发）", TAG_E, step);
    return NULL;
}

int lg_engine_try_install(const char **step) {
    int rc = try_install_once(step);
    if (rc == 0) return 0;
    if (strcmp(*step, "no-maps") == 0) {
        static _Atomic int spawned;
        if (!atomic_exchange(&spawned, 1)) {
            pthread_t th;
            if (pthread_create(&th, NULL, install_retry_thread, NULL) == 0)
                pthread_detach(th);
            lg_logf("%s-HOOK deferred step=%s（RE-ext 未映射，起重试线程 %d×1s 上限）",
                    TAG_E, *step, INSTALL_RETRY_MAX);
        }
    }
    return rc;
}

#else /* LG_HOST_TEST */

int g_eng_syms_ok = 1;

int lg_engine_resolve_syms(void) { return g_eng_syms_ok ? 0 : -1; }

int lg_engine_try_install(const char **step) {
    *step = "host";
    return 0;
}

#endif
