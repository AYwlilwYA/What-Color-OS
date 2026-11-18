#ifndef LG_ELFSYM_H
#define LG_ELFSYM_H

#include <stddef.h>

// ELF 符号获取件：按 mangled 名查函数虚拟地址
// 三级来源：.symtab 直读 → .gnu_debugdata（xz 压缩 mini symtab）→ 调用方兜底
// 纯只读、无全局状态；解压走设备 liblzma（标准 lzma_* 或 7-Zip XzUnpacker_* 两套 API）

// 查一个符号的 st_value（PIE 内即链接期 vaddr）。命中返回 0，未命中返回 -1。
int lg_elfsym_find(const char *path, const char *mangled, unsigned long *va_out);

// 批量查：names[i] → vas[i]，命中数返回；vas 未命中项置 0。max 为数组容量。
int lg_elfsym_find_many(const char *path, const char *const *names,
                        unsigned long *vas, int max);

// 最近一次查询的来源标记（诊断用）：0=未查 1=symtab 2=dynsym 3=debugdata
int lg_elfsym_last_source(void);

// 诊断输出钩子：由引擎注入日志函数；未注入时静默
typedef void (*lg_elfsym_diag_fn)(const char *fmt, ...);
void lg_elfsym_set_diag(lg_elfsym_diag_fn fn);
void lg_elfsym_diag(const char *fmt, ...);

#ifdef LG_HOST_TEST
// 单测注入：宿主侧用自带解压实现替换设备 liblzma 路径
typedef int (*lg_xz_decode_fn)(const unsigned char *src, size_t src_n,
                               unsigned char *dst, size_t dst_cap, size_t *out_n);
void lg_elfsym_set_xz_decoder(lg_xz_decode_fn fn);
#endif

#endif