# 模块与 WebUI 指南

MapleSU 按包内身份选择模块后端。MapleSU_Ultra 提供脚本模块后端与原生 SDK；市场独立维护安装包、版本和源码。

| 开发目标 | 格式与入口 | 指南 |
| --- | --- | --- |
| KernelSU 脚本模块 | 根目录 `module.prop` 与生命周期脚本 | 本文及 [KernelSU 模块文档](https://kernelsu.org/guide/module.html) |
| MapleSU_Ultra 原生模块 | 根目录 ARM64 `.so`、导出入口与原生描述 | [原生模块开发](ULTRA_MODULES.md) |
| 原 SKRoot(Pro) 模块 | 沿用现有 ZIP、原生入口和 ID | [ABI 与调用方式](ULTRA_MODULES.md) |
| 应用 Root 调用 | Binder 客户端 AAR | [应用 SDK](SDK.md) |
| `.fpt` 主题 | 主题商店与主题导入器 | [主题仓库](THEME_REPOSITORY.md) |

## 脚本模块

ZIP 根目录放置 `module.prop`，按功能加入脚本和资源：

```text
module.prop
customize.sh
post-fs-data.sh
post-mount.sh
service.sh
boot-completed.sh
action.sh
uninstall.sh
webroot/
```

元信息示例：

```properties
id=maplesu.example
name=MapleSU Example
version=1.0.0
versionCode=10000
author=Example contributors
description=MapleSU module example.
```

脚本 ID 以英文字母开头，后续使用字母、数字、点、下划线或连字符，至少两个字符。更新保持 ID，递增有符号 32 位整数 `versionCode`。安装由 MapleSU_Ultra 的脚本后端处理，挂载、策略、属性和注入依赖按对应后端接口接入。

## 原生模块与既有模块

MapleSU_Ultra 通过有效 ELF 导出与完整描述确定原生模块身份。已有 SKRoot(Pro) 模块保留原始包和 ID，安装、状态、卸载与 WebUI 继续走原生后端。新模块使用 `MAPLESU_MODULE_*` 宏和 `maplesu_module_main`，CMake 工程、SDK 调用及 ZIP 布局见[原生指南](ULTRA_MODULES.md)。

旧原生包同时带有根级 `module.prop` 时，Ultra 识别器以有效原生描述为准，包内原生文件作为脚本载荷。多个有效原生模块入口返回 `AMBIGUOUS_MODULE_TYPE`。

## 安装前检查

[`ModuleArchiveInspector`](../manager/src/main/java/dev/maplesu/manager/ModuleArchiveInspector.java) 检查路径、重复条目、CRC、大小和身份，安装确认后复核归档摘要。

| 项目 | 上限 |
| --- | --- |
| ZIP | 256 MiB |
| 总展开大小 | 512 MiB |
| 单条目 | 128 MiB |
| 根目录原生候选库 | 32 MiB |
| 条目数 | 20,000 |
| 根目录 `module.prop` | 64 KiB |

识别结果包含类型、ID、原生成员、包摘要和成员摘要；SDK 与内核组合由[模块兼容判定](MODULE_COMPATIBILITY.md)维护。

## WebUI 与生命周期

静态页面放在 `webroot/`。原生模块使用 `kernel_module::WebUIHttpHandler` 注册 C++ HTTP 回调，使用 `kernel_module::webui` 处理请求与响应。脚本模块使用当前后端的页面与执行桥。

页面、脚本和原生库随同一版本更新。入口完成初始化后返回，后台任务管理自身的启动、停止和资源释放。版本回归覆盖安装、启动、禁用、重新启用、卸载和重启；WebUI 按请求结果更新页面。

## 投稿与维护

提交原始 ZIP、对应源码、许可证、作者及维护账号、更新说明和运行组合。更新使用新版本并沿用原 ID，源码和包摘要保持对应。

导入、维护登记、签名与主备发布见[模块仓库指南](MODULE_REPOSITORY.md)。市场与 Manager 分仓维护，模块更新独立于管理器发版。
