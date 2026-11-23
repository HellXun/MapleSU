# Building MapleSU

The repository contains the source and pinned native inputs used by the complete Manager, application SDK and consumer example. The Manager build compiles its runtime, patch tool, policy tool, property tool and providers, and packages the retained Ultra SDK and paired backend. See [Native dependencies](NATIVE_DEPENDENCIES.md) for component ownership and update procedures.

## Toolchains

| Component | Version |
| --- | --- |
| JDK | 21 |
| Android SDK | API 37.0, Build Tools 37.0.0 |
| Gradle | Repository wrapper with pinned distribution checksum |
| Python | 3.12+ and `requirements-native.txt` |
| CMake | 3.22.1 |
| Android NDK | 26.1.10909125 for runtime/patch tools; 28.2.13676358 for providers; 29.0.14206865 for the toolchain checks |
| Rust | 1.99.1 with the `aarch64-linux-android` target |

Set `ANDROID_HOME` to the Android SDK directory and use JDK 21 for Gradle. Local SDK paths can also be supplied in an untracked `local.properties`. On Windows, use `./gradlew.bat` for the commands below. On Linux/macOS, run `chmod +x gradlew` once.

```shell
python -m pip install -r requirements-native.txt
rustup toolchain install 1.99.1 --profile minimal
rustup target add --toolchain 1.99.1 aarch64-linux-android
```

Windows GNU Rust hosts also need MinGW UCRT64. Pass `-PmaplesuRustToolchain=1.99.1-x86_64-pc-windows-gnu` and `-PmaplesuMingwBin=C:/msys64/ucrt64/bin` to Gradle when using that host. An MSVC Rust host needs the corresponding Visual Studio C++ Build Tools. Gradle uses `python` by default; select an interpreter with `MAPLESU_PYTHON` or `-PmaplesuPython`.

### Locating tools

Scripts and tests do not hard-code anyone's local directories. They locate tools through environment variables and, when a tool is missing, stop with an error that names the variable to set. The shared lookup is in `tools/native_toolchain.py`.

| Variable | Purpose |
| --- | --- |
| `ANDROID_HOME` / `ANDROID_SDK_ROOT` | Android SDK; scripts use it to find `ndk/`, `platforms/android-37.0/android.jar`, `platform-tools/adb` and CMake |
| `ANDROID_NDK_HOME` / `ANDROID_NDK_ROOT` | The NDK 26.1.10909125 used by the runtime and patch tools |
| `MAPLESU_PROVIDER_NDK` | The NDK r28c used by the providers; defaults to `<SDK>/ndk/28.2.13676358`; Gradle property `maplesuProviderNdk` |
| `JAVA_HOME` | JDK 21; when unset, `java` on `PATH` is used |
| `MAPLESU_PYTHON` | Python interpreter; Gradle property `maplesuPython` |
| `ADB`, `ANDROID_SERIAL` | The adb executable and the target device serial. Device scripts have no built-in serial |

The `portable-paths` rule of `python tools/check_architecture.py` rejects new local workspace directories, user profiles, fixed JDK or Python installation directories and device serials.

## Develop with the bundled native SDK

The default Manager build links the precompiled native SDKs included in the checkout. After installing the toolchains above, edit `manager/` and build:

```shell
./gradlew :manager:assembleDebug
```

The runtime archive and headers are in `runtime/upstream/`; the Ultra archive and headers are in `runtime/upstream-pro/lib/arm64-v8a/` and `runtime/upstream-pro/include/`. The matching helper, policy backend and build identity are included in `runtime/upstream-pro/`. Gradle and CMake use these paths automatically. Manager UI, application logic and client SDK changes use the existing native SDK directly, with no SDK rebuild or manual import step.

Ultra's provider kernel images, symbols, policy bundles, BusyBox and SELinux build inputs are retained in the repository. The [native input inventory](../release/native-inputs.json) pins their bytes. Updating the native SDK or its paired policy backend follows the separate [native maintenance procedure](NATIVE_DEPENDENCIES.md#update-the-policy-backend).

The Android application SDK builds from `client/`. For integration into another app, build its AAR as described [below](#build-the-application-sdk-and-example), or use the AAR uploaded by the [Build Manager workflow](ACTIONS.md#run-a-build).

## Verify native inputs

```shell
python scripts/sync-pro-sdk-identity.py --check
python tools/prepare_native_inputs.py --check
python tools/build_module_policy.py --prepare-only --offline
```

The checkout retains the SDK archives, helper, paired policy backend, provider kernel images, symbols, policy payloads and SELinux source. No research directory or private attachment is needed. `prepare_native_inputs.py` can restore the fixed BusyBox and stock provider inputs from their recorded upstream commits; `--check` verifies them without downloading.

Module-tool builds use the retained `ksu_props` snapshot and Cargo lockfile. The policy loader's in-kernel manager ABI comes from the two UAPI headers under `third_party/ksu-uapi/`, which `module-policy/CMakeLists.txt` adds to the include path.

## Build the complete Manager

```shell
./gradlew :manager:assembleDebug
./gradlew :manager:assembleRelease
```

The outputs are `manager/build/outputs/apk/debug/manager-debug.apk` and `manager/build/outputs/apk/release/manager-release-unsigned.apk` for an unsigned release. With release signing configured, the release output is `manager-release.apk`.

The manager build includes the Ultra backend and all configured provider inputs. It verifies the SDK archive, embedded helper, policy source identity and paired backend before packaging. When updating native sources, regenerate the matching candidate artifacts and identity together using [Native dependencies](NATIVE_DEPENDENCIES.md).

For a clean build without reusing task outputs:

```shell
./gradlew clean :maplesu-client:assembleRelease :maplesu-consumer:assembleDebug :manager:assembleRelease --no-build-cache --no-configuration-cache
```

Gradle and Cargo fetch their pinned package dependencies on first use. After preparing these caches, `--offline` performs the Gradle build without repository access.

## Build the application SDK and example

```shell
./gradlew :maplesu-client:assembleRelease :maplesu-consumer:assembleDebug
```

The SDK AAR is `client/build/outputs/aar/maplesu-client-release.aar`; the example APK is `consumer/build/outputs/apk/debug/maplesu-consumer-debug.apk`. Use a project dependency or the local AAR as described in [SDK integration](SDK.md).

## Run checks

```shell
python tools/check_architecture.py
python tools/check_host.py
python -m unittest discover -s tools/tests -p "test_source_export.py"
```

Host checks compile the image and installer sources and run their transaction and format tests. Results are written to `build/host-check/`. [Testing](TESTING.md) describes Android and device checks; [Releasing](RELEASING.md) describes checks on the final source archive.

## Signing

Release signing reads `MAPLE_RELEASE_STORE_FILE`, `MAPLE_RELEASE_STORE_PASSWORD`, `MAPLE_RELEASE_KEY_ALIAS` and `MAPLE_RELEASE_KEY_PASSWORD`. Keep the keystore outside Git; [Actions](ACTIONS.md) documents the repository Secrets used by cloud builds. Independent SDK builds need no release key.

Public AOSP AVB test keys are compatibility fixtures documented in their adjacent `NOTICE`. They are separate from the Manager signing identity.
