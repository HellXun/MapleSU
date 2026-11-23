# third_party/

Pinned upstream sources, prebuilt inputs, integration patches and their notices. Nothing here is MapleSU's own code, so each directory keeps the author, the license and the fixed version it came from.

[DEPENDENCIES.md](DEPENDENCIES.md) is the inventory: component, fixed version, scope and license. [CREDITS](../CREDITS.md) and [NOTICE](../NOTICE) carry the acknowledgements and the terms that travel with distribution.

| Directory | Content |
| --- | --- |
| `7kimisu/` | Source record of the Manager's derivation base |
| `ksu-uapi/` | The two KernelSU UAPI headers `module-policy` compiles against ([README](ksu-uapi/README.md)) |
| `libsu-service/` | The `:libsu-service` Gradle module, a pinned Apache-2.0 source tree ([README](libsu-service/README.md)) |
| `ksu_props/` | Pinned property library used by [`module-tools`](../module-tools/README.md) |
| `skroot/` | Source and hash records for the Lite and Pro backend inputs |
| `aosp-avb/`, `aosp-mkbootimg/`, `magiskboot/` | Image-format references and comparison tools; reference binaries are not built as MapleSU artifacts |
| `android-kernel/` | Recorded kernel call contracts (`call-contracts.json`) |
| `commons-compress/`, `capstone/` | License and source records of libraries used by checks and tools |
| `busybox/` | The runtime tool input and its provenance record |
| `rezygisk/` | Dependency source of the independent Zygisk module; not part of the Manager |
| `llvm-mingw-toolchain.json` | The pinned llvm-mingw toolchain asset: release, URL, size and SHA-256 |

## Rules

- **Never copy third-party code into first-party packages.** Add a pinned input with a record here and reach it through an existing adapter ([where new code goes](../docs/ARCHITECTURE.md#where-new-code-goes)).
- **Pin before you use.** A new input records its origin, fixed version, license, the file digests and what was changed. A prebuilt input also records its digest and the source it corresponds to.
- **Vendored headers stay verbatim.** `ksu-uapi/` copies the KernelSU UAPI as-is; `SOURCE.json` records their digests.
- `python tools/check_architecture.py` (`third-party-records`) fails when a directory here has no source, license or manifest record, or when a link in `DEPENDENCIES.md` does not resolve.
