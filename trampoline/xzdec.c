// 内置 xz 解码：薄封装 vendor/xz-embedded 下的解码器（许可与署名见 THIRD-PARTY-NOTICES.md）
// 单次调用模式，解码期零动态分配；只依赖 libc
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#include "xz.h"
#include "xzdec.h"

#define LG_XZ_DICT_MAX (64u * 1024u * 1024u)

int lg_xz_decode(const uint8_t *src, size_t src_n,
                 uint8_t *dst, size_t dst_cap, size_t *out_n) {
    if (!src || !dst || !out_n) return -1;
    *out_n = 0;
    xz_crc32_init();
    xz_crc64_init();
    struct xz_dec *s = xz_dec_init(XZ_SINGLE, LG_XZ_DICT_MAX);
    if (!s) return -1;
    struct xz_buf b;
    b.in = src;
    b.in_pos = 0;
    b.in_size = src_n;
    b.out = dst;
    b.out_pos = 0;
    b.out_size = dst_cap;
    enum xz_ret r = xz_dec_run(s, &b);
    xz_dec_end(s);
    if (r != XZ_STREAM_END) return -1;
    *out_n = b.out_pos;
    return 0;
}
