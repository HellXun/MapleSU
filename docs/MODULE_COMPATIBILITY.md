# 模块兼容判定

MapleSU_Ultra 沿用原 SKRoot(Pro) 原生模块 ABI，现有模块保留 ZIP、入口和 ID。[`UltraModuleCompatibilityRegistry`](../manager/src/main/java/dev/maplesu/manager/modules/compat/UltraModuleCompatibilityRegistry.kt) 保存固定兼容记录，Manager 按包身份、SDK、内核和请求动作在本地完成判定。

## 三种结果

| 结果 | 行为 |
| --- | --- |
| `KNOWN_INCOMPATIBLE` | 在宿主检查、安装或 WebUI SDK 调用前拦截本次请求，保留原因码 |
| `VERIFIED` | 命中记录中的设备组合、SDK 身份与动作，显示该动作的通过结果 |
| `UNVERIFIED` | 当前组合或动作没有匹配记录，返回诊断信息并沿用该入口的处理流程 |

包身份同时比较归档 SHA-256、原生成员 SHA-256 和固定模块 ID。显示版本用于诊断，同一版本号也可能对应不同字节。

## 固定保护记录

`aah-market-1.9.3-20261031` 是现有应用自动隐藏模块的保护记录：

| 字段 | 固定值 |
| --- | --- |
| 模块 ID | `cd08ae9eef7bb885c06f80ef3aea1d5c` |
| 版本 | `1.9.3` |
| 原生成员 | `libmodule_app_auto_hider.so` |
| 归档 SHA-256 | `2fdc08482075a9865d49e2595a2487f07a37ab499aab744d8d9bb09a098d4a31` |
| 原生成员 SHA-256 | `a0835b667db5ed33a12b7fa2d2f8ba9ee58b8f7fd538250b62111cffd2a647dc` |
| SDK 记录范围 | `5.1.0` |
| 内核 release | `6.6.89-android15-8-g7e1f3c083cc6-abogki467167594-4k` |
| 记录变体 | `k22` |
| boot SHA-256 | `47aa147acbe3edf8e58ce60d334ad4c4b26cad26b5cb93aa6acb59a67fdd5f59` |

该记录对应目录回调返回约定、进程回调类型与内核 BTF 的差异，以及 Hook 句柄和卸载回执的处理问题；`verifiedScopes` 为空，匹配结果按固定不兼容范围处理。

`k21` 以同一基础内核变体列入范围，并返回 `KERNEL_VARIANT_OUT_OF_VERIFIED_SCOPE`。其他 release、SDK 或变体继续按各自匹配结果处理。

匹配实现见 [`UltraModuleCompatibility`](../manager/src/main/java/dev/maplesu/manager/modules/compat/UltraModuleCompatibility.kt)，包身份由 [`ModuleArchiveInspector`](../manager/src/main/java/dev/maplesu/manager/ModuleArchiveInspector.java) 和 [`MapleSuModuleMetadata`](../manager/src/main/java/dev/maplesu/manager/MapleSuModuleMetadata.java) 读取。

## 添加记录

1. 固定 ZIP、原生成员、ID 和两份 SHA-256。
2. 填写 SDK 版本、完整内核 release、变体及对应 boot 摘要。
3. 按宿主检查、安装、启动、WebUI、禁用和卸载等动作填写 `verifiedScopes`，问题组合登记到 `incompatibleScope`。
4. 添加匹配及相邻组合的回归测试，更新模块、SDK 或内核时同步核对记录。

Manager 原生包识别回归测试位于 `manager/src/test`，由 `./gradlew :manager:testDebugUnitTest` 运行，覆盖原生描述、SDK 独立标签常量、旧包混合布局与重复入口识别。

开发与投稿入口见 [Ultra 原生模块指南](ULTRA_MODULES.md)和[模块仓库指南](MODULE_REPOSITORY.md)。
