# Credits

Thank you to the authors and contributors who share their work, designs and experience. The order below follows About MapleSU.

| Project | Contribution to MapleSU | Source records |
| --- | --- | --- |
| [FinaSU](https://github.com/LyraVoid/FinaSU) | Primary reference for UI, interaction and theme format | FPT and JSON-array theme contracts; directly derived code follows its file notices and Manager source manifest |
| [KernelSU](https://github.com/tiann/KernelSU) | Reference for the in-kernel manager ABI the policy loader speaks | [Two UAPI headers and their snapshot record](third_party/ksu-uapi/SOURCE.json); kernel GPL-2.0-only |
| [7kimisu](https://github.com/hartgerinktarcila-design/7kimisu) | Direct foundation of the Manager | [Source manifest](third_party/7kimisu/source-manifest.json), [upstream notice](third_party/7kimisu/UPSTREAM-SOURCE-NOTICE.md) and [license](third_party/7kimisu/LICENSE) |
| [KernelPatch](https://github.com/bmax121/KernelPatch) | Technical reference for kernel patching | Component manifests identify any directly integrated source |
| [SKRoot](https://github.com/abcz316/SKRoot-linuxKernelRoot) | Foundation and reference for Ultra patching, SU internals and native module integration | Pinned source manifests in `third_party/skroot/` and the retained native SDK identity |

## MapleSU development

MapleSU maintains operating-mode orchestration, installation transactions, image handling, constrained Binder sessions, the application SDK, authorization transactions, native adapters, module compatibility, theme/catalog integration and both Manager interfaces. Original authors and component notices remain with derived source.

Developer: **MapleAutumn**. Special thanks to 小酒, 晚风, [有希](https://github.com/matsuzaka-yuki), [佐佐鱼](https://github.com/Yutou04-sa) and [知秋](https://github.com/947409161), and to everyone reporting issues, maintaining modules, sharing themes and improving documentation.

## Other components

libsu, AndroidX, Compose, Miuix, MaterialKolor, Apache Commons Compress, Magisk/libsepol and image-format references provide supporting capabilities. Versions, original notices and integration scopes are listed in the [dependency inventory](third_party/DEPENDENCIES.md). ReZygisk, CSOLoader and PLTI are acknowledged with the independent Zygisk module in the module repository.

Modules and themes are maintained by their respective authors. Their code, resources, fonts and trademarks retain their own terms. MapleSU's project license applies to MapleSU-owned code; component licenses and notices remain applicable to their respective files.
