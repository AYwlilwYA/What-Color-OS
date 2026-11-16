// 玻璃 SkSL 模板载体：C 不支持 raw string，本 TU 单独按 C++ 编译
#include "shader_tpl.h"

extern "C" const char *lg_glass_tpl(void) { return kGlassTpl; }
