# Product

<!-- impeccable:product-schema 1 -->

## Platform

android

## Users

ColorOS 16 的 root 玩家 / 手机美化爱好者：已装 Drip 框架与本 Xposed 模块，在深夜动手折腾，
想把通知中心、控制中心、桌面元素调成 iOS 26 风格的液态玻璃，并逐项微调参数。
（推断自仓库与模块形态，未经用户访谈确认）

## Product Purpose

为 ColorOS 16 的通知中心 / 控制中心等系统 UI 提供 iOS 26 风格液态玻璃（Liquid Glass）效果。
设置页是模块的唯一配置入口：总开关、材质参数、各元素玻璃开关、动画与倾斜调参、
预设管理、日志诊断、配置导入导出。成功 = 用户能在几分钟内完成「装上 → 开总开关 →
调出自己想要的玻璃质感」，且每个效果都有独立开关可单独开关排障。

## Positioning

**产品伞定位 = ColorOS 美化模块（What Color OS）**：不止液态玻璃——液态玻璃是当前核心
引擎，在途功能线有 alive 息屏、流体云（星流），后续持续扩展；设置页信息架构按功能
分组、可扩展（分组方案见 doc/spec/159-设置页功能分组方案.md）。

技术引擎：改造 ColorOS 自带模糊管线（com.oplus.posteffect）：从系统管线拦截**未模糊原图**，
用自研 AGSL 液态玻璃 RuntimeShader 完整渲染（模糊 + SDF 圆角折射 + 色散 + 高光），
替代系统「纯模糊 + 混合」——真折射，不是贴图假玻璃。

## Operating Context

- 设置页 = Android 原生 Activity（设计目标架构已定稿：**底栏三栏 主页/功能/设置**，
  功能顶部全局搜索、六大类 玻璃/圆角/面板效果/桌面/流体云/息屏，玻璃内部含绘制总控·
  预设·配置管理·类名分配·玻璃化对象·可读性·背景与采样 —— 全文见
  `doc/spec/159` §九 与 requirements 总账 2026-11-13 定稿条目；**现行代码仍为 14 个一级
  分类卡片**，Demo 重构先行、真实设置页施工须用户亲眼确认后才动）。
- 用户通过「设置页 → 调参 → 真机看通知中心效果」的循环工作；参数页带效果预览
  （LiquidGlassPreviewView，spec/89）。
- 调试链路：imgui root 球（日志与诊断页启停）、节流诊断日志、配置 JSON 导出/导入。

## Capabilities and Constraints

- 14 个分类的功能与文案全部保留（重设计只换视觉世界，不减功能、不改事实）。
- 每个玻璃元素必须有独立开关（用户铁律）；总开关默认关闭。
- 用户可见文案禁止内部黑话（spec 编号 / 方案 B 之类一律不进 UI）。
- 必须遵守 Android 平台规范：系统返回手势、edge-to-edge inset、48dp 触控目标、
  Material 组件语法（开关/滑杆/对话框不用 iOS 造型）。
- 服务 binder 死亡提示、启动升级检查、预设管理对话框等既有状态必须有落点。
- 平台事实：目标设备 ColorOS 16（Android 16 系），真机验收唯一目标 = 无线主力机。
- 交付中的 HTML Demo 为设计稿（手机画幅静态演示），不是产品本体；产品本体仍是
  Kotlin/Views + Material 3 主题。

## Brand Commitments

- App 名「What Color OS」（仓库 AYwlilwYA/What-Color-OS）。
- 产品身份 = 液态玻璃（iOS 26 Liquid Glass 风格移植到 ColorOS），这是唯一被反复
  强调的品牌资产；算法参考 Kyant0/AndroidLiquidGlass（Apache-2.0，署名须保留）。
- 文案语气：说人话、面向普通玩机用户，不堆砌术语。

## Evidence on Hand

- 现行设置页源码：`app/src/main/kotlin/com/coloros16/liquidglass/ui/SettingsActivity.kt`
  （2627 行）与 `app/src/main/res/layout/` 下 15 个 view_cat_*.xml、item_category.xml。
- 设计规格与决策：`doc/spec/`（需求总账 requirements.md、spec/89 参数页效果预览等）。
- 项目文档：`SESSION_SNAPSHOT.md`、`doc/PROJECT_MAP.md`、`doc/QUEUE.md`。
- 缺：无用户访谈、无品牌色板、无既有 DESIGN.md（视觉世界 = 库默认 Material 3）。

## Product Principles

1. 每个效果都可独立开关——可排障优先于好看。
2. 调参即时可见（预览 + 真机秒生效），不盲调。
3. 文案说人话，专业术语只留在开发者诊断页。
4. 视觉重设计只换世界，产品事实、功能、结构一字不动。

## Open Decisions（推断标注）

- 以上 Users / Product Principles 均为**从仓库证据推断**（R-034 禁阻塞式提问，
  未做访谈）；如与用户意图不符，以用户后续口述为准修订。
- 视觉世界方向由 concept-seed 掷定，待交付件披露。
