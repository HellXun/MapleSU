# 测试说明

测试按可验证的行为组织。主机检查、Android 单元测试和设备集成验证分别报告结果，不以源码字符串检查替代运行验证。

## 主机检查

```shell
python tools/check_host.py
```

| 范围 | 验证内容 |
| --- | --- |
| 镜像组件 | 结构、边界、编解码、回封、修改范围、ramdisk、AVB 和 vendor_boot |
| 安装组件 | 文件事务、块设备模拟、多分区事务、切换计划、写入边界与恢复 |

主机入口在隔离工作目录运行合成测试，不需要真实 boot 镜像，也不连接设备。原有测试中需要真实镜像的可选部分不会作为公共 CI 的通过依据。依赖私有证据、旧 SDK 或历史交付目录的检查已移入维护者归档；实际行为、授权、事务、模块和恢复测试保留。

## 架构规则检查

```shell
python tools/check_architecture.py
python -m unittest discover -s tools/tests -p "test_check_architecture.py" -v
```

只读取仓库，不需要 Gradle 或设备。检查 Gradle 模块依赖、纯 Java 模块的导入、客户端与管理器共用的 AIDL、原生库加载点、`third_party/` 的来源记录、各顶层目录的 README、脚本的存放位置与仓库根解析、SDK 记录所固定的脚本，以及不得写入本机目录和设备序列号，另核对[架构说明](ARCHITECTURE.md#gradle-modules-and-dependency-rules)中的路径与文档索引。`--report` 额外输出 Manager 源码与固定 7kimisu 快照的逐项对比。

## Ultra 后端回归测试

`scripts/tests/` 保存 Ultra 后端与管理器恢复流程的主机回归测试，分类与注意事项见 [scripts/README.md](../scripts/README.md)。它们从仓库根目录运行；除 `test-parasite-loader.py` 与 `test-parasite-startup.py --device` 通过 adb 驱动已连接设备（需要 `ANDROID_SERIAL` 或 `--serial`）外，其余只在主机运行。需要 NDK、Docker 或保留的 SDK 输入的测试，在缺少输入时以指明输入的错误退出。

## 原生输入检查

原生输入获取、摘要拒绝、离线行为、源码快照及 host 工具定位另有独立检查：

```shell
python -m unittest discover -s tools/tests -p "test_native_inputs.py" -v
```


## Android 单元与资源检查

```shell
./gradlew :manager:testDebugUnitTest :manager:lintDebug
./gradlew :maplesu-client:assembleRelease :maplesu-consumer:assembleDebug
```

Manager 的检查需要[完整构建输入](BUILD.md#build-the-complete-manager)。单元测试重点覆盖授权策略、请求编码、状态推导、安装计划、模块行为与失败分支。品牌和文案检查只保留共同数据和链接契约，不锁定源码行序、具体布局字符串或整段宣传文案。

## 设备验证

`consumer` 提供独立 UID 的接入示例与 instrumentation 测试。设备测试应单独记录 Manager 版本、签名证书摘要、模式、内核/KMI、操作与观察结果；先确认已有 Root 后端与授权状态。

公开仓库不收录个人设备日志、账号、序列号、Root 凭据或历史取证包。真实设备用例与主机合成测试分开执行，涉及安装、分区和模式切换的步骤由维护者在明确目标设备上操作。

## 发布检查

```shell
python -m unittest discover -s tools/tests -p "test_release_audit.py"
python tools/release_audit.py --strict
```

发布检查验证候选文件范围、密钥与构建产物、文档链接、许可证材料和未解决的后端输入。文件筛选以 Git 实际候选清单为准，已被强制暂存的文件也会被检查。
