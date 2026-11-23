# KernelSU UAPI headers (extracted)

Only two headers are kept from the upstream KernelSU UAPI, because `module-policy/`
(`ksu_manager_fd.cpp`) talks to the in-kernel manager fd and needs the ioctl/struct
definitions. Everything else from `third_party/kernelsu/` (kernel patches, the LKM
build, `ksud`, the userspace tree) is **not** part of this Ultra-only build.

| File | Origin |
| --- | --- |
| `uapi/supercall.h` | `third_party/kernelsu/applied/uapi/supercall.h` @ the pinned KernelSU snapshot |
| `uapi/app_profile.h` | `third_party/kernelsu/applied/uapi/app_profile.h` @ the same snapshot |

The headers describe the **kernel ABI** that the Ultra shadow-policy hook speaks. They are
copied verbatim: do not edit them, and keep them in sync with the pinned KernelSU UAPI
version (`KERNEL_SU_UAPI_VERSION = 7`) if the kernel-side contract ever changes.

License: GPL-2.0-only, same as the upstream kernel sources they belong to.
