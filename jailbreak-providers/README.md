# Ultra kernel-side inputs (historical directory name)

This directory used to hold the temporary-root provider edge: Ghostlock and
DirtyFrag providers, the authenticated raw-root lease, the Android lease adapter
and the 93260 port. **All of it has been removed**; MapleSU ships Ultra only.

What remains is the kernel-side input tree Ultra cannot work without. The
directory keeps its old name so the pinned contracts, the policy-hook packaging
scripts and `module-policy` keep resolving the same paths; it no longer contains
any provider, lease or privilege-escalation code.

## Layout

- `root-loader/` — boot SDK and policy hook ABI, kernel adaptation contracts,
  module sources and the UMH canary.
  - `boot_sdk_abi.h`, `policy_hook_abi.h`, `selinux_hide_abi.h`: the ABIs the
    Ultra payload and `module-policy` are built against.
  - `contracts/`: per-kernel-family symbol/offset contracts consumed by
    `:manager:packagePolicyHooks`.
  - `module/`: L2/L3 sources, policy hook and boot SDK allocator.
  - `canary/`: UMH canary helper and its packaging test.
  - `tests/`: host-side contract checks for the above.
- `contracts/kmi-matrix.json` — KMI matrix the Ultra kernel builder selects from.

## Build integration

`manager/build.gradle.kts` reads this tree in exactly two places:

- `buildModulePolicy` takes `policy_hook_abi.h`, `boot_sdk_abi.h` and
  `selinux_hide_abi.h` as declared inputs of the `maplesu-policy` module build.
- `packagePolicyHooks` turns `root-loader/contracts/*.json` into the packaged
  policy-hook bundles.

There is no provider build, no `buildJailbreakProviders`, no
`packageJailbreakProviders` and no traceless-invariant gate any more. Nothing
here runs at build time on a device, fetches from the network, or produces a
privilege-granting binary.

## Verification limits

Native compilation and the host validators do not exercise device sockets, FD
transfer or runtime ownership. Each release records its own device checks. See
the [build guide](../docs/BUILD.md) for the toolchain and
[`docs/ARCHITECTURE.md`](../docs/ARCHITECTURE.md) for where these inputs sit in
the Ultra pipeline.
