// ELF 符号获取件：mangled 名 → 函数 vaddr
// 来源优先级：.symtab（完整符号表）→ .dynsym（导出表）→ .gnu_debugdata（xz 压缩 mini symtab）
// 解压优先走内置解码器（无外部依赖），失败再试设备 liblzma 两套 API
#include <dlfcn.h>
#include <fcntl.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

#include "elfsym.h"
#include "xzdec.h"

#define SYMTAB_ENT 24
#define SHDR_ENT 64
#define SH_SYMTAB 2u
#define SH_DYNSYM 11u
#define DBG_OUT_CAP (24u * 1024u * 1024u)
#define DBG_IN_CAP (8u * 1024u * 1024u)

static int g_last_source;
static lg_elfsym_diag_fn g_diag;

int lg_elfsym_last_source(void) { return g_last_source; }

void lg_elfsym_set_diag(lg_elfsym_diag_fn fn) { g_diag = fn; }

void lg_elfsym_diag(const char *fmt, ...) {
    if (!g_diag) return;
    char buf[192];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(buf, sizeof(buf), fmt, ap);
    va_end(ap);
    g_diag("%s", buf);
}

struct elf_view {
    const unsigned char *b;
    size_t n;
    uint64_t shoff;
    uint16_t shent, shnum, shstr;
};

static int view_open(const unsigned char *b, size_t n, struct elf_view *v) {
    if (n < 64 || memcmp(b, "\x7f""ELF", 4) != 0 || b[4] != 2 || b[5] != 1) return -1;
    memcpy(&v->shoff, b + 0x28, 8);
    memcpy(&v->shent, b + 0x3a, 2);
    memcpy(&v->shnum, b + 0x3c, 2);
    memcpy(&v->shstr, b + 0x3e, 2);
    if (!v->shoff || !v->shnum || v->shent < SHDR_ENT || v->shstr >= v->shnum) return -1;
    if (v->shoff + (uint64_t)v->shnum * v->shent > n) return -1;
    v->b = b;
    v->n = n;
    return 0;
}

static const unsigned char *shdr(const struct elf_view *v, uint16_t i) {
    return v->b + v->shoff + (uint64_t)i * v->shent;
}

static uint32_t sh_u32(const unsigned char *sh, int off) {
    uint32_t x;
    memcpy(&x, sh + off, 4);
    return x;
}

static uint64_t sh_u64(const unsigned char *sh, int off) {
    uint64_t x;
    memcpy(&x, sh + off, 8);
    return x;
}

// 节名匹配（名字在 shstrtab 内，越界即不匹配）
static int sh_name_is(const struct elf_view *v, const unsigned char *sh, const char *want) {
    const unsigned char *ss = shdr(v, v->shstr);
    uint64_t off = sh_u64(ss, 24), size = sh_u64(ss, 32);
    uint32_t no = sh_u32(sh, 0);
    if (off + size > v->n || no >= size) return 0;
    const char *nm = (const char *)(v->b + off + no);
    size_t cap = (size_t)(size - no);
    size_t l = strnlen(nm, cap);
    return l == strlen(want) && memcmp(nm, want, l) == 0;
}

// 子串命中选优状态：全名精确相等者胜，否则最短名（并列取字典序）
struct pick {
    const char *name;
    size_t len;
    unsigned long va;
    int exact;
    int matches;
};

static void pick_offer(struct pick *p, const char *nm, size_t l, unsigned long va,
                       const char *want, size_t wl) {
    p->matches++;
    int is_exact = (l == wl && memcmp(nm, want, l) == 0);
    if (!p->name) {
        p->name = nm;
        p->len = l;
        p->va = va;
        p->exact = is_exact;
        return;
    }
    if (p->exact && !is_exact) return;
    if (is_exact && !p->exact) {
        p->name = nm;
        p->len = l;
        p->va = va;
        p->exact = 1;
        return;
    }
    if (l < p->len || (l == p->len && strncmp(nm, p->name, l) < 0)) {
        p->name = nm;
        p->len = l;
        p->va = va;
    }
}

// 在一张符号表里子串搜索；只收 STT_FUNC 且 st_value 非零
static int symtab_lookup(const struct elf_view *v, const unsigned char *sh,
                         const char *want, unsigned long *va_out) {
    uint64_t off = sh_u64(sh, 24), size = sh_u64(sh, 32), ent = sh_u64(sh, 56);
    uint32_t link = sh_u32(sh, 40);
    if (ent != SYMTAB_ENT || !size || link >= v->shnum) return -1;
    if (off + size > v->n) return -1;
    const unsigned char *ss = shdr(v, link);
    uint64_t st = sh_u64(ss, 24), stsz = sh_u64(ss, 32);
    if (st + stsz > v->n) return -1;
    size_t n = (size_t)(size / ent);
    size_t wl = strlen(want);
    struct pick p = {0};
    for (size_t k = 0; k < n; k++) {
        const unsigned char *e = v->b + off + k * ent;
        uint32_t no;
        memcpy(&no, e, 4);
        unsigned char info = e[4];
        uint16_t shndx;
        memcpy(&shndx, e + 6, 2);
        uint64_t val;
        memcpy(&val, e + 8, 8);
        if ((info & 0xf) != 2 /* STT_FUNC */ || !val || shndx == 0) continue;
        if (no >= stsz) continue;
        const char *nm = (const char *)(v->b + st + no);
        size_t cap = (size_t)(stsz - no);
        size_t l = strnlen(nm, cap);
        if (l == cap || l < wl) continue;
        // 有界子串搜索（strtab 不保证 NUL 终止在视图内）
        int found = 0;
        for (size_t s = 0; s + wl <= l; s++) {
            if (memcmp(nm + s, want, wl) == 0) {
                found = 1;
                break;
            }
        }
        if (!found) continue;
        pick_offer(&p, nm, l, (unsigned long)val, want, wl);
    }
    if (!p.name) return -1;
    *va_out = p.va;
    return 0;
}

// 按节类型扫一遍符号表（.symtab 优先于 .dynsym，由调用方分两轮）
static int scan_type(const struct elf_view *v, uint32_t type,
                     const char *want, unsigned long *va_out) {
    for (uint16_t i = 0; i < v->shnum; i++) {
        const unsigned char *sh = shdr(v, i);
        if (sh_u32(sh, 4) != type) continue;
        if (symtab_lookup(v, sh, want, va_out) == 0) return 0;
    }
    return -1;
}

// 读 .note.gnu.build-id（name="GNU", type=3）→ 小写 hex
static int buildid_of(const struct elf_view *v, char *out, size_t cap) {
    for (uint16_t i = 0; i < v->shnum; i++) {
        const unsigned char *sh = shdr(v, i);
        if (sh_u32(sh, 4) != 7 /* SHT_NOTE */) continue;
        uint64_t off = sh_u64(sh, 24), size = sh_u64(sh, 32);
        if (off + size > v->n || size < 16) continue;
        uint64_t p = off, end = off + size;
        while (p + 12 <= end) {
            uint32_t nsz, dsz, ntype;
            memcpy(&nsz, v->b + p, 4);
            memcpy(&dsz, v->b + p + 4, 4);
            memcpy(&ntype, v->b + p + 8, 4);
            uint64_t name_off = p + 12;
            uint64_t desc_off = name_off + ((nsz + 3) & ~3u);
            if (desc_off + dsz > end || dsz > 64) break;
            if (ntype == 3 && nsz >= 4 && memcmp(v->b + name_off, "GNU", 3) == 0) {
                if (cap < (size_t)dsz * 2 + 1) return -1;
                static const char hexd[] = "0123456789abcdef";
                for (uint32_t k = 0; k < dsz; k++) {
                    unsigned char c = v->b[desc_off + k];
                    out[k * 2] = hexd[c >> 4];
                    out[k * 2 + 1] = hexd[c & 15];
                }
                out[dsz * 2] = 0;
                return 0;
            }
            p = desc_off + ((dsz + 3) & ~3u);
        }
    }
    return -1;
}

static char g_table_path[256];

int lg_elfsym_set_table(const char *table_path) {
    if (!table_path) {
        g_table_path[0] = 0;
        return 0;
    }
    snprintf(g_table_path, sizeof(g_table_path), "%s", table_path);
    return 0;
}

// 预生成表查询：行格式 "buildid substr va"，buildid 必须与目标 ELF 一致
static int table_lookup(const struct elf_view *v, const char *want, unsigned long *va_out) {
    if (!g_table_path[0]) return -1;
    char bid[129];
    if (buildid_of(v, bid, sizeof(bid)) != 0) return -1;
    FILE *f = fopen(g_table_path, "r");
    if (!f) {
        lg_elfsym_diag("symtab 表打开失败 %s", g_table_path);
        return -1;
    }
    char line[512];
    int rc = -1;
    size_t wl = strlen(want);
    struct pick p = {0};
    static char best_name[256];
    while (fgets(line, sizeof(line), f)) {
        if (line[0] == '#' || line[0] == '\n') continue;
        char t_bid[129], t_name[256];
        unsigned long t_va = 0;
        if (sscanf(line, "%128s %255s %lx", t_bid, t_name, &t_va) != 3) continue;
        if (strcmp(t_bid, bid) != 0 || !t_va) continue;
        size_t l = strlen(t_name);
        if (l < wl) continue;
        if (!strstr(t_name, want)) continue;
        // 表内名字驻留静态缓冲：只保留最优一条
        pick_offer(&p, t_name, l, t_va, want, wl);
        if (p.name == t_name) {
            snprintf(best_name, sizeof(best_name), "%s", t_name);
            p.name = best_name;
        }
    }
    fclose(f);
    if (p.name) {
        *va_out = p.va;
        lg_elfsym_diag("symtab 表命中 bid=%s va=0x%lx matches=%d", bid, p.va, p.matches);
        rc = 0;
    }
    return rc;
}

#ifdef LG_HOST_TEST
static lg_xz_decode_fn g_host_xz;
void lg_elfsym_set_xz_decoder(lg_xz_decode_fn fn) { g_host_xz = fn; }
#endif

// 标准 liblzma：lzma_stream_buffer_decode(uint64_t *memlimit, uint32_t flags,
//                                        void *alloc, const uint8_t *in, size_t *in_pos,
//                                        size_t in_size, uint8_t *out, size_t *out_pos, size_t out_size)
static int xz_standard(const unsigned char *src, size_t sn,
                       unsigned char *dst, size_t cap, size_t *out_n) {
    typedef int (*fn_t)(uint64_t *, uint32_t, void *, const unsigned char *,
                        size_t *, size_t, unsigned char *, size_t *, size_t);
    fn_t fn = (fn_t)dlsym(RTLD_DEFAULT, "lzma_stream_buffer_decode");
    if (!fn) return -1;
    uint64_t memlimit = UINT64_MAX;
    size_t in_pos = 0, out_pos = 0;
    int rc = fn(&memlimit, 0, NULL, src, &in_pos, sn, dst, &out_pos, cap);
    if (rc != 0) return -1;
    *out_n = out_pos;
    return 0;
}

// 设备 liblzma 的私有变体（无标准 lzma_* 导出，只有 XzUnpacker_* 一族）
// XzUnpacker_CodeFull(p, src, &srcLen, dst, &dstLen, finishMode, &status)
//   反汇编实证：内部把 x1→x8、x2  deref→x9 存进 p+904/+912，再尾调 XzUnpacker_Code
// ISzAlloc = { void *(*Alloc)(ISzAllocPtr, size_t); void (*Free)(ISzAllocPtr, void *); }
struct sz_alloc {
    void *(*alloc)(const struct sz_alloc *, size_t);
    void (*free)(const struct sz_alloc *, void *);
};

static void *sz_alloc_impl(const struct sz_alloc *p, size_t n) {
    (void)p;
    return malloc(n ? n : 1);
}

static void sz_free_impl(const struct sz_alloc *p, void *q) {
    (void)p;
    free(q);
}

// CXzUnpacker 实测字段最高偏移 920（CodeFull 写 p+920），给 2KB 冗余
#define XZ_OBJ_SIZE 2048

static int xz_vendor_api(const unsigned char *src, size_t sn,
                   unsigned char *dst, size_t cap, size_t *out_n) {
    typedef void (*ctor_t)(void *, const struct sz_alloc *);
    typedef void (*dtor_t)(void *, const struct sz_alloc *);
    typedef int (*full_t)(void *, const unsigned char *, size_t *, unsigned char *, size_t *,
                          int, int *);
    typedef int (*done_t)(const void *);

    ctor_t ctor = (ctor_t)dlsym(RTLD_DEFAULT, "XzUnpacker_Construct");
    full_t full = (full_t)dlsym(RTLD_DEFAULT, "XzUnpacker_CodeFull");
    done_t done = (done_t)dlsym(RTLD_DEFAULT, "XzUnpacker_IsStreamWasFinished");
    dtor_t dtor = (dtor_t)dlsym(RTLD_DEFAULT, "XzUnpacker_Free");
    if (!ctor || !full || !done || !dtor) return -1;

    struct sz_alloc al = {sz_alloc_impl, sz_free_impl};
    unsigned char *obj = (unsigned char *)calloc(1, XZ_OBJ_SIZE);
    if (!obj) return -1;
    ctor(obj, &al);
    size_t in_size = sn, out_size = cap;
    int status = 0;
    // 参数序反汇编实证（CodeFull 把 x6→x7、x5→w6、x1→x8、x1置零、w5=1 后尾调 Code）：
    //   CodeFull(p, src, &srcLen, dst, &dstLen, finishMode, &status)
    //   Code    (p, src, &srcLen, dst, &dstLen, srcFinished, finishMode, &status)
    // Code 入口把 *srcLen/*dstLen 清零 → 回填「本次处理量」，故 produced 直接取 out_size
    int rc = full(obj, src, &in_size, dst, &out_size, 0, &status);
    size_t produced = out_size;
    int finished = done(obj);
    dtor(obj, &al);
    free(obj);
    if (rc != 0 || !finished || produced < 64 || produced > cap) {
        lg_elfsym_diag("7z rc=%d st=%d fin=%d out=%zu in=%zu", rc, status, finished, produced, in_size);
        return -1;
    }
    *out_n = produced;
    return 0;
}

static int xz_decode(const unsigned char *src, size_t sn,
                     unsigned char *dst, size_t cap, size_t *out_n) {
#ifdef LG_HOST_TEST
    if (g_host_xz) return g_host_xz(src, sn, dst, cap, out_n);
#endif
    if (sn < 6 || memcmp(src, "\xfd""7zXZ\x00", 6) != 0) return -1;
    if (lg_xz_decode(src, sn, dst, cap, out_n) == 0) return 0;
    if (xz_standard(src, sn, dst, cap, out_n) == 0) return 0;
    return xz_vendor_api(src, sn, dst, cap, out_n);
}

// 解压能力自检日志：区分「无解码 API」与「有 API 但解码失败」
static void xz_diag_once(void) {
    static int done;
    if (done) return;
    done = 1;
    int std_api = dlsym(RTLD_DEFAULT, "lzma_stream_buffer_decode") != NULL;
    int z7_api = dlsym(RTLD_DEFAULT, "XzUnpacker_Code") != NULL;
    lg_elfsym_diag("xz-api builtin=1 std=%d 7z=%d", std_api, z7_api);
}

// .gnu_debugdata：xz 压缩的独立 ELF（只含 .symtab/.strtab/.shstrtab）
static int debugdata_lookup(const struct elf_view *v, const char *want, unsigned long *va_out) {
    const unsigned char *sec = NULL;
    for (uint16_t i = 0; i < v->shnum; i++) {
        const unsigned char *sh = shdr(v, i);
        if (sh_name_is(v, sh, ".gnu_debugdata")) {
            sec = sh;
            break;
        }
    }
    if (!sec) {
        lg_elfsym_diag("dbgdata 节缺失");
        return -1;
    }
    uint64_t off = sh_u64(sec, 24), size = sh_u64(sec, 32);
    if (!size || size > DBG_IN_CAP || off + size > v->n) {
        lg_elfsym_diag("dbgdata 尺寸异常 size=%llu", (unsigned long long)size);
        return -1;
    }
    xz_diag_once();
    unsigned char *out = (unsigned char *)malloc(DBG_OUT_CAP);
    if (!out) {
        lg_elfsym_diag("dbgdata 输出缓冲分配失败");
        return -1;
    }
    size_t produced = 0;
    int rc = -1;
    if (xz_decode(v->b + off, (size_t)size, out, DBG_OUT_CAP, &produced) != 0) {
        lg_elfsym_diag("dbgdata 解压失败 in=%llu", (unsigned long long)size);
    } else if (produced < 64) {
        lg_elfsym_diag("dbgdata 解压产物过小 out=%zu", produced);
    } else {
        struct elf_view dv;
        if (view_open(out, produced, &dv) != 0)
            lg_elfsym_diag("dbgdata 产物非 ELF out=%zu", produced);
        else {
            rc = scan_type(&dv, SH_SYMTAB, want, va_out);
            if (rc != 0) lg_elfsym_diag("dbgdata 内未命中符号 out=%zu", produced);
        }
    }
    free(out);
    return rc;
}

int lg_elfsym_find(const char *path, const char *mangled, unsigned long *va_out) {
    g_last_source = 0;
    if (!path || !mangled || !va_out) return -1;
    int fd = open(path, O_RDONLY);
    if (fd < 0) return -1;
    struct stat st;
    if (fstat(fd, &st) != 0 || st.st_size < 64 || st.st_size > 512L * 1024 * 1024) {
        close(fd);
        return -1;
    }
    size_t n = (size_t)st.st_size;
    unsigned char *b = (unsigned char *)mmap(NULL, n, PROT_READ, MAP_PRIVATE, fd, 0);
    close(fd);
    if (b == MAP_FAILED) return -1;
    struct elf_view v;
    int rc = -1;
    if (view_open(b, n, &v) == 0) {
        if (table_lookup(&v, mangled, va_out) == 0) {
            g_last_source = 4;
            rc = 0;
        } else if (scan_type(&v, SH_SYMTAB, mangled, va_out) == 0) {
            g_last_source = 1;
            rc = 0;
        } else if (scan_type(&v, SH_DYNSYM, mangled, va_out) == 0) {
            g_last_source = 2;
            rc = 0;
        } else if (debugdata_lookup(&v, mangled, va_out) == 0) {
            g_last_source = 3;
            rc = 0;
        }
    }
    munmap(b, n);
    return rc;
}

int lg_elfsym_find_many(const char *path, const char *const *names,
                        unsigned long *vas, int max) {
    int hit = 0;
    for (int i = 0; i < max; i++) {
        vas[i] = 0;
        if (lg_elfsym_find(path, names[i], &vas[i]) == 0)
            hit++;
        else
            vas[i] = 0;
    }
    return hit;
}