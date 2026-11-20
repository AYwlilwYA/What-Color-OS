// 内置 xz 解码的编译配置：单次调用 + 四种 check 全覆盖
// 符号可见性由编译期 -fvisibility=hidden 控制，不在这里改 XZ_EXTERN（跨编译单元引用需外部链接）
#ifndef LG_XZ_CONFIG_H
#define LG_XZ_CONFIG_H

#define XZ_DEC_SINGLE
#define XZ_DEC_ANY_CHECK
#define XZ_USE_CRC64
#define XZ_USE_SHA256

#include "xz_config.h"

#endif
