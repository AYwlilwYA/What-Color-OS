/* reloc fixture：对未定义符号的调用产生 JUMP_SLOT 重定位，供 lg_engine_lookup_reloc 单测 */
extern void lg_fixture_dep(void);

void fixture_call(void) { lg_fixture_dep(); }
