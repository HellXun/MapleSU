# Native dependencies

The complete Manager build uses the native inputs retained in this repository. The same SDK archive, embedded helper and paired policy backend are used for local and CI builds. [Build](BUILD.md) lists the toolchains and commands; [Credits](../CREDITS.md) records component origins.

## Interfaces and ownership

| Component | Location | Responsibility |
| --- | --- | --- |
| Application client SDK | `client/`, `consumer/` | Binder sessions, streams, cancellation and exit status |
| Manager | `manager/` | Caller authorization, backend detection, installation and module orchestration |
| Ultra bridge | `manager/src/main/java/dev/maplesu/manager/runtime/ultra/bridge/`, `runtime/ultra/bridge/` | Native SDK calls, request context and structured results |
| Ultra runtime | `runtime/src/`, `runtime/ultra/` | SU sessions, authorization transactions, environment and module lifecycle |
| Image and installation | `image-core/`, `installer-core/`, `patch-engine/` | Image containers, patch plans, write transactions and recovery |
| In-kernel manager ABI | `third_party/ksu-uapi/` | The supercall and app-profile UAPI headers the policy loader compiles against |

The application AAR and Ultra native module ABI have separate versioning. Native callbacks, symbols, descriptors and storage layouts retain their established ABI. MapleSU errors use `MAPLESU_*` identifiers; compatibility parsers preserve numeric results and success conditions.

## Retained build inputs

| Input | Location | Identity |
| --- | --- | --- |
| Offline patch core | `patch-engine/upstream/` | Fixed source manifests in `third_party/` |
| Runtime headers and archive | `runtime/upstream/` | Byte hashes in `release/native-inputs.json` |
| Ultra native SDK | `runtime/maplesu-sdk/` | `SDK-RELEASE.json`, `SDK-IDENTITY.cmake` |
| Embedded helper | `runtime/maplesu-sdk/helper/autorun_bootstrap` | Helper hash and build ID in the SDK release manifest |
| Paired policy backend | `runtime/maplesu-sdk/backend/` | SDK release manifest and ELF build ID |
| Kernel images and symbols | `native-inputs/kernels/` | Provider contracts and retained-input hashes |
| Policy payloads, BTF and configuration | `native-inputs/policy/` | Bundle manifest and compatibility contracts |
| SDK maintenance baseline and entry | `runtime/maplesu-sdk/maintenance/` | Archive member comparison and paired entry identity |
| Ultra kernel inputs | `third_party/`, `jailbreak-providers/root-loader` | Component source and build manifests |
| SELinux / libsepol | `.deps/selinux/` | `module-policy/upstream/selinux-source.json` |

`release/native-inputs.json` records the files, sizes and SHA-256 values required by the complete source export. The export checker verifies those bytes in the actual archive, including inputs that have binary extensions. Build caches, previous backend generations and research records are excluded.

Verify the paired SDK before building:

```shell
python scripts/sync-ultra-sdk-identity.py --check
python tools/prepare_native_inputs.py --check
python tools/build_module_policy.py --prepare-only --offline
```

The SDK identity check validates the archive, embedded helper, paired backend, current source hashes and all six policy profiles together. Manager BuildConfig and native CMake read the same identity. AAR builds are independent of this native ABI.

## Develop modules against the retained SDK

Native modules include `runtime/maplesu-sdk/include/` and link `runtime/maplesu-sdk/lib/arm64-v8a/libkernel_module_kit_static.a`. Module builds use C++17, ARM64, PIC and `-fno-rtti`; the [MapleSU_Ultra guide](ULTRA_MODULES.md) provides the complete CMake project, callbacks, WebUI and ZIP layout. Existing SKRoot(Pro) module packages retain their ABI and IDs.

## Maintain the SDK and paired backend

The maintained SDK build entry is `runtime/maplesu-sdk/sdk/build.py`. Its inputs are the member records in `sdk/manifest/`, the SDK sources in `sdk/src/`, third-party source recipes in `sdk/third_party/` and the selected kernel policy sources. The frozen comparison archive and member order are retained under `maintenance/freeze/`.

The complete SDK producer currently uses a Windows NDK host. Set `MAPLESU_NDK` to NDK `26.1.10909125`. For the six-family source build, `KERNEL_SOURCE_618`, `KERNEL_OBJTREE_618` and `KERNEL_TOOLCHAIN_618` identify the prepared 6.18 source, object tree and LLVM `usr` directory used by the Kbuild entry. Run from the repository root in PowerShell:

```powershell
python runtime/maplesu-sdk/sdk/build.py --all --source-payloads --ndk "$env:MAPLESU_NDK" --kernel-source-618 "$env:KERNEL_SOURCE_618" --kernel-objtree-618 "$env:KERNEL_OBJTREE_618" --kernel-toolchain-618 "$env:KERNEL_TOOLCHAIN_618" --build-dir work/sdk-next --jobs 2
```

`--policy-bundle-source` selects an existing generated C++ bundle together with its Kbuild input records. The normal source path builds the bundle, source backend, helper and embedded programs before composing the 260-member archive. Outputs and the build report stay in the selected build directory.

Update a generation as one change:

1. Modify the maintained SDK, helper or policy sources and their member recipes.
2. Build into a fresh output directory; keep the archive, helper, backend, kernel bundle and source identity from the same run.
3. Import the matching headers, archive, helper and content-addressed backend. Record their paths, sizes, hashes, ELF identities and helper key-slot identity in `SDK-RELEASE.json`; record source files and ordered archive members in `SDK-SOURCES.json`.
4. Generate the application/native identity and refresh retained input hashes:

```shell
python scripts/sync-ultra-sdk-identity.py
python tools/prepare_native_inputs.py --refresh-manifest
python scripts/sync-ultra-sdk-identity.py --check
python -m unittest discover -s tools/tests -p "test_sdk_distribution.py" -v
```

5. Run the complete Manager build and module lifecycle regression: installation, update, enable/disable, boot loading, WebUI and uninstall. Match the resulting APK and runtime identity to the imported generation.

The SDK distribution check compares the actual 260 archive members, source hashes, embedded helper, helper slot, backend filename/build ID and generated policy bundle. `manager/build.gradle.kts` and native CMake consume the same release identity. The application AAR retains its independent Binder API versioning.

Module catalog updates follow [module repository maintenance](MODULE_REPOSITORY.md). The two UAPI headers under `third_party/ksu-uapi/` keep their recorded snapshot and license; everything else that used to sit beside them is gone.
