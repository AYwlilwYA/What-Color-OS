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
#include "elfsym.h"

#define TAG_E "LG-GLASS"
#define CONF_PATH "/system_ext/lib64/oplusex/lg_glass.conf"
#define CODE_MAX 16384
#define KEY_MAX 96
#define CPU_CHECK_MS 5000
#define CPU_TRIP 3
#define CPU_LIMIT 0.8f
#define STAT_EVERY 100
#define INSTALL_RETRY_MAX 30
#define LG_BOOT_GATE_S 30

#define M_MAKE_FOR_SHADER "_ZN15SkRuntimeEffect13MakeForShaderE8SkStringRKNS_7OptionsE"
#define M_EFFECT_MKSHADER "_ZNK15SkRuntimeEffect10makeShaderE5sk_spIK6SkDataEPS0_I8SkShaderEmPK8SkMatrix"
#define M_IMG_MKSHADER "_ZNK7SkImage10makeShaderE10SkTileModeS0_RK17SkSamplingOptionsPK8SkMatrix"
#define M_DATA_EMPTY "_ZN6SkData9MakeEmptyEv"
#define M_DATA_WITH_COPY "_ZN6SkData12MakeWithCopyEPKvm"
#define M_MK_N32 "_ZN11SkImageInfo13MakeN32PremulEii"
#define M_RENDER_TARGET "_ZN10SkSurfaces12RenderTargetEP18GrRecordingContextN5skgpu8BudgetedERK11SkImageInfoi15GrSurfaceOriginPK14SkSurfacePropsbb"
#define M_GET_CANVAS "_ZN9SkSurface9getCanvasEv"
#define M_DRAW_IMAGE "_ZN8SkCanvas9drawImageEPK7SkImageffRK17SkSamplingOptionsPK7SkPaint"
#define M_DRAW_IMAGE_RECT "_ZN8SkCanvas13drawImageRectEPK7SkImageRK6SkRectRK17SkSamplingOptionsPK7SkPaint"
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

// 启动期门：SF primeCache 预热窗口内外部 shader 编译必崩，so 加载起 30s 内 generate 一律纯直通
static long g_load_mono;
__attribute__((constructor)) static void lg_engine_ctor(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    g_load_mono = ts.tv_sec;
}

static int lg_boot_gate_open(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    if (ts.tv_sec - g_load_mono < LG_BOOT_GATE_S) return 0;
    static int logged;
    if (!logged) {
        logged = 1;
        lg_logf("%s boot-gate open after %ds", TAG_E, LG_BOOT_GATE_S);
    }
    return 1;
}

void lg_cfg_reset_defaults(void) {
    g_cfg = (struct lg_cfg){0, 0, 1, 60, 60, 500, 300, 60, 100, 10, 28.0f, 0.0f, 0.5f, 45.0f, 1.5f, 0.0f};
}

static _Atomic long g_calls, g_ok, g_fb;
static _Atomic long g_hit, g_reject, g_over;
static _Atomic long g_q[8];
static _Atomic int g_over_streak;
static _Atomic long g_stat_calls;
static float g_last_rect[4];

#if defined(__aarch64__) && !defined(LG_HOST_TEST)
#define LG_RGEN_N 4
// B 路 generate 家族：符号名为主（跨 ROM 通用），硬偏移仅作符号缺失时的兜底
static const char *const k_rgen_sym[LG_RGEN_N] = {
    "_ZNK7android12renderengine4skia16KawaseBlurFilter8generateEPNS1_14SkiaGpuContextEj5sk_spI7SkImageERK6SkRect",
    "_ZNK7android12renderengine4skia18GaussianBlurFilter8generateEPNS1_14SkiaGpuContextEj5sk_spI7SkImageERK6SkRect",
    "_ZNK7android12renderengine4skia20KawaseBlurDualFilter8generateEPNS1_14SkiaGpuContextEj5sk_spI7SkImageERK6SkRect",
    "_ZN7android12renderengine4skia13IBlurUtilsExt8generateEP18GrRecordingContextj5sk_spI7SkImageERK6SkRect"};
// 兜底偏移：C15 三项 + C16 四项，paciasp/RX 门过滤不适用项
#define LG_RGEN_FB_N 7
static const unsigned long k_rgen_fb[LG_RGEN_FB_N] = {
    0x418a70UL, 0x640b24UL, 0x8d2194UL,
    0x4de8b0UL, 0x7150e0UL, 0x715ad8UL, 0x9c9678UL};
static unsigned long g_rgen_off[LG_RGEN_N];
static int g_rgen_resolved;
static void *g_rorig[LG_RGEN_N];
static void *g_rtramp[LG_RGEN_N];
static _Atomic int g_rdone[LG_RGEN_N];
static _Atomic int g_region_n;
static _Atomic long g_r_calls, g_r_hit, g_r_fb;
static _Atomic long g_r_site[LG_RGEN_N];
static float g_r_last[4];
static int g_r_first;
#endif

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
    __system_property_get("debug.lg.glass", val);
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
// 可选件：miss 不计入 arm 失败（有 draw_image 兜底）
static void *g_draw_image_rect;

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
    g_draw_image_rect = (void *)dlsym(h, M_DRAW_IMAGE_RECT);
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
    lg_logf("%s-SYMS miss=%d makeFs=%p rt=%p mk32=%p effMK=%p imgMK=%p empty=%p copy=%p imgRect=%p",
            TAG_E, miss, (void *)E.make_for_shader, (void *)E.render_target, (void *)E.mk_n32,
            (void *)E.effect_mkshader, (void *)E.img_mkshader, (void *)E.data_empty,
            E.data_copy, g_draw_image_rect);
    return miss ? -1 : 0;
}

static void *get_effect(void) {
    // 几何 uniform 化后 shader 编译一次永用
    char key[KEY_MAX];
    snprintf(key, sizeof(key), "static");
    if (g_effect && strcmp(key, g_key) == 0) return g_effect;
    if (g_effect) {
        sk_unref(g_effect);
        g_effect = NULL;
    }
    char code[CODE_MAX];
    int n = snprintf(code, sizeof(code), "%s", lg_glass_tpl());
    if (n <= 0 || n >= (int)sizeof(code)) {
        fb_log("code-overflow");
        return NULL;
    }
    char strbuf[128];
    memset(strbuf, 0, sizeof(strbuf));
    E.str_c1(strbuf, code);
    // Options 实收 ≥0x24 字节，源码 string_view 内嵌于 +0x8(data)/+0x10(size)，其余字段零默认
    unsigned long opts[8] = {0};
    opts[1] = (unsigned long)(uintptr_t)code;
    opts[2] = (unsigned long)n;
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
    float ubuf[9];
    ubuf[0] = g_cfg.rf;
    ubuf[1] = g_cfg.ds;
    ubuf[2] = g_cfg.hg;
    ubuf[3] = g_cfg.agDeg * 0.017453292519943295f;
    ubuf[4] = g_cfg.vb;
    ubuf[5] = g_cfg.ib;
    ubuf[6] = (float)w;
    ubuf[7] = (float)h;
    ubuf[8] = g_cfg.br;
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
    if (g_draw_image_rect) {
        // 底图按 dst 拉伸铺满 region 画布（系统源常为降采样小图，原始尺寸绘制会缩在左上）
        float dst[4] = {0.0f, 0.0f, (float)w, (float)h};
        const void *dr_args[5] = {canvas, img, dst, sampling, NULL};
        x8_call(NULL, g_draw_image_rect, dr_args, 5);
    } else {
        x8_draw_image(E.draw_image, canvas, img, 0.0f, 0.0f, sampling, NULL);
    }
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
    cfg_reload();
#if defined(__aarch64__) && !defined(LG_HOST_TEST)
    lg_logf("%s-STAT calls=%ld ok=%ld fb=%ld err=%d hit=%ld rej=%ld over=%ld "
            "streak=%d rect=(%.0f,%.0f,%.0f,%.0f) q=[%ld,%ld,%ld,%ld,%ld] "
            "R calls=%ld rhit=%ld rfb=%ld rsites=[%ld,%ld,%ld,%ld] "
            "Rrect=(%.0f,%.0f,%.0f,%.0f) "
            "gate=%d prop=%d file=%d alive=%d untried=%d region_n=%d",
            TAG_E, atomic_load(&g_calls), atomic_load(&g_ok), atomic_load(&g_fb),
            lg_guard_err_count(), atomic_load(&g_hit), atomic_load(&g_reject),
            atomic_load(&g_over), atomic_load(&g_over_streak),
            g_last_rect[0], g_last_rect[1], g_last_rect[2], g_last_rect[3],
            atomic_load(&g_q[2]), atomic_load(&g_q[3]), atomic_load(&g_q[4]),
            atomic_load(&g_q[5]), atomic_load(&g_q[1]),
            atomic_load(&g_r_calls), atomic_load(&g_r_hit), atomic_load(&g_r_fb),
            atomic_load(&g_r_site[0]), atomic_load(&g_r_site[1]),
            atomic_load(&g_r_site[2]), atomic_load(&g_r_site[3]),
            g_r_last[0], g_r_last[1], g_r_last[2], g_r_last[3],
            lg_boot_gate_open() ? 1 : 0, g_cfg.prop_on, g_cfg.file_on,
            lg_glass_engine_alive() ? 1 : 0, lg_glass_arm_untried() ? 1 : 0,
            atomic_load(&g_region_n));
#else
    lg_logf("%s-STAT calls=%ld ok=%ld fb=%ld err=%d hit=%ld rej=%ld over=%ld "
            "streak=%d rect=(%.0f,%.0f,%.0f,%.0f) q=[%ld,%ld,%ld,%ld,%ld]",
            TAG_E, atomic_load(&g_calls), atomic_load(&g_ok), atomic_load(&g_fb),
            lg_guard_err_count(), atomic_load(&g_hit), atomic_load(&g_reject),
            atomic_load(&g_over), atomic_load(&g_over_streak),
            g_last_rect[0], g_last_rect[1], g_last_rect[2], g_last_rect[3],
            atomic_load(&g_q[2]), atomic_load(&g_q[3]), atomic_load(&g_q[4]),
            atomic_load(&g_q[5]), atomic_load(&g_q[1]));
#endif
}

static void lg_hook_inner(void *a0, void *a1, unsigned a2, void *a3, void *a4, int a5,
                          void *sret) {
    int do_engine = 0;
    if (lg_boot_gate_open() && (lg_glass_engine_alive() || lg_glass_arm_untried())) {
        cfg_reload();
        if (g_cfg.prop_on && g_cfg.file_on) do_engine = lg_glass_arm_engine();
    }
    lg_call_orig(g_orig, a0, a1, (void *)(uintptr_t)a2, a3, a4, (void *)(uintptr_t)a5, sret);
    float *r = (float *)a4;
    if (r) {
        g_last_rect[0] = r[0];
        g_last_rect[1] = r[1];
        g_last_rect[2] = r[2];
        g_last_rect[3] = r[3];
        atomic_fetch_add(&g_hit, 1);
    }
    // A 路停渲只透传（rect 恒全屏层 bounds，全屏自渲即「仍然全屏」根因）；玻璃归 B 路 region
    (void)do_engine;
    (void)sret;
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

#ifdef __aarch64__
// maps 找 surfaceflinger 主程序首段（vaddr==offset 的 bias）与其 r-xp 段范围
static int lg_sfbin_from_maps(const char *maps_path, unsigned long *bias_out,
                              char *path_out, size_t n,
                              unsigned long *rx_s, unsigned long *rx_e) {
    FILE *f = fopen(maps_path, "r");
    if (!f) return -1;
    char line[512];
    int rc = -1;
    int have_bias = 0;
    *rx_s = 0;
    *rx_e = 0;
    while (fgets(line, sizeof(line), f)) {
        if (!strstr(line, "surfaceflinger")) continue;
        unsigned long start = 0, end = 0, off = 0, ino = 0;
        char perms[16], dev[16], path[256];
        int nconv = sscanf(line, "%lx-%lx %15s %lx %15s %lu %255s",
                           &start, &end, perms, &off, dev, &ino, path);
        if (nconv != 7) continue;
        if (!have_bias && off == 0) {
            *bias_out = start;
            snprintf(path_out, n, "%s", path);
            rc = 0;
            have_bias = 1;
            if (strchr(perms, 'x')) {
                *rx_s = start;
                *rx_e = end;
            }
            continue;
        }
        if (have_bias && *rx_e == 0 && strchr(perms, 'x') &&
            strcmp(path, path_out) == 0) {
            *rx_s = start;
            *rx_e = end;
            break;
        }
    }
    fclose(f);
    return rc;
}

// 写 n 字节进可执行地址：/proc/self/mem FOLL_FORCE 优先（不掉执行位），mprotect 兜底；写后清 I-cache
static int lg_write_text(unsigned long addr, const void *src, size_t n, int restore_prot) {
    int fd = open("/proc/self/mem", O_WRONLY);
    if (fd >= 0) {
        ssize_t w = -1;
        if (lseek(fd, (off_t)addr, SEEK_SET) != (off_t)-1)
            w = write(fd, src, n);
        close(fd);
        if (w == (ssize_t)n) {
            __builtin___clear_cache((char *)addr, (char *)addr + n);
            return 0;
        }
    }
    unsigned long page = addr & ~0xfffUL;
    if (mprotect((void *)page, 4096, PROT_READ | PROT_WRITE) == 0) {
        memcpy((void *)addr, src, n);
        if (mprotect((void *)page, 4096, restore_prot) != 0)
            lg_logf("%s-INL restore-fail addr=%p（页执行位可能丢失）", TAG_E, (void *)addr);
        __builtin___clear_cache((char *)addr, (char *)addr + n);
        return 0;
    }
    return -1;
}

// RX 段页尾零填充洞（filesz→页界，页已 RX）；C15/C16 filesz 不同，按 maps rx_e 反推
#define LG_CAVE_SLOT 48
#define LG_CAVE_USED 40

static int lg_find_cave(unsigned long bias, int idx, unsigned long rx_e,
                        unsigned long *out) {
    (void)bias;
    unsigned long total = (unsigned long)LG_RGEN_N * LG_CAVE_SLOT;
    if (rx_e < total) return -1;
    unsigned long base = rx_e - total;
    unsigned long a = base + (unsigned long)idx * LG_CAVE_SLOT;
    if (a + LG_CAVE_USED > rx_e) return -1;
    const unsigned char *p = (const unsigned char *)a;
    for (int i = 0; i < LG_CAVE_USED; i++)
        if (p[i]) return -1;
    *out = a;
    return 0;
}

static void lg_region_worker(int site, void *a0, void *a1, unsigned a2,
                             void *a3, void *a4, void *sret);
// used：跳板汇编按名引用，编译器 C 视角看不到会 DCE
__attribute__((used)) static void lg_region_w0(void *a0, void *a1, unsigned a2, void *a3, void *a4, void *sret) {
    lg_region_worker(0, a0, a1, a2, a3, a4, sret);
}
__attribute__((used)) static void lg_region_w1(void *a0, void *a1, unsigned a2, void *a3, void *a4, void *sret) {
    lg_region_worker(1, a0, a1, a2, a3, a4, sret);
}
__attribute__((used)) static void lg_region_w2(void *a0, void *a1, unsigned a2, void *a3, void *a4, void *sret) {
    lg_region_worker(2, a0, a1, a2, a3, a4, sret);
}
__attribute__((used)) static void lg_region_w3(void *a0, void *a1, unsigned a2, void *a3, void *a4, void *sret) {
    lg_region_worker(3, a0, a1, a2, a3, a4, sret);
}
// 入口跳板：sret x8 → x5（第6参），尾分支保调用方 lr；首条 bti jc 过间接跳转落地垫检查
__attribute__((naked)) static void lg_rthunk0(void) {
    __asm__ volatile("bti jc\n mov x5, x8\n b lg_region_w0");
}
__attribute__((naked)) static void lg_rthunk1(void) {
    __asm__ volatile("bti jc\n mov x5, x8\n b lg_region_w1");
}
__attribute__((naked)) static void lg_rthunk2(void) {
    __asm__ volatile("bti jc\n mov x5, x8\n b lg_region_w2");
}
__attribute__((naked)) static void lg_rthunk3(void) {
    __asm__ volatile("bti jc\n mov x5, x8\n b lg_region_w3");
}
static void *const g_rthunk[LG_RGEN_N] = {
    (void *)lg_rthunk0, (void *)lg_rthunk1, (void *)lg_rthunk2, (void *)lg_rthunk3};

static void lg_region_worker(int site, void *a0, void *a1, unsigned a2,
                             void *a3, void *a4, void *sret) {
    (void)a3;
    atomic_fetch_add(&g_r_calls, 1);
    if (site >= 0 && site < LG_RGEN_N) atomic_fetch_add(&g_r_site[site], 1);
    if (atomic_fetch_add(&g_stat_calls, 1) + 1 >= STAT_EVERY) lg_stat_print();

    void *orig = (site >= 0 && site < LG_RGEN_N) ? g_rorig[site] : NULL;
    if (!orig) return;
    if (g_reentry) {
        lg_call_orig(orig, a0, a1, (void *)(uintptr_t)a2, a3, a4, NULL, sret);
        return;
    }
    g_reentry = 1;
    int do_engine = 0;
    if (lg_boot_gate_open() && (lg_glass_engine_alive() || lg_glass_arm_untried())) {
        cfg_reload();
        if (g_cfg.prop_on && g_cfg.file_on) do_engine = lg_glass_arm_engine();
    }
    float l = 0, t = 0, rr = 0, b = 0;
    float *r = (float *)a4;
    if (do_engine && r) {
        l = r[0];
        t = r[1];
        rr = r[2];
        b = r[3];
        if (!(l == l && t == t && rr == rr && b == b) || !(rr > l) || !(b > t))
            do_engine = 0;
    } else {
        do_engine = 0;
    }

    lg_call_orig(orig, a0, a1, (void *)(uintptr_t)a2, a3, a4, NULL, sret);
    if (!do_engine) {
        g_reentry = 0;
        return;
    }

    void *img = *(void **)sret;
    if (!img) {
        atomic_fetch_add(&g_r_fb, 1);
        fb_log("r-null-img");
        g_reentry = 0;
        return;
    }
    int w = (int)(rr - l + 0.5f), h = (int)(b - t + 0.5f);
    if (w < 4 || h < 4 || w > 8192 || h > 8192) {
        atomic_fetch_add(&g_r_fb, 1);
        fb_log("r-rect-range");
        g_reentry = 0;
        return;
    }

    atomic_fetch_add(&g_r_hit, 1);
    if (l != g_r_last[0] || t != g_r_last[1] ||
        rr != g_r_last[2] || b != g_r_last[3]) {
        lg_logf("%s-RREGION site=%d (%.0f,%.0f,%.0f,%.0f)", TAG_E, site, l, t, rr, b);
        g_r_last[0] = l;
        g_r_last[1] = t;
        g_r_last[2] = rr;
        g_r_last[3] = b;
    }
    if (!g_r_first) {
        g_r_first = 1;
        lg_logf("%s-RFIRST site=%d rect=(%.0f,%.0f,%.0f,%.0f) w=%d h=%d img=%p",
                TAG_E, site, l, t, rr, b, w, h, img);
    }

    long t0 = lg_now_ms();
    int rc = render_glass(a1, img, w, h, sret);
    long elapsed = lg_now_ms() - t0;
    if (rc == 0) {
        atomic_fetch_add(&g_ok, 1);
        if (atomic_load(&g_ok) == 1) lg_logf("%s-RENDER-OK first", TAG_E);
    } else {
        lg_guard_note_error();
        atomic_fetch_add(&g_r_fb, 1);
    }
    if (g_cfg.timeout_ms <= 0 || elapsed > g_cfg.timeout_ms) {
        atomic_fetch_add(&g_over, 1);
        int streak = atomic_fetch_add(&g_over_streak, 1) + 1;
        if (streak >= g_cfg.overtrip) {
            lg_guard_trip_now("frame-timeout");
            g_reentry = 0;
            return;
        }
    } else {
        atomic_store(&g_over_streak, 0);
    }
    cpu_check_lazy();
    g_reentry = 0;
}

// 七候选逐站点装（BTI+PAC 保护：所有间接跳转目标首条必须是落地垫 bti jc / paciasp）：
// 入口 24B = bti jc + ldr x17,#8 + br x17 + thunk + 桥 bti jc（覆盖原指令 0..5），
// cave 40B = bti jc + 原指令 0..4 重放 + ldr/br → 桥（bti jc）→ 原指令 6 起原位续跑；
// worker 调原函数 = br 进 cave（重放 paciasp 与保存序，g_rorig 指 cave 不指 addr+16）
// generate 家族地址解析：符号表优先（跨 ROM），全 miss 才退回硬偏移表
static void resolve_rgen_offsets(const char *exe, unsigned long rx_s, unsigned long rx_e,
                                 unsigned long bias) {
    if (g_rgen_resolved) return;
    int hit = 0;
    for (int i = 0; i < LG_RGEN_N; i++) {
        unsigned long va = 0;
        if (lg_elfsym_find(exe, k_rgen_sym[i], &va) == 0 && va) {
            unsigned long a = bias + va;
            if (a >= rx_s && a + 24 <= rx_e) {
                g_rgen_off[i] = va;
                hit++;
                lg_logf("%s-SYM site=%d off=0x%lx src=%d", TAG_E, i, va,
                        lg_elfsym_last_source());
                continue;
            }
            lg_logf("%s-SYM site=%d off=0x%lx 超 RX 段，弃用", TAG_E, i, va);
        }
        g_rgen_off[i] = 0;
    }
    if (hit > 0) {
        g_rgen_resolved = 1;
        lg_logf("%s-SYM resolved=%d/%d（符号路径）", TAG_E, hit, LG_RGEN_N);
        return;
    }
    // 符号全 miss：兜底表逐项试，paciasp/RX 门在安装时过滤
    for (int i = 0; i < LG_RGEN_N && i < LG_RGEN_FB_N; i++) g_rgen_off[i] = k_rgen_fb[i];
    g_rgen_resolved = 1;
    lg_logf("%s-SYM miss=%d 全表，回退硬偏移兜底", TAG_E, LG_RGEN_N);
}

static int install_region_sites(const char **step) {
    if (atomic_load(&g_region_n) >= LG_RGEN_N) {
        *step = "already";
        return 0;
    }
    unsigned long bias = 0, rx_s = 0, rx_e = 0;
    char exe[256];
    if (lg_sfbin_from_maps("/proc/self/maps", &bias, exe, sizeof(exe),
                           &rx_s, &rx_e) != 0) {
        *step = "sf-no-maps";
        return -1;
    }
    resolve_rgen_offsets(exe, rx_s, rx_e, bias);
    int got = 0;
    for (int i = 0; i < LG_RGEN_N; i++) {
        if (atomic_load(&g_rdone[i])) {
            got++;
            continue;
        }
        if (!g_rgen_off[i]) continue;
        unsigned long addr = bias + g_rgen_off[i];
        if (addr < rx_s || addr + 24 > rx_e) {
            lg_logf("%s-INL site=%d addr=%p 超出 RX 段 [%p,%p) 跳过",
                    TAG_E, i, (void *)addr, (void *)rx_s, (void *)rx_e);
            *step = "out-of-rx";
            continue;
        }
        unsigned pro = *(const unsigned *)addr;
        if (pro != 0xd503233fu) {
            lg_logf("%s-INL site=%d addr=%p pro=%08x 非 paciasp 跳过",
                    TAG_E, i, (void *)addr, pro);
            continue;
        }
        unsigned char orig24[24];
        memcpy(orig24, (const void *)addr, 24);
        unsigned long cave = 0;
        if (lg_find_cave(bias, i, rx_e, &cave) != 0) {
            lg_logf("%s-INL site=%d cave 槽非零或越界 rx_e=%p", TAG_E, i, (void *)rx_e);
            *step = "cave-find";
            continue;
        }
        unsigned int w_bti = 0xd50324dfu, w_ldr = 0x58000051u, w_br = 0xd61f0220u;
        unsigned long bridge = addr + 20;
        unsigned char cavebuf[LG_CAVE_USED];
        memset(cavebuf, 0, sizeof(cavebuf));
        memcpy(cavebuf, &w_bti, 4);
        memcpy(cavebuf + 4, orig24, 20);
        memcpy(cavebuf + 24, &w_ldr, 4);
        memcpy(cavebuf + 28, &w_br, 4);
        memcpy(cavebuf + 32, &bridge, 8);
        if (lg_write_text(cave, cavebuf, LG_CAVE_USED, PROT_READ | PROT_EXEC) != 0 ||
            memcmp((const void *)cave, cavebuf, LG_CAVE_USED) != 0) {
            lg_logf("%s-INL site=%d cave-write fail cave=%p", TAG_E, i, (void *)cave);
            *step = "cave-write";
            continue;
        }

        unsigned long thunk = (unsigned long)g_rthunk[i];
        unsigned char ent[24];
        memset(ent, 0, sizeof(ent));
        memcpy(ent, &w_bti, 4);
        memcpy(ent + 4, &w_ldr, 4);
        memcpy(ent + 8, &w_br, 4);
        memcpy(ent + 12, &thunk, 8);
        memcpy(ent + 20, &w_bti, 4);
        g_rorig[i] = (void *)cave;
        g_rtramp[i] = (void *)bridge;
        if (lg_write_text(addr, ent, 24, PROT_READ | PROT_EXEC) != 0 ||
            memcmp((const void *)addr, ent, 24) != 0) {
            lg_logf("%s-INL site=%d entry-patch fail，回退原指令", TAG_E, i);
            lg_write_text(addr, orig24, 24, PROT_READ | PROT_EXEC);
            g_rorig[i] = NULL;
            *step = "entry-patch";
            continue;
        }
        atomic_store(&g_rdone[i], 1);
        atomic_fetch_add(&g_region_n, 1);
        got++;
        lg_logf("%s-INL site=%d bias=%p off=0x%lx addr=%p thunk=%p cave=%p",
                TAG_E, i, (void *)bias, g_rgen_off[i], (void *)addr,
                g_rthunk[i], (void *)cave);
    }
    if (got > 0) {
        *step = "ok";
        return 0;
    }
    return -1;
}
#endif /* __aarch64__ */

static int install_got(const char **step) {
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

// 总装：B 路 region inline = 玻璃真身（必须）；A 路 GOT = 透传+统计（辅）
static int try_install_once(const char **step) {
    const char *a_step = "?";
    int a_ok = atomic_load(&g_hook_ready) != 0;
    if (!a_ok) a_ok = install_got(&a_step) == 0;
#ifdef __aarch64__
    const char *r_step = "?";
    install_region_sites(&r_step);
    int r_ok = atomic_load(&g_region_n) > 0;
    if (r_ok) {
        *step = "ok";
        return 0;
    }
    lg_logf("%s-INL 装配结果 a_ok=%d a_step=%s r_step=%s（B 路 0 站点=本会话无模块玻璃）",
            TAG_E, a_ok, a_step, r_step);
    if (strcmp(a_step, "no-maps") == 0) {
        *step = a_step;
        return -1;
    }
    *step = r_step;
    return -1;
#else
    if (a_ok) {
        *step = "ok";
        return 0;
    }
    *step = a_step;
    return -1;
#endif
}

static void *install_retry_thread(void *arg) {
    (void)arg;
    const char *step = "?";
    for (int i = 1; i <= INSTALL_RETRY_MAX; i++) {
        int b_done =
#ifdef __aarch64__
            atomic_load(&g_region_n) > 0;
#else
            atomic_load(&g_hook_ready) != 0;
#endif
        if (b_done) return NULL;
        sleep(1);
        if (try_install_once(&step) == 0) {
            lg_logf("%s-HOOK ok after retry i=%d", TAG_E, i);
            return NULL;
        }
        if (i == 1 || i == 5 || i == 10 || i == INSTALL_RETRY_MAX)
            lg_log_throttled("hookretry", "%s-HOOK retry i=%d step=%s", TAG_E, i, step);
    }
#ifdef __aarch64__
    if (atomic_load(&g_region_n) == 0)
        lg_logf("%s-HOOK give-up step=%s（B 路 0 站点，本会话无玻璃）", TAG_E, step);
#else
    if (!atomic_load(&g_hook_ready))
        lg_logf("%s-HOOK give-up step=%s（本会话无玻璃，纯转发）", TAG_E, step);
#endif
    return NULL;
}

int lg_engine_try_install(const char **step) {
    int rc = try_install_once(step);
    if (rc == 0) return 0;
    int need_retry = (strcmp(*step, "no-maps") == 0 ||
                      strcmp(*step, "sf-no-maps") == 0);
#ifdef __aarch64__
    need_retry = need_retry || atomic_load(&g_region_n) == 0;
#endif
    if (need_retry) {
        static _Atomic int spawned;
        if (!atomic_exchange(&spawned, 1)) {
            pthread_t th;
            if (pthread_create(&th, NULL, install_retry_thread, NULL) == 0)
                pthread_detach(th);
            lg_logf("%s-HOOK deferred step=%s（起重试线程 %d×1s 上限）",
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
