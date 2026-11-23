# Standalone module policy loader

`maplesu-policy` reads and edits a monolithic SELinux policy using fixed libsepol and Magisk policydb source. It does not start Magisk, use KSU ioctls, install base privilege domains, or change the global enforcing switch.

Build from the workspace root with a real Python interpreter:

```
python tools/build_module_policy.py --prepare-only
python tools/build_module_policy.py --offline
```

The preparation step fetches SELinux at commit `be1b39a657fee7faacfae548b75cb53302043a01` into `.deps/selinux/` and verifies its clean tree. The UAPI 7 headers it compiles against are the two files under `third_party/ksu-uapi/uapi/`, added to the include path by `CMakeLists.txt`. The offline step builds ARM64 API 26 with static libc++. Set `ANDROID_HOME` or pass `--ndk` and `--cmake`; an existing pinned checkout can be selected with `--selinux-source`. Original Magisk v30.7 sources and license remain in `upstream/magisk`; `upstream/magisk-adapter.patch` records the mechanical Rust-bridge removal and strict return adaptations. `derived` is regenerated on each build. The linked libsepol LGPL sources and license remain separately available; build outputs retain the static archive and objects for relinking.

Internal CLI:

```
maplesu-policy check [--policy SAVED_POLICY] [--rules MODULE_RULES]...
maplesu-policy save --policy SAVED_POLICY [--rules MODULE_RULES]... --output CANDIDATE
maplesu-policy apply --rules MODULE_RULES [--rules MODULE_RULES]...
maplesu-policy apply --require-boot-policy --rules MODULE_RULES [--rules MODULE_RULES]...
```

`check` defaults to current live policy. `save` writes a candidate to an atomic regular-file output, with mode 0600 for the temporary file. `apply` always reads `/sys/fs/selinux/policy`, requires UID 0 and preserves the current global enforcing switch, checks for baseline/rule changes, and makes exactly one complete write to `/sys/fs/selinux/load`. A short write, EINTR, errno, failed serialization, or invalid rule fails the batch. A complete kernel write is followed by a check that the global switch stayed unchanged and semantic verification by idempotent replay against a freshly read policy. A post-commit verification failure explicitly reports `committed: true`; it does not attempt to roll back a live kernel policy.

Ultra live callers pass `--require-boot-policy` to `apply` or `restore`. Before parsing and immediately before committing, the CLI requires a current-boot effective Load or Hide backend; absent, stale, blocked or interrupted state returns 77 without a policy write. The option does not prove Hide isolation. Pending preferences affect the next boot only. Commands without this option retain the legacy/Normal no-backend contract, while still respecting an effective Block or interrupted current-boot backend. Read-only `check` and offline `save` do not accept the live-transaction option.

Android live policy exports can omit the Android netlink configuration bits. When reading the live path, the tool restores Android's GETLINK restriction from API 30 and GETNEIGH restriction from API 33, only if the policy defines the corresponding permission. This preserves the kernel's Android permission mapping when the policy is reloaded. Explicit offline `--policy` inputs retain their own configuration. MLS, unknown-permission handling, policy capabilities and permissive-domain bitmaps pass through unchanged.

Each nonempty source rule emits a JSON line with file, line and status. `validated` describes an in-memory operation, not a kernel commit. The final JSON result carries overall status, expanded operation count and byte counts. Exit codes: 0 success; 1 argument/I/O/serialization error; 2 one or more rules rejected without a commit; 3 complete live write followed by failed verification.

Supported rule grammar covers single-line `type`, `attribute`, `typeattribute`, `allow`, `deny`, `auditallow`, `dontaudit`, ioctl `allowxperm`/`auditallowxperm`/`dontauditxperm`, `type_transition`, `type_change`, `type_member`, `genfscon`, `enforce`, and module-requested `permissive`; brace sets, wildcard terms, a target `self`, optional class colon and trailing semicolon are accepted. Unsupported syntax is rejected with a source line. Complement ioctl ranges, a wildcard source with target `self`, and multiline statements are explicitly rejected. Per-domain permissive rules are applied only when supplied by a module; the loader never writes the global enforcing switch.

`deny` removes the requested effective permission, including grants inherited through source or target attributes. It splits only the affected permission bits and preserves other concrete source/target pairs and permissions. A matching conditional grant rejects the entire batch before any live write; it is never promoted to an unconditional grant. Offline tests use libsepol's real access calculation before and after serialization, in addition to idempotent replay.

The caller must provide the frozen active module set, boot ID, module hashes and deployment state. It must load the full set before dispatching scripts that depend on it. The lock serializes this tool's writers; the baseline reread detects prior external policy changes, but the standard kernel interface does not offer compare-and-swap against concurrent third-party policy writers. Disabling a module does not remove previously committed policy grants until the next boot.

Offline checks on Android (no live write):

```
maplesu-policy-tests SAVED_POLICY WRITABLE_TEMP_DIRECTORY
```

Tests exercise real libsepol read/serialize/readback, Android netlink restoration, repeated preservation of policy configuration and security bitmaps, declaration changes, malformed/unknown rules, semantic verification, errno/short-write failure handling using a nonblocking pipe, and complete regular-file output. A successful tool build alone does not establish current device load-policy permission or Zygisk/module backend compatibility.

An active Ultra boot may register one LSPosed revision with stock shared-data labels. Only its four superseded declarations/grants are omitted; other rules and Normal retain their contract. Unknown shared-label syntax rejects the batch. No SELinux query response is changed.
