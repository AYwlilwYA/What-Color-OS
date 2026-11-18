// ELF 符号获取件：mangled 名 → 函数 vaddr
// 来源优先级：.symtab（完整符号表）→ .dynsym（导出表）→ .gnu_debugdata（xz 压缩 mini symtab）
// 解压走设备 liblzma：标准 lzma_stream_buffer_decode 或 7-Zip 变体 XzUnpacker_* 两套 API 任一
#include <dlfcn.h>
#include <fcntl.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

#include "elfsym.h"

#define SYMTAB_ENT 24
#define SHDR_ENT 64
#define SH_SYMTAB 2u
#define SH_DYNSYM 11u
#define DBG_OUT_CAP (24u * 1024u * 1024u)
#define DBG_IN_CAP (8u * 1024u * 1024u)

static int g_last_source;

int lg_elfsym_last_source(void) { return g_last_source; }

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

// 在一张符号表里找 mangled 名；只收 STT_FUNC 且 st_value 非零
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
        if (l != wl || memcmp(nm, want, l) != 0) continue;
        *va_out = (unsigned long)val;
        return 0;
    }
    return -1;
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

// 7-Zip 变体：XzUnpacker_Construct(void *p, ISzAllocPtr) / _Code(...) / _IsStreamWasFinished / _Free
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

static int xz_7zip(const unsigned char *src, size_t sn,
                   unsigned char *dst, size_t cap, size_t *out_n) {
    typedef void (*ctor_t)(void *, const struct sz_alloc *);
    typedef void (*dtor_t)(void *, const struct sz_alloc *);
    // Code(void *p, const Byte *src, SizeT *srcLen, Byte *dest, SizeT *destLen,
    //      ECoderFinishMode finishMode, ECoderStatus *status) —— 另有多一个 srcFinished 参数的变体
    typedef int (*code8_t)(void *, const unsigned char *, size_t *, unsigned char *, size_t *,
                           int, int *, int *);
    typedef int (*code7_t)(void *, const unsigned char *, size_t *, unsigned char *, size_t *,
                           int, int *);
    typedef int (*done_t)(const void *);

    ctor_t ctor = (ctor_t)dlsym(RTLD_DEFAULT, "XzUnpacker_Construct");
    code8_t code8 = (code8_t)dlsym(RTLD_DEFAULT, "XzUnpacker_Code");
    done_t done = (done_t)dlsym(RTLD_DEFAULT, "XzUnpacker_IsStreamWasFinished");
    dtor_t dtor = (dtor_t)dlsym(RTLD_DEFAULT, "XzUnpacker_Free");
    if (!ctor || !code8 || !done || !dtor) return -1;

    struct sz_alloc al = {sz_alloc_impl, sz_free_impl};
    // CXzUnpacker 实测 sizeof 约 0x140；给足冗余并清零，越界风险由富余空间吸收
    unsigned char obj[1024];
    memset(obj, 0, sizeof(obj));
    ctor(obj, &al);
    size_t in_left = sn, out_left = cap;
    int status = 0, src_finished = 0;
    // 先按 8 参变体调（多传一个 &srcFinished）；ABI 上多余实参被忽略，7 参变体同样安全
    code8_t c8 = code8;
    int rc = c8(obj, src, &in_left, dst, &out_left, 1 /* CODER_FINISH_END */, &status, &src_finished);
    size_t produced = cap - out_left;
    if (rc != 0 /* SZ_OK */ || !done(obj)) {
        // 回退 7 参形态重试一次（对象已被消耗，重新构造）
        dtor(obj, &al);
        memset(obj, 0, sizeof(obj));
        ctor(obj, &al);
        in_left = sn;
        out_left = cap;
        status = 0;
        code7_t c7 = (code7_t)code8;
        rc = c7(obj, src, &in_left, dst, &out_left, 1, &status);
        produced = cap - out_left;
        int ok = (rc == 0 && done(obj));
        dtor(obj, &al);
        if (!ok) return -1;
        *out_n = produced;
        return 0;
    }
    dtor(obj, &al);
    *out_n = produced;
    return 0;
}

static int xz_decode(const unsigned char *src, size_t sn,
                     unsigned char *dst, size_t cap, size_t *out_n) {
#ifdef LG_HOST_TEST
    if (g_host_xz) return g_host_xz(src, sn, dst, cap, out_n);
#endif
    if (sn < 6 || memcmp(src, "\xfd""7zXZ\x00", 6) != 0) return -1;
    if (xz_standard(src, sn, dst, cap, out_n) == 0) return 0;
    return xz_7zip(src, sn, dst, cap, out_n);
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
    if (!sec) return -1;
    uint64_t off = sh_u64(sec, 24), size = sh_u64(sec, 32);
    if (!size || size > DBG_IN_CAP || off + size > v->n) return -1;
    unsigned char *out = (unsigned char *)malloc(DBG_OUT_CAP);
    if (!out) return -1;
    size_t produced = 0;
    int rc = -1;
    if (xz_decode(v->b + off, (size_t)size, out, DBG_OUT_CAP, &produced) == 0 && produced >= 64) {
        struct elf_view dv;
        if (view_open(out, produced, &dv) == 0)
            rc = scan_type(&dv, SH_SYMTAB, want, va_out);
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
        if (scan_type(&v, SH_SYMTAB, mangled, va_out) == 0) {
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