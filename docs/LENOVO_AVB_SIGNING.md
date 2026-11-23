# 联想 AVB 镜像签名接入

## 路由和使用方式

签名类型以最终目标分区为准，与所选安装目标独立：

| 目标分区 | 签名方式 | 选文件时的完整输入 | 输出 |
| --- | --- | --- | --- |
| `boot`，旧版 | 链式 | `boot` | 已修补并签名的 `boot` |
| `boot`，勾选新版 AOSP | AOSP 链式 | `boot`、`recovery`、`vbmeta_system`、`vbmeta` | 四个签名镜像 |
| `init_boot` | 非链式 | `init_boot`、`vbmeta` | 已修补的 `init_boot` 及配套 `vbmeta` |

`init_boot` 即使保留 AOSP 复选框状态，也始终使用非链式；切回 `boot` 后复用该偏好。
直接安装和迁移自动读取当前槽位的配套分区，不要求用户选文件。旧版链式直装还读取主
`vbmeta`，以验证父子公钥，并更新迁移时恢复分区的 hash / chain 描述符。

高级选项复用安装页的 Material / Miuix 主题。展开、收起和签名配置子项都有尺寸、透明度动画。
手动开启需要弹窗确认，AOSP 复选框仅在确认后应用。首页共享探针已返回的 fingerprint
若以 `LENOVO` 开头，会自动展开和开启一次；不启动额外探针，不等待指纹，不用品牌检测
作为安装门槛，手动关闭后也不被自动打开。

## 上游分析和来源

- 仓库：<https://github.com/Smart-Paocai/VioletToolBox>
- 分析固定版本：`153a0d13a0ae855c3e682bf281940e691ef22f21`，2026-10-23。
- 签名主体：`VioletToolBox/Avb/NativeAvbRebuilder.cs`。
- UI 调用：`MainWindow.xaml.cs` 的 `RunAvbRebuildToolAsync`，复制输入到独立目录后调用
  `Rebuild` 或 `RebuildAospChain`。实际 UI 固定 `useOriginalSalt=true`、`keySelection=0`
  （按原算法选密钥）。MapleSU 对应这两个实际默认参数。
- 脱机修补入口在 `MainWindow.xaml`，管理器修补与 AVB 重构分离；本次保留 MapleSU 原有
  Ultra 修补管线，只在所有修补完成后增加 AVB 最终化，不运行上游 Windows 工具。
- 上游 README 的许可证声明为 GPL-3.0 或更高。移植类保留 SPDX 和来源标记；根目录
  `LICENSE` 为 GPL-3.0 全文，APK 资产包含 `maplesu/avb/NOTICE`。

参考依赖归档 SHA-256：
`ce62cf80c170af33cae214681e77258d55033e1817da7cc46b4a77800271bde5`
（43,743,457 bytes）。该摘要标识分析输入，不作为公开构建依赖。

两把 PEM 的确切字节哈希见 `NOTICE`。RSA 公钥已与 AOSP
`platform/external/avb/test/data/testkey_rsa2048.pem` 和 `testkey_rsa4096.pem` 比较一致。
4096 位 PEM 的编码/换行与另一次官方下载存在字节差异，公钥身份相同。
这些是公开开发密钥；设备是否接受该密钥由其 Bootloader 信任配置决定，品牌指纹不证明兼容性。

独立互操作验证使用未修改的 AOSP `avbtool.py`，文件 SHA-256：
`e5a664a38db623da00f080219bc0ee60a640a9dc4a872803616fae4938ac749b`。
它只用于主机测试，不打入 APK；文件自带 AOSP 版权和 MIT 许可证。

## 参数与完整实现

纯 Java `LenovoAvbSigner` 在 Android 进程内执行，不依赖 Python、OpenSSL 或网络。
按流读取大镜像；AVB 元数据按边界解析，输入保持原样，输出在私有目录重建。

- **旧版链式**：按原算法选择 RSA2048 / RSA4096，支持 SHA256 / SHA512；保留 hash 算法、
  salt、hash flags、build properties、rollback index、header flags、required minor。
  footer 内 vbmeta 的 rollback location 为 0，与上游旧版实现一致。
- **非链式**：对 `NONE` 目标重建 hash footer；把目标描述符合并进原主 `vbmeta`，按原主算法
  重签，合并 required minor，保留 rollback / flags，location=0，按 4096 字节补齐。
  若源目标原本自带 RSA 签名，按其原算法重建并维护主 `vbmeta` 对它的引用。
- **新版 AOSP**：`boot` / `recovery` 重签为 SHA256_RSA4096，随机 32 字节 salt，保留 hash
  flags 和三个 build properties（版本、指纹、安全补丁）；缺失值使用上游相同默认值。
  `vbmeta_system` 原始描述符完整保留；主 `vbmeta` 重建 boot/recovery/vbmeta_system 三条链，
  保留各链 rollback location / flags、其他描述符以及原 header rollback / flags / minor / location。
  签名前检查必需输入和三条链存在；签名后验证四个输出的 RSA、vbmeta digest、可用
  payload hash 与父子公钥。未提取的 system 等分区保留原描述符，不声称重新校验其 payload。
- **迁移关联**：对将恢复的非目标 stock 分区重新计算主 hash；其公钥若与此前 AOSP 产物不同，
  同步更新对应 chain 公钥。旧版目标公钥与当前主链不同的情况也同步重签主 `vbmeta`。
- **真正回读验证**：RSA 签名、vbmeta digest、payload hash、主引用、父子 chain key 一并验证，
  不是仅打印镜像信息。签名失败清理该次私有输出并在写入前停止。

有意修正上游一个边界：修补可能增长 ramdisk，footer 的 `originalImageSize` 必须使用最终
payload 长度，而不是修补前长度。对于去掉 footer 但保留整分区零 padding 的产物，从最终
boot header 推导新 payload 结束位置，并保留非零尾数据；无 footer 的源 `init_boot` 从主
`vbmeta` 的 hash descriptor 获取原尺寸和 hash 参数。

## 安装、迁移和输出事务

1. root 服务在当前槽位捕获完整 PRE 及 stock originals，签名配套分区仅 opt-in 读取。
2. 原有修补管线完成后调用签名器；Ultra 配对凭据与最终签名哈希重新关联。
3. root 服务独立校验整个最终写入组，再按 `init_boot → boot → recovery → vbmeta_system → vbmeta`
   的适用子集刷写并读回。任一步失败，按反序恢复 PRE；回滚也验证全部分区。
4. 跨模式迁移保留原有基线建立过程，native PRE 建立后、环境退出前退休未使用的旧写入事务。
   签名与数据搬移完成后才提交。候选身份、重启验证及恢复路由都使用最终哈希。
5. 同一迁移的 native Binder 操作借用 root helper 已持有的设备锁，不重复申请；借用仅限匹配
   task ID 的同步调用，其他线程/任务仍受同一内核锁约束。管理器退出也等已接受的调用完成。
6. 签名任务的 PRE、候选、journal 放在 `/data/local/maplesu-recovery/signed-installs`，与设备锁
   路径分离，避免 Ultra SDK 清理 `/data/adb/maplesu` 时丢失恢复数据；pending 检查同时覆盖该目录。
7. 文件修补不启动分区写入。整组 MediaStore pending 文件全部复制并回读校验后发布，失败删除
   本组所有条目；日志包含签名开始/成功/失败、分区名和实际 Downloads 输出路径。

没有启用该选项时，继续使用原安装写入路径；通用 installer-core 的 `vbmeta` 禁写策略保留，
仅显式签名请求走 native 多分区写入。

## 可复现验证

主机验证使用 JDK 21；管理器的 Java 源级别为 21。签名测试自身使用离线固定 fixtures：

```powershell
.\gradlew.bat :image-core:check :installer-core:check
.\gradlew.bat :manager:testDebugUnitTest --tests '*Lenovo*' --tests '*NativeInstallSequenceTest' --tests '*InstallerSignedPendingTest' --tests '*ModeSwitchRootLeaseTest' --tests '*ModeSwitchLifecycleFacadeTest'
```

保留签名样本以便独立验证（每次使用新的输出目录）：

```powershell
.\gradlew.bat :image-core:checkLenovoAvbFixtures -PlenovoAvbOutputs=ABSOLUTE_OUTPUT_DIRECTORY
```

取得 AOSP 官方 `avbtool.py` 后，对各输出镜像执行 `verify_image`；涉及父子链时再验证
配套镜像与 chain public key。`--key` 会传给子镜像，主子密钥位数不同时先分别核验指定公钥，
再跟随链验证。测试使用的固定 fixtures 随 `image-core` 测试资源提供。

UI 测试使用 `maplesuThemeIsolatedTests=true`、包名 `dev.maplesu.manager.themetest`，
`PresentationTestRunner` 跳过 Root 初始化并渲染生产组件。覆盖范围包括 Material/Miuix、
深浅色、动画、展开收起、确认取消、AOSP 配套选择和 init_boot 输入选择。

主机 AVB 密码学检查和隔离 UI 测试分别记录结果；真机分区刷写、重启和 Bootloader
接受密钥的行为仍需独立设备验证。完整 Manager 的公开构建条件见[构建说明](BUILD.md)。
