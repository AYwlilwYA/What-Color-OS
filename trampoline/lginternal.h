// trampoline.cpp 与 engine.c 的共享面：日志/计时/护栏 API + 引擎配置结构 + x8 间接结果调用助手
#ifndef LG_INTERNAL_H
#define LG_INTERNAL_H

#ifdef __cplusplus
extern "C" {
#endif

// —— 定义在 trampoline.cpp（hidden 可见性）——
long lg_now_ms(void);
void lg_logf(const char *fmt, ...);
void lg_log_throttled(const char *key, const char *fmt, ...);
void sk_unref(void *obj);
void lg_guard_note_error(void);
void lg_guard_trip_now(const char *reason);
int lg_guard_err_count(void);
int lg_glass_arm_engine(void);
int lg_glass_engine_alive(void);
int lg_glass_arm_untried(void);

// —— 定义在 engine.c ——
struct lg_cfg {
    int prop_on;        // debug.lg.glass 总闸（debug_prop，全域可读）
    int file_on;        // conf on 字段
    int enabled;        // 引擎内部使能
    double bx, by, bw, bh, br;  // 圆角矩形几何（conf 行）
    int timeout_ms;     // 单帧渲染超时
    int overtrip;       // 连续超阈 trip 次数
    float rf, ds, hg;   // 折射/色散/高光
    float agDeg;        // 光照角度（度）
    float vb, ib;       // 振动/内发光
    long ms;            // 上次 conf 读取时刻（TTL 判据）
};
extern struct lg_cfg g_cfg;

void lg_cfg_reset_defaults(void);
void cfg_reload(void);
long read_proc_self_cpu(void);
int lg_engine_lookup_reloc(const char *path, const char *want,
                           unsigned long *off_out, unsigned *type_out);
int lg_engine_reext_from_maps(const char *maps_path, unsigned long *bias_out,
                              char *path_out, size_t n);
int lg_engine_try_install(const char **step);
int lg_engine_resolve_syms(void);
const char *lg_glass_tpl(void);

#ifdef LG_HOST_TEST
extern int g_eng_syms_ok;
#endif

#ifdef __cplusplus
}  // extern "C"
#endif

#if defined(__aarch64__) && !defined(LG_HOST_TEST)
#define LG_X8CLOB "x0","x1","x2","x3","x4","x5","x6","x7","x8","x9","x10","x11","x12","x13","x14","x15","x16","x17","x30", \
                 "v0","v1","v2","v3","v4","v5","v6","v7","v8","v9","v10","v11","v12","v13","v14","v15", \
                 "v16","v17","v18","v19","v20","v21","v22","v23","v24","v25","v26","v27","v28","v29","v30","v31", \
                 "memory","cc"

// AAPCS64 x8 间接结果调用：入参收进栈上结构体、单指针进汇编，避免多输入寄存器被提前破坏
static inline __attribute__((always_inline)) void x8_call(void *ret, void *fn,
                                                          const void **args, int n) {
    struct {
        void *fn;
        void *ret;
        void *args[8];
    } s;
    s.fn = fn;
    s.ret = ret;
    for (int i = 0; i < 8; i++) s.args[i] = (args && i < n) ? (void *)args[i] : NULL;
    __asm__ volatile(
        "mov x9, %0\n\t"
        "ldr x10, [x9]\n\t"
        "ldr x11, [x9, #8]\n\t"
        "ldp x0, x1, [x9, #16]\n\t"
        "ldp x2, x3, [x9, #32]\n\t"
        "ldp x4, x5, [x9, #48]\n\t"
        "ldp x6, x7, [x9, #64]\n\t"
        "mov x8, x11\n\t"
        "blr x10\n\t"
        ::"r"(&s)
        : LG_X8CLOB);
}

// drawImage 的浮点 x/y 走 s0/s1，其余整参走 x0-x3
static inline __attribute__((always_inline)) void x8_draw_image(void *fn, void *canvas, void *img,
                                                                float fx, float fy,
                                                                void *sampling, void *paint) {
    struct {
        void *fn;
        void *canvas;
        void *img;
        unsigned fx, fy;
        void *sampling;
        void *paint;
    } s;
    s.fn = fn;
    s.canvas = canvas;
    s.img = img;
    __builtin_memcpy(&s.fx, &fx, 4);
    __builtin_memcpy(&s.fy, &fy, 4);
    s.sampling = sampling;
    s.paint = paint;
    __asm__ volatile(
        "mov x9, %0\n\t"
        "ldr x10, [x9]\n\t"
        "ldr x0, [x9, #8]\n\t"
        "ldr x1, [x9, #16]\n\t"
        "ldr w11, [x9, #24]\n\t"
        "ldr w12, [x9, #28]\n\t"
        "fmov s0, w11\n\t"
        "fmov s1, w12\n\t"
        "ldr x2, [x9, #32]\n\t"
        "ldr x3, [x9, #40]\n\t"
        "blr x10\n\t"
        ::"r"(&s)
        : LG_X8CLOB);
}
#endif

#endif
