#ifndef LG_XZDEC_H
#define LG_XZDEC_H

#include <stddef.h>
#include <stdint.h>

// 内置 xz 解码（LZMA2 单过滤，无 BCJ）：只依赖 libc，不碰设备 liblzma
// 支持 .gnu_debugdata 的实际形态：单/多 block、LZMA2 + 未压缩 chunk、CRC32/None/CRC64/SHA256 校验位跳过
// 成功返回 0 并写 *out_n；失败返回 -1（输入越界、参数不支持、容量不足等一律安全失败）
int lg_xz_decode(const uint8_t *src, size_t src_n,
                 uint8_t *dst, size_t dst_cap, size_t *out_n);

#endif