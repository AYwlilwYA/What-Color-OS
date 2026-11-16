// 单测：装载层转发准入 + 引擎门禁金丝雀 + 护栏熔断 + 日志节流
#include <dlfcn.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <string>

#include "../trampoline.cpp"

static int g_fail;
static int g_pass;

#define CHECK(cond, name)                                        \
    do {                                                         \
        if (cond) {                                              \
            printf("PASS %s\n", name);                           \
            g_pass++;                                            \
        } else {                                                 \
            printf("FAIL %s\n", name);                           \
            g_fail++;                                            \
        }                                                        \
    } while (0)

static std::string g_out;
static std::string g_tramp_so;
static std::string g_real_so;

static void reset_all(void) {
    g_arm.store(0);
    g_guard_err.store(0);
    g_guard_trip.store(0);
    g_test_sym_ok = 1;
    g_eng_syms_ok = 1;
    lg_cfg_reset_defaults();
    memset(g_thr, 0, sizeof(g_thr));
    g_test_log[0] = 0;
    unsetenv("LG_TEST_CANARY");
    unsetenv("LG_TEST_CONF");
}

static void test_ctor_non_sf_noop(void) {
    reset_all();
    lg_ctor();
    CHECK(strstr(g_test_log, "ALIVE") == NULL, "ctor-non-sf-no-log");
}

static void test_on_load_logs(void) {
    reset_all();
    lg_on_load();
    CHECK(strstr(g_test_log, "ALIVE ver=") != NULL, "onload-alive-log");
    CHECK(strstr(g_test_log, "REAL handle=") != NULL, "onload-real-log");
}

static void test_arm_pass(void) {
    reset_all();
    CHECK(lg_glass_arm_engine() == 1, "arm-pass");
    CHECK(lg_glass_arm_engine() == 1, "arm-idempotent");
    CHECK(lg_glass_engine_alive() == 1, "alive-after-arm");
    CHECK(strstr(g_test_log, "ARMED ver=") != NULL, "arm-log");
}

static void test_arm_canary_fail_permanent(void) {
    reset_all();
    setenv("LG_TEST_CANARY", "fail", 1);
    CHECK(lg_glass_arm_engine() == 0, "canary-fail-deny");
    unsetenv("LG_TEST_CANARY");
    CHECK(lg_glass_arm_engine() == 0, "canary-fail-permanent");
    CHECK(strstr(g_test_log, "CANARY fail") != NULL, "canary-fail-log");
}

static void test_arm_sym_miss_permanent(void) {
    reset_all();
    g_test_sym_ok = 0;
    CHECK(lg_glass_arm_engine() == 0, "sym-miss-deny");
    g_test_sym_ok = 1;
    CHECK(lg_glass_arm_engine() == 0, "sym-miss-permanent");
    CHECK(strstr(g_test_log, "sym-miss") != NULL, "sym-miss-log");
}

static void test_guard_trip(void) {
    reset_all();
    CHECK(lg_glass_arm_engine() == 1, "guard-arm-first");
    for (int i = 0; i < LG_ERR_TRIP; i++) lg_guard_note_error();
    CHECK(g_guard_trip.load() == 1, "guard-trip-set");
    CHECK(lg_glass_engine_alive() == 0, "guard-alive-off");
    CHECK(strstr(g_test_log, "TRIP reason=err-threshold") != NULL, "guard-trip-log");
    lg_guard_note_error();
    CHECK(strstr(g_test_log, "engine-error n=") != NULL, "guard-error-throttled-log");
    CHECK(lg_glass_arm_engine() == 0, "arm-after-trip-denied");
}

static void test_log_throttle(void) {
    reset_all();
    for (int i = 0; i < 50; i++)
        lg_log_throttled("tkey", "%s ENGINE-ERR-MARK n=%d", TAG, i);
    int hits = 0;
    for (const char *p = strstr(g_test_log, "ENGINE-ERR-MARK"); p;
         p = strstr(p + 1, "ENGINE-ERR-MARK"))
        hits++;
    CHECK(hits == 1, "throttle-single-key-once");
    lg_log_throttled("tkey2", "%s ENGINE-ERR-MARK-2", TAG);
    CHECK(strstr(g_test_log, "ENGINE-ERR-MARK-2") != NULL, "throttle-other-key-logged");
}

static void test_forwarding(void) {
    if (g_tramp_so.empty() || g_real_so.empty()) {
        printf("FAIL forwarding-env\n");
        g_fail++;
        return;
    }
    void *h = dlopen(g_tramp_so.c_str(), RTLD_NOW);
    CHECK(h != NULL, "forward-trampoline-dlopen");
    if (!h) return;
    void *p = dlsym(h, "lg_real_probe");
    CHECK(p != NULL, "forward-dlsym-through-handle");
    CHECK(dlsym(h, "lg_on_load") == NULL, "forward-internal-hidden");
    void *r = dlopen(g_real_so.c_str(), RTLD_NOLOAD | RTLD_LAZY);
    CHECK(r != NULL, "forward-real-loaded-by-needed");
    if (r && p) {
        void *q = dlsym(r, "lg_real_probe");
        CHECK(q == p, "forward-address-equal");
        int (*fn)(void) = (int (*)(void))p;
        CHECK(fn() == 42, "forward-call-result");
    }
    if (r) dlclose(r);
    dlclose(h);

    std::string bak = g_real_so + ".bak";
    rename(g_real_so.c_str(), bak.c_str());
    dlerror();
    void *h2 = dlopen(g_tramp_so.c_str(), RTLD_NOW);
    const char *err = dlerror();
    CHECK(h2 == NULL && err != NULL, "forward-missing-real-clean-fail");
    if (h2) dlclose(h2);
    rename(bak.c_str(), g_real_so.c_str());
}

static void test_arm_engine_sym_miss(void) {
    reset_all();
    g_eng_syms_ok = 0;
    CHECK(lg_glass_arm_engine() == 0, "engine-sym-miss-deny");
    g_eng_syms_ok = 1;
    CHECK(lg_glass_arm_engine() == 0, "engine-sym-miss-permanent");
    CHECK(strstr(g_test_log, "engine-sym-miss") != NULL, "engine-sym-miss-log");
}

static void test_guard_trip_now(void) {
    reset_all();
    lg_guard_trip_now("manual-test");
    CHECK(g_guard_trip.load() == 1, "trip-now-set");
    CHECK(lg_glass_engine_alive() == 0, "trip-now-alive-off");
    CHECK(strstr(g_test_log, "TRIP reason=manual-test") != NULL, "trip-now-log");
    CHECK(lg_glass_arm_engine() == 0, "trip-now-arm-denied");
    lg_guard_trip_now("manual-test-2");
    CHECK(strstr(g_test_log, "TRIP reason=manual-test-2") == NULL, "trip-now-idempotent");
    CHECK(lg_guard_err_count() == 0, "err-count-zero");
    lg_guard_note_error();
    CHECK(lg_guard_err_count() == 1, "err-count-increment");
}

static void test_arm_untried(void) {
    reset_all();
    CHECK(lg_glass_arm_untried() == 1, "untried-initial");
    lg_glass_arm_engine();
    CHECK(lg_glass_arm_untried() == 0, "untried-after-arm");
}

static void test_reloc_lookup(void) {
    const char *fx = getenv("LG_TEST_FIXTURE_SO");
    if (!fx) {
        printf("FAIL reloc-env\n");
        g_fail++;
        return;
    }
    unsigned long off = 0;
    unsigned type = 0;
    int rc = lg_engine_lookup_reloc(fx, "lg_fixture_dep", &off, &type);
    CHECK(rc == 0 && off > 0, "reloc-found");
    CHECK(type == 7, "reloc-jump-slot-type");
    off = 0;
    type = 0;
    rc = lg_engine_lookup_reloc(fx, "no_such_symbol_xyz", &off, &type);
    CHECK(rc != 0, "reloc-missing-symbol");
    rc = lg_engine_lookup_reloc("/nonexistent/path.so", "lg_fixture_dep", &off, &type);
    CHECK(rc != 0, "reloc-missing-file");
}

static void test_maps_reext(void) {
    char maps_path[512];
    snprintf(maps_path, sizeof(maps_path), "%s/fake_maps.txt", g_out.c_str());
    FILE *f = fopen(maps_path, "w");
    if (!f) {
        printf("FAIL maps-env\n");
        g_fail++;
        return;
    }
    fputs("7a1c00000000-7a1c01000000 r-xp 00000000 00:00 1 /system/lib64/librenderengineextimpl.so\n", f);
    fputs("7a1c01000000-7a1c02000000 r--p 00100000 00:00 1 /system/lib64/librenderengineextimpl.so\n", f);
    fputs("7a2d00000000-7a2d01000000 r-xp 00000000 00:00 2 /system/lib64/libother.so\n", f);
    fclose(f);
    unsigned long bias = 0;
    char path[256] = {0};
    int rc = lg_engine_reext_from_maps(maps_path, &bias, path, sizeof(path));
    CHECK(rc == 0, "maps-found");
    CHECK(bias == 0x7a1c00000000UL, "maps-bias");
    CHECK(strstr(path, "librenderengineextimpl.so") != NULL, "maps-path");

    f = fopen(maps_path, "w");
    fputs("7a1c00000000-7a1c01000000 r-xp 00100000 00:00 1 /system/lib64/librenderengineextimpl.so\n", f);
    fclose(f);
    rc = lg_engine_reext_from_maps(maps_path, &bias, path, sizeof(path));
    CHECK(rc != 0, "maps-reject-nonzero-off");

    rc = lg_engine_reext_from_maps("/nonexistent/maps", &bias, path, sizeof(path));
    CHECK(rc != 0, "maps-missing-file");
    remove(maps_path);
}

static void test_cfg_reload(void) {
    reset_all();
    char conf[512];
    snprintf(conf, sizeof(conf), "%s/glass.conf", g_out.c_str());
    setenv("LG_TEST_CONF", conf, 1);
    lg_cfg_reset_defaults();
    CHECK(g_cfg.file_on == 0, "cfg-default-off");

    FILE *f = fopen(conf, "w");
    fprintf(f, "on=1 bx=10 by=20 bw=300 bh=400 br=50 timeout_ms=200 overtrip=5"
               " rf=999 ds=0.5 hg=1.0 ag=90 vb=2 ib=8\n");
    fclose(f);
    cfg_reload();
    CHECK(g_cfg.file_on == 1, "cfg-on");
    CHECK(g_cfg.bx == 10 && g_cfg.bw == 300 && g_cfg.br == 50, "cfg-geometry");
    CHECK(g_cfg.timeout_ms == 200 && g_cfg.overtrip == 5, "cfg-timeout");
    CHECK(g_cfg.rf == 200.0f, "cfg-clamp-rf");
    CHECK(g_cfg.ds == 0.5f && g_cfg.hg == 1.0f, "cfg-params");
    CHECK(strstr(g_test_log, "PARAM") != NULL, "cfg-param-log");

    f = fopen(conf, "w");
    fprintf(f, "on=1 bx=999 by=999 bw=999 bh=999 br=99 timeout_ms=1 overtrip=1"
               " rf=1 ds=0 hg=0 ag=0 vb=0 ib=0\n");
    fclose(f);
    cfg_reload();
    CHECK(g_cfg.bx == 10, "cfg-ttl-skip");

    lg_cfg_reset_defaults();
    cfg_reload();
    CHECK(g_cfg.bx == 999, "cfg-reread-after-reset");
    unsetenv("LG_TEST_CONF");
    lg_cfg_reset_defaults();
    remove(conf);
}

static void test_read_cpu(void) {
    long v = read_proc_self_cpu();
    CHECK(v >= 0, "proc-self-cpu-parse");
    for (int i = 0; i < 50 && v == 0; i++) {
        for (volatile int j = 0; j < 2000000; j++) {
        }
        v = read_proc_self_cpu();
    }
    CHECK(v > 0, "proc-self-cpu-ticks-accumulate");
}

int main(int argc, char **argv) {
    if (argc > 1 && strcmp(argv[1], "--selfcheck") == 0) {
        printf("CONTROL selfcheck: expecting deliberate failure\n");
        CHECK(1 == 0, "selfcheck-deliberate-fail");
        printf("RESULT: %s pass=%d fail=%d\n", g_fail ? "FAIL" : "PASS", g_pass, g_fail);
        return g_fail ? 1 : 0;
    }
    const char *out = getenv("LG_TEST_OUT");
    if (!out) {
        printf("FAIL env-LG_TEST_OUT\n");
        return 2;
    }
    g_out = out;
    const char *tso = getenv("LG_TEST_TRAMP_SO");
    const char *rso = getenv("LG_TEST_REAL_SO");
    if (tso) g_tramp_so = tso;
    if (rso) g_real_so = rso;

    printf("CONTROL env: out=%s tramp=%s real=%s\n", out, tso ? tso : "-", rso ? rso : "-");

    test_ctor_non_sf_noop();
    test_on_load_logs();
    test_arm_pass();
    test_arm_canary_fail_permanent();
    test_arm_sym_miss_permanent();
    test_arm_engine_sym_miss();
    test_guard_trip();
    test_guard_trip_now();
    test_arm_untried();
    test_log_throttle();
    test_forwarding();
    test_reloc_lookup();
    test_maps_reext();
    test_cfg_reload();
    test_read_cpu();

    printf("RESULT: %s pass=%d fail=%d\n", g_fail ? "FAIL" : "PASS", g_pass, g_fail);
    return g_fail ? 1 : 0;
}
