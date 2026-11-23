"""Build maplesu_root.ko from source with the Android NDK only, and prove it loads.

Why a hand-written builder instead of kbuild: this repository has no Linux tree and no
`make`, and every loader-visible byte of the module is a property of *one* vendor
image (`struct module` size, vermagic, KCFI type ids). The module is therefore
compiled freestanding, linked with `ld.lld -r`, and its loader-facing objects are
written by hand:

  * `module/root_glue.S.in`     -- `.gnu.linkonce.this_module`, `.plt`, `.init.plt`,
                                   `.text.ftrace_trampoline`, `__versions`;
  * a generated `.modinfo`      -- the glue has none, and without `vermagic=` this
                                   kernel returns -ENOEXEC without printing anything
                                   (`CONFIG_MODULE_FORCE_LOAD` is off);
  * `module/maplesu_root.c`     -- the entry point and the restore point.

Nothing here is per-release data: every ABI number is read from the contract
(`contracts/<uname>.json`), and every KCFI type id used for a check is re-derived from
the image's own bytes and symbol table during verification. The only literals in the
produced module are symbol *names* and BTF-confirmed field offsets.

What `--verify-only` runs, and what each gate means (G1-G7 are the gate names in
docs/patching/HANDOVER-2026-10-29.md:272):

  G1 layout          ELF envelope the loader validates, exactly one this_module and
                     one .modinfo, this_module size == sizeof(struct module) from the
                     image's BTF, name/init/exit at their BTF offsets, vermagic exact,
                     __versions present with no module_layout pin.
  G2 PLT sections    `.plt` and `.init.plt` exist and are loadable -- arm64's
                     module_finalize() returns -ENOEXEC ("module PLT section(s)
                     missing") without them.
  G3 importability   the undefined-symbol set is exactly the four exported names
                     (memset tolerated only if the compiler emitted it), each has a
                     __ksymtab row in this image's symbol table, and none is one of
                     the names this kernel does not export.
  G4 cleanup_module  defined, in permanent text, and the target of this_module.exit.
  G5 patch plan      no `.ir_spec`/spec banner, no kernel virtual address anywhere in
                     an allocated section or relocation addend, every relocation type
                     is one arm64's apply_relocate_add() implements (and none is a GOT
                     form), no UMH_WAIT_PROC, and the handover call passes w3 = 1.
  G6 KCFI            init_module's 4-byte type prefix equals the id do_one_initcall
                     compares (contract + image-derived), cleanup_module's equals the
                     id __arm64_sys_delete_module compares, every KCFI compare
                     immediate in the module names a function type this image also
                     checks, and no BRK other than kCFI's own trap.
  G7 struct shim     the `_Static_assert` values in module/kernel_compat.h (which the
                     compiler enforced, since the build succeeded) equal the layouts
                     the image's BTF declares for struct kprobe / selinux_state, and
                     the glue's struct-module offsets equal BTF's.

`--verify-only` prefers `run_gates()` from jailbreak-providers/ir-loader/tests/
loader_contract.py (the shared 7-gate replica). While that function does not exist the
script runs the same seven gates itself and says so; when it exists both engines must
agree, and a disagreement fails the run.

Host-side only: nothing is pushed, inserted or executed on a device.
"""
from __future__ import annotations

import argparse
import datetime
import hashlib
import importlib.util
import json
import platform
import re
import shutil
import struct
import subprocess
import sys
import tempfile
import time
import traceback
from pathlib import Path
from types import SimpleNamespace

HERE = Path(__file__).resolve().parent
PROJECT_ROOT = HERE.parents[1]
sys.path.insert(0, str(PROJECT_ROOT / "tools"))
import native_toolchain  # noqa: E402
MODULE_DIR = HERE / "module"
_LEGACY_SPEC = importlib.util.spec_from_file_location("maplesu_legacy_cfi", HERE / "legacy_cfi.py")
legacy_cfi = importlib.util.module_from_spec(_LEGACY_SPEC)
_LEGACY_SPEC.loader.exec_module(legacy_cfi)
_POLICY_SPEC = importlib.util.spec_from_file_location("maplesu_policy_build", HERE / "policy_hook_build.py")
policy_build = importlib.util.module_from_spec(_POLICY_SPEC)
_POLICY_SPEC.loader.exec_module(policy_build)
_BOOT_SDK_SPEC = importlib.util.spec_from_file_location("maplesu_boot_sdk_build", HERE / "boot_sdk_build.py")
boot_sdk_build = importlib.util.module_from_spec(_BOOT_SDK_SPEC)
_BOOT_SDK_SPEC.loader.exec_module(boot_sdk_build)
_LAYOUT_PRODUCT_SPEC = importlib.util.spec_from_file_location(
    "maplesu_layout_product", PROJECT_ROOT / "tools/specgen/specgen/product.py")
layout_product = importlib.util.module_from_spec(_LAYOUT_PRODUCT_SPEC)
_LAYOUT_PRODUCT_SPEC.loader.exec_module(layout_product)
LAST_RENDERED_HEADER = None
CONTRACTS_DIR = HERE / "contracts"
LOADER_CONTRACT = HERE.parent / "ir-loader" / "tests" / "loader_contract.py"
DEFAULT_NDK = native_toolchain.provider_ndk()
DEFAULT_OUT = HERE / "build"

# The step-1 acceptance record: raw commands, their verbatim output, the digests and a
# readelf summary, all in one file a reviewer can read without re-running anything.
EVIDENCE_NAME = "verify-evidence.txt"
TRACELESS = HERE.parent / "check_traceless_invariants.py"
IR_TESTS = HERE.parent / "ir-loader" / "tests"
ROOT_TESTS = HERE / "tests"

# --------------------------------------------------------------------------- #
# Compiler flags: the old builder's proven set plus what this image forces.
# --------------------------------------------------------------------------- #

# Every flag carries the fact that makes it mandatory; see
# docs/patching/maplesu-root-build-mechanics-2026-10-29.md section 2.1.
CLANG_FLAGS = [
    "--target=aarch64-linux-gnu",
    "-ffreestanding",            # no libc: keeps the import set at the exported four
    "-nostdlib",
    "-fno-builtin",              # no compiler-generated memset/memcpy
    "-fno-stack-protector",      # CONFIG_STACKPROTECTOR is on, but __stack_chk_guard
                                 # is not exported (CONFIG_TRIM_UNUSED_KSYMS)
    "-fno-asynchronous-unwind-tables",
    "-fno-unwind-tables",        # no .eh_frame: nothing unwinds module text
    "-fno-pic",                  # modules are non-PIC; adrp/add is what the loader
                                 # implements for every shipped module
    "-ffixed-x18",               # CONFIG_SHADOW_CALL_STACK: x18 is the SCS pointer
    "-mgeneral-regs-only",       # no FP/SIMD in kernel context
    "-mno-outline-atomics",      # otherwise __aarch64_* helpers become imports
    "-fno-delete-null-pointer-checks",   # the != NULL guards must survive -O2
    "-fsanitize=kcfi",           # emits the prefix AND the checks; never strip it
    "-mbranch-protection=pac-ret",       # CONFIG_ARM64_PTR_AUTH_KERNEL; not
                                 # "standard", because CONFIG_ARM64_BTI_KERNEL is off
    "-O2",
    "-Wall",
    "-Wextra",
    "-Werror",
    "-c",
]

# Flags the task brief listed that this build must NOT pass, with the measurement
# that decided it. Recorded in build.json so the deviation is auditable.
FLAGS_WITHHELD = {
    "-fno-plt": (
        "measured: '-fno-plt' makes clang call through the GOT, so the object gains "
        "R_AARCH64_ADR_GOT_PAGE (311) + R_AARCH64_LD64_GOT_LO12_NC (312) relocations "
        "(4+4). No module shipped for this kernel has a .got or any GOT relocation "
        "(the vendor dfroot-android15-6.6.ko has 0 and uses plain CALL26), so GOT "
        "support would be an unproven dependency on module_emit_got_entry(). "
        "Reproduce with --extra-flag -fno-plt."
    ),
}

ASM_FLAGS = ["--target=aarch64-linux-gnu", "-c"]

MODINFO_DESCRIPTION = "MapleSU resident root channel"

# --------------------------------------------------------------------------- #
# ELF / AArch64 constants.
# --------------------------------------------------------------------------- #

ET_REL, EM_AARCH64 = 1, 183
SHT_NULL, SHT_PROGBITS, SHT_SYMTAB, SHT_RELA, SHT_NOBITS = 0, 1, 2, 4, 8
SHF_WRITE, SHF_ALLOC, SHF_EXECINSTR = 0x1, 0x2, 0x4
R_AARCH64_ABS64 = 257
STT_FUNC = 2

SECTION_TYPE_NAMES = {
    SHT_NULL: "NULL", SHT_PROGBITS: "PROGBITS", SHT_SYMTAB: "SYMTAB",
    3: "STRTAB", SHT_RELA: "RELA", SHT_NOBITS: "NOBITS", 9: "REL", 7: "NOTE",
    0x6FFF4C03: "LLVM_ADDRSIG",
}

# arch/arm64/kernel/module.c: the cases apply_relocate_add() implements. The numbers
# are the ABI's, not the ones the old replica printed: 279 is TSTBR14, 284/285/286 are
# LDST16/32/64 (loader_contract.py:26-38 is shifted from 279 up; that is its bug, this
# table is the correct one).
ARM64_MODULE_RELOCS = {
    256: "R_AARCH64_NONE", 257: "R_AARCH64_ABS64", 258: "R_AARCH64_ABS32",
    259: "R_AARCH64_ABS16", 260: "R_AARCH64_PREL64", 261: "R_AARCH64_PREL32",
    262: "R_AARCH64_PREL16",
    273: "R_AARCH64_LD_PREL_LO19", 274: "R_AARCH64_ADR_PREL_LO21",
    275: "R_AARCH64_ADR_PREL_PG_HI21", 276: "R_AARCH64_ADR_PREL_PG_HI21_NC",
    277: "R_AARCH64_ADD_ABS_LO12_NC", 278: "R_AARCH64_LDST8_ABS_LO12_NC",
    279: "R_AARCH64_TSTBR14", 280: "R_AARCH64_CONDBR19", 282: "R_AARCH64_JUMP26",
    283: "R_AARCH64_CALL26", 284: "R_AARCH64_LDST16_ABS_LO12_NC",
    285: "R_AARCH64_LDST32_ABS_LO12_NC", 286: "R_AARCH64_LDST64_ABS_LO12_NC",
    299: "R_AARCH64_LDST128_ABS_LO12_NC",
    288: "R_AARCH64_MOVW_UABS_G0", 289: "R_AARCH64_MOVW_UABS_G0_NC",
    290: "R_AARCH64_MOVW_UABS_G1", 291: "R_AARCH64_MOVW_UABS_G1_NC",
    292: "R_AARCH64_MOVW_UABS_G2", 293: "R_AARCH64_MOVW_UABS_G2_NC",
    294: "R_AARCH64_MOVW_UABS_G3",
    311: "R_AARCH64_ADR_GOT_PAGE", 312: "R_AARCH64_LD64_GOT_LO12_NC",
}
GOT_RELOC_TYPES = {311, 312}

# arm64 instruction encodings this verifier decodes (no disassembler on the host).
BRK_CFI_KCFI = {0xD4304500: "#0x8228", 0xD4304660: "#0x8233"}   # kCFI's own traps
MOVZ_W3_ONE = 0x52800023          # mov w3, #1  -> UMH_WAIT_EXEC
MOVZ_W3_TWO = 0x52800043          # mov w3, #2  -> UMH_WAIT_PROC (forbidden)
KERNEL_VA_LOW, KERNEL_VA_HIGH = 0xFFFFFF8000000000, 0xFFFFFFFFFFFFFFFF

UMH_WAIT_EXEC = 1

# The exports this module is allowed to need; the C source imports exactly these.
#
# call_usermodehelper is NOT here. Measured on the 5.15 and 6.1 vendor images: there is
# no __ksymtab_call_usermodehelper, so importing it makes the load fail with Unknown
# symbol on those families. It is resolved through kallsyms instead, next to the
# setup/exec pair - the same mechanism this module already uses for everything else.
EXPECTED_IMPORTS = {"register_kprobe", "unregister_kprobe", "_printk"}
# Kept for the report: an image that does export it may still show it as an optional
# import if a future build chooses to link it directly.
OPTIONAL_IMPORTS = {"call_usermodehelper"}
TOLERATED_IMPORTS = {"memset"}    # only if the compiler really emits it
COMPILER_HELPER_PREFIXES = ("__stack_chk", "__aarch64_", "__aeabi_", "__cfi_",
                            "__kcfi_", "__ubsan_", "__asan_", "_GLOBAL_OFFSET_TABLE_")

# The complete set of names the module is allowed to ask kallsyms for. This is the
# design's list (mechanics doc section 7.3, and the four maplesu_sym() calls plus the
# one kprobe symbol_name in module/maplesu_root.c). It is written here, not derived
# from the C file on purpose: adding a runtime lookup must be an explicit decision
# that edits both places, and G5 fails until it is.
LOOKUP_WHITELIST = {
    "kallsyms_lookup_name",            # resolved through the kprobe's own addr
    "selinux_state",                   # .bss object; not in the file-backed symbol list
    "call_usermodehelper_setup",       # step 4 primary: the two-stage handover
    "call_usermodehelper_exec",        # step 4 primary: ditto (not exported either)
    "call_usermodehelper",             # four-argument wrapper: not exported on all
                                       # vendor kernels, so resolved at run time
    "aarch64_insn_patch_text",         # patch primitive (vendor step, off by default)
    "oplus_exe_block_ret_handler",     # vendor anti-root handlers, absent on this build
    "oplus_root_check_post_handler",
    "task_defex_enforce",
    "task_defex_user_exec",
}

# The two runtime symbols that make the handover real on a static-UMH kernel. Their
# *absence* from the module is what G5 treats as "only the four-argument wrapper",
# i.e. a handover that cannot exec on this image (see module/maplesu_root.c).
UMH_PRIMARY_LOOKUPS = ("call_usermodehelper_setup", "call_usermodehelper_exec")
# subprocess_info.path, BTF-confirmed (+0x38): setup() writes its own value there and
# exec() reads it, so a store of our helper pointer at that offset is the fix itself.
UMH_PATH_OFFSET_FALLBACK = 0x38
# The build must record this flag: it emits the mandatory init/exit KCFI prefixes.
KCFI_FLAG = "-fsanitize=kcfi"

GATES = [
    ("G1", "layout"),
    ("G2", "PLT sections"),
    ("G3", "importability"),
    ("G4", "cleanup_module"),
    ("G5", "patch plan"),
    ("G6", "KCFI"),
    ("G7", "struct shim vs BTF"),
]

# --------------------------------------------------------------------------- #
# L2 (maplesu_l2.ko). Design rev-3 sections 6.1 and 3.1-3.7; the gate ids are
# G8-G14, the namespace the Lead fixed together with S26-S40 / O6-O10.
#
# The L2 module is built by the same code path as the root module (same
# CLANG_FLAGS, same link, same G1-G7 where they are module-generic) plus exactly
# one flag, -fno-jump-tables, and seven gate bodies. The artifacts are separate:
# `--module maplesu_l2` never touches maplesu_root.ko, and the L2 .ko is moved
# into place only after every gate passes (fail-closed, see main()).
# --------------------------------------------------------------------------- #

L2_MODULE = "maplesu_l2"
L2_SOURCE = MODULE_DIR / "maplesu_l2.c"                       # design C1-3
L2_GLUE_TEMPLATE = MODULE_DIR / "root_glue_l2.S.in"           # design C1-5/6
L2_MODINFO_DESCRIPTION = "MapleSU L2 window-scoped hook module"
# The one flag that differs from the root build (design C1-7 / review M2). It is
# L2-only on purpose: adding it to CLANG_FLAGS would rebuild the frozen
# maplesu_root.ko, whose bytes are a C0 baseline.
L2_CLANG_FLAGS = ["-fno-jump-tables"]
# design G11 ①: 453 must never be materialised in module text. The number lives
# in the helper (C2) and the contract; it is *forbidden* here.
L2_MAGIC_SCNO = 453
# Imports: the six M4 exports plus the cookie parameter's ops (M6). O6 in
# hook_contract.py asserts this complete set; the builder asserts it too, so
# `--module maplesu_l2` is fail-closed without the other gate runner.
L2_EXPECTED_IMPORTS = {
    "register_kprobe", "register_kretprobe", "unregister_kprobe",
    "unregister_kretprobe", "_printk", "copy_from_kernel_nofault",
    "param_ops_ullong",
}
L2_COOKIE_PARAM = "param_ops_ullong"
# The three unexported names plus the two symbols this module also resolves by
# name (the resolver bootstrap, the SELinux object). G5 asserts that no *other*
# resolvable kernel symbol name is carried in .rodata.
L2_LOOKUP_WHITELIST = {
    "kallsyms_lookup_name",        # resolver bootstrap (kprobe on its own name)
    "__arm64_sys_ni_syscall",      # H1 carrier (runtime lookup, never an import)
    "sys_call_table",              # read one slot at ARM, never written
    "copy_to_user_nofault",        # 32-byte result block (unexported)
    "selinux_state",               # WL_SELINUX target (.bss object, not listed)
    "do_execveat_common",          # probe target, resolved by kprobe by name
}
# The four callback prototypes, byte-for-byte the contract's
# l2.callbacks.prototypes. G8 ④ compares the source against this table, so the
# authoritative text exists once (here) and the contract is its second statement.
L2_PROTOTYPES = (
    "static int  l2_pre_execve(struct kprobe *p, struct pt_regs *regs);",
    "static void l2_post_execve(struct kprobe *p, struct pt_regs *regs, "
    "unsigned long flags);",
    "static int  l2_ret_execve(struct kretprobe_instance *ri, struct pt_regs *regs);",
    "static int  l2_pre_ctl(struct kprobe *p, struct pt_regs *regs);",
)
# callback symbol -> contract kcfiIds key. An unregistered callback symbol is red
# (G8), not silently skipped: a new callback type needs a new row *and* a new TU
# experiment (design 3.3.2, "honest boundary").
L2_CALLBACKS = (
    ("l2_pre_ctl", "preHandler"),
    ("l2_pre_execve", "preHandler"),
    ("l2_post_execve", "postHandler"),
    ("l2_ret_execve", "kretprobeHandler"),
)
# The only functions allowed to contain _printk. Each is a state-transition line
# (design 5.4 allows ready/arm/first-hit/selftest/disarm/cleanup) and each is
# guarded: l2_first_hit by a cmpxchg, the other three by the latch transitions.
# Per-hit logging is what this list makes impossible.
L2_PRINTK_FUNCTIONS = {"l2_first_hit", "l2_print_arm", "l2_print_disarm",
                       "l2_print_selftest"}
# G10: what a callback's call closure may never reach. Allocating, sleeping,
# taking a mutex, copying user memory (the sleeping spelling) or touching policy
# is a panic or a policy change from kprobe context. The *_nofault variants are
# the ones L2 uses and they are not in this list.
L2_FORBIDDEN_CALLS = {
    "msleep": "sleeping in kprobe context",
    "schedule": "sleeping in kprobe context",
    "schedule_timeout": "sleeping in kprobe context",
    "mutex_lock": "may sleep",
    "mutex_unlock": "may sleep",
    "down": "may sleep",
    "down_read": "may sleep",
    "kmalloc": "allocating from atomic context",
    "kzalloc": "allocating from atomic context",
    "kfree": "freeing from atomic context",
    "vmalloc": "allocating from atomic context",
    "copy_from_user": "may fault/sleep; use the *_nofault spelling",
    "copy_to_user": "may fault/sleep; use the *_nofault spelling",
    "printk": "per-hit logging is forbidden (see L2_PRINTK_FUNCTIONS)",
    "_printk": "per-hit logging is forbidden (see L2_PRINTK_FUNCTIONS)",
    "avc_denied": "no policy suppression (H6)",
    "security_setenforce": "no policy setter (H6)",
    "selinux_set_mnt_opts": "no policy setter (H6)",
    "set_memory_rw": "never changes kernel text/rodata permissions",
    "apply_to_page_range": "never changes kernel text/rodata permissions",
    "aarch64_insn_patch_text": "never writes kernel text",
    "text_poke": "never writes kernel text",
}
# O7/O8/O9 mirrored at the gate level: `--module maplesu_l2` must be fail-closed
# on its own, without the other runner. hook_contract.py asserts the same things
# object-wide (S31/S39/O7-O9); the duplication is deliberate.
L2_FORBIDDEN_SYMBOLS = ("set_memory_rw", "apply_to_page_range",
                        "aarch64_insn_patch_text", "text_poke",
                        "copy_to_kernel_nofault", "security_setenforce",
                        "mount", "security_hook_heads", "security_add_hooks",
                        "avc_denied", "selinux_set_mnt_opts")
L2_FORBIDDEN_STRINGS = (b"/dev/df", b"BLKROSET", b"/sys/fs/selinux",
                        b"security_setenforce", b"ksu", b"kernelsu")
# The two write tags, and nothing else (design 3.4.2).
L2_WRITE_TAGS = ("WL_SCRATCH", "WL_SELINUX")

GATES_L2 = GATES + [
    ("G8", "callback ABI"),
    ("G9", "hook lifecycle"),
    ("G10", "atomic-context safety"),
    ("G11", "control channel"),
    ("G12", "no residual arm"),
    ("G13", "zero KCFI check sites"),
    ("G14", "write scope"),
]

# --------------------------------------------------------------------------- #
# L3 (maplesu_l3.ko). ABI v2: the magic prctl gate, entered through a kprobe.
#
# The artifact is separate from both earlier modules and so is its gate
# namespace: `--module maplesu_l3` runs G1-G7 (G5 with an L3 body; the resident-
# root patch plan it encodes does not exist here) plus L3-1..L3-11. Nothing about
# maplesu_root.ko changes, and its bytes stay identical.
#
# ABI v2 (this round; it replaces v1's private option 0x4D535503). The gate now
# rides on the kernel's own `PR_SET_DUMPABLE` (option 4), which carries no
# payload, so a real call has arg3 == arg4 == 0 and can never be mistaken for the
# gate. What makes a call the gate's is the pair:
#
#     option == 4  &&  (arg3 | arg4) != 0        (token = (arg4 << 32) | arg3)
#     arg2 == 0 -> ARM        arg2 == 1 -> SU    owner_uid = arg5
#
# and `arg2 in {0, 1}` is exactly what the native option accepts, so the syscall
# answers 0 for a granted and a denied request alike. That is the ABI's answer to
# "a kprobe cannot rewrite the return value": it does not have to. Success is the
# kernel's own `maplesu_l3: armed owner=<uid> pool=<n>` line (ARM) and the
# caller's `getuid() == 0` (SU), and the callback writes no byte of the probed
# frame (L3-11).
#
# The mechanism changed on 2026-10-31 and that is what most of this block is
# about. The first L3 design replaced `sys_call_table[__NR_prctl]`; the slot is
# plain `.rodata` in the kernel image and the two helpers that could make it
# writable (`set_memory_rw`/`set_memory_ro`) refuse a kernel-image address with
# -EINVAL (measured on this image: `change_memory_common` calls `find_vm_area`,
# and the image is not a vmap area). The module now installs a kprobe on
# `__arm64_sys_prctl` and writes no kernel-image byte at all.
#
# Consequences for this builder, each one a gate:
#   * nothing is derived from `sys_call_table` any more -- registration is by
#     symbol name, so there is no syscall index to get wrong (L3-9);
#   * the callback's KCFI type id is no longer `__arm64_sys_*`'s: the kernel
#     calls `kprobe->pre_handler` through a checked indirect call, so the id must
#     be the one `kprobe_breakpoint_handler` compares. That id is *not* patched
#     into the module: a reference TU compiled with this build's own flags says
#     what clang emits for the kernel's own prototype, the image says which ids
#     that function compares, and the two must meet (L3-1);
#   * the ABI numbers are the controller's (L3-7): option 4, op 0/1, pool 64,
#     owner range [10000, 100000), and the magic is `(arg3 | arg4) != 0`;
#   * the callback may observe but never write: no regs/pt_regs field is stored,
#     so the probed syscall's arguments and its return slot are untouched (L3-11);
#   * the cred source is still decided from the image's `prepare_kernel_cred`
#     entry (`kernel/cred.c:637` warns on a NULL daemon).
#
# Every number the module needs is read out of the image here, never typed into
# the C file.
# --------------------------------------------------------------------------- #

L3_MODULE = "maplesu_l3"
L3_SOURCE = MODULE_DIR / "maplesu_l3.c"
L3_GLUE_TEMPLATE = MODULE_DIR / "l3_glue.S.in"
L3_MODINFO_DESCRIPTION = "MapleSU L3 kernel credential gate"
# ABI v2, stated once here so the gates and the tests read the same numbers. The
# option is PR_SET_DUMPABLE, so its two accepted arg2 values (0/1) are the ops.
L3_ABI = {
    "schema": "maplesu-l3-abi/2",
    "version": 2,
    "option": 4,
    "optionName": "PR_SET_DUMPABLE",
    "opArm": 0,
    "opSu": 1,
    "pool": 64,
    "ownerRange": [10000, 100000],
    "errno": {"EPERM": 1, "EIO": 5, "ENOMEM": 12, "ENOSYS": 38},
    # The magic, as one statement: the option test in the callback plus this.
    # A zero token is never magic, so SU's comparison can never be vacuous.
    "magic": "(arg3 | arg4) != 0",
    "magicMacro": "MAPLESU_L3_MAGIC",
    "tokenMacro": "MAPLESU_L3_TOKEN",
    # The kernel never rewrites the syscall's return value: PR_SET_DUMPABLE for
    # arg2 in {0, 1} returns 0 natively and the callback always returns 0.
    "returnValue": "unchanged (native PR_SET_DUMPABLE returns 0)",
    # The ARM line the helper copies out of the ring buffer. The token is NOT
    # part of it: `suToken` is a userspace JSON field written by the helper from
    # the token it generated, not a kernel format (L3-8 asserts its absence).
    "evidenceGateFormat": "armed owner=%u pool=%u",
    "evidenceTokenJsonField": "suToken",
}
# Imports: exactly the three this module's design names -- `_printk` plus the
# `register_kprobe`/`unregister_kprobe` kprobe pair. No tolerance: a compiler
# helper would be a new dependency nobody reviewed, and `put_cred` (the failure
# path's free) is a kallsyms lookup rather than a fourth import.
L3_EXPECTED_IMPORTS = {"_printk", "register_kprobe", "unregister_kprobe"}
# The function the kprobe is registered on, by name. There is no syscall index
# in this design: the loader resolves the name, the image has to carry it.
L3_PRCTL_SYMBOL = "__arm64_sys_prctl"
# The image function that is preferred as the primary dispatch anchor: it is the
# one every 6.6/6.1/6.12 image carries, and it is where `kprobe_breakpoint_handler`
# calls `p->pre_handler` (and, before 6.1, `p->post_handler`). It is *not* required
# to exist: 6.1 moved the post dispatch into `post_kprobe_handler` and 6.18 has
# neither symbol (the equivalent dispatch points are the aggregators), so the
# anchor list below -- not this name -- is what the derivation iterates over.
L3_KPROBE_ANCHOR = "kprobe_breakpoint_handler"
# The candidate anchor functions per callback (G-1). For every callback the
# builder decodes each present candidate's kCFI compare sites, attributes them by
# the `struct kprobe` member the checked pointer was loaded from, and requires the
# attributed sites to agree on exactly ONE type id. A candidate that is absent from
# the image is recorded as absent, not treated as an error: that is how 6.1's
# `post_kprobe_handler` and 6.18's aggregator-only dispatch are stated.
#
# This table is the *default* for contracts that predate the `l3.callbackAnchors`
# block (6.6.66/6.6.89), so those contracts stay byte-identical; the three families
# that needed G-1 carry the same list explicitly in their contract.
L3_DEFAULT_CALLBACK_ANCHORS = {
    "preHandler": ("aggr_pre_handler", "kprobe_breakpoint_handler"),
    "postHandler": ("aggr_post_handler", "post_kprobe_handler",
                    "kprobe_breakpoint_handler"),
}
# Which `struct kprobe` member each callback is dispatched through, by the BTF
# member name the contract records. The offsets are read from `kprobe.members`
# (BTF-derived), never typed here.
L3_CALLBACK_MEMBERS = {"preHandler": "pre_handler", "postHandler": "post_handler"}
L3_KPROBE_LIST_MEMBER = "list"
# How far before a compare site a load of the checked pointer may sit and still be
# attributed to that callback. 0x80 is the window the contract generator used and
# it covers every real site on the four families (the widest is 0x50).
L3_MEMBER_LOAD_WINDOW = 0x80
# The two ids the *pinned toolchain* derives for the kernel's two callback
# prototypes (measured with the reference TU, recorded independently of any image;
# the contract's `l2.kcfiIds` block carries the same pair for the L2 module). They
# are what the reference TU has to reproduce: a toolchain that hashes the prototype
# differently must fail the build, not ship a surprise. They are NOT the image's
# dispatch ids on 6.12/6.18 -- the derivation is compiler/ABI dependent and those
# two images compare 0xc073bc77 / 0x02bb4397 for the same prototypes. Which id the
# *artifact* carries is the image's dispatch id (L3-1), and the builder writes it
# into the callback's prefix (`patch_kcfi_prefixes`).
L3_CALLBACK_PINS = {"preHandler": 0xA6BE5DD9, "postHandler": 0x6E21B9C7}
# The contract value that opts a family into writing the image's dispatch id into
# `maplesu_l3_pre`'s prefix. Without it the builder fails when the image id and the
# emitted bytes differ, so the design change is never silent.
L3_PREFIX_POLICY_PATCHED = "image-dispatch-id"
L3_PREFIX_POLICY_NONE = "none"
# The exact spelling of the two callback prototypes. `pre` is the one the module
# must use verbatim: clang hashes the spelling it sees, and the kernel compares
# the hash of `int (*)(struct kprobe *, struct pt_regs *)`.
L3_PRE_PROTOTYPE = "int maplesu_l3_pre(struct kprobe *p, struct pt_regs *regs)"
L3_REF_PRE_PROTOTYPE = "int maplesu_l3_ref_pre(struct kprobe *p, struct pt_regs *regs)"
L3_REF_POST_PROTOTYPE = ("void maplesu_l3_ref_post(struct kprobe *p, "
                         "struct pt_regs *regs, unsigned long flags)")
# Every kernel symbol *name* the module may carry in .rodata for a runtime
# lookup. A new lookup is a design change: it has to appear here and in the
# source, and G5's L3 body fails on anything else that resolves in this image.
L3_LOOKUP_WHITELIST = {
    "kallsyms_lookup_name",   # resolver bootstrap (kprobe on its own name)
    "prepare_kernel_cred",    # pool construction, init_module only
    "commit_creds",           # the handover, SU path only
    "put_cred", "__put_cred", # pool release, including inline put_cred kernels
    "__arm64_sys_getuid",     # caller uid without any struct offset
    "init_task",              # cred source when NULL is rejected by the image
    "selinux_state",          # READ ONLY: the ARM gate's enforcing byte
    "avc_denied",              # root-subject-only SELinux return gate
    "groups_alloc", "groups_free", "set_groups", # private credential marker
    "__arm64_sys_prctl",      # the kprobe's target, registered by name
    "vm_map_ram", "vm_unmap_ram", "mutex_lock", "mutex_unlock", "try_module_get",
    "selinux_kernel_status_page", "simple_read_from_buffer", "selinux_status_update_setenforce",
    "copy_from_user_nofault", "copy_to_user_nofault", "sel_read_handle_status",
    "__inittext_begin", "__inittext_end", "_sinittext", "_einittext", # boot SDK init-text bounds
}
# Strings that must not appear in the L3 artifact at all (L3-3): the data-path
# and policy shape of the root module, plus every kernel-image write primitive.
# `selinux_state` is deliberately NOT here any more: this module reads the
# enforcing byte and never writes it, and reading it requires the name.
L3_FORBIDDEN_STRINGS = (b"/data/", b"/system/bin", b"call_usermodehelper",
                        b"security_setenforce", b"sys_call_table",
                        b"set_memory_rw", b"set_memory_ro", b"text_poke")
# The same statement at the source level, on comment-stripped C: a design that
# cannot write the kernel image must not even name the primitives that would.
L3_FORBIDDEN_SOURCE = ("sys_call_table", "set_memory_rw", "set_memory_ro",
                       "text_poke", "aarch64_insn_patch_text")
# The two cred sources: NULL (the standard idiom) and &init_task (used when the
# image's prepare_kernel_cred warns on NULL).
L3_CRED_NULL = 0
L3_CRED_INIT_TASK = 1
GATES_L3 = GATES + [
    ("L3-1", "handler KCFI prefix"),
    ("L3-2", "import set"),
    ("L3-3", "no data-path literal"),
    ("L3-4", "handler is permanent text"),
    ("L3-5", "zero traps, zero check sites"),
    ("L3-6", "cleanup unregisters the kprobe"),
    ("L3-7", "ABI v2 constants and image facts"),
    ("L3-8", "no token in printk"),
    ("L3-9", "registered symbol name"),
    ("L3-10", "ARM enforcing gate"),
    ("L3-11", "prctl callback writes no frame, changes no return"),
    ("L3-12", "root-subject SELinux data path"),
]


class BuildError(RuntimeError):
    """A condition that must stop the build with a message a human can act on."""


class GateFailure(RuntimeError):
    """A gate could not be evaluated at all (missing input, unparsable image)."""


# --------------------------------------------------------------------------- #
# Small utilities.
# --------------------------------------------------------------------------- #

def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def sha256_file(path: Path) -> str:
    return sha256_bytes(Path(path).read_bytes())


def rel(path) -> str:
    """Project-relative POSIX path when possible, else absolute POSIX."""
    path = Path(path)
    try:
        relative = path.resolve().relative_to(PROJECT_ROOT)
        return relative.as_posix() if relative.as_posix() != "." else path.resolve().name
    except (ValueError, OSError):
        return path.as_posix()


class Log:
    """Collect the transcript, echo it, and be able to save it on any exit path.

    Lines marked `detail=True` always reach the transcript but only reach the console
    with --verbose, so the default run reads as "seven gates PASS + the import set"
    while build/verify-build.txt keeps every sub-check.
    """

    def __init__(self, echo: bool = True, verbose: bool = False):
        self.lines: list[str] = []
        self.echo = echo
        self.verbose = verbose

    def __call__(self, text: str = "", detail: bool = False) -> None:
        self.lines.append(text)
        if self.echo and (self.verbose or not detail):
            print(text)

    def save(self, path: Path) -> None:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text("\n".join(self.lines) + "\n", encoding="utf-8")


# --------------------------------------------------------------------------- #
# ELF64 relocatable reader (enough for every loader-visible fact).
# --------------------------------------------------------------------------- #

class Elf:
    def __init__(self, path: Path):
        self.path = Path(path)
        self.raw = self.path.read_bytes()
        if self.raw[:4] != b"\x7fELF":
            raise BuildError("%s is not an ELF file" % rel(self.path))
        if self.raw[4] != 2 or self.raw[5] != 1:
            raise BuildError("%s is not ELF64 little-endian" % rel(self.path))
        (self.e_type, self.e_machine, _version, _entry, _phoff, self.e_shoff,
         _flags, self.e_ehsize, _phentsize, _phnum, self.e_shentsize,
         self.e_shnum, self.e_shstrndx) = struct.unpack_from("<HHIQQQIHHHHHH",
                                                            self.raw, 16)
        self.sections = [self._section(i) for i in range(self.e_shnum)]
        if self.e_shnum and self.e_shoff + self.e_shnum * 64 > len(self.raw):
            raise BuildError("%s: section header table runs past EOF"
                             % rel(self.path))
        if self.e_shnum:
            names = self.sections[self.e_shstrndx]
            table = self.raw[names["offset"]: names["offset"] + names["size"]]
            for section in self.sections:
                end = table.find(b"\x00", section["name_off"])
                section["name"] = table[section["name_off"]:end].decode(
                    "utf-8", "replace")
        self.symbols = self._symbols()

    def _section(self, index: int) -> dict:
        base = self.e_shoff + index * 64
        (name_off, kind, flags, addr, offset, size, link, info, align,
         entsize) = struct.unpack_from("<IIQQQQIIQQ", self.raw, base)
        return {"index": index, "name_off": name_off, "type": kind, "flags": flags,
                "addr": addr, "offset": offset, "size": size, "link": link,
                "info": info, "align": align, "entsize": entsize, "name": ""}

    def _symbols(self) -> list:
        table = next((s for s in self.sections if s["type"] == SHT_SYMTAB), None)
        if table is None:
            return []
        strings = self.sections[table["link"]]
        strtab = self.raw[strings["offset"]: strings["offset"] + strings["size"]]
        out = []
        for i in range(table["size"] // 24):
            base = table["offset"] + i * 24
            name_off, info, other, shndx, value, size = struct.unpack_from(
                "<IBBHQQ", self.raw, base)
            end = strtab.find(b"\x00", name_off)
            out.append({"name": strtab[name_off:end].decode("utf-8", "replace"),
                        "info": info, "bind": info >> 4, "type": info & 0xF,
                        "shndx": shndx, "value": value, "size": size})
        return out

    # -- lookups ----------------------------------------------------------- #
    def sections_named(self, name: str) -> list:
        return [s for s in self.sections if s["name"] == name]

    def section(self, name: str) -> dict | None:
        return next((s for s in self.sections if s["name"] == name), None)

    def section_bytes(self, name_or_section) -> bytes:
        section = (name_or_section if isinstance(name_or_section, dict)
                   else self.section(name_or_section))
        if section is None:
            return b""
        return self.raw[section["offset"]: section["offset"] + section["size"]]

    def symbol(self, name: str) -> dict | None:
        return next((s for s in self.symbols if s["name"] == name), None)

    def defined_symbols(self) -> list:
        return [s for s in self.symbols if s["shndx"] != 0 and s["name"]]

    def imports(self) -> list:
        return sorted({s["name"] for s in self.symbols if s["shndx"] == 0 and s["name"]})

    def symbol_file_offset(self, symbol: dict) -> int | None:
        """File offset of a defined symbol's value (section offset + value)."""
        if symbol["shndx"] == 0 or symbol["shndx"] >= len(self.sections):
            return None
        return self.sections[symbol["shndx"]]["offset"] + symbol["value"]

    def symbol_section(self, symbol: dict) -> dict | None:
        if symbol["shndx"] == 0 or symbol["shndx"] >= len(self.sections):
            return None
        return self.sections[symbol["shndx"]]

    def relocations(self, section_name: str) -> list:
        target = self.section(section_name)
        if target is None:
            return []
        return self._rela_for(target["index"])

    def _rela_for(self, target_index: int) -> list:
        rela = next((s for s in self.sections
                     if s["type"] == SHT_RELA and s["info"] == target_index), None)
        if rela is None:
            return []
        out = []
        for i in range(rela["size"] // 24):
            base = rela["offset"] + i * 24
            offset, info, addend = struct.unpack_from("<QQq", self.raw, base)
            sym_index = info >> 32
            sym = self.symbols[sym_index] if sym_index < len(self.symbols) else None
            out.append({"offset": offset, "type": info & 0xFFFFFFFF,
                        "type_name": ARM64_MODULE_RELOCS.get(info & 0xFFFFFFFF,
                                                            "UNKNOWN"),
                        "symbol": sym["name"] if sym else "",
                        "symbol_index": sym_index, "addend": addend,
                        "section": rela["name"]})
        return out

    def all_relocations(self) -> dict:
        out = {}
        for section in self.sections:
            if section["type"] == SHT_RELA:
                target = (self.sections[section["info"]]
                          if section["info"] < len(self.sections) else None)
                if target is not None:
                    out[target["name"]] = self._rela_for(target["index"])
        return out

    def section_table(self) -> list:
        table = []
        for s in self.sections:
            table.append({
                "index": s["index"], "name": s["name"],
                "type": SECTION_TYPE_NAMES.get(s["type"], "0x%x" % s["type"]),
                "typeValue": s["type"], "flags": "0x%x" % s["flags"],
                "alloc": bool(s["flags"] & SHF_ALLOC),
                "exec": bool(s["flags"] & SHF_EXECINSTR),
                "write": bool(s["flags"] & SHF_WRITE),
                "addr": "0x%x" % s["addr"], "offset": s["offset"],
                "size": s["size"], "align": s["align"],
            })
        return table


# --------------------------------------------------------------------------- #
# BTF: the image's own type information, parsed the way kernel/bpf/btf.c encodes it.
# (Prior art: .analysis/ir-dev-20261028/btf_struct.py; kept inline so this script has
# no dependency on a scratch analysis directory.)
# --------------------------------------------------------------------------- #

BTF_MAGIC = b"\x9f\xeb\x01\x00"
BTF_STRUCT, BTF_UNION = 4, 5

BTF_SKIP = {   # kind -> extra bytes after the 12-byte header, for the kinds we meet
    1: lambda vlen: 4,            # INT
    3: lambda vlen: 12,           # ARRAY
    6: lambda vlen: 8 * vlen,     # ENUM
    13: lambda vlen: 8 * vlen,    # FUNC_PROTO
    14: lambda vlen: 4,           # VAR
    15: lambda vlen: 12 * vlen,   # DATASEC
    17: lambda vlen: 4,           # DECL_TAG
    19: lambda vlen: 12 * vlen,   # ENUM64
}


class Btf:
    def __init__(self, image: bytes, magic_offset: int | None = None):
        offset = magic_offset if magic_offset is not None else image.find(BTF_MAGIC)
        if offset is None or offset < 0 or image[offset:offset + 4] != BTF_MAGIC:
            raise GateFailure("image carries no BTF blob (magic 9f eb 01 00 not found)")
        (_magic, _version, _flags, hdr_len, type_off, type_len, str_off,
         str_len) = struct.unpack_from("<HBBIIIII", image, offset)
        base = offset + hdr_len
        self.blob_offset = offset
        types = image[base + type_off: base + type_off + type_len]
        self.strings = image[base + str_off: base + str_off + str_len]
        self.types = self._parse(types)

    def _name(self, offset: int) -> str:
        if offset == 0:
            return ""
        end = self.strings.find(b"\x00", offset)
        return self.strings[offset:end].decode("utf-8", "replace")

    def _parse(self, types: bytes) -> list:
        out = [None]
        cursor = 0
        while cursor < len(types):
            name_off, info, size_type = struct.unpack_from("<III", types, cursor)
            kind, vlen, kflag = (info >> 24) & 0x1F, info & 0xFFFF, info >> 31
            record = {"name": self._name(name_off), "kind": kind, "vlen": vlen,
                      "size": size_type, "members": [], "bits": bool(kflag)}
            cursor += 12
            if kind in (BTF_STRUCT, BTF_UNION):
                for _ in range(vlen):
                    m_name, m_type, m_off = struct.unpack_from("<III", types, cursor)
                    cursor += 12
                    bit = (m_off & 0xFFFFFF) if kflag else m_off
                    record["members"].append({"name": self._name(m_name),
                                              "type": m_type, "byte": bit // 8,
                                              "bit": bit, "bitfield_bits": m_off >> 24 if kflag else 0})
            elif kind == 1:
                record["int_encoding"] = struct.unpack_from("<I", types, cursor)[0]
                cursor += 4
            elif kind == 3:
                record["array_type"], _index, record["array_count"] = struct.unpack_from("<III", types, cursor)
                cursor += 12
            elif kind == 13:  # FUNC_PROTO: retain types as well as parameter count.
                record["params"] = []
                for _ in range(vlen):
                    p_name, p_type = struct.unpack_from("<II", types, cursor)
                    cursor += 8
                    record["params"].append({"name": self._name(p_name), "type": p_type})
            elif kind in BTF_SKIP:
                cursor += BTF_SKIP[kind](vlen)
            out.append(record)
        return out

    def structs(self, name: str) -> list:
        return [(i, t) for i, t in enumerate(self.types)
                if i and t and t["kind"] == BTF_STRUCT and t["name"] == name]

    def struct(self, name: str) -> dict:
        hits = self.structs(name)
        if not hits:
            raise GateFailure("image BTF has no struct %s" % name)
        return hits[0][1]

    def member(self, struct: dict, member: str) -> dict:
        for entry in struct["members"]:
            if entry["name"] == member:
                return entry
        raise GateFailure("struct %s has no member %s" % (struct["name"], member))

    def field_layout(self, name: str, path: str) -> tuple[int, int]:
        """Resolve effective field bits using the runtime kernel_layout contract."""
        def plain(type_id):
            for _ in range(64):
                if not 0 < type_id < len(self.types) or not self.types[type_id]:
                    raise GateFailure("BTF layout type ID")
                value = self.types[type_id]
                if value["kind"] not in (8, 9, 10, 11, 18):
                    return value
                type_id = value["size"]
            raise GateFailure("BTF layout modifier cycle")

        def size(type_id, depth=0):
            if depth >= 32:
                raise GateFailure("BTF layout size cycle")
            value = plain(type_id)
            if value["kind"] == 2:
                return 8
            if value["kind"] == 3:
                result = size(value["array_type"], depth + 1) * value["array_count"]
                if result > 0xffffffff:
                    raise GateFailure("BTF layout array size")
                return result
            if value["kind"] in (1, 4, 5, 6, 16, 19):
                return value["size"]
            raise GateFailure("BTF layout field type")

        def field(type_id, wanted, prefix=0, depth=0):
            if depth >= 16:
                raise GateFailure("BTF layout member cycle")
            if "." in wanted:
                first, rest = wanted.split(".", 1)
                if not first or not rest:
                    raise GateFailure("BTF layout member path")
                parent = field(type_id, first, prefix, depth)
                if parent is None:
                    return None
                offset, width, parent_id = parent
                if offset & 7 or width != size(parent_id) * 8:
                    raise GateFailure("BTF layout parent type")
                return field(parent_id, rest, offset, depth + 1)
            owner = plain(type_id)
            if owner["kind"] not in (4, 5):
                raise GateFailure("BTF layout member owner")
            found = []
            for entry in owner["members"]:
                offset = entry.get("bit", entry["byte"] * 8)
                width = entry.get("bitfield_bits", 0)
                if offset < 0 or prefix + offset > 0xffffffff:
                    raise GateFailure("BTF layout member offset")
                member_id = entry["type"]
                value = plain(member_id)
                if not entry["name"]:
                    if value["kind"] in (4, 5):
                        nested = field(member_id, wanted, prefix + offset, depth + 1)
                        if nested is not None:
                            if offset + size(member_id) * 8 > owner["size"] * 8:
                                raise GateFailure("BTF layout anonymous owner bounds")
                            found.append(nested)
                elif entry["name"] == wanted:
                    storage = size(member_id) * 8
                    if storage > 0xffffffff:
                        raise GateFailure("BTF layout member size")
                    effective = width or storage
                    if value["kind"] == 1:
                        encoding = value["int_encoding"]
                        bits, internal = encoding & 0xff, encoding >> 16 & 0xff
                        if not bits or bits + internal > min(128, storage):
                            raise GateFailure("BTF layout integer bits")
                        if owner.get("bits", False):
                            if internal or bits < 8 or bits & (bits - 1) or width > bits:
                                raise GateFailure("BTF layout modern integer bits")
                            effective = width or bits
                        else:
                            offset += internal
                            effective = bits
                    elif width and (value["kind"] not in (6, 19) or width > storage):
                        raise GateFailure("BTF layout field bits")
                    if prefix + offset > 0xffffffff or offset + effective > owner["size"] * 8:
                        raise GateFailure("BTF layout member bounds")
                    found.append((prefix + offset, effective, member_id))
                if len(found) > 1:
                    raise GateFailure("BTF layout ambiguous member: " + wanted)
            return found[0] if found else None

        selected = None
        for type_id, owner in self.structs(name):
            result = field(type_id, path)
            if result is None or not owner["size"]:
                raise GateFailure("BTF layout missing member: " + name + "." + path)
            offset, width, _ = result
            if offset + width > owner["size"] * 8:
                raise GateFailure("BTF layout owner bounds")
            actual = (owner["size"], offset, width)
            if selected is not None and selected != actual:
                raise GateFailure("BTF layout ambiguous owner: " + name)
            selected = actual
        if selected is None:
            raise GateFailure("BTF layout missing owner: " + name)
        return selected[1:]


# --------------------------------------------------------------------------- #
# Symbol table (kallsyms-style TSV: "#offset\ttype\tname", CRLF tolerated).
# --------------------------------------------------------------------------- #

class SymbolTable:
    def __init__(self, path: Path):
        self.path = Path(path)
        self.rows: dict[str, list] = {}
        self.ordered: list[tuple] = []
        for line in self.path.read_text(encoding="utf-8", errors="replace").splitlines():
            parts = line.rstrip("\r\n").split("\t")
            if len(parts) != 3 or parts[0].startswith("#"):
                continue
            try:
                address = int(parts[0])
            except ValueError:
                continue
            self.rows.setdefault(parts[2], []).append((address, parts[1]))
            self.ordered.append((address, parts[1], parts[2]))
        self.ordered.sort()
        self._addresses = [row[0] for row in self.ordered]

    def address(self, name: str) -> int | None:
        rows = self.rows.get(name)
        return rows[0][0] if rows else None

    def next_address(self, address: int, limit: int = 0x1000) -> int:
        lo, hi = 0, len(self._addresses)
        while lo < hi:
            mid = (lo + hi) // 2
            if self._addresses[mid] <= address:
                lo = mid + 1
            else:
                hi = mid
        if lo >= len(self._addresses):
            return address + limit
        return min(self._addresses[lo], address + limit)

    def kind(self, name: str) -> str | None:
        rows = self.rows.get(name)
        return rows[0][1] if rows else None


# --------------------------------------------------------------------------- #
# AArch64 decoding: just the instructions the gates make claims about.
# --------------------------------------------------------------------------- #

def decode_movk(insn: int):
    """MOVK <Wd>, #imm16, lsl #hw*16  -> (rd, imm16, hw) or None."""
    if (insn & 0x7F800000) == 0x72800000:
        return insn & 0x1F, (insn >> 5) & 0xFFFF, (insn >> 21) & 0x3
    return None


def decode_movz(insn: int):
    """MOVZ <Wd>, #imm16, lsl #hw*16  -> (rd, imm16, hw) or None."""
    if (insn & 0x7F800000) == 0x52800000:
        return insn & 0x1F, (insn >> 5) & 0xFFFF, (insn >> 21) & 0x3
    return None


def decode_cmp_reg(insn: int):
    """CMP <Wn>, <Wm> (== SUBS WZR, Wn, Wm) -> (rn, rm) or None."""
    if (insn & 0xFF200000) == 0x6B000000 and (insn & 0x1F) == 31:
        return (insn >> 5) & 0x1F, (insn >> 16) & 0x1F
    return None


def is_blr(insn: int) -> bool:
    return (insn & 0xFFFFFC1F) == 0xD63F0000


def decode_brk(insn: int):
    if (insn & 0xFFE0001F) == 0xD4200000:
        return (insn >> 5) & 0xFFFF
    return None


def decode_str_uoffset(insn: int):
    """STR <Xt>, [<Xn|SP>, #imm] (64-bit, unsigned scaled offset) -> (rt, imm)."""
    if (insn & 0xFFC00000) != 0xF9000000:
        return None
    return insn & 0x1F, ((insn >> 10) & 0xFFF) * 8


def decode_ldr_uoffset(insn: int):
    """LDR <Xt>, [<Xn|SP>, #imm] (64-bit, unsigned scaled) -> (rt, rn, imm).

    This is the form that loads a 64-bit pointer out of a kernel struct:
    `ldr x8, [x20, #0x40]` is the pre-handler dispatch's own read of
    `kp->pre_handler`. Only the 64-bit size is accepted: a 32-bit load cannot be
    the callback pointer, and accepting it would let a `ldr w8` masquerade as one.
    """
    if (insn & 0xFFC00000) != 0xF9400000:
        return None
    return insn & 0x1F, (insn >> 5) & 0x1F, ((insn >> 10) & 0xFFF) * 8


def decode_ldur_w(insn: int):
    """LDUR <Wt>, [<Xn>, #simm9] -> (rt, rn, imm).

    kCFI's type-id load is `ldur w16, [x8, #-4]` (the 4 bytes in front of the
    function); the base register is the callback pointer the compare is about.
    """
    if (insn & 0xFFE00C00) != 0xB8400000:
        return None
    immediate = (insn >> 12) & 0x1FF
    if immediate & 0x100:
        immediate -= 0x200
    return insn & 0x1F, (insn >> 5) & 0x1F, immediate


def nocfi_covers(source: str, function: str) -> bool:
    """True when MAPLESU_NOCFI is attached to `function`'s own declaration.

    The attribute is the *intent* (the binary cannot show which function it was
    written on), so it is checked in the source: comments are stripped, then each
    occurrence is followed to the end of its declaration (`;` or the body's `{`).
    """
    text = re.sub(r"/\*.*?\*/", " ", source, flags=re.S)
    text = re.sub(r"//[^\n]*", " ", text)
    for match in re.finditer(r"MAPLESU_NOCFI", text):
        window = text[match.end(): match.end() + 240]
        stops = [index for index in (window.find(";"), window.find("{"))
                 if index >= 0]
        cut = min(stops) if stops else len(window)
        if re.search(r"\b%s\s*\(" % re.escape(function), window[:cut]):
            return True
    return False


def str_offset_sites(elf: "Elf", offset: int) -> list:
    """Every `str xN, [xM, #offset]` in module text, as `section+0x..`.

    Used by G5 to find the subprocess_info.path store: on this kernel the whole fix
    for CONFIG_STATIC_USERMODEHELPER_PATH="" is a single 64-bit store at that offset
    after call_usermodehelper_setup() returned.
    """
    hits = []
    for section in elf.sections:
        if not section["flags"] & SHF_EXECINSTR:
            continue
        blob = elf.section_bytes(section)
        for index in range(len(blob) // 4):
            decoded = decode_str_uoffset(read_uint32(blob, index * 4))
            if decoded is not None and decoded[1] == offset:
                hits.append("%s+0x%x" % (section["name"], index * 4))
    return hits


def kcfi_type_ids(code: bytes, require_blr: bool = True) -> list:
    """Every 32-bit kCFI type id built just before a compare in `code`.

    clang's kCFI check is exactly `movk wT,#lo ; movk wT,#hi,lsl#16 ; cmp wC,wT`
    followed by `b.eq` and, on the failure path, `brk`. Requiring a BLR within the
    next four instructions keeps data constants (which clang builds with
    movz+movk, never movk+movk) out of the result.
    """
    words = [struct.unpack_from("<I", code, i * 4)[0] for i in range(len(code) // 4)]
    out = []
    for i in range(len(words) - 2):
        low = decode_movk(words[i])
        if not low or low[2] != 0:
            continue
        high = decode_movk(words[i + 1])
        if not high or high[2] != 1 or high[0] != low[0]:
            continue
        cmp = decode_cmp_reg(words[i + 2])
        if not cmp or cmp[1] != low[0]:
            continue
        tail = words[i + 3: i + 7]
        if require_blr and not any(is_blr(word) for word in tail):
            continue
        out.append({"instruction": i, "typeId": (high[1] << 16) | low[1]})
    return out


def first_kcfi_id(image: bytes, start: int, end: int) -> dict | None:
    hits = kcfi_type_ids(image[start:end])
    if not hits:
        return None
    return {"offset": start + hits[0]["instruction"] * 4,
            "typeId": hits[0]["typeId"]}


# --------------------------------------------------------------------------- #
# Toolchain.
# --------------------------------------------------------------------------- #

def find_tool(ndk: Path, name: str) -> Path:
    candidates = [
        ndk / "toolchains" / "llvm" / "prebuilt" / "windows-x86_64" / "bin" / (name + ".exe"),
        ndk / "toolchains" / "llvm" / "prebuilt" / "linux-x86_64" / "bin" / name,
        ndk / "toolchains" / "llvm" / "prebuilt" / "darwin-x86_64" / "bin" / name,
    ]
    for candidate in candidates:
        if candidate.is_file():
            return candidate
    raise BuildError("could not find %s under %s (set --ndk-dir)" % (name, ndk))


def tool_version(executable: Path) -> str:
    try:
        result = subprocess.run([str(executable), "--version"], capture_output=True,
                                text=True, errors="replace")
    except OSError as error:
        return "unavailable (%s)" % error
    lines = (result.stdout or result.stderr).strip().splitlines()
    return lines[0].strip() if lines else "unknown"


class Runner:
    """Run a command, keep it in the build record, fail with the real diagnostics."""

    def __init__(self):
        self.commands: list[dict] = []

    def __call__(self, argv: list, cwd: Path | None = None) -> dict:
        argv = [str(part) for part in argv]
        result = subprocess.run(argv, cwd=str(cwd) if cwd else None,
                                capture_output=True, text=True, errors="replace")
        record = {"argv": argv, "cwd": rel(cwd) if cwd else None,
                  "exit": result.returncode,
                  "stdout": (result.stdout or "").strip(),
                  "stderr": (result.stderr or "").strip()}
        self.commands.append(record)
        if result.returncode != 0:
            raise BuildError("command failed (exit %d): %s\n%s\n%s"
                             % (result.returncode, " ".join(argv),
                                record["stdout"], record["stderr"]))
        return record


# --------------------------------------------------------------------------- #
# Sources: the glue is copied verbatim, .modinfo is generated.
# --------------------------------------------------------------------------- #

def load_contract(path: Path) -> dict:
    contract = json.loads(path.read_text(encoding="utf-8"))
    for key in ("module", "license", "vermagic", "vermagicSuffix", "imagePath",
                "imageSha256", "thisModule", "kcfiInitTypeId", "modinfo",
                "symbolsPath", "helperPathPolicy", "handover"):
        if key not in contract:
            raise BuildError("contract %s has no %r" % (rel(path), key))
    return contract


def resolve_contract(explicit: str | None) -> Path:
    if explicit:
        path = Path(explicit)
        if path.is_file():
            return path
        # `--contract 6.6.89` is how the acceptance command spells it: a version
        # prefix of the contract's file name. Accept it only when it names exactly
        # one contract, so a typo is an error and not a silent pick. An existing
        # path is returned untouched, which is what every earlier command used.
        # (Path.suffix cannot be used here: "6.6.89" parses as name "6" + suffix
        # ".6.89", which is exactly the spelling that has to work.)
        matches = sorted(CONTRACTS_DIR.glob(path.name + "*.json"))
        if len(matches) == 1:
            return matches[0]
        if len(matches) > 1:
            raise BuildError(
                "--contract %r matches %d contracts; name one:\n  %s"
                % (explicit, len(matches),
                   "\n  ".join(rel(candidate) for candidate in matches)))
        return path
    found = sorted(CONTRACTS_DIR.glob("*.json"))
    if len(found) == 1:
        return found[0]
    if not found:
        raise BuildError("no contracts/*.json found under %s" % rel(CONTRACTS_DIR))
    raise BuildError("several contracts match; pass --contract:\n  "
                     + "\n  ".join(rel(path) for path in found))


def recorded_cflags(out_dir: Path) -> list | None:
    """The compiler flags the build recorded beside the artifact, if there are any.

    G6 asserts that the recorded flags contain -fsanitize=kcfi. A fresh build passes
    its in-memory list; --verify-only has none, so it reads the one the last build
    wrote (`cflags`/`compileFlags` in build.json). Looking in the committed build
    directory as a fallback keeps `--verify-only` meaningful on a copied .ko, which is
    what tests/test_root_build.py does.
    """
    for candidate in (Path(out_dir) / "build.json", DEFAULT_OUT / "build.json"):
        if not candidate.is_file():
            continue
        try:
            record = json.loads(candidate.read_text(encoding="utf-8"))
        except json.JSONDecodeError:
            continue
        flags = record.get("cflags") or record.get("compileFlags")
        if isinstance(flags, list) and flags:
            return list(flags)
    return None


def render_glue(contract: dict, out: Path) -> str:
    """Copy root_glue.S.in with its one substitution token filled in.

    The template is hand-written and has *one* token (`@ROOT_VERMAGIC@`, inside its
    `.modinfo` section) which is deliberate: every other byte is a struct whose
    members are only meaningful at fixed offsets, so a silent substitution would be
    worse than a failed build. The assertions below make a contract/template
    divergence a build error instead of a load-time surprise, and the token whitelist
    means a typo like `@VERMAGIC@` cannot reach the assembler.

    `.modinfo` lives in the template (not in a generated side-car) so that the tags
    travel with the struct they describe and a from-source build always carries
    `vermagic=`. The loader rejects a module with two `.modinfo` sections, so the
    template must carry exactly one and the builder must emit none.
    """
    template = (MODULE_DIR / "root_glue.S.in").read_text(encoding="utf-8")
    layout = contract["thisModule"]
    module_name = contract["module"]

    # The layout is contract-driven now, so this checks coherence rather than one
    # family's literals: ordered, aligned, and large enough to hold the exit pointer.
    name_off, init_off = layout["nameOffset"], layout["initOffset"]
    exit_off, size = layout["exitOffset"], layout["size"]
    if not (0x10 <= name_off < init_off < exit_off < size - 8):
        raise BuildError(
            "contract thisModule is not a coherent struct module layout: "
            "name=0x%x init=0x%x exit=0x%x size=0x%x"
            % (name_off, init_off, exit_off, size))
    if init_off % 8 or exit_off % 8 or size % 8:
        raise BuildError(
            "contract thisModule offsets must be 8-byte aligned on arm64: "
            "init=0x%x exit=0x%x size=0x%x" % (init_off, exit_off, size))
    if len(module_name) + 1 != 13:
        raise BuildError(
            "module/root_glue.S.in reserves exactly 13 bytes for the module name "
            "(0x38 - 0x18 - 13 in the name[56] field), but %r needs %d; regenerate "
            "the template" % (module_name, len(module_name) + 1))

    required_fragments = [
        '.section .gnu.linkonce.this_module,"aw",@progbits',
        ".zero\t@NAME_OFF@",
        '.asciz\t"%s"' % module_name,
        ".zero\t@INIT_OFF@ - (. - __this_module)",
        ".quad\tinit_module",
        ".zero\t@EXIT_OFF@ - (. - __this_module)",
        ".quad\tcleanup_module",         # this_module.exit stays the restore point
        '.section .modinfo,"a",@progbits',
        '.asciz\t"license=GPL"',
        '.asciz\t"name=%s"' % module_name,
        '.asciz\t"depends="',
        '.asciz\t"vermagic=@ROOT_VERMAGIC@"',
        '.section .plt,"ax",@progbits',
        '.section .init.plt,"ax",@progbits',
        '.section .text.ftrace_trampoline,"ax",@progbits',
        '.section __versions,"a",@progbits',
    ]
    for fragment in required_fragments:
        if fragment not in template:
            raise BuildError("module/root_glue.S.in lost %r" % fragment)
    if template.count(".section .modinfo") != 1:
        raise BuildError("module/root_glue.S.in must carry exactly one .modinfo "
                         "section (found %d); the loader rejects a module with two"
                         % template.count(".section .modinfo"))
    description = 'description=' + MODINFO_DESCRIPTION
    if description not in template:
        raise BuildError("module/root_glue.S.in does not carry %r; the .modinfo "
                         "description and MODINFO_DESCRIPTION must agree"
                         % description)
    tokens = set(re.findall(r"@[A-Z0-9_]{2,}@", template))
    layout_tokens = {"@NAME_OFF@", "@INIT_OFF@", "@EXIT_OFF@", "@SIZE@"}
    unknown = tokens - layout_tokens - {"@ROOT_VERMAGIC@"}
    if unknown:
        raise BuildError("module/root_glue.S.in carries unknown substitution token(s) "
                         "%s; only @ROOT_VERMAGIC@ is defined" % sorted(unknown))
    if contract["modinfo"].get("license", contract["license"]) != "GPL":
        raise BuildError("module/root_glue.S.in hard-codes license=GPL but the contract "
                         "says %r" % contract["modinfo"].get("license"))

    rendered = template.replace("@ROOT_VERMAGIC@", contract["vermagic"])
    for token, key in (("@NAME_OFF@", "nameOffset"), ("@INIT_OFF@", "initOffset"),
                       ("@EXIT_OFF@", "exitOffset"), ("@SIZE@", "size")):
        rendered = rendered.replace(token, "0x%x" % layout[key])
    if re.search(r"@[A-Z0-9_]{2,}@", rendered):
        raise BuildError("the rendered glue still contains a substitution token: a "
                         "token outside the whitelist would reach the assembler")
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(rendered, encoding="utf-8")
    return sha256_bytes(rendered.encode("utf-8"))


# --------------------------------------------------------------------------- #
# Build.
# --------------------------------------------------------------------------- #

def render_l2_glue(contract: dict, out: Path) -> str:
    """Copy root_glue_l2.S.in with its one substitution token filled in.

    Same discipline as render_glue(): the template is hand-written, has exactly
    one token (`@L2_VERMAGIC@`), and every fixed offset it embeds is asserted
    against the contract here, so a release whose `struct module` or
    `struct kernel_param` moved fails the build instead of the load. The `__param`
    row is the part that has no root counterpart, so its five offsets are checked
    against `structOffsets.kernelParam` field by field.
    """
    template = L2_GLUE_TEMPLATE.read_text(encoding="utf-8")
    layout = contract["l2"]["module"]["thisModule"]
    module_name = contract["l2"]["module"]["name"]

    expected = {"size": 0x600, "nameOffset": 0x18, "initOffset": 0x188,
                "exitOffset": 0x5B8}
    for key, value in expected.items():
        if layout[key] != value:
            raise BuildError(
                "contract l2.module.thisModule.%s = 0x%x, but %s hard-codes 0x%x; "
                "the template must be regenerated for this release"
                % (key, layout[key], rel(L2_GLUE_TEMPLATE), value))
    if len(module_name) + 1 != 11:
        raise BuildError(
            "%s reserves exactly 11 bytes for the module name (0x38 - 0x18 - 11 in "
            "the name[56] field), but %r needs %d; regenerate the template"
            % (rel(L2_GLUE_TEMPLATE), module_name, len(module_name) + 1))

    param = contract["structOffsets"]["kernelParam"]
    for key, value in (("name", 0x0), ("mod", 0x8), ("ops", 0x10), ("perm", 0x18),
                       ("arg", 0x20), ("size", 0x28)):
        if param[key] != value:
            raise BuildError(
                "contract structOffsets.kernelParam.%s = %r, but %s writes the "
                "0x28-byte row C1-6 measured (0x0/0x8/0x10/0x18/0x20/0x28); the "
                "__param row must be re-derived for this release"
                % (key, param[key], rel(L2_GLUE_TEMPLATE)))

    required_fragments = [
        '.section .gnu.linkonce.this_module,"aw",@progbits',
        ".zero\t0x18",
        '.asciz\t"%s"' % module_name,
        ".zero\t0x188 - 0x38",
        ".quad\tinit_module",
        ".zero\t0x5b8 - 0x190",
        ".quad\tcleanup_module",
        '.section .modinfo,"a",@progbits',
        '.asciz\t"license=GPL"',
        '.asciz\t"name=%s"' % module_name,
        '.asciz\t"depends="',
        '.asciz\t"vermagic=@L2_VERMAGIC@"',
        '.asciz\t"parm=cookie:ullong"',
        '.section .plt,"ax",@progbits',
        '.section .init.plt,"ax",@progbits',
        '.section .text.ftrace_trampoline,"ax",@progbits',
        '.section __versions,"a",@progbits',
        '.section .bss.l2_cookie,"aw",@nobits',
        ".globl\tl2_load_cookie",
        '.section __param,"aw",@progbits',
        ".quad\tl2_cookie_name",
        ".quad\t__this_module",
        ".quad\tparam_ops_ullong",
        ".hword\t0",
        ".quad\tl2_load_cookie",
        'l2_cookie_name:',
        '.asciz\t"cookie"',
    ]
    missing = [fragment for fragment in required_fragments if fragment not in template]
    if missing:
        raise BuildError("%s lost %r" % (rel(L2_GLUE_TEMPLATE), missing[0]))
    if template.count(".section .modinfo") != 1:
        raise BuildError("%s must carry exactly one .modinfo section (found %d); the "
                         "loader rejects a module with two"
                         % (rel(L2_GLUE_TEMPLATE), template.count(".section .modinfo")))
    if template.count('.section __param,"aw",@progbits') != 1:
        raise BuildError("%s must carry exactly one __param row (the kernel reads the "
                         "section as an array of struct kernel_param)"
                         % rel(L2_GLUE_TEMPLATE))
    description = "description=" + L2_MODINFO_DESCRIPTION
    if description not in template:
        raise BuildError("%s does not carry %r; the .modinfo description and "
                         "L2_MODINFO_DESCRIPTION must agree"
                         % (rel(L2_GLUE_TEMPLATE), description))
    tokens = set(re.findall(r"@[A-Z0-9_]{2,}@", template))
    unknown = tokens - {"@L2_VERMAGIC@"}
    if unknown:
        raise BuildError("%s carries unknown substitution token(s) %s; only "
                         "@L2_VERMAGIC@ is defined" % (rel(L2_GLUE_TEMPLATE),
                                                       sorted(unknown)))
    if contract["l2"]["module"]["modinfo"].get("license",
                                              contract["license"]) != "GPL":
        raise BuildError("%s hard-codes license=GPL but the contract says %r"
                         % (rel(L2_GLUE_TEMPLATE),
                            contract["l2"]["module"]["modinfo"].get("license")))

    rendered = template.replace("@L2_VERMAGIC@", contract["vermagic"])
    if re.search(r"@[A-Z0-9_]{2,}@", rendered):
        raise BuildError("the rendered L2 glue still contains a substitution token")
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(rendered, encoding="utf-8")
    return sha256_bytes(rendered.encode("utf-8"))


def render_l3_glue(contract: dict, out: Path) -> str:
    """Copy l3_glue.S.in with this contract's struct-module layout filled in.

    The template carries five tokens and no literal offsets: `struct module` is
    0x600 bytes with init at +0x188 / exit at +0x5b8 on this image, but that is a
    per-family fact (0x640 / 0x440 elsewhere), so the numbers come from
    `contract["thisModule"]` exactly as the root module's glue does. The checks
    below make a contract/template divergence a build error rather than a module
    that loads and then calls into the middle of nothing.
    """
    template = L3_GLUE_TEMPLATE.read_text(encoding="utf-8")
    layout = contract["thisModule"]
    name_off, init_off = layout["nameOffset"], layout["initOffset"]
    exit_off, size = layout["exitOffset"], layout["size"]
    if not (0x10 <= name_off < init_off < exit_off <= size - 8):
        raise BuildError(
            "contract thisModule is not a coherent struct module layout for L3: "
            "name=0x%x init=0x%x exit=0x%x size=0x%x"
            % (name_off, init_off, exit_off, size))
    if init_off % 8 or exit_off % 8 or size % 8:
        raise BuildError("contract thisModule offsets must be 8-byte aligned on "
                         "arm64: init=0x%x exit=0x%x size=0x%x"
                         % (init_off, exit_off, size))
    if len(L3_MODULE) + 1 > 56:
        raise BuildError("%r does not fit this_module.name[56]" % L3_MODULE)

    required_fragments = [
        '.section .gnu.linkonce.this_module,"aw",@progbits',
        ".zero\t@L3_NAME_OFF@",
        '.asciz\t"%s"' % L3_MODULE,
        ".zero\t@L3_INIT_OFF@ - (. - __this_module)",
        ".quad\tinit_module",
        ".zero\t@L3_EXIT_OFF@ - (. - __this_module)",
        ".quad\tcleanup_module",        # this_module.exit is the slot restore
        '.section .modinfo,"a",@progbits',
        '.asciz\t"license=GPL"',
        '.asciz\t"name=%s"' % L3_MODULE,
        '.asciz\t"depends="',
        '.asciz\t"vermagic=@L3_VERMAGIC@"',
        '.section .plt,"ax",@progbits',
        '.section .init.plt,"ax",@progbits',
        '.section .text.ftrace_trampoline,"ax",@progbits',
        '.section __versions,"a",@progbits',
    ]
    for fragment in required_fragments:
        if fragment not in template:
            raise BuildError("%s lost %r" % (rel(L3_GLUE_TEMPLATE), fragment))
    if template.count(".section .modinfo") != 1:
        raise BuildError("%s must carry exactly one .modinfo section (found %d); "
                         "the loader rejects a module with two"
                         % (rel(L3_GLUE_TEMPLATE),
                            template.count(".section .modinfo")))
    description = 'description=' + L3_MODINFO_DESCRIPTION
    if description not in template:
        raise BuildError("%s does not carry %r; the .modinfo description and "
                         "L3_MODINFO_DESCRIPTION must agree"
                         % (rel(L3_GLUE_TEMPLATE), description))
    tokens = set(re.findall(r"@[A-Z0-9_]{2,}@", template))
    layout_tokens = {"@L3_NAME_OFF@", "@L3_INIT_OFF@", "@L3_EXIT_OFF@",
                     "@L3_SIZE@"}
    unknown = tokens - layout_tokens - {"@L3_VERMAGIC@"}
    if unknown:
        raise BuildError("%s carries unknown substitution token(s) %s"
                         % (rel(L3_GLUE_TEMPLATE), sorted(unknown)))
    if contract["modinfo"].get("license", contract["license"]) != "GPL":
        raise BuildError("%s hard-codes license=GPL but the contract says %r"
                         % (rel(L3_GLUE_TEMPLATE),
                            contract["modinfo"].get("license")))

    rendered = template.replace("@L3_VERMAGIC@", contract["vermagic"])
    for token, key in (("@L3_NAME_OFF@", "nameOffset"),
                       ("@L3_INIT_OFF@", "initOffset"),
                       ("@L3_EXIT_OFF@", "exitOffset"),
                       ("@L3_SIZE@", "size")):
        rendered = rendered.replace(token, "0x%x" % layout[key])
    if re.search(r"@[A-Z0-9_]{2,}@", rendered):
        raise BuildError("the rendered L3 glue still contains a substitution "
                         "token: a token outside the whitelist would reach the "
                         "assembler")
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(rendered, encoding="utf-8")
    return sha256_bytes(rendered.encode("utf-8"))


def render_family_header(contract, out_dir):
    """Write kernel_compat.h with this family's numbers as plain literals.

    The shim header has to state this kernel's struct sizes and offsets, and it has to
    state them as numbers: the second gate engine parses the header text and cannot
    evaluate an array bound like `MAPLESU_SP_SIZE - 0x10`. The rendered copy keeps the
    same include guard, so it is force-included and the source's own
    `#include "kernel_compat.h"` becomes a no-op.
    """
    NL = chr(10)
    text = (MODULE_DIR / "kernel_compat.h").read_text(encoding="utf-8")
    sel = contract["selinuxState"]
    size, init = sel["size"], sel["initializedOffset"]
    spi = contract["structOffsets"]["subprocessInfo"]
    members = {m["name"]: m["byteOffset"] for m in spi["members"]}

    start = text.index("struct maplesu_selinux_state {")
    end = text.find("};", start) + 2
    body = ["struct maplesu_selinux_state {"]
    body.append("    u8 enforcing;")
    if init != 1:
        body.append("    u8 pad_init[0x%x];" % (init - 1))
    body.append("    u8 initialized;")
    body.append("    u8 rest[0x%x];" % (size - init - 1))
    body.append("};")
    text = text[:start] + NL.join(body) + text[end:]
    extra = ('_Static_assert(__builtin_offsetof(struct maplesu_selinux_state, '
             'enforcing) == 0x0, "enforcing");' + NL +
             '_Static_assert(__builtin_offsetof(struct maplesu_selinux_state, '
             'initialized) == 0x%x, "initialized");' % init + NL)
    marker = "};" + NL + NL + "_Static_assert(sizeof(struct maplesu_selinux_state)"
    if marker in text:
        text = text.replace(marker, "};" + NL + NL + extra +
                            "_Static_assert(sizeof(struct maplesu_selinux_state)", 1)

    get = lambda name: members.get(name, 0)
    # struct kprobe: 6.18 is 0x78 rather than 0x80. addr/symbol_name sit at the same
    # offsets in every family seen so far, but they come from the contract too so a
    # family that moves them cannot slip through.
    kp = contract["kprobe"]
    text = text.replace("u8 rest[0x80 - 0x40];",
                        "u8 rest[0x%x - 0x40];" % kp["size"])
    text = text.replace("sizeof(struct maplesu_kprobe) == 0x80",
                        "sizeof(struct maplesu_kprobe) == 0x%x" % kp["size"])
    text = text.replace("struct maplesu_kprobe, addr) == 0x28",
                        "struct maplesu_kprobe, addr) == 0x%x" % kp["addrOffset"])
    text = text.replace("struct maplesu_kprobe, symbol_name) == 0x30",
                        "struct maplesu_kprobe, symbol_name) == 0x%x"
                        % kp["symbolNameOffset"])

    start = text.index("struct maplesu_subprocess_info {")
    tail = text.index("subprocess_info.data must be at", start)
    end = text.find(NL, tail) + 1
    sp = ["struct maplesu_subprocess_info {"]
    sp.append("    u8 work[0x%x];" % get("complete"))
    sp.append("    u8 complete[0x%x];" % (get("path") - get("complete")))
    sp.append("    const char *path;")
    sp.append("    u8 argv[0x%x];" % (get("envp") - get("argv")))
    sp.append("    u8 envp[0x%x];" % (get("wait") - get("envp")))
    sp.append("    u32 wait;")
    sp.append("    u32 retval;")
    sp.append("    u8 init[0x%x];" % (get("cleanup") - get("init")))
    sp.append("    u8 cleanup[0x%x];" % (get("data") - get("cleanup")))
    sp.append("    u8 data[0x%x];" % (spi["size"] - get("data")))
    sp.append("};")
    sp.append("")
    sp.append('_Static_assert(sizeof(struct maplesu_subprocess_info) == 0x%x, "size");'
              % spi["size"])
    for field in ("complete", "path", "argv", "envp", "wait", "retval", "init",
                  "cleanup", "data"):
        sp.append('_Static_assert(__builtin_offsetof(struct maplesu_subprocess_info, '
                  '%s) == 0x%x, "%s offset");' % (field, get(field), field))
    text = text[:start] + NL.join(sp) + text[end:]

    # Drop the macro definition first, then make every remaining use literal: a
    # blind replace also rewrote the `#define` line into `#define 0x88 0x80`.
    guard = NL.join(["#ifndef MAPLESU_SELINUX_STATE_SIZE",
                     "#define MAPLESU_SELINUX_STATE_SIZE 0x80",
                     "#endif"])
    text = text.replace(guard + NL, "")
    text = text.replace("MAPLESU_SELINUX_STATE_SIZE", "0x%x" % size)
    global LAST_RENDERED_HEADER
    rendered = Path(out_dir) / "kernel_compat_family.h"
    rendered.parent.mkdir(parents=True, exist_ok=True)
    rendered.write_text(text, encoding="utf-8", newline=NL)
    LAST_RENDERED_HEADER = str(rendered)
    return rendered


def patch_kcfi_prefixes(module_path, image_path, contract, log,
                        extra_prefixes=None):
    """Write this image's KCFI type ids into every function prefix the kernel checks.

    -fsanitize=kcfi emits a 4-byte type id before every address-taken function, and the
    kernel compares it with the id at its own call site. That id follows the function's
    signature but the derivation is compiler/ABI dependent: on 6.6 and 6.1 the
    compiler's value equals the image's, on 5.15 it does not (init: module 0x36b1c5a6 vs
    image 0x6fbb3035) and a mismatch traps -- for the module's own entry points
    (`init_module`/`cleanup_module`) as much as for a callback.

    The image is the authority, so its ids are written into the module after linking.
    Where they already agree this rewrites identical bytes -- a no-op, which is what
    makes it safe to land before touching another family.

    `extra_prefixes` carries the ids the caller measured for other checked functions,
    currently the L3 callback: the kernel reaches `p->pre_handler` through a checked
    indirect call, so the 4 bytes in front of `maplesu_l3_pre` have to be the id the
    image's own pre-handler dispatch site compares. On 6.6.66/6.1 clang's hash of the
    kernel's prototype equals that id (a no-op rewrite); on 6.12/6.18 the same
    prototype hashes differently (0xa6be5dd9 vs 0xc073bc77), and the callback is not
    the module's own entry point -- it is a function the *kernel* calls and type-checks
    -- so the image's number is the one that must be there. The caller measures it
    (`derive_kprobe_handler_ids`) and the L3 contract opts in with
    `l3.callbackPrefixPolicy == "image-dispatch-id"`; this function only writes.
    """
    elf = Elf(module_path)
    image = image_path.read_bytes()
    symbols = SymbolTable(Path(contract["symbolsPath"])
                          if not Path(contract["symbolsPath"]).is_absolute()
                          else Path(contract["symbolsPath"]))
    facts = derive_image_facts(image, symbols, contract)
    wanted = {
        "init_module": int(facts["initCheckSite"]["kcfiTypeId"], 16),
        "cleanup_module": int(facts["exitCheckSite"]["kcfiTypeId"], 16),
    }
    for name, type_id in (extra_prefixes or {}).items():
        wanted[name] = int(type_id)
    data = bytearray(elf.raw)
    rewritten = []
    for name, type_id in wanted.items():
        symbol = elf.symbol(name)
        if symbol is None:
            raise GateFailure("module has no %s symbol to patch" % name)
        offset = elf.symbol_file_offset(symbol)
        if offset is None or offset < 4:
            raise GateFailure("cannot locate %s in the module image" % name)
        at = offset - 4
        current = int.from_bytes(data[at:at + 4], "little")
        if current != type_id:
            data[at:at + 4] = type_id.to_bytes(4, "little")
            rewritten.append((name, current, type_id))
    if rewritten:
        module_path.write_bytes(bytes(data))
        for name, current, type_id in rewritten:
            log("  [kcfi] %-15s 0x%08x -> 0x%08x (image compare site)"
                % (name, current, type_id))
    else:
        log("  [kcfi] every prefix already equals this image's compare sites")
    return [name for name, _old, _new in rewritten]


def emitted_handler_prefix(module_path) -> int | None:
    """The 4 bytes in front of `maplesu_l3_pre` in the module as it stands.

    The builder reads it twice: before `patch_kcfi_prefixes` (that value is the
    compiler's own hash of the kernel prototype, and it must equal the reference TU's)
    and after (that value is what ships, and it must equal the image's dispatch id).
    L3-1 states the same fact about the published artifact.
    """
    elf = Elf(module_path)
    symbol = elf.symbol("maplesu_l3_pre")
    if symbol is None or symbol["shndx"] == 0:
        return None
    offset = elf.symbol_file_offset(symbol)
    if offset is None or offset < 4:
        return None
    return read_uint32(elf.raw, offset - 4)


def build(ndk: Path, out_dir: Path, contract: dict, log: Log,
          extra_flags: list, module: str = "maplesu_root",
          l3_facts: dict | None = None) -> dict:
    out_dir.mkdir(parents=True, exist_ok=True)
    clang = find_tool(ndk, "clang")
    ld = find_tool(ndk, "ld.lld")
    runner = Runner()

    rendered_header = None
    if module == L2_MODULE:
        glue = out_dir / "root_glue_l2.s"
        glue_sha = render_l2_glue(contract, glue)
        source = L2_SOURCE
        cflags = list(CLANG_FLAGS) + list(L2_CLANG_FLAGS) + list(extra_flags)
        module_name = contract["l2"]["module"]["name"]
        object_name = L2_MODULE + ".o"
    elif module == L3_MODULE:
        if not l3_facts:
            raise BuildError(
                "the L3 build needs the image facts (prctl index, cred source, "
                "handler prefix); run it through --module maplesu_l3")
        glue = out_dir / "l3_glue.s"
        glue_sha = render_l3_glue(contract, glue)
        source = L3_SOURCE
        # The same family rendering the root module uses: kernel_compat.h states
        # this kernel's sizes as numbers, and G7 compares them against BTF.
        rendered_header = render_family_header(contract, out_dir)
        family = ["-include", str(rendered_header)]
        # The three per-image facts, injected as -D so the C file carries no
        # per-release constant and the gates can re-derive and compare them.
        reference = l3_facts.get("referenceKcfi") or {}
        if not reference:
            raise BuildError(
                "the L3 build needs the reference TU's KCFI ids (pre/post handler); "
                "run it through --module maplesu_l3 so they are measured first")
        dispatch = l3_facts.get("callbackDispatch") or {}
        pre_dispatch = (dispatch.get("preHandler") or {}).get("typeId")
        if not pre_dispatch:
            raise BuildError(
                "the L3 build needs the image's pre-handler dispatch id; "
                "derive_kprobe_handler_ids() produced no dispatch block")
        # The -D is the id the module *ships* (the image's dispatch site), because
        # the C file's load-time self-check compares its own prefix with it. The
        # compiler's own derivation is a separate fact: the builder checks it against
        # the reference TU before writing the image's id over the prefix.
        injected = ["-DMAPLESU_CRED_SOURCE=%d" % l3_facts["credSource"]["source"],
                    "-DMAPLESU_L3_PRE_KCFI_ID=%s" % pre_dispatch,
                    "-DMAPLESU_L3_KPROBE_SIZE=0x%x" % l3_facts["callbackSize"]]
        injected += ["-D%s=%d" % (key, value) for key, value in
                     l3_facts["rootSubject"]["defines"].items()]
        injected += ["-D%s=%d" % (key, value) for key, value in
                     l3_facts.get("statusView", {}).items()]
        if l3_facts.get("policyBridge"):
            injected += ["-DMAPLESU_L3_POLICY_BRIDGE=1"]
        if l3_facts.get("bootSdk"):
            injected += ["-DMAPLESU_L3_BOOT_SDK=1"]
        cflags = list(CLANG_FLAGS) + family + injected + list(extra_flags)
        module_name = L3_MODULE
        object_name = L3_MODULE + ".o"
    else:
        glue = out_dir / "root_glue.s"
        glue_sha = render_glue(contract, glue)
        source = MODULE_DIR / "maplesu_root.c"
        # Struct facts that differ by family are injected, never written in the header:
        # a wrong sizeof() here is a boot-time surprise, not a build error.
        rendered_header = render_family_header(contract, out_dir)
        family = ["-include", str(rendered_header)]
        cflags = list(CLANG_FLAGS) + family + list(extra_flags)
        module_name = contract["module"]
        object_name = "maplesu_root.o"

    if legacy_cfi.enabled(contract):
        cflags = [flag for flag in cflags if flag != "-fsanitize=kcfi"]
        cflags += legacy_cfi.CLANG_FLAGS
    if contract.get("printkSymbol") == "printk":
        cflags += ["-D_printk=printk"]

    log("build%s" % ("" if module == "maplesu_root" else " (%s)" % module))
    objects = []
    obj = out_dir / object_name
    runner([clang, *cflags, source, "-o", obj])
    objects.append(obj)
    log("  [c]   %-28s -> %s (%d bytes)"
        % (rel(source), rel(obj), obj.stat().st_size))

    if module == L3_MODULE and l3_facts.get("policyBridge"):
        (out_dir / "policy_hook_layout.h").write_text(policy_build.header(l3_facts["policyBridge"]), encoding="utf-8")
        for name in ("maplesu_policy_hook.c", "policy_hook_dispatch.c", "policy_hook_original.S"):
            path = MODULE_DIR / name
            obj = out_dir / (path.stem + ".o")
            flags = (cflags + ["-DMAPLESU_POLICY_HOOK_EMBEDDED=1", "-I" + str(out_dir)]
                     if path.suffix == ".c" else ASM_FLAGS)
            runner([clang, *flags, path, "-o", obj])
            objects.append(obj)

    if module == L3_MODULE and l3_facts.get("bootSdk"):
        (out_dir / "boot_sdk_layout.h").write_text(boot_sdk_build.header(l3_facts["bootSdk"]), encoding="utf-8")
        boot_sdk_build.stage_protocol(out_dir)
        for name in ("maplesu_boot_sdk.c", "boot_sdk_call.S"):
            path = MODULE_DIR / name
            obj = out_dir / (path.stem + ".o")
            flags = cflags + ["-I" + str(out_dir)] if path.suffix == ".c" else ASM_FLAGS
            runner([clang, *flags, path, "-o", obj])
            objects.append(obj)

    assembled = out_dir / (glue.stem + ".o")
    runner([clang, *ASM_FLAGS, glue, "-o", assembled])
    objects.append(assembled)
    log("  [asm] %-28s -> %s (%d bytes)"
        % (rel(glue), rel(assembled), assembled.stat().st_size))

    module_path = out_dir / (module_name + ".ko")
    link_flags = []
    if legacy_cfi.enabled(contract):
        script = out_dir / "legacy-sections.lds"
        script.write_text("SECTIONS { .text : { *(.text) *(.text.*) } "
                          ".bss : { *(.bss) *(.bss.*) } "
                          ".data : { *(.data) *(.data.*) } }\n", encoding="utf-8")
        link_flags = ["-T", str(script)]
    runner([ld, "-r", *link_flags, "-o", module_path, *objects])
    log("  [ld]  ld.lld -r -o %s (%d bytes, sha256 %s)"
        % (rel(module_path), module_path.stat().st_size,
           sha256_file(module_path)[:16] + "..."))

    return {"module": module_path, "objects": objects, "glue": glue,
            "glue_sha256": glue_sha, "modinfo": None,
            "commands": runner.commands, "cflags": cflags,
            "header": str(rendered_header) if rendered_header else None,
            "clang": clang, "ld": ld, "source": source,
            "moduleName": module_name}


# --------------------------------------------------------------------------- #
# Gate plumbing.
# --------------------------------------------------------------------------- #

class Gate:
    def __init__(self, gate_id: str, name: str):
        self.id = gate_id
        self.name = name
        self.checks: list[dict] = []
        self.extras: dict = {}

    def check(self, ok: bool, what: str, detail: str = "", fatal: bool = True) -> bool:
        self.checks.append({"what": what, "ok": bool(ok), "detail": detail,
                            "fatal": fatal})
        return bool(ok)

    def require(self, ok: bool, what: str, detail: str = "") -> None:
        if not self.check(ok, what, detail, fatal=True):
            raise _CheckFailed(what + (": " + detail if detail else ""))

    @property
    def ok(self) -> bool:
        return all(entry["ok"] for entry in self.checks if entry["fatal"])

    def result(self) -> dict:
        fatal_failures = [entry for entry in self.checks
                          if entry["fatal"] and not entry["ok"]]
        detail = ("; ".join(entry["what"] for entry in self.checks
                            if entry["fatal"] and entry["ok"])
                  if not fatal_failures else
                  "; ".join("%s -> %s" % (e["what"], e["detail"] or "failed")
                            for e in fatal_failures))
        return {"id": self.id, "name": self.name, "ok": self.ok,
                "checks": self.checks, "detail": detail, "extras": self.extras}


class _CheckFailed(RuntimeError):
    """A fatal check inside a gate; the gate stops and reports."""


def read_uint32(data: bytes, offset: int) -> int:
    return struct.unpack_from("<I", data, offset)[0]


def derive_image_facts(image: bytes, symbols: SymbolTable, contract: dict) -> dict:
    """Every KCFI id and every layout this build relies on, read from the image itself.

    No number here comes from a table typed by hand: the function ids are the four
    bytes clang placed in front of the function the kernel exports, and the two check
    ids are the immediates the image's own compare sites build.
    """
    facts: dict = {}
    for name in ("kallsyms_lookup_name", "aarch64_insn_patch_text"):
        address = symbols.address(name)
        if address is None:
            raise GateFailure("symbol table has no %s" % name)
        if address < 4:
            raise GateFailure("%s at 0x%x has no room for a KCFI prefix" % (name, address))
        facts[name] = {
            "address": "0x%x" % address,
            "prefixTypeId": "0x%08x" % read_uint32(image, address - 4),
            "entryInstruction": "0x%08x" % read_uint32(image, address),
        }

    for name, label in (("do_one_initcall", "init"), ("__arm64_sys_delete_module", "exit")):
        address = symbols.address(name)
        if address is None:
            raise GateFailure("symbol table has no %s" % name)
        site = first_kcfi_id(image, address, symbols.next_address(address, limit=0x900))
        if site is None:
            raise GateFailure("no KCFI compare site found in %s (0x%x)" % (name, address))
        facts[label + "CheckSite"] = {
            "function": name, "functionAddress": "0x%x" % address,
            "compareSiteOffset": "0x%x" % (site["offset"] - address),
            "compareSiteAddress": "0x%x" % site["offset"],
            "kcfiTypeId": "0x%08x" % site["typeId"],
        }
    return facts


# --------------------------------------------------------------------------- #
# L3 image facts: the kprobe callback ABI, the registration target, cred source.
# --------------------------------------------------------------------------- #

def bug_table_warnings(image: bytes, symbols: SymbolTable) -> dict:
    """Every `brk` the image's own `__bug_table` marks as a WARNING.

    arm64 compiles BUG() and WARN_ON() to the same `brk #0x800`; the difference
    is a flag in `struct bug_entry` (`BUGFLAG_WARNING`, bit 0), next to the file
    name and line. That table is the image's own statement about its code, and it
    is what turns "this branch looks like a warning" into "kernel/cred.c:637 is a
    WARN_ON_ONCE". Returns {brk_address: {"file", "line", "flags"}}.
    """
    start, stop = (symbols.address("__start___bug_table"),
                   symbols.address("__stop___bug_table"))
    if start is None or stop is None or not (0 <= start < stop <= len(image)):
        return {}
    warnings = {}
    for offset in range(start, stop - 11, 12):
        disp, file_disp, line, flags = struct.unpack_from("<iiHH", image, offset)
        if not flags & 0x1:            # BUGFLAG_WARNING
            continue
        name_at = offset + 4 + file_disp
        if 0 < name_at < len(image):
            end = image.find(b"\x00", name_at)
            name = image[name_at:end].decode("ascii", "replace")
        else:
            name = ""
        warnings.setdefault(offset + disp, {"file": name, "line": line,
                                            "flags": flags})
    return warnings


def derive_cred_source(image: bytes, symbols: SymbolTable) -> dict:
    """Does this image's prepare_kernel_cred() accept a NULL daemon?

    The standard idiom is `prepare_kernel_cred(NULL)`, and older kernels treat
    NULL as "use init_cred". A kernel that rejects NULL starts with a test on x0
    whose cold block warns and returns NULL -- and that warning is a ring-buffer
    trace (and a panic under panic_on_warn), so it decides *which call this
    module makes*, not merely whether one works.

    On this image the answer is visible three ways at once, and this function
    records all three: a `cbz x0` in the prologue, a cold block that zeroes the
    result and hits `brk #0x800`, and that brk being flagged BUGFLAG_WARNING in
    the image's own __bug_table (kernel/cred.c:637).
    """
    address = symbols.address("prepare_kernel_cred")
    if address is None:
        raise GateFailure("symbol table has no prepare_kernel_cred")
    warnings = bug_table_warnings(image, symbols)
    for index in range(16):
        site = address + index * 4
        insn = read_uint32(image, site)
        if (insn & 0xFF000000) not in (0xB4000000, 0xB5000000):
            continue                       # not a 64-bit CBZ/CBNZ
        if (insn & 0x1F) != 0:
            continue                       # not on x0 (the daemon argument)
        if (insn & 0xFF000000) != 0xB4000000:
            continue                       # CBNZ: "if non-NULL", not the NULL path
        immediate = (insn >> 5) & 0x7FFFF
        if immediate & 0x40000:
            immediate -= 0x80000
        target = site + immediate * 4
        block = [read_uint32(image, target + step * 4) for step in range(8)]
        zeroes_result = any((word & 0xFFFFFFE0) == 0xAA1F03E0 for word in block)
        brk_at = next((target + step * 4 for step, word in enumerate(block)
                       if decode_brk(word) is not None), None)
        if not zeroes_result or brk_at is None:
            continue
        warn = warnings.get(brk_at)
        if warn is None:
            continue                       # a brk with no warning flag is a BUG
        return {"source": L3_CRED_INIT_TASK,
                "reason": "prepare_kernel_cred() warns and returns NULL for a NULL "
                          "daemon (%s:%d, BUGFLAG_WARNING at 0x%x); &init_task is "
                          "the source that yields the same init-derived root cred "
                          "without a trace" % (warn["file"], warn["line"], brk_at),
                "nullBranch": {"site": "0x%x" % site, "target": "0x%x" % target,
                               "brk": "0x%x" % brk_at,
                               "file": warn["file"], "line": warn["line"],
                               "flags": "0x%x" % warn["flags"]}}
    return {"source": L3_CRED_NULL,
            "reason": "prepare_kernel_cred() has no NULL-rejecting warning branch; "
                      "NULL is the standard idiom",
            "nullBranch": None}


def callback_anchor_lists(contract: dict) -> dict:
    """The candidate dispatch anchors per callback, and where the list came from.

    `l3.callbackAnchors` is the authority when the contract carries one (the G-1
    field). The builder's default is used for the contracts that predate it, and it
    is the same list, so a 6.6.66/6.6.89 build derives its facts the same way --
    those contracts stay byte-identical.
    """
    recorded = ((contract.get("l3") or {}).get("callbackAnchors") or {})
    out = {}
    for key in ("preHandler", "postHandler"):
        names = recorded.get(key)
        if isinstance(names, list) and names and all(isinstance(n, str) and n
                                                     for n in names):
            out[key] = {"anchors": [str(name) for name in names],
                        "source": "contract:l3.callbackAnchors"}
        else:
            out[key] = {"anchors": list(L3_DEFAULT_CALLBACK_ANCHORS[key]),
                        "source": "builder-default (contract has no l3.callbackAnchors)"}
    return out


def kprobe_member_offsets(contract: dict) -> dict:
    """The three `struct kprobe` member offsets the attribution needs, from BTF.

    The offsets come from the contract's `kprobe.members` block, which the contract
    derivation read out of the image's own BTF. They are not typed here: a family
    whose `pre_handler` moved would move this table and the derivation with it.
    """
    members = {member.get("name"): member.get("byteOffset")
               for member in ((contract.get("kprobe") or {}).get("members") or [])}
    needed = set(L3_CALLBACK_MEMBERS.values()) | {L3_KPROBE_LIST_MEMBER}
    missing = sorted(name for name in needed if not isinstance(members.get(name), int))
    if missing:
        raise GateFailure(
            "the contract's struct kprobe records no byte offset for %s; the "
            "dispatch attribution cannot say which callback a compare site is for"
            % missing)
    return {name: members[name] for name in needed}


def kcfi_site_register(image: bytes, site: int, lower: int) -> int | None:
    """The register whose `[x-4]` type id the compare at `site` reads, or None.

    Every kCFI check is `ldur wT,[R,#-4] ; movk/movk ; cmp wT,w17 ; b.eq ; brk ;
    blr R`. R is the callback pointer, so it is the register whose definition says
    which struct member the dispatch is for.
    """
    for back in range(4, 4 * 5, 4):
        at = site - back
        if at < lower:
            return None
        decoded = decode_ldur_w(read_uint32(image, at))
        if decoded is not None and decoded[2] == -4:
            return decoded[1]
    return None


def member_load_before(image: bytes, lower: int, site: int, register: int | None,
                       offsets: set) -> dict | None:
    """The nearest `ldr <register>, [<base>, #offset]` that reads a callback member.

    Bounded by `L3_MEMBER_LOAD_WINDOW` bytes before the compare and by the anchor
    function's own start. Only a 64-bit immediate-offset load into the very register
    the type check reads counts: a `ldr w8,[x1,#0x18]` that happens to sit closer is
    a different value, and a load into another register says nothing about this
    compare.

    `offsets` holds the member's own offset and, for the aggregator handlers, the
    list-relative form (`member - offsetof(list)`): `aggr_pre_handler` iterates
    `p->list` and loads `container_of(entry)->pre_handler`, so its load is
    `[kp + list + member - list]` -- the same member, reached through the list.
    """
    if register is None:
        return None
    for back in range(4, L3_MEMBER_LOAD_WINDOW + 4, 4):
        at = site - back
        if at < lower:
            return None
        decoded = decode_ldr_uoffset(read_uint32(image, at))
        if decoded is None or decoded[0] != register or decoded[2] not in offsets:
            continue
        return {"address": at, "distance": back, "immediate": decoded[2],
                "baseRegister": decoded[1]}
    return None


def derive_kprobe_handler_ids(image: bytes, symbols: SymbolTable,
                              contract: dict) -> dict:
    """The CFI type ids the image compares at its kprobe callback dispatch points.

    This is the set the callback's prefix has to belong to, and the per-callback
    id the builder writes into it. The method is a derivation, not a table:

      1. iterate the contract's candidate anchors per callback (`l3.callbackAnchors`,
         defaulting to `L3_DEFAULT_CALLBACK_ANCHORS`); a candidate the image does
         not carry is recorded as absent, not treated as an error -- 6.1's post
         dispatch moved to `post_kprobe_handler` and 6.18 has no
         `kprobe_breakpoint_handler`/`post_kprobe_handler` at all;
      2. inside every present candidate, decode the `movk/movk/cmp/b.eq/brk/blr`
         sequences (`kcfi_type_ids`) -- those are the kernel's checked indirect
         calls;
      3. attribute a site to the callback whose `struct kprobe` member the checked
         pointer was loaded from (`ldr R,[base,#pre_handler]`, or the list-relative
         form the aggregators use), reading the member offsets from BTF;
      4. require the attributed sites of one callback to agree on exactly ONE type
         id, and the two callbacks' ids to be distinct.

    Step 4 is what replaces the old "both pins are in the set" assertion: two
    independent anchors must name the same id for the same callback, and the two
    callbacks must name different ids. An image where a site cannot be attributed,
    or where two anchors disagree, stops the build instead of guessing.
    """
    anchor_lists = callback_anchor_lists(contract)
    members = kprobe_member_offsets(contract)
    list_offset = members[L3_KPROBE_LIST_MEMBER]
    inventory, dispatch = [], {}
    for key, member_name in L3_CALLBACK_MEMBERS.items():
        member = members[member_name]
        allowed = {member}
        if 0 < member - list_offset < member:
            allowed.add(member - list_offset)
        attributed, present, absent = [], [], []
        for name in anchor_lists[key]["anchors"]:
            address = symbols.address(name)
            if address is None:
                absent.append(name)
                inventory.append({"callback": key, "function": name,
                                  "present": False, "address": None,
                                  "extent": None, "compareSites": []})
                continue
            present.append(name)
            end = symbols.next_address(address, limit=0x1000)
            sites = []
            for hit in kcfi_type_ids(image[address:end]):
                at = address + hit["instruction"] * 4
                register = kcfi_site_register(image, at, address)
                load = member_load_before(image, address, at, register, allowed)
                site = {"address": "0x%x" % at,
                        "typeId": "0x%08x" % hit["typeId"],
                        "typeRegister": None if register is None
                                         else "x%d" % register,
                        "memberLoad": None}
                if load is not None:
                    site["memberLoad"] = {
                        "address": "0x%x" % load["address"],
                        "immediate": "0x%x" % load["immediate"],
                        "distance": "0x%x" % load["distance"],
                        "baseRegister": "x%d" % load["baseRegister"],
                        "form": ("direct" if load["immediate"] == member
                                 else "list-relative")}
                    attributed.append({"function": name,
                                       "site": site["address"],
                                       "typeId": site["typeId"],
                                       "loadAddress": site["memberLoad"]["address"],
                                       "loadImmediate": site["memberLoad"]["immediate"],
                                       "form": site["memberLoad"]["form"]})
                sites.append(site)
            inventory.append({"callback": key, "function": name, "present": True,
                              "address": "0x%x" % address,
                              "extent": ["0x%x" % address, "0x%x" % end],
                              "compareSites": sites})
        if not attributed:
            raise GateFailure(
                "no %s dispatch site could be attributed in %s: no compare site "
                "reads a callback pointer out of kprobe.%s (+0x%x%s) within 0x%x "
                "bytes; the dispatch cannot be proved for this image"
                % (key, present or anchor_lists[key]["anchors"], member_name, member,
                   " or +0x%x list-relative" % (member - list_offset)
                   if member - list_offset in allowed else "",
                   L3_MEMBER_LOAD_WINDOW))
        ids = sorted({site["typeId"] for site in attributed})
        if len(ids) != 1:
            raise GateFailure(
                "the %s anchors disagree on the callback type id (%s): %s. The "
                "attribution has to name one id, not a set, because the artifact's "
                "prefix is one 4-byte value"
                % (key, ids, attributed))
        dispatch[key] = {
            "typeId": ids[0],
            "member": member_name,
            "memberOffset": "0x%x" % member,
            "allowedLoadOffsets": ["0x%x" % value for value in sorted(allowed)],
            "attributedSites": attributed,
            "anchorsPresent": present,
            "anchorsAbsent": absent,
            "anchorsSource": anchor_lists[key]["source"],
        }
    if dispatch["preHandler"]["typeId"] == dispatch["postHandler"]["typeId"]:
        raise GateFailure(
            "the pre and post handler dispatch sites compare the same type id %s; "
            "two different callback prototypes cannot hash to one id, so the "
            "attribution mixed the two"
            % dispatch["preHandler"]["typeId"])

    # The 6.6-compatible projection: the primary anchor's own decode, plus the
    # union across every present anchor. `kprobe_breakpoint_handler` is the primary
    # where the contract lists it and the image carries it (every family that has
    # both), and the first present pre-handler anchor otherwise (6.18: the
    # aggregator).
    primaries = [entry for entry in inventory
                 if entry["callback"] == "preHandler" and entry["present"]]
    if not primaries:
        raise GateFailure("no pre-handler anchor is present in this image")
    primary_entry = next((entry for entry in primaries
                          if entry["function"] == L3_KPROBE_ANCHOR), primaries[0])
    primary = primary_entry["function"]
    union = sorted({site["typeId"] for entry in inventory
                    for site in entry["compareSites"]})
    return {
        "symbol": primary,
        "address": primary_entry["address"],
        "extent": primary_entry["extent"],
        "compareSites": [{"address": site["address"], "typeId": site["typeId"]}
                         for site in primary_entry["compareSites"]],
        "cfiTypeIds": union,
        # New (G-1): which functions were searched, what each one compares, and
        # which id each callback's attributed sites agreed on.
        "callbackAnchors": {key: list(anchor_lists[key]["anchors"])
                            for key in ("preHandler", "postHandler")},
        "anchorInventory": inventory,
        "dispatch": dispatch,
    }


def l3_subject_defines(description: dict) -> dict:
    """Project the shared target description into the existing C consumer ABI."""
    if not isinstance(description, dict) or description.get("schemaVersion") != 1 or \
            description.get("operation") != "PRO_LAYOUT_SUBJECT_DESCRIBE":
        raise GateFailure("subject layout description has an unsupported schema")
    fields = description.get("fields")
    if not isinstance(fields, dict):
        raise GateFailure("subject layout description has no fields")
    required = {"task_struct.cred": ("MAPLESU_TASK_CRED_OFF", (8,)),
                "cred.uid": ("MAPLESU_CRED_UID_OFF", (4,)),
                "cred.euid": ("MAPLESU_CRED_EUID_OFF", (4,)),
                "cred.group_info": ("MAPLESU_CRED_GROUPS_OFF", (8,)),
                "group_info.usage": ("MAPLESU_GROUP_USAGE_OFF", (4,)),
                "cred.usage": ("MAPLESU_CRED_USAGE_OFF", (4, 8))}
    defines = {}
    for name, (macro, widths) in required.items():
        field = fields.get(name)
        if not isinstance(field, dict):
            raise GateFailure("subject layout missing " + name)
        offset, width, owner = (field.get(key) for key in
                                ("offsetBytes", "widthBytes", "ownerSizeBytes"))
        if type(offset) is not int or type(width) is not int or width not in widths or \
                offset < 0 or offset % width:
            raise GateFailure("subject layout has an invalid offset/width for " + name)
        if owner is not None and (type(owner) is not int or offset + width > owner):
            raise GateFailure("subject layout exceeds its owner for " + name)
        defines[macro] = offset
        if name == "cred.usage":
            defines["MAPLESU_CRED_USAGE_SIZE"] = width
    return defines


def derive_l3_seccomp_facts(image: bytes, symbols: SymbolTable) -> dict:
    """Measure the arm64 current-task mode and syscall-entry flag, including 5.10."""
    getter = symbols.address("prctl_get_seccomp")
    trace = symbols.address("syscall_trace_enter")
    if getter is None or trace is None:
        raise GateFailure("seccomp task ABI anchors missing")
    mode_offsets = set()
    for at in range(getter, symbols.next_address(getter) - 4, 4):
        mrs, load = read_uint32(image, at), read_uint32(image, at + 4)
        if mrs & 0xffffffe0 == 0xd5384100 and load & 0xffc0001f == 0xb9800000 and \
                (load >> 5) & 31 == mrs & 31:
            mode_offsets.add(((load >> 10) & 0xfff) * 4)
    if len(mode_offsets) != 1:
        raise GateFailure("prctl_get_seccomp does not prove one 32-bit task mode")
    end = symbols.next_address(trace)
    task_regs = {read_uint32(image, at) & 31 for at in range(trace, end, 4)
                 if read_uint32(image, at) & 0xffffffe0 == 0xd5384100}
    targets = {symbols.address(name) for name in ("__secure_computing", "__seccomp_filter")}
    calls = set()
    for at in range(trace, end, 4):
        word = read_uint32(image, at)
        if word & 0xfc000000 == 0x94000000:
            imm = word & 0x3ffffff
            if imm & 0x2000000:
                imm -= 0x4000000
            if at + imm * 4 in targets:
                calls.add(at)
    flags = set()
    for at in range(trace + 4, end, 4):
        branch, load = read_uint32(image, at), read_uint32(image, at - 4)
        if branch & 0x7e000000 != 0x36000000 or load & 0xffc00000 != 0xf9400000 or \
                (load >> 5) & 31 not in task_regs or load & 31 != branch & 31:
            continue
        imm = (branch >> 5) & 0x3fff
        if imm & 0x2000:
            imm -= 0x4000
        # TBNZ enters the seccomp block; TBZ skips it. Tie the tested flag to
        # the actual seccomp call rather than assuming a TIF number per KMI.
        start = at + imm * 4 if branch & 0x01000000 else at + 4
        reaches_seccomp = False
        for cursor in range(start, min(start + 64, end), 4):
            word = read_uint32(image, cursor)
            if word & 0xfc000000 == 0x94000000:
                reaches_seccomp = cursor in calls
                break
            if word & 0x7c000000 == 0x14000000 or word & 0xfffffc1f == 0xd65f0000:
                break
        if not reaches_seccomp:
            continue
        bit = ((branch >> 19) & 31) | ((branch >> 26) & 32)
        flags.add((((load >> 10) & 0xfff) * 8, 1 << bit, at))
    if len(flags) != 1:
        raise GateFailure("syscall_trace_enter does not prove one seccomp flag")
    flags_offset, mask, branch = flags.pop()
    return {"modeGetter": getter, "traceBranch": branch, "defines": {
        "MAPLESU_TASK_SECCOMP_MODE_OFF": mode_offsets.pop(),
        "MAPLESU_TASK_THREAD_FLAGS_OFF": flags_offset,
        "MAPLESU_TIF_SECCOMP_MASK": mask}}


def derive_l3_current_model(image: bytes, symbols: SymbolTable, defines: dict) -> dict:
    """Prove the direct SP_EL0 task consumer from the target UID return path."""
    task_cred, uid = defines["MAPLESU_TASK_CRED_OFF"], defines["MAPLESU_CRED_UID_OFF"]
    if task_cred < 0 or task_cred & 7 or uid < 0 or uid & 3:
        raise GateFailure("current task layout is not an aligned cred/uid pair")
    def extent(address):
        if address < 0 or address & 3 or address + 4 > len(image):
            raise GateFailure("current task getter has an invalid start")
        end = symbols.next_address(address, limit=len(image) + 1)
        if end <= address or end > len(image) or (end - address) & 3:
            raise GateFailure("current task getter has an invalid symbol extent")
        return end

    def target(pc, immediate, bits):
        if immediate & (1 << (bits - 1)):
            immediate -= 1 << bits
        return pc + immediate * 4

    def hint(word):
        return word in (0xd503201f, 0xd503233f, 0xd50323bf, 0xd503211f, 0xd503219f) or word & 0xffffff3f == 0xd503241f

    models = []
    for getter in ("__arm64_sys_getuid", "sys_getuid"):
        start = symbols.address(getter)
        if start is None:
            continue
        entry, thunks = start, []
        while True:
            end = extent(start)
            limit = min(end, start + 512)
            first = start
            while first < limit and hint(read_uint32(image, first)):
                first += 4
            if first >= limit:
                raise GateFailure("current task getter has no body")
            word = read_uint32(image, first)
            if word & 0xfc000000 != 0x14000000:
                break
            branch = target(first, word & 0x3ffffff, 26)
            if start <= branch < limit:
                break
            if len(thunks) == 8 or branch in [entry, *thunks] or branch < 1 or symbols.next_address(branch - 1, limit=len(image) + 1) != branch:
                raise GateFailure("current task entry thunk has no exact bounded target")
            thunks.append(start)
            start = branch

        overflow = symbols.address("overflowuid")
        converter = symbols.address("from_kuid_munged")
        if overflow is not None and (overflow < 0 or overflow & 3 or overflow + 4 > len(image)):
            overflow = None
        if converter is not None and (converter < 0 or converter & 3 or converter + 4 > len(image)):
            converter = None
        # Values are only this proof's origin, pointer, UID and overflow projection.
        pending, visited, returns = [(start, (None,) * 32, None)], set(), 0
        while pending:
            pc, frozen, compared = pending.pop()
            if pc < start or pc + 4 > limit or pc & 3:
                raise GateFailure("current task path leaves the getter extent")
            state = (pc, frozen, compared)
            if state in visited:
                continue
            if len(visited) >= 4096:
                raise GateFailure("current task path exceeds its state bound")
            visited.add(state)
            values, next_compared = list(frozen), compared
            word = read_uint32(image, pc)
            rd, rn, rm = word & 31, word >> 5 & 31, word >> 16 & 31
            following = pc + 4
            if word == 0xd65f03c0:
                if values[0] != ("uid", 0):
                    raise GateFailure("current task getter return does not prove target cred.uid")
                returns += 1
                continue
            elif hint(word):
                pass
            elif word & 0xffffffe0 == 0xd5384100:
                values[rd] = ("origin", 0)
            elif word & 0x7fe0ffe0 == 0x2a0003e0:
                value = values[rm]
                values[rd] = value if word & 0x80000000 or value == ("uid", 0) else None
            elif decode_movz(word) is not None or decode_movk(word) is not None:
                values[rd] = None
            elif word & 0x9f000000 in (0x90000000, 0x10000000):
                immediate = ((word >> 5 & 0x7ffff) << 2) | (word >> 29 & 3)
                if immediate & (1 << 20):
                    immediate -= 1 << 21
                page = word & 0x9f000000 == 0x90000000
                values[rd] = ("address", (pc & ~4095 if page else pc) + (immediate * 4096 if page else immediate))
            elif word & 0xff000000 == 0x91000000:
                base = values[rn]
                immediate = (word >> 10 & 0xfff) << (12 if word & (1 << 22) else 0)
                values[rd] = (base[0], base[1] + immediate) if base and base[0] in ("origin", "cred", "address") else None
            elif (load := decode_ldr_uoffset(word)) is not None:
                destination, base_register, offset = load
                base = values[base_register]
                values[destination] = ("cred", 0) if base and base[0] == "origin" and base[1] + offset == task_cred else None
            elif word & 0xffc00000 == 0xb9400000:
                base, offset = values[rn], (word >> 10 & 0xfff) * 4
                values[rd] = None
                if base and base[0] == "cred" and base[1] + offset == uid:
                    values[rd] = ("uid", 0)
                elif base and base[0] == "address" and overflow is not None and base[1] + offset == overflow:
                    values[rd] = ("overflow", 0)
            elif word & 0xfffffc1f == 0x3100041f:
                next_compared = values[rn]
            elif word & 0x7fe00c00 == 0x1a800000:
                left, right, condition = values[rn], values[rm], word >> 12 & 15
                values[rd] = left if left == right else None
                if not word & 0x80000000 and compared == ("uid", 0):
                    if (condition, left, right) in ((0, ("overflow", 0), ("uid", 0)), (1, ("uid", 0), ("overflow", 0))):
                        values[rd] = ("uid", 0)
            elif word & 0x7c000000 == 0x14000000:
                branch = target(pc, word & 0x3ffffff, 26)
                call = bool(word & 0x80000000)
                if not call and start <= branch < limit:
                    if branch <= pc:
                        raise GateFailure("current task branch is not a bounded forward path")
                    following = branch
                elif converter is not None and branch == converter and values[1] == ("uid", 0):
                    if not call:
                        returns += 1
                        continue
                    values[:19], values[30] = [None] * 19, None
                    values[0] = ("uid", 0)
                    next_compared = None
                else:
                    raise GateFailure("current task call has no proven UID converter")
            elif word & 0xff000010 == 0x54000000 or word & 0x7e000000 in (0x34000000, 0x36000000):
                bits = 14 if word & 0x7e000000 == 0x36000000 else 19
                branch = target(pc, word >> 5 & ((1 << bits) - 1), bits)
                if not pc < branch < limit:
                    raise GateFailure("current task conditional leaves its bounded forward path")
                pending.append((branch, tuple(values), next_compared))
            elif word & 0x3a000000 == 0x28000000 and rn == 31:
                if word & (1 << 22):
                    values[rd], values[word >> 10 & 31] = None, None
            else:
                raise GateFailure("current task getter has an unsupported instruction: 0x%08x" % word)
            values[31] = None
            pending.append((following, tuple(values), next_compared))
        if not returns:
            raise GateFailure("current task getter has no proven return")
        models.append({"currentModel": "sp_el0-task", "source": "target-accessor",
                "getter": getter, "entry": entry, "body": start, "end": end,
                "thunks": thunks, "returns": returns, "taskCred": task_cred, "credUid": uid})
    if not models:
        raise GateFailure("current task getter is missing")
    return {**models[0], "checkedGetters": [{key: model[key] for key in ("getter", "entry", "body", "end")}
                                          for model in models]}


def derive_l3_subject_facts(image: bytes, symbols: SymbolTable,
                            defines: dict | None = None) -> dict:
    """The private group_info marker and safe entry-return ABI, from this image."""
    layout = None
    if defines is None:
        try:
            layout = layout_product.describe_subject(image)
        except (RuntimeError, OSError) as error:
            raise GateFailure("subject layout: " + str(error)) from error
        defines = l3_subject_defines(layout)
    seccomp = derive_l3_seccomp_facts(image, symbols)
    defines = {**defines, **seccomp["defines"]}
    for name in ("avc_denied", "groups_alloc", "groups_free", "set_groups"):
        if symbols.address(name) is None:
            raise GateFailure("root-subject data path has no " + name)
    target = symbols.address("avc_denied")
    insn = read_uint32(image, target)
    # Entry-only, before stack adjustment or PAC signing. A new prologue must be
    # inspected rather than silently assuming that a relocated probe still is entry.
    safe = (insn in (0xd503245f, 0xd503233f, 0xd503201f) or
            (insn & 0x7e000000) in (0x34000000, 0x36000000))
    if not safe:
        raise GateFailure("avc_denied entry needs a new return-ABI audit: 0x%08x" % insn)
    current = derive_l3_current_model(image, symbols, defines)
    return {"symbol": "avc_denied", "entryInstruction": "0x%08x" % insn,
            "defines": defines, "marker": "private refcounted group_info",
            "globalEnforcingWritten": False, "seccomp": seccomp, "currentModel": current,
            "layout": layout}


def derive_l3_policy_bridge(image: bytes, symbols: SymbolTable, probe_size: int) -> dict:
    inputs = SimpleNamespace(PROJECT_ROOT=PROJECT_ROOT, Btf=Btf, BTF_STRUCT=BTF_STRUCT,
                             BTF_UNION=BTF_UNION, BTF_SKIP=BTF_SKIP)
    return policy_build.derive(inputs, image, symbols, probe_size)


def derive_l3_boot_sdk(image: bytes, symbols: SymbolTable, probe_size: int, bridge: dict) -> dict:
    inputs = SimpleNamespace(Btf=Btf)
    return boot_sdk_build.derive(inputs, image, symbols, probe_size, bridge)


def derive_l3_facts(image: bytes, symbols: SymbolTable, contract: dict) -> dict:
    """Everything maplesu_l3 is built from, read out of the image.

    Three independent statements about the callback ABI live here:

      * the per-callback dispatch id, derived from the image's own compare sites and
        the `struct kprobe` member each one reads (`derive_kprobe_handler_ids`);
      * the two pinned toolchain ids (`L3_CALLBACK_PINS`), which the reference TU
        has to reproduce -- a toolchain that hashes the prototype differently is a
        build failure, not a surprise;
      * the contract's `l2.kcfiIds` block, when this contract carries one, which
        records the same pair for the L2 module.

    The dispatch ids are the authority for the *artifact*: on 6.6.66/6.1 they equal
    the pins, on 6.12/6.18 they do not (0xc073bc77 / 0x02bb4397 for the same
    prototypes), which is exactly the drift the prefix write exists for.
    """
    kprobe = derive_kprobe_handler_ids(image, symbols, contract)

    l2_ids = ((contract.get("l2") or {}).get("kcfiIds") or {})
    contract_ids = {}
    for key in ("preHandler", "postHandler"):
        recorded = (l2_ids.get(key) or {}).get("value")
        if recorded is not None:
            contract_ids[key] = recorded

    prctl = symbols.address(L3_PRCTL_SYMBOL)
    if prctl is None:
        raise GateFailure("symbol table has no %s; the kprobe has no target"
                          % L3_PRCTL_SYMBOL)
    if prctl < 4:
        raise GateFailure("%s at 0x%x has no room for a KCFI prefix"
                          % (L3_PRCTL_SYMBOL, prctl))
    cred = derive_cred_source(image, symbols)
    selinux = contract.get("selinuxState") or {}
    policy = str(((contract.get("l3") or {}).get("callbackPrefixPolicy")
                  or L3_PREFIX_POLICY_NONE))
    bridge = derive_l3_policy_bridge(image, symbols, contract["kprobe"]["size"])
    return {
        "kprobe": kprobe,
        "callbackPins": {"preHandler": "0x%08x" % L3_CALLBACK_PINS["preHandler"],
                         "postHandler": "0x%08x" % L3_CALLBACK_PINS["postHandler"]},
        "callbackDispatch": {key: {"typeId": kprobe["dispatch"][key]["typeId"],
                                   "member": kprobe["dispatch"][key]["member"],
                                   "anchorsPresent": kprobe["dispatch"][key]
                                                     ["anchorsPresent"],
                                   "anchorsAbsent": kprobe["dispatch"][key]
                                                    ["anchorsAbsent"]}
                             for key in ("preHandler", "postHandler")},
        "callbackPrefixPolicy": policy,
        "contractCallbacks": contract_ids,
        "callbackSize": int((contract.get("kprobe") or {}).get("size") or 0),
        "probedSymbol": {"symbol": L3_PRCTL_SYMBOL, "address": "0x%x" % prctl,
                         "prefixTypeId": "0x%08x" % read_uint32(image, prctl - 4),
                         "entryInstruction": "0x%08x" % read_uint32(image, prctl)},
        "selinux": {"size": selinux.get("size"),
                    "enforcingOffset": selinux.get("enforcingOffset"),
                    "writtenByModule": False},
        "credSource": cred,
        "rootSubject": derive_l3_subject_facts(image, symbols),
        "statusView": derive_l3_status_view(image, symbols, contract),
        "policyBridge": bridge,
        "bootSdk": derive_l3_boot_sdk(image, symbols, contract["kprobe"]["size"], bridge),
    }


def derive_l3_status_view(image: bytes, symbols: SymbolTable, contract: dict) -> dict:
    # Layout and the state/no-state function ABI are resolved from the running
    # kernel before the first status-page call, not pinned to a release string.
    required = ("vm_map_ram", "vm_unmap_ram", "mutex_lock", "mutex_unlock", "try_module_get",
        "selinux_kernel_status_page", "simple_read_from_buffer", "copy_from_user_nofault",
        "copy_to_user_nofault", "sel_read_handle_status")
    if any(symbols.address(name) is None for name in required):
        return {}
    entry = read_uint32(image, symbols.address("sel_read_handle_status"))
    if entry not in (0xd503245f, 0xd503233f, 0xd503201f) and entry & 0xffc00000 not in (0xf9400000, 0xb9400000):
        raise GateFailure("status read redirection needs an entry/PAC ABI audit: 0x%08x" % entry)
    return {"MAPLESU_L3_STATUS_HIDE": 1}


# The reference TU. Two functions with the kernel's own prototype spelling,
# compiled with this build's own flags: they measure what clang derives for the
# kernel's two callback types. That measurement is the *toolchain* statement; the
# artifact's shipped prefix is the *image's* dispatch id (the two coincide on
# 6.6.66/6.1 and differ on 6.12/6.18, where the builder writes the image's id over
# the emitted bytes).
L3_REFERENCE_TU = """\
/*
 * The kprobe callback prototypes, compiled with the same flags as the module.
 *
 * Nothing here is linked into maplesu_l3.ko: this translation unit exists so the
 * builder can ask the pinned toolchain what KCFI type id it derives for
 * `int (*)(struct kprobe *, struct pt_regs *)` and
 * `void (*)(struct kprobe *, struct pt_regs *, unsigned long)` -- the two callback
 * types the kernel's kprobe dispatch points check.
 *
 * The addresses are taken (the array below) because a prefix is emitted for
 * address-taken functions; without that, a future clang could legitimately emit
 * no prefix here and the comparison would be vacuous.
 */
struct kprobe;
struct pt_regs;

%(PRE_PROTOTYPE)s
{
\t(void)p;
\t(void)regs;
\treturn 0;
}

%(POST_PROTOTYPE)s
{
\t(void)p;
\t(void)regs;
\t(void)flags;
}

void *maplesu_l3_ref_taken[2] = {
\t(void *)maplesu_l3_ref_pre,
\t(void *)maplesu_l3_ref_post,
};
"""


def derive_kprobe_callback_ids(clang: Path, out_dir: Path) -> dict:
    """Compile the reference TU and read the two KCFI type ids it emits.

    Returns the ids (as ints), the source/object digests and the command, so a
    build record can name exactly what was compiled. The caller is responsible for
    checking the ids against the image's `kprobe_breakpoint_handler` set; this
    function only measures the toolchain.
    """
    out_dir = Path(out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    source = out_dir / "l3-kcfi-reference.c"
    object_path = out_dir / "l3-kcfi-reference.o"
    text = L3_REFERENCE_TU % {"PRE_PROTOTYPE": L3_REF_PRE_PROTOTYPE,
                              "POST_PROTOTYPE": L3_REF_POST_PROTOTYPE}
    source.write_text(text, encoding="utf-8", newline=chr(10))
    runner = Runner()
    record = runner([clang, *CLANG_FLAGS, source, "-o", object_path])
    elf = Elf(object_path)
    ids = {}
    for key, symbol_name in (("preHandler", "maplesu_l3_ref_pre"),
                             ("postHandler", "maplesu_l3_ref_post")):
        symbol = elf.symbol(symbol_name)
        if symbol is None:
            raise GateFailure("the reference TU has no %s symbol" % symbol_name)
        offset = elf.symbol_file_offset(symbol)
        if offset is None or offset < 4:
            raise GateFailure("%s in the reference TU has no KCFI prefix"
                              % symbol_name)
        ids[key] = read_uint32(elf.raw, offset - 4)
    return {
        "preHandler": ids["preHandler"],
        "postHandler": ids["postHandler"],
        "preHandlerHex": "0x%08x" % ids["preHandler"],
        "postHandlerHex": "0x%08x" % ids["postHandler"],
        "source": {"path": rel(source), "sha256": sha256_file(source),
                   "bytes": source.stat().st_size},
        "object": {"path": rel(object_path), "sha256": sha256_file(object_path),
                   "bytes": object_path.stat().st_size},
        "commands": record["argv"],
        "flags": list(CLANG_FLAGS),
        "prototypes": [L3_REF_PRE_PROTOTYPE, L3_REF_POST_PROTOTYPE],
    }


# One measurement per process: the reference TU depends only on the compiler and
# on CLANG_FLAGS, both constants of this file. L3-1 (and the tests) may ask for it
# more than once per run, and recompiling the same six lines each time would make
# a gate suite slow for no extra evidence.
_L3_CALLBACK_CACHE: dict = {}


def kprobe_callback_ids(work_dir: Path | None = None,
                        ndk: Path | None = None) -> dict:
    """The reference TU's ids, measured once per process (cached)."""
    key = tuple(CLANG_FLAGS)
    if key in _L3_CALLBACK_CACHE:
        return _L3_CALLBACK_CACHE[key]
    clang = find_tool(Path(ndk) if ndk else DEFAULT_NDK, "clang")
    scratch = Path(work_dir) if work_dir else Path(
        tempfile.mkdtemp(prefix="maplesu-l3-kcfi-"))
    try:
        result = derive_kprobe_callback_ids(clang, scratch)
    finally:
        if work_dir is None:
            shutil.rmtree(scratch, ignore_errors=True)
    _L3_CALLBACK_CACHE[key] = result
    return result


class VerifyContext:
    def __init__(self, module: Path, contract: dict, contract_path: Path,
                 image_path: Path, log: Log, cflags: list | None = None,
                 helper_path: str | None = None, module_key: str = "maplesu_root",
                 header_path: Path | None = None,
                 l3_callbacks: dict | None = None,
                 l3_prefix_measurement: dict | None = None,
                 image_facts: dict | None = None,
                 l3_image_facts: dict | None = None,
                 image_layouts: dict | None = None):
        self.module = Path(module)
        # The header the compiler actually saw. It is a per-family rendering with
        # literal sizes and offsets, so the gates must parse that same file.
        self.header = Path(header_path) if header_path else             (MODULE_DIR / "kernel_compat.h")
        self.contract = contract
        self.contract_path = Path(contract_path)
        self.image_path = Path(image_path)
        self.log = log
        self.cflags = list(cflags) if cflags else None
        # Which artifact this run is a statement about. It selects the gate set
        # (G1-G7 for the root module, G1-G14 for L2, G1-G7 + L3-* for L3) and it
        # is where the module-generic gates read their identity (name, source,
        # modinfo, imports) from, instead of from the root-only contract keys.
        self.module_key = module_key
        self.is_l2 = module_key == L2_MODULE
        self.is_l3 = module_key == L3_MODULE
        self.l2 = (contract.get("l2") or {}) if self.is_l2 else {}
        if self.is_l2:
            self.module_name = self.l2["module"]["name"]
            self.source_path = L2_SOURCE
            self.glue_template = L2_GLUE_TEMPLATE
        elif self.is_l3:
            self.module_name = L3_MODULE
            self.source_path = L3_SOURCE
            self.glue_template = L3_GLUE_TEMPLATE
        else:
            self.module_name = contract["module"]
            self.source_path = MODULE_DIR / "maplesu_root.c"
            self.glue_template = MODULE_DIR / "root_glue.S.in"
        self.source = self.source_path.read_text(encoding="utf-8")
        # The helper path this verification is a statement about. Default: the
        # contract's D-5 policy path. `--helper-path` exists so a future delivery
        # (for example the design's "stage to DE and pass the path in argv" escape
        # hatch) can be verified without editing the contract; step-3 uses the
        # default, and G5 records which source was used.
        self.helper_path = helper_path or contract["helperPathPolicy"]["policyPath"]
        self.helper_path_source = "cli" if helper_path else "contract"
        self.elf = Elf(self.module)
        self.image = self.image_path.read_bytes()
        self.symbols = SymbolTable(PROJECT_ROOT / contract["symbolsPath"]
                                   if not Path(contract["symbolsPath"]).is_absolute()
                                   else Path(contract["symbolsPath"]))
        btf_magic = contract.get("btf", {}).get("magicOffset")
        self.image_layouts = image_layouts
        self.btf = None if image_layouts else Btf(self.image, btf_magic)
        if btf_magic is not None and self.btf is not None and self.btf.blob_offset != btf_magic:
            raise GateFailure("BTF magic is at 0x%x, contract says 0x%x"
                              % (self.btf.blob_offset, btf_magic))
        self.image_facts = (image_facts if image_facts is not None else
                            derive_image_facts(self.image, self.symbols, contract))
        # L3's facts live beside them: the kprobe callback ids the image compares,
        # the registration target and the cred source are all read from this
        # image, never from the C file.
        self.l3_facts = (l3_image_facts if l3_image_facts is not None else
                         derive_l3_facts(self.image, self.symbols, contract)
                         if self.is_l3 else None)
        # The reference TU's ids, when the caller already compiled them (run_l3
        # does, in both build and --verify-only mode). L3-1 falls back to
        # measuring them itself, so a context built by a test or by another tool
        # is judged by the same evidence instead of by a default.
        self.l3_callbacks = l3_callbacks
        # The prefix measurement a fresh build made, when there is one: what clang
        # emitted before the image write, what shipped, and whether a rewrite
        # happened. None on --verify-only and in tests, where L3-1 judges the
        # artifact's bytes alone.
        self.l3_prefix_measurement = l3_prefix_measurement
        self._kcfi_sites = None

    # -- shared helpers ---------------------------------------------------- #
    def kcfi(self, label: str) -> int:
        return int(self.image_facts[label]["prefixTypeId"], 16)

    def callback_ids(self) -> dict:
        """The KCFI ids the pinned toolchain emits for the kprobe prototypes.

        Cached per process (see kprobe_callback_ids): the reference TU depends on
        the compiler and on CLANG_FLAGS only, and L3-1 may be evaluated for
        several mutated artifacts in one test run.
        """
        if self.l3_callbacks is not None:
            return self.l3_callbacks
        return kprobe_callback_ids()

    def suffix(self, vermagic: str) -> str:
        return " " + " ".join(vermagic.split(" ")[1:])

    @property
    def gates(self) -> list:
        if self.is_l2:
            return GATES_L2
        if self.is_l3:
            return GATES_L3
        return GATES

    def image_kcfi_sites(self) -> list:
        """Every kCFI dispatch check in the image, decoded from its own bytes.

        The shape is the one the committed generator uses
        (contracts/derive_contract.py:find_kcfi_site): movk w17,#lo ;
        movk w17,#hi,lsl#16 ; cmp ; b.eq +8 ; brk ; blr. Matching all six words is
        what makes a hit a dispatch site rather than any 32-bit constant, and it
        is what lets G8 assert that a pinned id is the id this image compares at
        the address the contract names, instead of trusting the JSON.
        """
        if self._kcfi_sites is not None:
            return self._kcfi_sites
        image = self.image
        sites = []
        limit = len(image) - 24
        unpack = struct.unpack_from
        for offset in range(0, limit, 4):
            low = unpack("<I", image, offset)[0]
            if (low & 0xFFE0001F) != 0x72800000 | (17 << 5) | 17:
                continue
            high = unpack("<I", image, offset + 4)[0]
            if (high & 0xFFE0001F) != 0x72A00000 | (1 << 21) | (17 << 5) | 17:
                continue
            if unpack("<I", image, offset + 12)[0] != 0x54000040:      # b.eq +8
                continue
            if (unpack("<I", image, offset + 16)[0] & 0xFFE0001F) != 0xD4200000:
                continue                                              # brk
            if (unpack("<I", image, offset + 20)[0] & 0xFFFFFC1F) != 0xD63F0000:
                continue                                              # blr
            sites.append({"fileOffset": offset,
                          "typeId": ((((high >> 5) & 0xFFFF) << 16)
                                     | ((low >> 5) & 0xFFFF)),
                          "compareInstruction": unpack("<I", image, offset + 8)[0]})
        self._kcfi_sites = sites
        return sites

    # -- per-module identity the generic gates read ------------------------- #
    def this_module_layout(self) -> dict:
        if self.is_l2:
            return self.l2["module"]["thisModule"]
        return self.contract["thisModule"]

    def modinfo_required(self) -> list:
        if self.is_l2:
            return self.l2["module"]["modinfo"]["required"]
        required = list(self.contract["modinfo"]["required"])
        if not self.is_l3:
            return required
        # The contract's `modinfo.required` names maplesu_root; the L3 artifact's
        # name is a different fact, and the contract is not allowed to say two
        # things about one artifact. Substitute only the `name=` row from the
        # builder's own constant, so G1 still checks every other tag against the
        # contract verbatim.
        return ["name=" + self.module_name if entry.split("=", 1)[0] == "name"
                else entry for entry in required]

    def expected_imports(self) -> set:
        """The complete undefined-symbol set this artifact must have.

        The root module tolerates `memset` only if the compiler really emitted it
        (G3 reports it either way); the L2 module has no tolerance at all, because
        its import set is the contract's `importSetFull` (six M4 exports + the
        cookie parameter) and a compiler helper would be a new dependency nobody
        reviewed. L3 has exactly three imports and no tolerance either.
        """
        if self.is_l2:
            return set(L2_EXPECTED_IMPORTS)
        expected = set(L3_EXPECTED_IMPORTS if self.is_l3 else EXPECTED_IMPORTS)
        if self.contract.get("printkSymbol") == "printk":
            expected.remove("_printk")
            expected.add("printk")
        if not self.is_l3 and "memset" in self.elf.imports():
            expected |= TOLERATED_IMPORTS
        return expected


# --------------------------------------------------------------------------- #
# G1 -- layout.
# --------------------------------------------------------------------------- #

def gate_layout(ctx: VerifyContext, gate: Gate) -> None:
    elf, contract = ctx.elf, ctx.contract
    layout = ctx.this_module_layout()

    gate.require(elf.e_type == ET_REL, "ELF is ET_REL",
                 "e_type=%d (the loader requires a relocatable object)" % elf.e_type)
    gate.require(elf.e_machine == EM_AARCH64, "ELF is AArch64",
                 "e_machine=%d" % elf.e_machine)
    gate.require(elf.e_shentsize == 64, "section headers are 64 bytes",
                 "e_shentsize=%d" % elf.e_shentsize)
    gate.require(elf.e_shstrndx != 0 and elf.e_shstrndx < elf.e_shnum,
                 "section name table is addressable", "e_shstrndx=%d" % elf.e_shstrndx)
    # Bounds before anything is read out of a section: a malformed module must be a
    # gate failure, not a traceback.
    for section in elf.sections[1:]:
        if section["type"] == SHT_NOBITS:
            continue
        gate.require(section["offset"] + section["size"] <= len(elf.raw),
                     "section %r stays inside the file" % section["name"],
                     "ends at 0x%x of %d bytes"
                     % (section["offset"] + section["size"], len(elf.raw)))
    shstr = elf.sections[elf.e_shstrndx]
    gate.require(shstr["size"] > 0
                 and shstr["offset"] + shstr["size"] <= len(elf.raw)
                 and elf.raw[shstr["offset"] + shstr["size"] - 1] == 0,
                 "section name table is NUL terminated",
                 "size 0x%x at 0x%x" % (shstr["size"], shstr["offset"]))
    zero = elf.sections[0]
    gate.require(zero["type"] == SHT_NULL and zero["size"] == 0 and zero["addr"] == 0,
                 "section 0 is the empty SHT_NULL")
    symtabs = [s for s in elf.sections if s["type"] == SHT_SYMTAB]
    gate.require(len(symtabs) == 1, "exactly one symtab",
                 "found %d (the loader rejects a stripped module)" % len(symtabs))
    gate.require(symtabs[0]["link"] not in (0,) and symtabs[0]["link"] < elf.e_shnum,
                 "symtab sh_link points at its string table",
                 "sh_link=%d" % symtabs[0]["link"])

    modules = elf.sections_named(".gnu.linkonce.this_module")
    modinfo = elf.sections_named(".modinfo")
    gate.require(len(modules) == 1, "exactly one .gnu.linkonce.this_module",
                 "found %d" % len(modules))
    gate.require(len(modinfo) == 1, "exactly one .modinfo section",
                 "found %d" % len(modinfo))

    this_module = modules[0]
    measured_module = (ctx.image_layouts["module"] if ctx.image_layouts else
                       ctx.btf.struct("module"))
    gate.require(this_module["type"] != SHT_NOBITS,
                 "this_module has a size set (not NOBITS)")
    gate.require(bool(this_module["flags"] & SHF_ALLOC),
                 "this_module occupies memory (SHF_ALLOC)")
    gate.require(this_module["size"] == layout["size"],
                 "this_module size == contract sizeof(struct module)",
                 "0x%x vs 0x%x" % (this_module["size"], layout["size"]))
    gate.require(this_module["size"] == measured_module["size"],
                 "this_module size == measured sizeof(struct module)",
                 "0x%x vs measured 0x%x (%s)"
                 % (this_module["size"], measured_module["size"],
                    ctx.image_layouts["source"] if ctx.image_layouts else "image BTF"))

    body = elf.section_bytes(this_module)
    name_at = layout["nameOffset"]
    embedded = body[name_at:name_at + 56].split(b"\x00")[0].decode("utf-8", "replace")
    gate.require(embedded == ctx.module_name,
                 "module name embedded at this_module+0x%x" % name_at,
                 "found %r, expected %r" % (embedded, ctx.module_name))
    gate.require(len(ctx.module_name) + 1 <= 56,
                 "module name fits char[56]",
                 "%d bytes with the terminator" % (len(ctx.module_name) + 1))

    relocs = elf.relocations(".gnu.linkonce.this_module")
    by_offset = {entry["offset"]: entry for entry in relocs}
    init_reloc = by_offset.get(layout["initOffset"])
    exit_reloc = by_offset.get(layout["exitOffset"])
    gate.require(init_reloc is not None and init_reloc["type"] == R_AARCH64_ABS64,
                 "ABS64 relocation at this_module.init (+0x%x)" % layout["initOffset"],
                 "found %r" % (init_reloc,))
    gate.require(init_reloc["symbol"] == "init_module",
                 "this_module.init resolves to init_module",
                 "found %r" % init_reloc["symbol"])
    gate.require(exit_reloc is not None and exit_reloc["type"] == R_AARCH64_ABS64,
                 "ABS64 relocation at this_module.exit (+0x%x)" % layout["exitOffset"],
                 "found %r" % (exit_reloc,))
    gate.require(exit_reloc["symbol"] == "cleanup_module",
                 "this_module.exit resolves to cleanup_module",
                 "found %r" % exit_reloc["symbol"])
    unexpected = sorted(set(by_offset) - {layout["initOffset"], layout["exitOffset"]})
    gate.require(not unexpected, "no other relocation inside this_module",
                 "unexpected offsets: %s" % [hex(value) for value in unexpected])

    tags = {}
    for entry in elf.section_bytes(".modinfo").split(b"\x00"):
        if not entry:
            continue
        key, _, value = entry.partition(b"=")
        tags.setdefault(key.decode("utf-8", "replace"),
                        value.decode("utf-8", "replace"))
    for required in ctx.modinfo_required():
        key = required.split("=", 1)[0]
        if key == "vermagic":
            gate.require(tags.get("vermagic") == contract["vermagic"],
                         "modinfo vermagic is the exact contract string",
                         "found %r" % tags.get("vermagic"))
        else:
            gate.require(tags.get(key) == required.split("=", 1)[1],
                         "modinfo carries %s" % required, "found %r" % tags.get(key))
    gate.require(ctx.suffix(tags.get("vermagic", "")) == contract["vermagicSuffix"],
                 "vermagic suffix matches (the part same_magic compares)",
                 "%r vs %r" % (ctx.suffix(tags.get("vermagic", "")),
                               contract["vermagicSuffix"]))
    gate.check(tags.get("description") is not None,
               "modinfo carries a description",
               "found %r" % tags.get("description"), fatal=False)

    # The two places the kernel itself reads must agree; the file name is a packaging
    # convention (the syscall takes an fd, so the kernel never sees it), so it is
    # reported but not fatal -- --verify-only may legitimately be pointed at a copy.
    gate.require(embedded == tags.get("name"),
                 "module name agrees in .modinfo and this_module",
                 "%r vs %r" % (embedded, tags.get("name")))
    gate.check(tags.get("name") == ctx.module.stem,
               "output file is named after the module",
               "%s vs %r" % (ctx.module.name, tags.get("name")), fatal=False)

    versions = elf.section("__versions")
    gate.require(versions is not None, "__versions present",
                 "absence makes the loader call try_to_force_load(), which is fatal "
                 "here (CONFIG_MODULE_FORCE_LOAD is off)")
    gate.require(versions["size"] % 64 == 0, "__versions is a whole number of entries",
                 "size 0x%x" % versions["size"])
    raw_versions = elf.section_bytes(versions)
    names = []
    for index in range(versions["size"] // 64):
        raw = raw_versions[index * 64 + 8:(index + 1) * 64]
        names.append(raw.split(b"\x00")[0].decode("utf-8", "replace"))
    gate.require("module_layout" not in names,
                 "no module_layout CRC is pinned (we cannot supply one)",
                 "entries: %r" % names)
    gate.check(versions["size"] == 64,
               "__versions is the single empty-name entry the glue emits",
               "%d entries" % (versions["size"] // 64), fatal=False)


# --------------------------------------------------------------------------- #
# G2 -- PLT sections (arm64's module_finalize refuses the module without them).
# --------------------------------------------------------------------------- #

def gate_plt(ctx: VerifyContext, gate: Gate) -> None:
    elf = ctx.elf
    for name in (".plt", ".init.plt"):
        sections = elf.sections_named(name)
        gate.require(len(sections) == 1, "%s exists exactly once" % name,
                     "found %d (the kernel prints 'module PLT section(s) missing' "
                     "and returns -ENOEXEC)" % len(sections))
        section = sections[0]
        gate.require(section["type"] == SHT_PROGBITS, "%s is PROGBITS" % name,
                     "type=%s" % SECTION_TYPE_NAMES.get(section["type"], section["type"]))
        gate.require(bool(section["flags"] & SHF_ALLOC)
                     and bool(section["flags"] & SHF_EXECINSTR),
                     "%s is allocated and executable" % name,
                     "flags=0x%x" % section["flags"])
        gate.require(section["size"] >= 1, "%s carries the kbuild placeholder byte"
                     % name, "size=%d" % section["size"])

    sections = elf.sections_named(".text.ftrace_trampoline")
    gate.check(len(sections) == 1, ".text.ftrace_trampoline exists (kbuild parity)",
               "found %d" % len(sections), fatal=False)
    if sections:
        gate.check(bool(sections[0]["flags"] & SHF_ALLOC),
                   ".text.ftrace_trampoline is allocated",
                   "flags=0x%x" % sections[0]["flags"], fatal=False)
    gate.check(True, "ftrace is inert on this build",
               "CONFIG_FUNCTION_TRACER / CONFIG_DYNAMIC_FTRACE are not set, so "
               "nothing patches module text; the section is carried for parity only",
               fatal=False)


# --------------------------------------------------------------------------- #
# G3 -- importability against this image's export table.
# --------------------------------------------------------------------------- #

def gate_imports(ctx: VerifyContext, gate: Gate) -> None:
    elf, contract = ctx.elf, ctx.contract
    imports = elf.imports()
    gate.extras["imports"] = imports

    expected = ctx.expected_imports()
    extra = sorted(set(imports) - expected)
    missing = sorted(expected - set(imports))
    gate.require(not extra, "no unexpected undefined symbol",
                 "extra: %s -- a compiler helper means a flag or source change is "
                 "needed, not a whitelist edit" % extra)
    gate.require(not missing,
                 "all %d deliberate imports are present (%s)"
                 % (len(expected), ", ".join(sorted(expected))),
                 "missing: %s" % missing)

    helpers = [name for name in imports
               if name.startswith(COMPILER_HELPER_PREFIXES)
               or name in ("memcpy", "memmove", "__memcpy", "__memset")]
    gate.require(not helpers, "no compiler-runtime or libc helper is imported",
                 "found: %s" % helpers)

    export_table = contract.get("exportTable", {})
    plain = export_table.get("__ksymtab", {})
    gpl = export_table.get("__ksymtab_gpl", {})
    classes = {}
    for name in imports:
        rows = ctx.symbols.rows.get("__ksymtab_" + name)
        if not rows:
            gate.require(False, "%s is exported by this image" % name,
                         "no __ksymtab_%s row: the loader would fail with "
                         "'Unknown symbol'" % name)
            continue
        address = rows[0][0]
        if gpl and gpl.get("start", 0) <= address < gpl.get("stop", 0):
            classes[name] = "EXPORT_SYMBOL_GPL (0x%x)" % address
        elif plain and plain.get("start", 0) <= address < plain.get("stop", 0):
            classes[name] = "EXPORT_SYMBOL (0x%x)" % address
        else:
            gate.require(False, "%s is inside __ksymtab" % name,
                         "__ksymtab_%s at 0x%x is outside both export ranges"
                         % (name, address))
            classes[name] = "outside"
    gpl_only = [name for name, kind in classes.items() if "GPL" in kind]
    if gpl_only:
        gate.require(contract["license"] == "GPL",
                     "GPL-only imports are legal under this license",
                     "%s resolve through __ksymtab_gpl, license is %r"
                     % (gpl_only, contract["license"]))
    gate.extras["importClasses"] = classes

    forbidden = [entry["name"] for entry in contract.get("forbiddenSymbolsAbsent", [])]
    present = [name for name in forbidden if name in imports]
    gate.require(not present, "no reference to a symbol this image does not export",
                 "found: %s" % present)

    if ctx.is_l2:
        # The contract's own complete set (`importSetFull` = six M4 + the cookie
        # ops) must equal the builder's, and the delta must be exactly the
        # declared cookie parameter. Two independent statements of the same fact:
        # an accidental eighth import fails both, a contract edit alone fails
        # here because L2_EXPECTED_IMPORTS is written in this file.
        declared = [row.get("name") for row in ctx.l2.get("importsCookieParam") or []
                    if row.get("name")]
        full = [name for name in ctx.l2.get("importSetFull") or []
                if isinstance(name, str)]
        gate.require(sorted(full) == sorted(L2_EXPECTED_IMPORTS),
                     "contract importSetFull and builder agree on the 7 imports",
                     "%s vs %s" % (full, sorted(L2_EXPECTED_IMPORTS)))
        gate.require(sorted(declared) == [L2_COOKIE_PARAM],
                     "the one non-M4 import is the declared cookie parameter",
                     "declared=%s, builder allows exactly %r"
                     % (declared, L2_COOKIE_PARAM))
        gate.require(set(imports) == L2_EXPECTED_IMPORTS,
                     "the complete undefined set is the 7-import set",
                     "undefined=%s" % sorted(set(imports)))
        danger = [name for name in L2_FORBIDDEN_SYMBOLS if name in imports]
        gate.require(not danger,
                     "no forbidden helper or policy symbol is imported",
                     "found: %s" % danger)
    elif ctx.is_l3:
        # L3's import set is three names and the contract has to cover all three
        # (they are the only exported symbols the design names). No tolerance and
        # no second statement to reconcile: L3_EXPECTED_IMPORTS is the only list,
        # and the gate above already required the object to be exactly it.
        contract_imports = [entry["name"]
                            for entry in contract.get("requiredSymbols", [])
                            if entry.get("imported")]
        gate.require(expected <= set(contract_imports),
                     "the contract covers all three L3 imports",
                     "%s vs %s" % (sorted(contract_imports),
                                   sorted(expected)))
        gate.require(set(imports) == expected,
                     "the complete undefined set is the measured three-import set",
                     "undefined=%s" % sorted(set(imports)))
        danger = [name for name in ("call_usermodehelper", "call_usermodehelper_setup",
                                    "call_usermodehelper_exec", "selinux_state",
                                    "security_setenforce", "avc_denied",
                                    "aarch64_insn_patch_text", "text_poke")
                  if name in imports]
        gate.require(not danger, "no handover or policy helper is imported",
                     "found: %s" % danger)
    else:
        contract_imports = [entry["name"]
                            for entry in contract.get("requiredSymbols", [])
                            if entry.get("imported")]
        if contract_imports:
            gate.require(expected - TOLERATED_IMPORTS <= set(contract_imports),
                         "contract covers every required import",
                         "%s vs %s" % (contract_imports, sorted(expected)))


# --------------------------------------------------------------------------- #
# G4 -- cleanup_module is defined and is this_module.exit.
# --------------------------------------------------------------------------- #

def gate_cleanup(ctx: VerifyContext, gate: Gate) -> None:
    elf, contract = ctx.elf, ctx.contract
    symbol = elf.symbol("cleanup_module")
    gate.require(symbol is not None and symbol["shndx"] != 0,
                 "cleanup_module is defined",
                 "the glue emits `.quad cleanup_module`, so a missing definition is "
                 "an undefined symbol the loader refuses")
    section = elf.symbol_section(symbol)
    gate.require(section is not None, "cleanup_module lives in a section")
    gate.require(bool(section["flags"] & SHF_EXECINSTR),
                 "cleanup_module lives in executable text",
                 "section %r flags 0x%x" % (section["name"], section["flags"]))
    gate.require(section["name"] != ".init.text",
                 "cleanup_module is not in .init.text",
                 "it is in %r; .init.text is freed after do_one_initcall() returns, "
                 "so an exit symbol there would be unreachable at delete_module()"
                 % section["name"])
    gate.check(section["name"] == ".text",
               "cleanup_module is in permanent .text",
               "section %r" % section["name"], fatal=False)
    gate.require(symbol["size"] > 0, "cleanup_module has a body",
                 "size=%d" % symbol["size"])

    layout = contract["thisModule"]
    exit_reloc = next((entry for entry in
                       elf.relocations(".gnu.linkonce.this_module")
                       if entry["offset"] == layout["exitOffset"]), None)
    gate.require(exit_reloc is not None
                 and exit_reloc["symbol"] == "cleanup_module"
                 and exit_reloc["type"] == R_AARCH64_ABS64,
                 "this_module.exit is the ABS64 address of cleanup_module",
                 "found %r" % (exit_reloc,))
    gate.check(True, "return 0 means handed over and resident",
               "source-level property (module/maplesu_root.c:186-187): the success "
               "path prints 'handover started; resident' and returns 0; the binary "
               "guarantee here is that the restore point stays reachable",
               fatal=False)

    init_symbol = elf.symbol("init_module")
    gate.require(init_symbol is not None and init_symbol["shndx"] != 0,
                 "init_module is defined")
    init_section = elf.symbol_section(init_symbol)
    gate.check(init_section is not None and init_section["name"] == ".init.text",
               "init_module is in .init.text",
               "section %r" % (init_section["name"] if init_section else None),
               fatal=False)


# --------------------------------------------------------------------------- #
# G5 -- the patch plan carries no address, and the handover mode is right.
# --------------------------------------------------------------------------- #

def gate_patch_plan(ctx: VerifyContext, gate: Gate) -> None:
    elf, contract = ctx.elf, ctx.contract
    raw = elf.raw
    if ctx.is_l2:
        gate_patch_plan_l2(ctx, gate)
        return
    if ctx.is_l3:
        gate_patch_plan_l3(ctx, gate)
        return
    gate.require(elf.section(".ir_spec") is None, "no .ir_spec section",
                 "the old per-release byte-patch blob must not come back")
    banner = raw.find(b"#maplesu-ir-spec")
    gate.require(banner < 0, "no #maplesu-ir-spec banner anywhere in the file",
                 "absent" if banner < 0 else "found at file offset 0x%x" % banner)

    scanned = []
    hits = []
    for section in elf.sections:
        if not section["flags"] & SHF_ALLOC:
            continue
        if section["type"] in (SHT_NOBITS,):
            continue
        blob = elf.section_bytes(section)
        scanned.append((section["name"], len(blob)))
        for offset in range(max(0, len(blob) - 7)):
            value = struct.unpack_from("<Q", blob, offset)[0]
            if KERNEL_VA_LOW <= value <= KERNEL_VA_HIGH:
                hits.append("%s+0x%x = 0x%x" % (section["name"], offset, value))
    gate.require(not hits, "no kernel virtual address in any allocated section",
                 "0 hits in %d bytes across %d sections"
                 % (sum(size for _name, size in scanned), len(scanned))
                 if not hits else "a per-release address table looks like this: %s"
                 % hits[:5])
    gate.check(True, "scanned %d allocated sections for kernel VAs"
               % len(scanned), ", ".join("%s(%d)" % item for item in scanned),
               fatal=True)

    addend_hits = []
    for target, relocs in elf.all_relocations().items():
        for entry in relocs:
            if KERNEL_VA_LOW <= entry["addend"] & 0xFFFFFFFFFFFFFFFF <= KERNEL_VA_HIGH:
                addend_hits.append("%s+0x%x -> 0x%x"
                                   % (target, entry["offset"], entry["addend"]))
    gate.require(not addend_hits, "no relocation addend is a kernel address",
                 "%s" % addend_hits[:5])

    type_counts: dict = {}
    unknown = set()
    for target, relocs in elf.all_relocations().items():
        for entry in relocs:
            type_counts[entry["type"]] = type_counts.get(entry["type"], 0) + 1
            if entry["type"] not in ARM64_MODULE_RELOCS:
                unknown.add(entry["type"])
    gate.require(not unknown, "every relocation type is one arm64 implements",
                 "unknown: %s" % sorted(unknown))
    got = sorted(set(type_counts) & GOT_RELOC_TYPES)
    gate.require(not got,
                 "no GOT relocation (no .got dependency)",
                 "none" if not got else
                 "types %s: no shipped module for this kernel has a .got, so GOT "
                 "support is an unproven dependency (drop -fno-plt)"
                 % [ARM64_MODULE_RELOCS[value] for value in got])
    gate.extras["relocationTypes"] = {str(value): type_counts[value]
                                      for value in sorted(type_counts)}

    exec_sections = [s for s in elf.sections if s["flags"] & SHF_EXECINSTR]
    waits = []
    for section in exec_sections:
        blob = elf.section_bytes(section)
        for index in range(len(blob) // 4):
            insn = read_uint32(blob, index * 4)
            if insn == MOVZ_W3_TWO:
                waits.append("%s+0x%x" % (section["name"], index * 4))
    gate.require(not waits, "no `mov w3, #2` (UMH_WAIT_PROC) anywhere in module text",
                 "none" if not waits else
                 "found at %s -- WAIT_PROC parks init_module() in kernel_wait() "
                 "while holding module_mutex" % waits)

    umh_sites = []
    for target, relocs in elf.all_relocations().items():
        section = elf.section(target)
        if section is None or not section["flags"] & SHF_EXECINSTR:
            continue
        blob = elf.section_bytes(section)
        for entry in relocs:
            if entry["type"] != 283 or entry["symbol"] != "call_usermodehelper":
                continue
            index = entry["offset"] // 4
            writer = None
            for back in range(index - 1, max(-1, index - 17), -1):
                insn = read_uint32(blob, back * 4)
                movz = decode_movz(insn)
                if movz and movz[0] == 3:
                    writer = ("movz", movz[1], back, insn)
                    break
                movk = decode_movk(insn)
                if movk and movk[0] == 3:
                    writer = ("movk", movk[1], back, insn)
                    break
                if (insn & 0xFFC00000) == 0x32000000:
                    writer = ("orr-imm", None, back, insn)
                    break
            if writer is None:
                gate.require(False, "call_usermodehelper passes a decidable w3",
                             "no w3 writer in the 16 instructions before %s+0x%x"
                             % (target, entry["offset"]))
                continue
            kind, value, back, insn = writer
            if kind != "movz":
                gate.require(False, "call_usermodehelper passes w3 = 1",
                             "nearest w3 writer at %s+0x%x is %s (0x%08x)"
                             % (target, back * 4, kind, insn))
                continue
            umh_sites.append({"section": target, "offset": entry["offset"],
                              "w3WriterOffset": back * 4, "value": value})
            gate.require(value == UMH_WAIT_EXEC,
                         "call_usermodehelper passes w3 = 1 (UMH_WAIT_EXEC)",
                         "%s+0x%x sets w3 = %d" % (target, back * 4, value))
    if not umh_sites:
        # The wrapper is resolved through kallsyms now, so there is no relocation to
        # decode. The property that mattered - the wait flag is UMH_WAIT_EXEC - is
        # still checkable: find `movz w3, #1` directly feeding an indirect call.
        # init_module lives in .init.text, so scanning only .text found nothing. Every
        # executable section is a candidate.
        for text_section in [section for section in elf.sections
                             if section["flags"] & SHF_EXECINSTR]:
            blob = elf.section_bytes(text_section)
            for back in range(0, len(blob) // 4 - 8):
                movz = decode_movz(read_uint32(blob, back * 4))
                if not movz or movz[0] != 3 or movz[1] != UMH_WAIT_EXEC:
                    continue
                for ahead in range(1, 5):
                    insn = read_uint32(blob, (back + ahead) * 4)
                    if (insn & 0xFFFFFC1F) == 0xD63F0000:  # blr xN
                        umh_sites.append({"section": text_section["name"],
                                          "offset": (back + ahead) * 4,
                                          "w3WriterOffset": back * 4,
                                          "value": movz[1], "resolved": True})
                        break
                if umh_sites:
                    break
    gate.require(bool(umh_sites),
                 "the runtime-resolved handover is called with UMH_WAIT_EXEC",
                 "found %d handover call sites (direct or indirect)" % len(umh_sites))
    gate.check(len(umh_sites) == 1, "one handover call site",
               "%d sites" % len(umh_sites), fatal=False)
    gate.extras["umhSites"] = umh_sites

    rodata_blob = b"".join(elf.section_bytes(section) for section in elf.sections
                           if section["flags"] & SHF_ALLOC
                           and section["name"].startswith(".rodata"))
    candidates = sorted({text.decode("utf-8", "replace")
                         for text in rodata_blob.split(b"\x00")
                         if text.startswith(b"/data/")})
    gate.require(len(candidates) == 1, "exactly one staged helper path constant",
                 "found %r" % candidates)
    helper = candidates[0] if candidates else ""
    gate.require(".." not in helper, "helper path has no traversal", helper)
    gate.require(not any(foreign in raw for foreign in
                         (b"/system/bin/sh", b"/data/local/tmp/", b"/sbin/",
                          b"/system/xbin/")),
                 "no foreign helper path in the module",
                 "the module must not carry a second, unexpected exec target")
    policy = contract["helperPathPolicy"]["policyPath"]
    gate.check(helper == policy, "helper path equals the contract's D-5 policy path",
               "module carries %r, contract says %r (status %r)"
               % (helper, policy, contract["helperPathPolicy"]["status"]),
               fatal=False)
    gate.check(helper == ctx.helper_path, "helper path equals the D-5 policy path",
               "module carries %r, expected %r (source: %s; contract records %s); a "
               "decision, not a build defect"
               % (helper, ctx.helper_path, ctx.helper_path_source,
                  contract["helperPathPolicy"]["status"]),
               fatal=False)
    gate.require(helper == ctx.helper_path,
                 "the module's only staged helper path is the one under test",
                 "module carries %r, expected %r (source: %s)"
                 % (helper, ctx.helper_path, ctx.helper_path_source))
    gate.extras["helperPath"] = helper
    gate.extras["helperPathExpected"] = ctx.helper_path
    gate.extras["helperPathSource"] = ctx.helper_path_source

    # R1 (design §10): the UMH argument block must be file-scope static storage, not
    # a stack local. UMH_WAIT_EXEC returns when the exec succeeds and the kernel is
    # left holding the pointers, so a stack block would be read after init_module()
    # returned. The check is a property of the object file: the three symbols exist as
    # local data objects in .bss/.data (not in .init.text, not absent because the
    # compiler folded them into a stack slot).
    storage = {}
    for name in ("maplesu_argv", "maplesu_envp", "maplesu_entry"):
        matches = [symbol for symbol in ctx.elf.defined_symbols()
                   if symbol["name"] == name]
        if not matches:
            gate.require(False, "%s is a named object in the module's data" % name,
                         "symbol %r not found with a defining section: the UMH "
                         "argument block is not file-scope static storage (R1)" % name)
            continue
        section = ctx.elf.symbol_section(matches[0])
        storage[name] = section["name"] if section else "?"
    const_data = (".data", ".bss", ".data..read_mostly", ".rodata")
    gate.require(bool(storage) and all(name in const_data for name in storage.values()),
                 "the UMH argument block lives in module data, not on the stack (R1)",
                 "sections: %s -- expected .bss/.data; a stack array would not appear "
                 "as a section-defined object at all" % storage)
    gate.extras["umhArgvStorage"] = storage

    # G5.8 (mechanics doc): the only kernel symbol *names* in the module are the ones
    # the design names. A name that exists in this image's symbol table and is not in
    # the whitelist is a lookup we cannot reason about -- the module would resolve it
    # at runtime, off-contract. Names outside the symbol table (a .bss object such as
    # selinux_state, or a vendor symbol absent from this build) cannot be told from a
    # format string, so they are listed from the source contract instead.
    whitelist = set(LOOKUP_WHITELIST)
    literals = set()
    for section in elf.sections:
        if not section["flags"] & SHF_ALLOC or section["type"] == SHT_NOBITS:
            continue
        if not section["name"].startswith(".rodata"):
            continue
        for text in elf.section_bytes(section).split(b"\x00"):
            if len(text) < 4 or len(text) > 128:
                continue
            try:
                decoded = text.decode("utf-8")
            except UnicodeDecodeError:
                continue
            if decoded.replace("_", "").isalnum():
                literals.add(decoded)
    resolvable = {name for name in literals if name in ctx.symbols.rows}
    unexpected = sorted(resolvable - whitelist)
    gate.require(not unexpected,
                 "every resolvable kernel symbol name in the module is whitelisted",
                 "unexpected: %s -- a new runtime lookup is a design change, so the "
                 "whitelist in this builder has to grow with it" % unexpected)
    present = sorted(whitelist & literals)
    missing = sorted(whitelist - literals)
    gate.check(True, "lookup names carried by the module: %s" % ", ".join(present),
               "%d of %d whitelisted names%s"
               % (len(present), len(whitelist),
                  "" if not missing else "; not carried: %s" % ", ".join(missing)),
               fatal=False)
    gate.extras["lookupNames"] = present

    # -- the handover on a static-UMH kernel (G5.9) ------------------------- #
    # CONFIG_STATIC_USERMODEHELPER=y with _PATH="" makes the four-argument wrapper a
    # *silent no-op*: setup() overwrites subprocess_info.path with a lone NUL and
    # exec() returns 0 without forking. A module that only calls the wrapper therefore
    # prints "handover started; resident" while nothing ran -- the false positive that
    # blocked the canary. The contract records both config values; when they say the
    # wrapper is dead, the module must carry the two-stage handover (resolve
    # setup/exec, call setup, write our path back at subprocess_info.path, call exec).
    static_umh = contract["config"]["staticUsermodehelper"]
    static_path = contract["config"]["staticUsermodehelperPath"]
    path_value = static_path["value"]
    path_empty = path_value in ("", "<not set>")
    gate.extras["staticUsermodehelper"] = {"enabled": bool(static_umh["satisfied"]),
                                           "path": path_value}
    path_offset = contract["structOffsets"]["subprocessInfo"]["path"]
    gate.extras["subprocessInfoPathOffset"] = path_offset
    missing_primary = [name for name in UMH_PRIMARY_LOOKUPS if name not in literals]
    if static_umh["satisfied"] and path_empty:
        gate.require(not missing_primary,
                     "the module resolves call_usermodehelper_setup/_exec",
                     "missing %s: with CONFIG_STATIC_USERMODEHELPER_PATH=%r the "
                     "four-argument wrapper cannot exec, so a module that carries only "
                     "the wrapper is a silent no-op"
                     % (missing_primary, path_value))
        stores = str_offset_sites(elf, path_offset)
        gate.require(bool(stores),
                     "the module stores the helper pointer at subprocess_info.path "
                     "(+0x%x)" % path_offset,
                     "no 64-bit store with that immediate offset in module text; that "
                     "store IS the static-UMH fix (setup() replaces the path with \"\")")
        gate.check(True, "path overwrite sites: %s" % ", ".join(stores[:4]),
                   "%d site(s) at +0x%x" % (len(stores), path_offset), fatal=False)
        gate.extras["handoverMode"] = "setup-exec"
    else:
        gate.check(True, "static-UMH handover not required by this contract",
                   "CONFIG_STATIC_USERMODEHELPER=%s path=%r"
                   % (static_umh["value"], path_value), fatal=False)
        gate.extras["handoverMode"] = "wrapper-only"
    gate.extras["umhPrimary"] = {
        "setup": UMH_PRIMARY_LOOKUPS[0],
        "exec": UMH_PRIMARY_LOOKUPS[1],
        "present": [name for name in UMH_PRIMARY_LOOKUPS if name in literals],
        "missing": missing_primary,
        "fallback": "call_usermodehelper",
    }


# --------------------------------------------------------------------------- #
# G6 -- KCFI: the prefix the kernel compares, and no unproven indirect call.
# --------------------------------------------------------------------------- #

def gate_kcfi(ctx: VerifyContext, gate: Gate) -> None:
    elf, contract = ctx.elf, ctx.contract
    facts = ctx.image_facts

    for symbol_name, label in (("init_module", "init"), ("cleanup_module", "exit")):
        symbol = elf.symbol(symbol_name)
        gate.require(symbol is not None and symbol["shndx"] != 0,
                     "%s is defined" % symbol_name)
        offset = elf.symbol_file_offset(symbol)
        gate.require(offset is not None and offset >= 4,
                     "%s has room for its KCFI prefix" % symbol_name)
        prefix = read_uint32(elf.raw, offset - 4)
        expected = int(facts[label + "CheckSite"]["kcfiTypeId"], 16)
        gate.require(prefix == expected,
                     "%s KCFI prefix == the id %s compares"
                     % (symbol_name, facts[label + "CheckSite"]["function"]),
                     "module emits 0x%08x, image compares 0x%08x at %s+%s"
                     % (prefix, expected, facts[label + "CheckSite"]["function"],
                        facts[label + "CheckSite"]["compareSiteOffset"]))
        gate.check(True, "%s prefix = 0x%08x" % (symbol_name, prefix),
                   "entry instruction 0x%08x" % read_uint32(elf.raw, offset),
                   fatal=False)
        gate.extras["prefix_" + label] = prefix

    contract_init = int(contract["kcfiInitTypeId"], 16)
    gate.require(gate.extras["prefix_init"] == contract_init,
                 "init prefix equals the contract's kcfiInitTypeId",
                 "module 0x%08x, contract 0x%08x"
                 % (gate.extras["prefix_init"], contract_init))
    gate.require(int(facts["initCheckSite"]["kcfiTypeId"], 16) == contract_init,
                 "the contract's kcfiInitTypeId is what this image compares",
                 "image compares 0x%08x at %s"
                 % (int(facts["initCheckSite"]["kcfiTypeId"], 16),
                    facts["initCheckSite"]["compareSiteAddress"]))

    # -- zero BRK: the load-bearing assertion (G6.4) ------------------------ #
    # CONFIG_PANIC_ON_OOPS=y means a kernel-mode BRK is a panic, not an oops. Every
    # indirect call in this module goes through a runtime-resolved address, so the
    # only defensible posture is "no trap can fire": MAPLESU_NOCFI on those functions
    # removes the checks, which removes every BRK clang would have emitted while
    # keeping the mandatory 4-byte init/exit prefixes asserted above.
    traps = {}
    scanned = 0
    for section in elf.sections:
        if not section["flags"] & SHF_EXECINSTR:
            continue
        blob = elf.section_bytes(section)
        scanned += len(blob)
        for index in range(len(blob) // 4):
            insn = read_uint32(blob, index * 4)
            imm = decode_brk(insn)
            if imm is None:
                continue
            traps.setdefault(imm, []).append("%s+0x%x" % (section["name"], index * 4))
    if traps:
        first_imm = sorted(traps)[0]
        trap_detail = ("%d BRK(s), first #0x%x at %s -- with CONFIG_PANIC_ON_OOPS=y "
                       "any trap is a panic"
                       % (sum(len(where) for where in traps.values()), first_imm,
                          ", ".join(traps[first_imm][:3])))
    else:
        trap_detail = "0 traps in %d bytes of module text" % scanned
    gate.require(not traps, "zero BRK instructions in every SHF_EXECINSTR section",
                 trap_detail)
    gate.extras["traps"] = {str(imm): where for imm, where in traps.items()}

    # A surviving compare site means a check survived, which cannot happen with zero
    # BRKs in clang's lowering -- so it is also a statement that __nocfi covered every
    # indirect call. Sites are reported either way.
    sites = []
    for section in elf.sections:
        if not section["flags"] & SHF_EXECINSTR:
            continue
        for hit in kcfi_type_ids(elf.section_bytes(section)):
            sites.append({"section": section["name"],
                          "offset": hit["instruction"] * 4,
                          "typeId": hit["typeId"]})
    gate.require(not sites, "no kCFI compare site is left in module text",
                 "%d site(s): %s -- a check that survived __nocfi is a trap waiting "
                 "for a type id to drift" % (len(sites),
                                             ["%s+0x%x" % (s["section"], s["offset"])
                                              for s in sites[:4]])
                 if sites else "none (every indirect call is in a __nocfi function)")
    gate.extras["kcfiSites"] = sites

    # -- the flags and the source, which the binary cannot show (G6.5) ------ #
    flags = ctx.cflags
    gate.require(flags is not None, "the build flags are recorded",
                 "no cflags in memory or in build.json: run a build, not only "
                 "--verify-only on a copied .ko")
    if flags is not None:
        gate.require(KCFI_FLAG in flags,
                     "%s is in the recorded build flags" % KCFI_FLAG,
                     "flags: %s" % " ".join(flags))
        gate.require("-ffixed-x18" in flags,
                     "-ffixed-x18 is in the recorded build flags",
                     "CONFIG_SHADOW_CALL_STACK=y: x18 is the SCS pointer and clang "
                     "will allocate it without this flag")
        gate.check(True, "recorded flags: %d" % len(flags),
                   " ".join(flag for flag in flags
                            if flag in (KCFI_FLAG, "-ffixed-x18")), fatal=False)
    source = ctx.source
    if ctx.is_l2:
        # The three callers of a runtime-resolved address. The callbacks are NOT
        # in this list on purpose: they must keep the prefix the kernel checks
        # (design 3.3.1 rule 5), and G8 asserts it is there.
        nocfi_required = ("l2_sym", "l2_kread", "l2_result_out")
    elif ctx.is_l3:
        # Every function in this module that calls through a runtime-resolved
        # address. The kprobe callback is deliberately NOT in this list: its 4-byte
        # prefix is the id the kernel's kprobe dispatch compares, so it must carry
        # the compiler's own hash of `int (*)(struct kprobe *, struct pt_regs *)`
        # (L3-1 asserts exactly that, and that the attribute is absent). The
        # indirect calls it needs live in the helpers below, one layer down.
        nocfi_required = ("maplesu_sym", "maplesu_caller_uid", "maplesu_handover",
                          "maplesu_pool_release", "init_module", "cleanup_module")
    else:
        nocfi_required = ("maplesu_sym", "maplesu_patch_return_zero", "init_module")
    for function in nocfi_required:
        gate.require(nocfi_covers(source, function),
                     "%s is declared MAPLESU_NOCFI in the source" % function,
                     "the attribute is the intent; the binary alone cannot show it, "
                     "and a function that calls a runtime-resolved address without it "
                     "gets a KCFI check back")


# --------------------------------------------------------------------------- #
# G7 -- the struct shim the compiler saw equals the image's BTF.
# --------------------------------------------------------------------------- #

def header_assertions(contract, header_path=None) -> dict:
    """The `_Static_assert` values in module/kernel_compat.h, as the compiler saw them.

    These are the numbers the C source claims; because the module compiled, every one
    of them held. G7's job is to check them against the image, not against themselves.
    """
    path = Path(header_path) if header_path else (MODULE_DIR / "kernel_compat.h")
    text = path.read_text(encoding="utf-8")
    # The header expresses family-dependent sizes as macros; resolve them exactly the
    # way the compiler did, so both engines compare the same numbers.
    text = text.replace("MAPLESU_SELINUX_STATE_SIZE",
                        contract["selinuxState"]["sizeHex"])
    out = {"sizeof": {}, "sizeofAtLeast": {}, "offsetof": {}}
    for match in re.finditer(r"_Static_assert\(\s*sizeof\(\s*struct\s+(\w+)\s*\)\s*>=\s*"
                             r"(0x[0-9a-fA-F]+|\d+)", text):
        out["sizeofAtLeast"][match.group(1)] = int(match.group(2), 0)
    for match in re.finditer(r"_Static_assert\(\s*sizeof\(\s*struct\s+(\w+)\s*\)\s*==\s*"
                             r"(0x[0-9a-fA-F]+|\d+)", text):
        out["sizeof"][match.group(1)] = int(match.group(2), 0)
    for match in re.finditer(r"_Static_assert\(\s*__builtin_offsetof\(\s*struct\s+"
                             r"(\w+)\s*,\s*(\w+)\s*\)\s*==\s*(0x[0-9a-fA-F]+|\d+)",
                             text):
        out["offsetof"].setdefault(match.group(1), {})[match.group(2)] = \
            int(match.group(3), 0)
    return out


def gate_struct_shim(ctx: VerifyContext, gate: Gate) -> None:
    elf, contract, btf = ctx.elf, ctx.contract, ctx.btf
    if ctx.is_l2:
        gate_struct_shim_l2(ctx, gate)
        return
    asserted = header_assertions(contract, ctx.header)
    gate.require(bool(asserted["sizeof"]),
                 "kernel_compat.h still carries _Static_asserts",
                 "parsed %r" % asserted)
    gate.extras["headerAsserts"] = asserted

    # struct module: the glue writes it by hand, BTF says what it is.
    module_hits = btf.structs("module")
    module = module_hits[0][1]
    gate.require(len({hit[1]["size"] for hit in module_hits}) == 1,
                 "all BTF struct module definitions agree on size",
                 "%s" % ["id %d: 0x%x" % (i, t["size"]) for i, t in module_hits])
    this_module = elf.section(".gnu.linkonce.this_module")
    gate.require(module["size"] == this_module["size"],
                 "sizeof(struct module) from BTF == this_module section size",
                 "BTF 0x%x, section 0x%x" % (module["size"], this_module["size"]))
    for member, contract_key in (("name", "nameOffset"), ("init", "initOffset"),
                                 ("exit", "exitOffset")):
        offset = btf.member(module, member)["byte"]
        gate.require(offset == contract["thisModule"][contract_key],
                     "BTF offsetof(struct module, %s) == contract %s" % (member,
                                                                        contract_key),
                     "BTF 0x%x, contract 0x%x"
                     % (offset, contract["thisModule"][contract_key]))

    # struct kprobe: register_kprobe() writes the whole object, so a wrong size is a
    # silent overflow, not a graceful failure.
    kprobe = btf.struct("kprobe")
    gate.require(kprobe["size"] == asserted["sizeof"].get("maplesu_kprobe"),
                 "BTF sizeof(struct kprobe) == kernel_compat.h sizeof(maplesu_kprobe)",
                 "BTF 0x%x, header 0x%x" % (kprobe["size"],
                                            asserted["sizeof"].get("maplesu_kprobe", 0)))
    for member in ("addr", "symbol_name"):
        offset = btf.member(kprobe, member)["byte"]
        claimed = asserted["offsetof"].get("maplesu_kprobe", {}).get(member)
        gate.require(offset == claimed,
                     "BTF offsetof(struct kprobe, %s) == header assert" % member,
                     "BTF 0x%x, header %s"
                     % (offset, "0x%x" % claimed if claimed is not None
                        else "missing"))
    gate.check(True, "struct kprobe shim holds",
               "0x%x bytes, addr@0x%x, symbol_name@0x%x"
               % (kprobe["size"], btf.member(kprobe, "addr")["byte"],
                  btf.member(kprobe, "symbol_name")["byte"]), fatal=False)

    # struct selinux_state: only the enforcing byte is ever written.
    selinux = btf.struct("selinux_state")
    gate.require(selinux["size"] == asserted["sizeof"].get("maplesu_selinux_state"),
                 "BTF sizeof(struct selinux_state) == kernel_compat.h sizeof",
                 "BTF 0x%x, header 0x%x"
                 % (selinux["size"],
                    asserted["sizeof"].get("maplesu_selinux_state", 0)))
    enforcing = btf.member(selinux, "enforcing")["byte"]
    gate.require(enforcing == 0,
                 "BTF puts selinux_state.enforcing at +0x0",
                 "+0x%x" % enforcing)
    # The offset is a per-family fact, not a constant: 6.1 puts `initialized` at +0x2.
    # What must hold is that BTF, the contract and the header agree with each other.
    initialized = btf.member(selinux, "initialized")["byte"]
    gate.require(initialized == contract["selinuxState"]["initializedOffset"],
                 "BTF puts selinux_state.initialized where the contract says",
                 "BTF +0x%x, contract +0x%x"
                 % (initialized, contract["selinuxState"]["initializedOffset"]))
    header_initialized = asserted["offsetof"].get("maplesu_selinux_state", {}).get(
        "initialized")
    gate.require(header_initialized == initialized,
                 "kernel_compat.h agrees with BTF on selinux_state.initialized",
                 "header +%s, BTF +0x%x" % (header_initialized, initialized))

    # struct subprocess_info: only `path` is touched, and that offset is the whole
    # static-UMH fix. A wrong value writes our pointer into a different field while
    # exec() keeps reading the empty path setup() stored -- i.e. the silent no-op
    # returns with extra memory corruption.
    sub_hits = btf.structs("subprocess_info")
    gate.require(bool(sub_hits), "image BTF has struct subprocess_info")
    if sub_hits:
        sub = sub_hits[0][1]
        sub_path = btf.member(sub, "path")["byte"]
        gate.require(btf.member(sub, "argv")["byte"] == sub_path + 8 and
                     btf.member(sub, "envp")["byte"] == sub_path + 16,
                     "BTF subprocess_info keeps argv/envp right after path",
                     "path@0x%x argv@0x%x envp@0x%x"
                     % (sub_path, btf.member(sub, "argv")["byte"],
                        btf.member(sub, "envp")["byte"]))
        gate.require(sub["size"] == asserted["sizeof"].get(
                         "maplesu_subprocess_info"),
                     "BTF sizeof(struct subprocess_info) == kernel_compat.h sizeof",
                     "BTF 0x%x, header 0x%x"
                     % (sub["size"],
                        asserted["sizeof"].get("maplesu_subprocess_info", 0)))
        gate.require(sub_path == asserted["offsetof"].get(
                         "maplesu_subprocess_info", {}).get("path"),
                     "BTF offsetof(subprocess_info, path) == header assert",
                     "BTF 0x%x, header %s"
                     % (sub_path,
                        "0x%x" % asserted["offsetof"]["maplesu_subprocess_info"]["path"]
                        if asserted["offsetof"].get("maplesu_subprocess_info", {}
                                                    ).get("path") is not None
                        else "missing"))
        gate.require(sub_path == contract["structOffsets"]["subprocessInfo"]["path"],
                     "BTF offsetof(subprocess_info, path) == contract offset",
                     "BTF 0x%x, contract 0x%x"
                     % (sub_path, contract["structOffsets"]["subprocessInfo"]["path"]))
        # The tail is where the kernel's own stores land (setup: init/cleanup/data at
        # +0x58/+0x60/+0x68; exec: complete/wait/retval at +0x30/+0x50/+0x54), so a
        # shim that got `path` right and the tail wrong would still corrupt the struct
        # this module hands to exec(). Every asserted offset is compared against the
        # image's BTF and against the contract's member table, by name.
        asserted_members = asserted["offsetof"].get("maplesu_subprocess_info", {})
        contract_members = {entry.get("name"): entry.get("byteOffset")
                            for entry in
                            contract["structOffsets"]["subprocessInfo"].get(
                                "members", [])}
        for member in ("complete", "path", "argv", "envp", "wait", "retval",
                       "init", "cleanup", "data"):
            offset = btf.member(sub, member)["byte"]
            gate.require(asserted_members.get(member) == offset,
                         "kernel_compat.h asserts subprocess_info.%s == BTF" % member,
                         "header %s, BTF 0x%x"
                         % ("0x%x" % asserted_members[member]
                            if member in asserted_members else "missing", offset))
            gate.require(contract_members.get(member) == offset,
                         "contract subprocess_info.%s == BTF" % member,
                         "contract %s, BTF 0x%x"
                         % ("0x%x" % contract_members[member]
                            if member in contract_members else "missing", offset))
        gate.check(True, "struct subprocess_info shim holds",
                   "0x%x bytes, path@0x%x, argv@0x%x, envp@0x%x; tail asserted "
                   "through data@0x%x"
                   % (sub["size"], sub_path, btf.member(sub, "argv")["byte"],
                      btf.member(sub, "envp")["byte"],
                      btf.member(sub, "data")["byte"]), fatal=False)

    # The values the C file embeds come from the same source: report them together so
    # a future release bump cannot pass with a stale shim.
    gate.check(True, "compile is the shim gate",
               "the build succeeded, so every _Static_assert above held; the asserts "
               "and BTF agree on all %d values"
               % (len(asserted["sizeof"]) + sum(len(v) for v in
                                                asserted["offsetof"].values())),
               fatal=False)
    gate.extras["btfFacts"] = {
        "btfBlobOffset": "0x%x" % btf.blob_offset,
        "structModule": {"typeId": module_hits[0][0], "size": "0x%x" % module["size"],
                         "nameOffset": "0x%x" % btf.member(module, "name")["byte"],
                         "initOffset": "0x%x" % btf.member(module, "init")["byte"],
                         "exitOffset": "0x%x" % btf.member(module, "exit")["byte"]},
        "structKprobe": {"size": "0x%x" % kprobe["size"],
                         "addrOffset": "0x%x" % btf.member(kprobe, "addr")["byte"],
                         "symbolNameOffset":
                             "0x%x" % btf.member(kprobe, "symbol_name")["byte"]},
        "structSelinuxState": {"size": "0x%x" % selinux["size"],
                               "enforcingOffset": "0x%x" % enforcing},
        "structSubprocessInfo": {
            "typeIds": [hit[0] for hit in sub_hits],
            "size": "0x%x" % (sub_hits[0][1]["size"] if sub_hits else 0),
            "pathOffset": "0x%x" % (btf.member(sub_hits[0][1], "path")["byte"]
                                    if sub_hits else 0)},
        "headerAsserts": {
            "sizeof": {name: "0x%x" % value
                       for name, value in asserted["sizeof"].items()},
            "offsetof": {struct: {member: "0x%x" % value
                                  for member, value in table.items()}
                         for struct, table in asserted["offsetof"].items()},
        },
    }


# --------------------------------------------------------------------------- #
# L2 gate bodies: G8-G14, plus the module-aware G5/G7 variants.
#
# Every gate here is red on a tree without the artifact (the build fails before
# it gets this far) and green on the C1 module. Each one also has a negative
# sample in tests/test_l2_c1_gates.py: a mutation that makes exactly that gate
# fail. A gate that cannot be red is not a gate.
# --------------------------------------------------------------------------- #


def function_body(source: str, name: str) -> str:
    """The body of `name`'s definition (brace-matched), or "" when absent.

    Declarations end in `;` and are skipped by construction: the pattern needs a
    `{` after the parameter list.
    """
    match = re.search(r"(?m)^[A-Za-z_][^\n;{}]*?\b%s\s*\([^;{}]*\)\s*\{" % re.escape(name),
                      source)
    if match is None:
        return ""
    start = source.find("{", match.start())
    depth = 0
    for index in range(start, len(source)):
        char = source[index]
        if char == "{":
            depth += 1
        elif char == "}":
            depth -= 1
            if depth == 0:
                return source[start:index + 1]
    return source[start:]


def materialised_immediates(code: bytes, value: int) -> list:
    """Every place `code` materialises `value` with movz/movk, or compares it.

    This is G11 ① as reviewed (M7): a byte-pattern search is vacuous -- measured,
    `453` never appears as `c5 01 00 00`; it is `movz wN,#0x1c5`. The algorithm is
    the one hook_contract.py:_materialised uses (tests/test_l2_c1_gates.py
    cross-checks the two on one compiled TU so they cannot drift).
    """
    hits: list = []
    state: dict = {}
    for offset in range(0, len(code) - 3, 4):
        insn = struct.unpack_from("<I", code, offset)[0]
        if (insn & 0x7F800000) in (0x52800000, 0xD2800000):        # movz
            reg = insn & 0x1F
            immediate = ((insn >> 5) & 0xFFFF) << (((insn >> 21) & 0x3) * 16)
            state[reg] = immediate
            if immediate == value:
                hits.append({"offset": offset, "how": "movz"})
            continue
        if (insn & 0x7F800000) in (0x72800000, 0xF2800000):        # movk
            reg = insn & 0x1F
            shift = ((insn >> 21) & 0x3) * 16
            immediate = (insn >> 5) & 0xFFFF
            merged = (state.get(reg, 0) & ~(0xFFFF << shift)) | (immediate << shift)
            state[reg] = merged
            if merged == value:
                hits.append({"offset": offset, "how": "movk"})
            continue
        if (insn & 0xFFC0001F) in (0x7100001F, 0xF100001F):         # cmp #imm
            reg = (insn >> 5) & 0x1F
            immediate = ((insn >> 10) & 0xFFF) << (12 if (insn >> 22) & 1 else 0)
            if immediate == value or state.get(reg) == value:
                hits.append({"offset": offset, "how": "cmp #imm"})
            continue
        state.pop(insn & 0x1F, None)
    return hits


def store_base_register(insn: int):
    """The base register of a store instruction, or None.

    Covered: STRB/STRH/STR (imm, unsigned offset), STUR* (imm, unscaled) and
    STP/STNP (offset/pre/post). Loads are excluded by the L/opc bits, which is
    the part that matters -- an LDR misread as a store would invent violations.
    """
    if (insn & 0x3B400000) == 0x39000000:          # STR* immediate, unsigned
        return (insn >> 5) & 0x1F
    if (insn & 0x3B600C00) == 0x38000000:          # STUR* unscaled
        return (insn >> 5) & 0x1F
    if (insn & 0x3A400000) == 0x28000000:          # STP/STNP, any writeback
        return (insn >> 5) & 0x1F
    return None


def store_is_byte(insn: int) -> bool:
    """True for a one-byte store (STRB/STURB): size field == 00."""
    if (insn & 0x3B400000) == 0x39000000:
        return (insn & 0x00C00000) == 0
    if (insn & 0x3B600C00) == 0x38000000:
        return (insn & 0x00C00000) == 0
    return False


def store_immediate(insn: int):
    """The unsigned immediate of a STR*/STP, or None when it has none."""
    if (insn & 0x3B400000) == 0x39000000:
        return ((insn >> 10) & 0xFFF) * (1 << ((insn >> 30) & 0x3))
    if (insn & 0x3A400000) == 0x28000000:
        return ((insn >> 15) & 0x7F) * (8 if (insn >> 31) & 1 else 4)
    return None


def is_branch_table_load(insn: int) -> bool:
    """A load with register offset and shift -- the jump table's first move."""
    return (insn & 0x3B200C00) == 0x38200800


def _l2_exec_sections(ctx: VerifyContext) -> list:
    return [section for section in ctx.elf.sections if section["flags"] & SHF_EXECINSTR]


def _l2_functions(ctx: VerifyContext) -> list:
    """(section, start, end, name) for every defined function in exec sections."""
    out = []
    for section in _l2_exec_sections(ctx):
        for symbol in ctx.elf.defined_symbols():
            if (symbol["type"] == STT_FUNC and symbol["shndx"] == section["index"]
                    and symbol["size"]):
                out.append((section["name"], symbol["value"],
                            symbol["value"] + symbol["size"], symbol["name"]))
    return sorted(out)


def _l2_function_at(functions: list, section: str, offset: int):
    for name_of_section, start, end, name in functions:
        if name_of_section == section and start <= offset < end:
            return name
    return None


def _l2_calls(ctx: VerifyContext) -> list:
    """(caller, callee, section, offset) for every direct call in module text.

    Two sources, because neither alone is complete:
      * CALL26/JUMP26 relocations -- calls to imports and to other sections
        (`bl _printk`, and the .init.text -> .text calls);
      * the `bl`/`b` immediates themselves -- an intra-section call to a static
        function needs no relocation (the assembler resolves it), so the whole
        callback subtree would be invisible to a relocation-only graph.

    CALL26 and JUMP26 both count: at -O2 a tail call (`br`) is still a call for
    every purpose these gates care about.
    """
    functions = _l2_functions(ctx)
    edges = []
    for target, relocs in ctx.elf.all_relocations().items():
        section = ctx.elf.section(target)
        if section is None or not section["flags"] & SHF_EXECINSTR:
            continue
        for entry in relocs:
            if entry["type"] not in (282, 283):
                continue
            caller = _l2_function_at(functions, target, entry["offset"])
            edges.append((caller or "?", entry["symbol"], target, entry["offset"]))
    for section in _l2_exec_sections(ctx):
        blob = ctx.elf.section_bytes(section)
        for index in range(len(blob) // 4):
            insn = read_uint32(blob, index * 4)
            if (insn & 0xFC000000) not in (0x94000000, 0x14000000):
                continue
            immediate = insn & 0x03FFFFFF
            if immediate & 0x02000000:
                immediate -= 0x04000000
            target_offset = index * 4 + immediate * 4
            if not 0 <= target_offset < len(blob):
                continue
            caller = _l2_function_at(functions, section["name"], index * 4)
            callee = _l2_function_at(functions, section["name"], target_offset)
            if caller and callee and caller != callee:
                edges.append((caller, callee, section["name"], index * 4))
    return edges


def _l2_module_functions(ctx: VerifyContext) -> set:
    return {entry[3] for entry in _l2_functions(ctx)}


def _l2_call_closure(ctx: VerifyContext, roots: set) -> set:
    callees: dict = {}
    for caller, callee, _section, _offset in _l2_calls(ctx):
        callees.setdefault(caller, set()).add(callee)
    module_functions = _l2_module_functions(ctx)
    seen = set()
    pending = list(roots)
    while pending:
        name = pending.pop()
        if name in seen:
            continue
        seen.add(name)
        pending.extend(callee for callee in callees.get(name, ())
                       if callee in module_functions)
    return seen


def _l2_body_contains_call(ctx: VerifyContext, function: str, callee: str) -> bool:
    """True when a call to `callee` has its relocation inside `function`'s body."""
    functions = _l2_functions(ctx)
    for _caller, target, section, offset in _l2_calls(ctx):
        if target != callee:
            continue
        if _l2_function_at(functions, section, offset) == function:
            return True
    return False


def _l2_own_adrp_offsets(ctx: VerifyContext) -> set:
    """(section, offset) of every ADRP relocation that names own .bss/.data."""
    own = {section["index"] for section in ctx.elf.sections
           if section["flags"] & SHF_ALLOC and section["flags"] & SHF_WRITE
           and not section["flags"] & SHF_EXECINSTR
           and section["type"] in (SHT_PROGBITS, SHT_NOBITS)}
    offsets = set()
    for target, relocs in ctx.elf.all_relocations().items():
        section = ctx.elf.section(target)
        if section is None or not section["flags"] & SHF_EXECINSTR:
            continue
        for entry in relocs:
            if entry["type"] not in (275, 276):
                continue
            index = entry["symbol_index"]
            if 0 <= index < len(ctx.elf.symbols) \
                    and ctx.elf.symbols[index]["shndx"] in own:
                offsets.add((target, entry["offset"]))
    return offsets


def l2_store_census(ctx: VerifyContext) -> dict:
    """G14: every store in module text, classified by its base register.

    Stricter than hook_contract.py's skeleton in one specific way: a store counts
    as "own data" only when its base register was derived in the same function
    from an ADRP that a relocation proves points at this module's .bss/.data, or
    from `sp`. The skeleton's per-function attribution would accept a
    caller-pointer store in any function that also touches a global; this does
    not.

    `l2_kwrite` is the one function allowed to store through a caller pointer,
    because that is what the two-entry whitelist *is*; G14 checks separately that
    its only such store is a single byte.
    """
    own_adrp = _l2_own_adrp_offsets(ctx)
    census = {"stores": 0, "stack": 0, "own": 0, "whitelist": 0,
              "violations": [], "byFunction": {}, "detail": []}
    functions = _l2_functions(ctx)
    for section in _l2_exec_sections(ctx):
        blob = ctx.elf.section_bytes(section)
        for name_of_section, start, end, name in functions:
            if name_of_section != section["name"]:
                continue
            derived = set()
            for offset in range(start, end - 3, 4):
                insn = read_uint32(blob, offset)
                if (section["name"], offset) in own_adrp:
                    derived.add(insn & 0x1F)                 # adrp xD, own
                    continue
                if (insn & 0x7F000000) in (0x11000000, 0x51000000):
                    destination = insn & 0x1F
                    source = (insn >> 5) & 0x1F
                    if source == 31 or source in derived:
                        derived.add(destination)             # add xD, sp/xOwn, #imm
                    else:
                        derived.discard(destination)
                    continue
                if (insn & 0x7F000000) in (0x12000000, 0x52000000):
                    destination = insn & 0x1F
                    source = (insn >> 5) & 0x1F
                    if source in derived:
                        derived.add(destination)             # and/orr xD, xOwn, #imm
                    else:
                        derived.discard(destination)
            for offset in range(start, end - 3, 4):
                insn = read_uint32(blob, offset)
                base = store_base_register(insn)
                if base is None:
                    continue
                census["stores"] += 1
                census["byFunction"][name] = census["byFunction"].get(name, 0) + 1
                if base in (31, 29):
                    classification = "stack"
                    census["stack"] += 1
                elif base in derived:
                    classification = "own"
                    census["own"] += 1
                elif name == "l2_kwrite":
                    classification = "whitelist"
                    census["whitelist"] += 1
                else:
                    classification = "violation"
                    census["violations"].append(
                        {"section": section["name"], "offset": "0x%x" % offset,
                         "function": name, "base": "x%d" % base})
                census["detail"].append(
                    {"section": section["name"], "offset": offset, "function": name,
                     "base": base, "class": classification,
                     "byte": store_is_byte(insn),
                     "immediate": store_immediate(insn)})
    return census


def _l2_no_jump_tables(ctx: VerifyContext, gate: Gate, gate_id: str) -> None:
    """No computed jump: the property, not a `br x*` count.

    A sibling/tail call `br` is legitimate (the design's own shapes compile to
    two), so the assertion is "no `br xN` fed by a scaled register-offset load"
    -- the jump table's signature -- plus the flag that keeps clang from emitting
    one. The Lead's brief names this check in G9; the design also states it as
    G12 ①, so both gates call it and either id failing blocks the build.
    """
    functions = _l2_functions(ctx)
    sites = []
    for section in _l2_exec_sections(ctx):
        blob = ctx.elf.section_bytes(section)
        for index in range(len(blob) // 4):
            insn = read_uint32(blob, index * 4)
            if (insn & 0xFFFFFC1F) != 0xD61F0000:        # br xN
                continue
            for back in range(1, 9):
                if index - back < 0:
                    break
                if is_branch_table_load(read_uint32(blob, (index - back) * 4)):
                    sites.append({"section": section["name"],
                                  "offset": "0x%x" % (index * 4),
                                  "function": _l2_function_at(
                                      functions, section["name"], index * 4)})
                    break
    gate.require(not sites,
                 "%s: no jump-table dispatch (br fed by a scaled table load)"
                 % gate_id,
                 "none" if not sites else "found: %s" % sites[:4])
    flags = ctx.cflags or []
    gate.require("-fno-jump-tables" in flags,
                 "%s: -fno-jump-tables is in the recorded build flags" % gate_id,
                 ("recorded flags: %s" % " ".join(flags)) if flags else
                 "no cflags were recorded (run a build, not --verify-only on a copy)")


# --------------------------------------------------------------------------- #
# G5 (L2) -- the patch plan without the root module's handover.
# --------------------------------------------------------------------------- #

def gate_patch_plan_l2(ctx: VerifyContext, gate: Gate) -> None:
    elf = ctx.elf
    raw = elf.raw

    gate.require(elf.section(".ir_spec") is None, "no .ir_spec section",
                 "the old per-release byte-patch blob must not come back")
    gate.require(raw.find(b"#maplesu-ir-spec") < 0,
                 "no #maplesu-ir-spec banner anywhere in the file", "absent")

    hits = []
    scanned = []
    for section in elf.sections:
        if not section["flags"] & SHF_ALLOC or section["type"] == SHT_NOBITS:
            continue
        blob = elf.section_bytes(section)
        scanned.append((section["name"], len(blob)))
        for offset in range(max(0, len(blob) - 7)):
            value = struct.unpack_from("<Q", blob, offset)[0]
            if KERNEL_VA_LOW <= value <= KERNEL_VA_HIGH:
                hits.append("%s+0x%x = 0x%x" % (section["name"], offset, value))
    gate.require(not hits, "no kernel virtual address in any allocated section",
                 "0 hits in %d bytes across %d sections"
                 % (sum(size for _name, size in scanned), len(scanned))
                 if not hits else "a per-release address table looks like this: %s"
                 % hits[:5])

    addend_hits = []
    for target, relocs in elf.all_relocations().items():
        for entry in relocs:
            if KERNEL_VA_LOW <= entry["addend"] & 0xFFFFFFFFFFFFFFFF <= KERNEL_VA_HIGH:
                addend_hits.append("%s+0x%x -> 0x%x"
                                   % (target, entry["offset"], entry["addend"]))
    gate.require(not addend_hits, "no relocation addend is a kernel address",
                 "%s" % addend_hits[:5])

    type_counts: dict = {}
    unknown = set()
    for target, relocs in elf.all_relocations().items():
        for entry in relocs:
            type_counts[entry["type"]] = type_counts.get(entry["type"], 0) + 1
            if entry["type"] not in ARM64_MODULE_RELOCS:
                unknown.add(entry["type"])
    gate.require(not unknown, "every relocation type is one arm64 implements",
                 "unknown: %s" % sorted(unknown))
    got = sorted(set(type_counts) & GOT_RELOC_TYPES)
    gate.require(not got, "no GOT relocation (no .got dependency)",
                 "none" if not got else "types %s"
                 % [ARM64_MODULE_RELOCS[value] for value in got])
    gate.extras["relocationTypes"] = {str(value): type_counts[value]
                                      for value in sorted(type_counts)}

    # The module must not carry a write surface it does not need. O7/O8/O9 in
    # hook_contract.py assert the same object-wide; doing it here keeps
    # `--module maplesu_l2` fail-closed on its own.
    imports = elf.imports()
    danger = [name for name in L2_FORBIDDEN_SYMBOLS
              if name in imports or name.encode() in raw]
    gate.require(not danger, "no policy/device/text-mutation symbol anywhere",
                 "found: %s" % danger)
    blob = b"".join(elf.section_bytes(section) for section in elf.sections
                    if section["flags"] & SHF_ALLOC)
    lowered = blob.lower()
    for needle in L2_FORBIDDEN_STRINGS:
        gate.require(needle not in lowered,
                     "no %r string in allocated data" % needle.decode())

    # The lookup names, exactly: every .rodata word that resolves in this image's
    # symbol table must be one the design names. A new runtime lookup is a design
    # change, so it has to grow L2_LOOKUP_WHITELIST as well as the source.
    literals = set()
    for section in elf.sections:
        if not section["flags"] & SHF_ALLOC or section["type"] == SHT_NOBITS:
            continue
        if not section["name"].startswith(".rodata"):
            continue
        for text in elf.section_bytes(section).split(b"\x00"):
            if not 4 <= len(text) <= 128:
                continue
            try:
                decoded = text.decode("utf-8")
            except UnicodeDecodeError:
                continue
            if decoded.replace("_", "").isalnum():
                literals.add(decoded)
    resolvable = {name for name in literals if name in ctx.symbols.rows}
    unexpected = sorted(resolvable - L2_LOOKUP_WHITELIST)
    gate.require(not unexpected,
                 "every resolvable kernel symbol name in the module is whitelisted",
                 "unexpected: %s -- a new runtime lookup is a design change, so the "
                 "whitelist in this builder has to grow with it" % unexpected)
    gate.check(True, "lookup names carried by the module: %s"
               % ", ".join(sorted(resolvable & L2_LOOKUP_WHITELIST)),
               "%d of %d whitelisted names"
               % (len(resolvable & L2_LOOKUP_WHITELIST), len(L2_LOOKUP_WHITELIST)),
               fatal=False)
    gate.extras["lookupNames"] = sorted(resolvable & L2_LOOKUP_WHITELIST)

    # The cookie channel as an object fact: one row, perm 0, ops imported, arg is
    # this module's own cell. G7 checks the offsets against BTF; this checks the
    # semantic fields.
    rows = elf.sections_named("__param")
    gate.require(len(rows) == 1 and rows[0]["size"] == 0x28,
                 "exactly one 0x28-byte __param row",
                 "found %d section(s), sizes %s"
                 % (len(rows), [hex(row["size"]) for row in rows]))
    relocs = {entry["offset"]: entry for entry in elf.relocations("__param")}
    body = elf.section_bytes("__param") if rows else b""
    gate.require(bool(body) and all(byte == 0 for byte in body[0x18:0x20]),
                 "the __param row carries perm=0 (no sysfs entry for the cookie)",
                 "bytes at +0x18..+0x1f: %s"
                 % (body[0x18:0x20].hex() if body else "-"))
    gate.require(relocs.get(0x10, {}).get("symbol") == L2_COOKIE_PARAM,
                 "the __param row's ops is %s" % L2_COOKIE_PARAM,
                 "found %r" % (relocs.get(0x10, {}).get("symbol"),))
    gate.require(relocs.get(0x20, {}).get("symbol") == "l2_load_cookie",
                 "the __param row's arg is the module's own cookie cell",
                 "found %r" % (relocs.get(0x20, {}).get("symbol"),))
    mod_entry = relocs.get(0x8) or {}
    mod_index = mod_entry.get("symbol_index", -1)
    mod_symbol = (ctx.elf.symbols[mod_index]
                  if 0 <= mod_index < len(ctx.elf.symbols) else {})
    this_module_section = ctx.elf.section(".gnu.linkonce.this_module")
    mod_ok = mod_entry.get("symbol") == "__this_module" or (
        mod_symbol.get("name") == "" and this_module_section is not None
        and mod_symbol.get("shndx") == this_module_section["index"])
    gate.require(mod_ok, "the __param row's mod points at this module",
                 "found %r (section %r)"
                 % (mod_entry.get("symbol"), mod_symbol.get("shndx")))
    gate.check(True, "no handover surface in the L2 module",
               "G5's root-module half (UMH setup/exec, the staged helper path) is "
               "not applicable: L2 is loaded by the helper, it never starts one",
               fatal=False)


# --------------------------------------------------------------------------- #
# G7 (L2) -- the shims the compiler saw equal the image's BTF.
# --------------------------------------------------------------------------- #

def gate_struct_shim_l2(ctx: VerifyContext, gate: Gate) -> None:
    btf, contract, elf = ctx.btf, ctx.contract, ctx.elf
    asserted = header_assertions(contract)
    offsets = contract["structOffsets"]
    kprobe_offsets = contract["kprobe"]
    gate.require(bool(asserted["sizeof"]),
                 "kernel_compat.h still carries _Static_asserts",
                 "parsed %r" % asserted)
    gate.extras["headerAsserts"] = asserted

    # struct kprobe: the L2 shim is the full BTF layout, because L2 sets
    # pre_handler/post_handler and the kernel calls them through these offsets.
    kprobe = btf.struct("kprobe")
    gate.require(kprobe["size"] == asserted["sizeof"].get("maplesu_l2_kprobe"),
                 "BTF sizeof(struct kprobe) == sizeof(struct maplesu_l2_kprobe)",
                 "BTF 0x%x, header 0x%x"
                 % (kprobe["size"],
                    asserted["sizeof"].get("maplesu_l2_kprobe", 0)))
    for member in ("addr", "symbol_name", "pre_handler", "post_handler"):
        offset = btf.member(kprobe, member)["byte"]
        claimed = asserted["offsetof"].get("maplesu_l2_kprobe", {}).get(member)
        gate.require(offset == claimed,
                     "BTF offsetof(kprobe, %s) == header assert" % member,
                     "BTF 0x%x, header %s"
                     % (offset, "0x%x" % claimed if claimed is not None
                        else "missing"))
    member_pins = {}
    for key in ("preHandler", "postHandler"):
        row = (ctx.l2.get("kcfiIds") or {}).get(key) or {}
        member_pins[key] = ((row.get("member") or {}).get("offset"), key)
    gate.require(member_pins["preHandler"][0] == 0x40
                 and member_pins["postHandler"][0] == 0x48,
                 "the contract's kcfiIds pin pre_handler@0x40 / post_handler@0x48",
                 "contract %r" % {key: value[0] for key, value in member_pins.items()})
    gate.check(True, "struct kprobe shim holds",
               "0x%x bytes, addr@0x%x, symbol_name@0x%x, pre_handler@0x%x, "
               "post_handler@0x%x (no fault_handler member on this image)"
               % (kprobe["size"], btf.member(kprobe, "addr")["byte"],
                  btf.member(kprobe, "symbol_name")["byte"],
                  btf.member(kprobe, "pre_handler")["byte"],
                  btf.member(kprobe, "post_handler")["byte"]), fatal=False)

    # struct kretprobe: the kernel writes the whole object, including freelist and
    # rph past data_size, so a short surrogate corrupts .bss.
    kretprobe = btf.struct("kretprobe")
    minimum = asserted["sizeofAtLeast"].get("maplesu_kretprobe")
    gate.require(minimum == offsets["kretprobe"]["size"],
                 "kernel_compat.h asserts sizeof(kretprobe) >= the contract size",
                 "header >= %s, contract 0x%x"
                 % ("0x%x" % minimum if minimum is not None else None,
                    offsets["kretprobe"]["size"]))
    gate.require(kretprobe["size"] == offsets["kretprobe"]["size"],
                 "BTF sizeof(struct kretprobe) == the contract",
                 "BTF 0x%x, contract 0x%x"
                 % (kretprobe["size"], offsets["kretprobe"]["size"]))
    for member, key in (("handler", "handler"), ("entry_handler", "entryHandler"),
                        ("data_size", "dataSize"), ("kp", "kp")):
        offset = btf.member(kretprobe, member)["byte"]
        gate.require(offset == offsets["kretprobe"][key],
                     "BTF kretprobe.%s == contract" % member,
                     "BTF 0x%x, contract 0x%x" % (offset, offsets["kretprobe"][key]))
        claimed = asserted["offsetof"].get("maplesu_kretprobe", {}).get(member)
        gate.require(claimed == offset,
                     "header asserts kretprobe.%s == BTF" % member,
                     "header %s, BTF 0x%x"
                     % ("0x%x" % claimed if claimed is not None else "missing",
                        offset))
    gate.check(True, "struct kretprobe shim holds",
               "0x%x bytes, handler@0x%x, entry_handler@0x%x, data_size@0x%x"
               % (kretprobe["size"], btf.member(kretprobe, "handler")["byte"],
                  btf.member(kretprobe, "entry_handler")["byte"],
                  btf.member(kretprobe, "data_size")["byte"]), fatal=False)

    # struct pt_regs: syscallno is the control channel's only source of the
    # syscall number, so a wrong offset there is a wrong channel.
    pt_regs = btf.struct("pt_regs")
    gate.require(pt_regs["size"] == asserted["sizeof"].get("maplesu_pt_regs"),
                 "BTF sizeof(struct pt_regs) == sizeof(struct maplesu_pt_regs)",
                 "BTF 0x%x, header 0x%x"
                 % (pt_regs["size"], asserted["sizeof"].get("maplesu_pt_regs", 0)))
    syscallno = offsets["ptRegs"]["syscallno"]
    gate.require(0x118 == syscallno ==
                 asserted["offsetof"].get("maplesu_pt_regs", {}).get("syscallno"),
                 "syscallno is at +0x118 in BTF, the header and the contract",
                 "BTF 0x%x, header %s, contract 0x%x"
                 % (syscallno,
                    asserted["offsetof"].get("maplesu_pt_regs", {}).get("syscallno"),
                    offsets["ptRegs"]["syscallno"]))
    flat = offsets["ptRegs"].get("flatMembers") or {}
    for key in ("regs", "sp", "pc", "pstate"):
        gate.require(key in flat and int(flat[key], 16) == offsets["ptRegs"][key],
                     "pt_regs.%s offset is recorded consistently" % key,
                     "flat %r, top-level 0x%x"
                     % (flat.get(key), offsets["ptRegs"][key]))
    gate.check(True, "struct pt_regs shim holds",
               "0x%x bytes, regs@0x%x, sp@0x%x, pc@0x%x, syscallno@0x%x"
               % (pt_regs["size"], offsets["ptRegs"]["regs"],
                  offsets["ptRegs"]["sp"], offsets["ptRegs"]["pc"], syscallno),
               fatal=False)

    # struct kernel_param: the __param row is written by hand in the glue, so the
    # only thing that can keep the two in step is this assertion set.
    param = btf.struct("kernel_param")
    gate.require(param["size"] == asserted["sizeof"].get("maplesu_kernel_param"),
                 "BTF sizeof(struct kernel_param) == sizeof(maplesu_kernel_param)",
                 "BTF 0x%x, header 0x%x"
                 % (param["size"],
                    asserted["sizeof"].get("maplesu_kernel_param", 0)))
    # `arg` is an anonymous union member in BTF (no name), so the offset is taken
    # from the member table by position; `ops`/`perm` are named and matched by name.
    anonymous = [entry["byte"] for entry in param["members"] if not entry["name"]]
    for member, key in (("ops", "ops"), ("perm", "perm")):
        offset = btf.member(param, member)["byte"]
        gate.require(offset == offsets["kernelParam"][key],
                     "BTF kernel_param.%s == contract" % member,
                     "BTF 0x%x, contract 0x%x"
                     % (offset, offsets["kernelParam"][key]))
        gate.require(asserted["offsetof"].get("maplesu_kernel_param", {}).get(member)
                     == offset,
                     "header asserts kernel_param.%s == BTF" % member,
                     "header %s, BTF 0x%x"
                     % (asserted["offsetof"].get("maplesu_kernel_param",
                                                 {}).get(member), offset))
    gate.require(offsets["kernelParam"]["arg"] in anonymous
                 and offsets["kernelParam"]["arg"]
                 == asserted["offsetof"].get("maplesu_kernel_param", {}).get("arg"),
                 "kernel_param.arg (anonymous union) is at the contract's +0x%x"
                 % offsets["kernelParam"]["arg"],
                 "BTF anonymous members at %s, header %s"
                 % (["0x%x" % value for value in anonymous],
                    asserted["offsetof"].get("maplesu_kernel_param", {}).get("arg")))

    cookie = elf.symbol("l2_load_cookie")
    gate.require(cookie is not None and cookie["shndx"] != 0 and cookie["size"] == 8,
                 "l2_load_cookie is a defined 8-byte object",
                 "symbol %r" % (cookie,))
    if cookie is not None and cookie["shndx"] != 0:
        section = elf.symbol_section(cookie)
        gate.require(section is not None and bool(section["flags"] & SHF_ALLOC)
                     and bool(section["flags"] & SHF_WRITE),
                     "the cookie cell is in writable module data",
                     "section %r" % (section["name"] if section else None))
    tags = {}
    for entry in elf.section_bytes(".modinfo").split(b"\x00"):
        if entry and b"=" in entry:
            key, _, value = entry.partition(b"=")
            tags[key.decode("utf-8", "replace")] = value.decode("utf-8", "replace")
    gate.require(tags.get("parm") == "cookie:ullong",
                 ".modinfo carries parm=cookie:ullong", "found %r" % tags.get("parm"))
    gate.require(tags.get("description") == L2_MODINFO_DESCRIPTION,
                 ".modinfo description is the builder's constant",
                 "found %r" % tags.get("description"))
    gate.extras["btfFacts"] = {
        "structKprobe": {"size": "0x%x" % kprobe["size"],
                         "preHandler": "0x%x" % btf.member(kprobe, "pre_handler")["byte"],
                         "postHandler": "0x%x" % btf.member(kprobe, "post_handler")["byte"]},
        "structKretprobe": {"size": "0x%x" % kretprobe["size"],
                            "handler": "0x%x" % btf.member(kretprobe, "handler")["byte"]},
        "structPtRegs": {"size": "0x%x" % pt_regs["size"],
                         "syscallno": "0x%x" % syscallno},
        "structKernelParam": {"size": "0x%x" % param["size"],
                              "ops": "0x%x" % btf.member(param, "ops")["byte"],
                              "arg": "0x%x" % btf.member(param, "arg")["byte"]},
    }


# --------------------------------------------------------------------------- #
# G8 -- callback ABI: the pinned ids, at the sites this image really compares.
# --------------------------------------------------------------------------- #

def gate_callback_abi(ctx: VerifyContext, gate: Gate) -> None:
    elf, l2 = ctx.elf, ctx.l2
    ids = l2.get("kcfiIds") or {}
    # Independent statement first: the three ids the review measured, written here
    # so a contract that silently changed one is caught by this gate, not blessed
    # by it.
    pins = {"preHandler": 0xA6BE5DD9, "postHandler": 0x6E21B9C7,
            "kretprobeHandler": 0x8078BE15}
    for key, value in pins.items():
        recorded = ids.get(key) or {}
        gate.require(int(recorded.get("value") or "0x0", 16) == value,
                     "contract pins %s to 0x%08x" % (key, value),
                     "contract says %r" % recorded.get("value"))

    # The pinned id must be the id the image builds at the compare site the
    # contract names -- decoded from the image's bytes, not read from the JSON.
    for key, value in pins.items():
        recorded = ids.get(key) or {}
        sites = recorded.get("callSitesWithThisId") or recorded.get("callSites") or []
        gate.require(bool(sites), "%s records at least one dispatch compare site" % key,
                     "callSitesWithThisId is empty")
        verified = []
        for site in sites:
            address = int(site.get("compareSiteAddress") or "0x0", 16)
            if address <= 0:
                continue
            low = read_uint32(ctx.image, address)
            high = read_uint32(ctx.image, address + 4)
            lo = decode_movk(low)
            hi = decode_movk(high)
            if not lo or lo[2] != 0 or not hi or hi[2] != 1 or lo[0] != hi[0]:
                continue
            if ((hi[1] << 16) | lo[1]) == value:
                if decode_cmp_reg(read_uint32(ctx.image, address + 8)) is not None:
                    verified.append("0x%x" % address)
        gate.require(bool(verified),
                     "the image really compares 0x%08x at %s"
                     % (value, [site.get("compareSiteAddress")
                                for site in sites][:3]),
                     "no recorded compare site builds 0x%08x" % value)

    # G8 ② -- set membership, sanity only (design: it passes for hundreds of
    # wrong ids, so it cannot be the assertion). Reported either way.
    image_sites = ctx.image_kcfi_sites()
    image_ids = {site["typeId"] for site in image_sites}
    for key, value in pins.items():
        gate.check(value in image_ids,
                   "0x%08x is one of the %d ids the image compares (%d sites)"
                   % (value, len(image_ids), len(image_sites)),
                   "membership is a fallback, not the assertion", fatal=False)
    gate.extras["imageKcfi"] = {"sites": len(image_sites), "uniqueIds": len(image_ids)}

    declarations = {case["case"]: case["typeId"]
                    for case in (l2.get("declarationCheck") or {}).get("cases", [])}

    # The callbacks: prefix equality.
    for symbol_name, key in L2_CALLBACKS:
        symbol = elf.symbol(symbol_name)
        gate.require(symbol is not None and symbol["shndx"] != 0,
                     "%s is a defined symbol" % symbol_name)
        offset = elf.symbol_file_offset(symbol)
        gate.require(offset is not None and offset >= 4,
                     "%s has room for its KCFI prefix" % symbol_name)
        prefix = read_uint32(elf.raw, offset - 4)
        expected = pins[key]
        gate.require(prefix == expected,
                     "%s KCFI prefix == 0x%08x (%s)" % (symbol_name, expected, key),
                     "module emits 0x%08x; the kernel compares 0x%08x"
                     % (prefix, expected))
        gate.extras["prefix_" + symbol_name] = "0x%08x" % prefix
        declared = declarations.get(key + ".kernelNames")
        gate.require(declared is not None and int(declared, 16) == expected,
                     "the contract's declaration experiment records %s" % key,
                     "declarationCheck says %r" % declared)

    # G8 ④ -- the prototypes, byte for byte, with the kernel's struct names.
    for prototype in L2_PROTOTYPES:
        gate.require(prototype in ctx.source,
                     "prototype present verbatim: %s" % prototype.strip())
    shim_typed = [line.strip() for line in ctx.source.splitlines()
                  if any(name in line for name, _key in L2_CALLBACKS)
                  and "struct maplesu_" in line and "=" not in line]
    gate.require(not shim_typed,
                 "no callback is declared with a shim struct pointer",
                 "found: %s" % shim_typed[:2] if shim_typed else "none")

    # G8 ⑤ -- no no_sanitize on a callback. The attribute would not remove the
    # prefix anyway (measured), so this is a source-level statement about intent.
    lines = ctx.source.splitlines()
    sanitised = []
    for index, line in enumerate(lines):
        if not any(name in line for name, _key in L2_CALLBACKS):
            continue
        window = " ".join(lines[max(0, index - 1):index + 1])
        if "no_sanitize" in window or "MAPLESU_NOCFI" in window:
            sanitised.append(line.strip())
    gate.require(not sanitised,
                 "no callback carries no_sanitize(\"kcfi\")",
                 "found: %s" % sanitised[:2] if sanitised else "none")

    # G8 ⑥ -- the shim spelling measured a *different* id, which is the whole
    # reason the kernel names are mandatory. Recorded and asserted.
    shim_pre = declarations.get("preHandler.shimNames")
    shim_kret = declarations.get("kretprobeHandler.shimNames")
    gate.require(shim_pre is not None and int(shim_pre, 16) != pins["preHandler"],
                 "the shim-named pre handler hashes to a different id",
                 "shim %r vs kernel 0x%08x" % (shim_pre, pins["preHandler"]))
    gate.require(shim_kret is not None
                 and int(shim_kret, 16) != pins["kretprobeHandler"],
                 "the shim-named kretprobe handler hashes to a different id",
                 "shim %r vs kernel 0x%08x" % (shim_kret, pins["kretprobeHandler"]))
    gate.extras["shimIds"] = {"preHandler": shim_pre, "kretprobeHandler": shim_kret}


# --------------------------------------------------------------------------- #
# G9 -- hook lifecycle: register/unregister pairing, rollback, cleanup.
# --------------------------------------------------------------------------- #

def gate_hook_lifecycle(ctx: VerifyContext, gate: Gate) -> None:
    source = ctx.source
    # The Lead's brief places the jump-table assertion in G9; the design states
    # it as G12 ①. Both gates run it, so either id failing blocks the build.
    _l2_no_jump_tables(ctx, gate, "G9")

    for register, unregister in (("register_kprobe(", "unregister_kprobe("),
                                 ("register_kretprobe(", "unregister_kretprobe(")):
        gate.require(source.count(register) >= 1,
                     "the module registers with %s" % register.rstrip("("))
        gate.require(source.count(unregister) >= 1,
                     "the module unregisters with %s" % unregister.rstrip("("))

    arm = function_body(source, "l2_probe_register")
    disarm = function_body(source, "l2_probe_unregister")
    dispose = function_body(source, "l2_disarm_all")
    cleanup = function_body(source, "cleanup_module")
    register_all = function_body(source, "l2_register_probes")
    rollback = function_body(source, "l2_rollback")
    gate.require(bool(arm) and bool(disarm) and bool(dispose) and bool(cleanup),
                 "the registry's four functions exist",
                 "lengths %s" % {"register": len(arm), "unregister": len(disarm),
                                 "disarm_all": len(dispose), "cleanup": len(cleanup)})
    gate.require("l2_probe_unregister(" in dispose,
                 "l2_disarm_all calls the unregister path",
                 "body length %d" % len(dispose))
    gate.require("l2_disarm_all(" in cleanup,
                 "cleanup_module calls l2_disarm_all",
                 "body length %d" % len(cleanup))
    gate.require("l2_disarm_all(" in rollback,
                 "l2_rollback routes through l2_disarm_all",
                 "body length %d" % len(rollback))
    # Every registration is checked, and every failure rolls back: after the
    # first l2_probe_register( call in l2_register_probes, a `return` that is not
    # `return 0` must carry l2_rollback() on its own or the previous line.
    register_calls = [match.start() for match in
                      re.finditer(r"l2_probe_register\(", register_all)]
    gate.require(bool(register_calls),
                 "l2_register_probes calls l2_probe_register",
                 "body length %d" % len(register_all))
    if register_calls:
        # The first failure has nothing to roll back (no probe is registered
        # yet); from the second call on, every non-zero return must roll back.
        start = register_calls[1] if len(register_calls) > 1 else register_calls[0]
        lines = register_all[start:].splitlines()
        unguarded = []
        for index, line in enumerate(lines):
            stripped = line.strip()
            if not stripped.startswith("return") or stripped == "return 0;":
                continue
            window = " ".join(lines[max(0, index - 1):index + 1])
            if "l2_rollback(" not in window:
                unguarded.append(stripped)
        gate.require(not unguarded,
                     "every registration failure after the first rolls back",
                     "unguarded returns: %s" % unguarded)

    # Object side: the calls are where the source says they are, and cleanup
    # reaches the unregister path through l2_disarm_all.
    calls = _l2_calls(ctx)
    for callee in ("register_kprobe", "register_kretprobe",
                   "unregister_kprobe", "unregister_kretprobe"):
        gate.require(any(edge[1] == callee for edge in calls),
                     "the object calls %s" % callee)
    gate.require(_l2_body_contains_call(ctx, "cleanup_module", "l2_disarm_all"),
                 "cleanup_module's object text calls l2_disarm_all",
                 "callees: %s" % sorted({edge[1] for edge in calls
                                         if edge[0] == "cleanup_module"}))
    unregister_owners = {edge[0] for edge in calls
                         if edge[1] in ("unregister_kprobe", "unregister_kretprobe")}
    gate.require(bool(unregister_owners & {"l2_probe_unregister", "l2_disarm_all"}),
                 "the unregister calls live in the disarm path",
                 "called from: %s" % sorted(unregister_owners))
    gate.require(not any(edge[1].startswith("unregister_") for edge in calls
                         if edge[0] in {"l2_pre_ctl", "l2_pre_execve",
                                        "l2_post_execve", "l2_ret_execve"}),
                 "no callback unregisters anything (they run in kprobe context, "
                 "where unregister would sleep)", "callbacks are clean")
    gate.check(True, "lifecycle: register in init, unregister in cleanup",
               "probes are registered once (%d sites) and unregistered by "
               "l2_disarm_all (reverse order, idempotent)" % len(register_calls),
               fatal=False)


# --------------------------------------------------------------------------- #
# G10 -- atomic-context safety: the callbacks' call closure.
# --------------------------------------------------------------------------- #

def gate_atomic_safety(ctx: VerifyContext, gate: Gate) -> None:
    callbacks = {"l2_pre_ctl", "l2_pre_execve", "l2_post_execve", "l2_ret_execve"}
    closure = _l2_call_closure(ctx, callbacks)
    callees: dict = {}
    for caller, callee, _section, _offset in _l2_calls(ctx):
        callees.setdefault(caller, set()).add(callee)
    violations = []
    for function in sorted(closure):
        for callee in sorted(callees.get(function, ())):
            if callee not in L2_FORBIDDEN_CALLS:
                continue
            if callee in ("printk", "_printk") and function in L2_PRINTK_FUNCTIONS:
                continue
            violations.append("%s -> %s (%s)"
                              % (function, callee, L2_FORBIDDEN_CALLS[callee]))
    gate.require(not violations,
                 "no callback reachable function calls a forbidden primitive",
                 "none" if not violations else "; ".join(violations[:4]))

    # The `bl` closure cannot see a `blr`; G13 covers "no check site", and here
    # the complementary statement: every function in the closure that contains an
    # indirect call is one of the NOCFI functions.
    functions = _l2_functions(ctx)
    indirect = []
    for section in _l2_exec_sections(ctx):
        blob = ctx.elf.section_bytes(section)
        for name_of_section, start, end, name in functions:
            if name_of_section != section["name"] or name not in closure:
                continue
            if any(is_blr(read_uint32(blob, offset))
                   for offset in range(start, end - 3, 4)):
                indirect.append(name)
    not_nocfi = [name for name in indirect if not nocfi_covers(ctx.source, name)]
    gate.require(not not_nocfi,
                 "every indirect call in the callback closure is in a "
                 "MAPLESU_NOCFI function",
                 "direct callers with a blr: %s; not covered: %s"
                 % (indirect, not_nocfi))
    gate.require("l2_result_out" in indirect,
                 "the result-block write is the closure's indirect call (so the "
                 "check above is not vacuous)",
                 "indirect callers: %s" % indirect)
    gate.extras["callbackClosure"] = sorted(closure)
    gate.check(True, "callbacks reach %d module function(s)" % len(closure),
               ", ".join(sorted(closure)), fatal=False)


# --------------------------------------------------------------------------- #
# G11 -- control-channel invariants: no per-release constant, one syscall source.
# --------------------------------------------------------------------------- #

def gate_control_channel(ctx: VerifyContext, gate: Gate) -> None:
    source = ctx.source
    hits = []
    for section in _l2_exec_sections(ctx):
        for hit in materialised_immediates(ctx.elf.section_bytes(section),
                                           L2_MAGIC_SCNO):
            hits.append("%s+0x%x (%s)" % (section["name"], hit["offset"],
                                          hit["how"]))
    gate.require(not hits,
                 "the magic syscall number is never materialised "
                 "(movz/movk/cmp #imm decoded, not a byte pattern)",
                 "none" if not hits else "found: %s" % hits[:4])

    self_check = [line for line in source.splitlines()
                  if "sys_call_table" in line and "ni_addr" in line]
    gate.require(bool(self_check),
                 "ARM self-checks sys_call_table[scno] == &__arm64_sys_ni_syscall",
                 "no line carries both sys_call_table and ni_addr")

    assignments = re.findall(r"l2\.scno\s*=(?!=)\s*([^;]+);", source)
    producer = re.search(r"scno\s*=\s*\(u32\)\s*r->syscallno\s*;", source)
    gate.require(len(assignments) == 1 and producer is not None
                 and assignments[0].strip() in ("scno", "(u32)r->syscallno"),
                 "l2.scno is assigned once, from pt_regs->syscallno (never from a "
                 "caller-supplied number)",
                 "assignments: %r, producer: %r"
                 % (assignments, producer.group(0) if producer else None))
    gate.require(re.search(r"scno\s*!=\s*l2\.scno", source) is not None,
                 "every op after ARM compares regs->syscallno with the bound number",
                 "no `scno != l2.scno` comparison")

    ctl = function_body(source, "l2_pre_ctl")
    cookie_at = ctl.find("load_cookie")
    dispatch_at = ctl.find("l2_ctl_dispatch(")
    gate.require(cookie_at >= 0 and dispatch_at > cookie_at,
                 "the cookie gate runs before any op is dispatched",
                 "cookie=%d dispatch=%d" % (cookie_at, dispatch_at))
    gate.require(source.count("l2_ctl_dispatch(") == 2
                 and ctl.count("l2_ctl_dispatch(") == 1,
                 "l2_ctl_dispatch is called from the control callback only",
                 "file=%d callback=%d" % (source.count("l2_ctl_dispatch("),
                                         ctl.count("l2_ctl_dispatch(")))

    writes = re.findall(r"l2\.load_cookie\s*=", source)
    init = function_body(source, "init_module")
    gate.require(len(writes) == 1 and "l2.load_cookie" in init,
                 "l2.load_cookie is written once, in init_module (the channel never "
                 "creates a token, it only proves one)",
                 "writes=%d, in init=%s" % (len(writes), "l2.load_cookie" in init))
    gate.require("l2.load_cookie == 0" in init and "-L2_EINVAL" in init,
                 "a zero cookie refuses the load with -EINVAL",
                 "the refusal is in init_module")

    # The helper's half is C2's S27. It is asserted here when hook_l2 exists, and
    # reported as not-yet-applicable when it does not, so the C1 build is not
    # blocked by work it does not own -- and the omission stays visible.
    helper = (PROJECT_ROOT / "jailbreak-providers" / "dirtyfrag" / "adapter"
              / "bootstrap.c")
    helper_text = helper.read_text(encoding="utf-8") if helper.is_file() else ""
    hook = function_body(helper_text, "hook_l2") if helper_text else ""
    if hook:
        gate.require('"cookie=0x' in hook,
                     "hook_l2 passes a non-zero cookie in finit_module's uargs",
                     "the helper half of the cookie channel (M6)")
    else:
        gate.check(True, "hook_l2 does not exist yet (C2); its uargs cookie is S27",
                   "the module half is asserted above: the cell is read, zero is "
                   "refused, and the channel only proves the value", fatal=False)

    blob = b"".join(ctx.elf.section_bytes(section) for section in ctx.elf.sections
                    if section["flags"] & SHF_ALLOC)
    gate.require(blob.count(b'"/data/') == 0,
                 'zero "/data/" literals in the module (the path is the helper\'s)',
                 "found %d" % blob.count(b'"/data/'))
    virtual = []
    for offset in range(0, max(0, len(blob) - 7), 8):
        value = struct.unpack_from("<Q", blob, offset)[0]
        if KERNEL_VA_LOW <= value <= KERNEL_VA_HIGH:
            virtual.append("0x%x+0x%x" % (offset, value))
    gate.require(not virtual, "no kernel virtual address literal in allocated data",
                 "none" if not virtual else "found: %s" % virtual[:3])


# --------------------------------------------------------------------------- #
# G12 -- no residual arm: the latch gates the callbacks, the loop is reverse.
# --------------------------------------------------------------------------- #

def gate_no_residual_arm(ctx: VerifyContext, gate: Gate) -> None:
    source = ctx.source
    _l2_no_jump_tables(ctx, gate, "G12")

    dispose = function_body(source, "l2_disarm_all")
    gate.require(bool(dispose), "l2_disarm_all exists")
    reverse = re.search(r"for\s*\(\s*i\s*=\s*L2_MAX_PROBES\s*-\s*1\s*;\s*"
                        r"i\s*>=\s*0\s*;\s*--i\s*\)", dispose)
    gate.require(reverse is not None,
                 "l2_disarm_all walks the registry in reverse from L2_MAX_PROBES-1",
                 "body: %r" % dispose[:120])
    gate.require("state != L2_REGISTERED" in dispose
                 or "state == L2_REGISTERED" in dispose,
                 "the loop skips rows that were never registered (idempotent)",
                 "no state test in the loop")
    gate.require("unregister_" in dispose or "l2_probe_unregister" in dispose,
                 "the loop unregisters")
    for callback in ("l2_pre_execve", "l2_post_execve", "l2_ret_execve"):
        body = function_body(source, callback)
        gate.require("l2.latch != L2_LATCH_ARMED" in body,
                     "%s observes only while the latch is ARMED" % callback,
                     "no latch test in the callback: DISARM would leave it live")
    gate.require(re.search(r"l2\.latch\s*=\s*L2_LATCH_DISARMED", source) is not None,
                 "DISARM moves the latch to its terminal state")
    gate.require(source.count("l2.latch = L2_LATCH_ARMED") == 1,
                 "ARM is the only transition into ARMED (one ARM per load)",
                 "found %d assignment(s)" % source.count("l2.latch = L2_LATCH_ARMED"))
    arm = function_body(source, "l2_op_arm")
    gate.require("l2.latch == L2_LATCH_ARMED" in arm and "-L2_EBUSY" in arm,
                 "a second ARM in the same load returns -EBUSY",
                 "the latch test is in l2_op_arm")
    gate.require(_l2_body_contains_call(ctx, "cleanup_module", "l2_disarm_all"),
                 "cleanup_module's object text reaches the unregister path")
    register = {edge[1] for edge in _l2_calls(ctx)
                if edge[1] in ("register_kprobe", "register_kretprobe")}
    unregister = {edge[1] for edge in _l2_calls(ctx)
                  if edge[1] in ("unregister_kprobe", "unregister_kretprobe")}
    gate.require(len(register) == len(unregister),
                 "the object's register and unregister symbol sets are paired",
                 "register=%s unregister=%s" % (sorted(register), sorted(unregister)))


# --------------------------------------------------------------------------- #
# G13 -- zero KCFI check sites (the property that makes __nocfi complete).
# --------------------------------------------------------------------------- #

def gate_zero_check_sites(ctx: VerifyContext, gate: Gate) -> None:
    sites = []
    traps = []
    for section in _l2_exec_sections(ctx):
        blob = ctx.elf.section_bytes(section)
        for hit in kcfi_type_ids(blob):
            sites.append({"section": section["name"],
                          "offset": "0x%x" % (hit["instruction"] * 4),
                          "typeId": "0x%08x" % hit["typeId"]})
        for index in range(len(blob) // 4):
            imm = decode_brk(read_uint32(blob, index * 4))
            if imm is not None:
                traps.append({"section": section["name"],
                              "offset": "0x%x" % (index * 4),
                              "immediate": "0x%x" % imm})
    gate.require(not sites,
                 "zero KCFI check sites in module text (no ldur/movk/cmp/brk/blr "
                 "sequence)",
                 "none" if not sites else "found: %s" % sites[:4])
    gate.require(not traps,
                 "zero BRK instructions in module text (PANIC_ON_OOPS makes a trap "
                 "a panic)", "none" if not traps else "found: %s" % traps[:4])
    gate.extras["kcfiSites"] = sites
    gate.extras["traps"] = traps
    gate.check(True, "the ids a check site would build are the pinned ones",
               "0x%08x / 0x%08x / 0x%08x -- asserted by G8 at the callbacks"
               % (0xA6BE5DD9, 0x6E21B9C7, 0x8078BE15), fatal=False)


# --------------------------------------------------------------------------- #
# G14 -- write scope: no arbitrary write, one byte for the policy window.
# --------------------------------------------------------------------------- #

def gate_write_scope(ctx: VerifyContext, gate: Gate) -> None:
    source = ctx.source
    census = l2_store_census(ctx)
    gate.require(not census["violations"],
                 "every .text store is stack-derived, own-data-derived, or the "
                 "whitelist writer",
                 "stores=%d stack=%d own=%d whitelist=%d violations=%s"
                 % (census["stores"], census["stack"], census["own"],
                    census["whitelist"], census["violations"][:3]))
    gate.require(census["stores"] > 0,
                 "the module really stores (so the census is not vacuous)",
                 "stores=%d" % census["stores"])

    kwrite = function_body(source, "l2_kwrite")
    gate.require(bool(kwrite), "l2_kwrite exists")
    for tag in L2_WRITE_TAGS:
        gate.require(tag in kwrite, "l2_kwrite handles %s" % tag)
    gate.require(kwrite.count("return -L2_EACCES") >= 2,
                 "everything outside the whitelist is refused with -EACCES",
                 "refusal count %d" % kwrite.count("return -L2_EACCES"))
    one_byte = re.search(r"\*\(\s*volatile\s+u8\s*\*\s*\)[^=;\n]{0,40}=\s*[^=]",
                         kwrite)
    gate.require(one_byte is not None,
                 "the SELinux store is a one-byte store through volatile u8")
    gate.require("enforcing_now" in kwrite
                 and kwrite.find("enforcing_now") < kwrite.find("*(volatile u8 *)"),
                 "the byte is compared with the value just read before it is written",
                 "the same-value rule is source-visible")
    gate.require("l2.selinux_addr" in kwrite,
                 "the SELinux store is restricted to the resolved object")

    # The policy pointer must never be a store target. The rule is about the
    # SELinux base, not about the number 0x48 in general (the module legitimately
    # stores into its own struct at that offset): the only store l2_kwrite makes
    # through the resolved object must be one byte at offset 0, which is
    # selinux_state.enforcing and nothing else.
    outside = [entry for entry in census["detail"]
               if entry["function"] == "l2_kwrite" and entry["class"] != "stack"]
    gate.require(len(outside) == 1 and bool(outside[0]["byte"])
                 and outside[0]["immediate"] in (0, None),
                 "l2_kwrite's only caller-visible store is one byte at offset 0 "
                 "(enforcing; policy@0x48 stays unreachable)",
                 "stores outside the stack in l2_kwrite: %s" % outside)
    gate.extras["writeCensus"] = {"stores": census["stores"],
                                  "stack": census["stack"], "own": census["own"],
                                  "whitelist": census["whitelist"],
                                  "byFunction": census["byFunction"]}


# --------------------------------------------------------------------------- #
# L3 gate bodies: the module-generic G5 with an L3 plan, plus L3-1..L3-11.
#
# Every one of these is red on a defect, and tests/test_l3_module.py carries a
# mutation for each one (a gate that cannot be red is not a gate). The numbers
# they compare against are either read out of the image by derive_l3_facts() or
# written in L3_ABI/L3_EXPECTED_IMPORTS/L3_LOOKUP_WHITELIST/L3_CALLBACK_PINS
# above -- never scraped out of the artifact they are judging.
# --------------------------------------------------------------------------- #

def l3_injected_flags(cflags) -> dict:
    """The -D facts the L3 build injected, parsed back out of the flags.

    G6 already asserts the flag list exists; this is how the ABI gate compares
    the numbers the *compiler* saw with the numbers the *image* and the
    *reference TU* state. A missing -D is a missing key here, and the gate turns
    that into a failure rather than a default.
    """
    out = {}
    for flag in cflags or []:
        if not isinstance(flag, str) or not flag.startswith("-D"):
            continue
        body = flag[2:]
        key, _, value = body.partition("=")
        try:
            if key == "MAPLESU_CRED_SOURCE":
                out["credSource"] = int(value, 0)
            elif key == "MAPLESU_L3_PRE_KCFI_ID":
                out["preKcfiId"] = int(value, 0)
            elif key == "MAPLESU_L3_KPROBE_SIZE":
                out["kprobeSize"] = int(value, 0)
        except ValueError:
            out[key] = value
    return out


def strip_c_comments(source: str) -> str:
    """The source with both comment forms removed (string literals untouched).

    Every source-level L3 assertion is made against this text, so a design
    decision that only exists in a comment cannot satisfy a gate -- and a comment
    that mentions a forbidden primitive cannot fail one either.
    """
    text = re.sub(r"/\*.*?\*/", " ", source, flags=re.S)
    return re.sub(r"//[^\n]*", " ", text)


def printk_calls(source: str) -> list:
    """Every `_printk(...)` argument list, brace-matched, comment-free.

    Used by L3-8: "the token never reaches printk" is a statement about the
    arguments of every printk in the file, not about the token variable.
    """
    text = strip_c_comments(source)
    calls = []
    for match in re.finditer(r"_printk\s*\(", text):
        depth = 1
        index = match.end()
        while index < len(text) and depth > 0:
            if text[index] == "(":
                depth += 1
            elif text[index] == ")":
                depth -= 1
            index += 1
        calls.append(text[match.end():index - 1])
    return calls


def l3_rodata_literals(elf) -> set:
    """Every NUL-terminated ASCII identifier-ish string in the module's .rodata."""
    literals = set()
    for section in elf.sections:
        if not section["flags"] & SHF_ALLOC or section["type"] == SHT_NOBITS:
            continue
        if not section["name"].startswith(".rodata"):
            continue
        for text in elf.section_bytes(section).split(b"\x00"):
            if len(text) < 4 or len(text) > 128:
                continue
            try:
                decoded = text.decode("utf-8")
            except UnicodeDecodeError:
                continue
            if decoded.replace("_", "").isalnum():
                literals.add(decoded)
    return literals


def gate_patch_plan_l3(ctx: VerifyContext, gate: Gate) -> None:
    """G5 for maplesu_l3: the plan is symbolic, and every name in it is allowed.

    The resident-root plan G5 encodes (helper path, UMH wait mode, SELinux byte)
    does not exist in this module, so its body would fail for the wrong reason.
    What carries over is the part that is about *this* module: no per-release
    address anywhere, no relocation form the loader cannot apply, no GOT, no
    runtime lookup outside the whitelist, and no primitive that writes the kernel
    image. The plan then names the kprobe design step by step, and every step is
    a symbol or an operation -- never an address.
    """
    elf, contract = ctx.elf, ctx.contract
    facts = ctx.l3_facts
    callbacks = ctx.callback_ids()
    gate.require(facts is not None, "the L3 image facts were derived",
                 "derive_l3_facts() has no result")

    gate.require(elf.section(".ir_spec") is None, "no .ir_spec section",
                 "the old per-release byte-patch blob must not come back")
    banner = elf.raw.find(b"#maplesu-ir-spec")
    gate.require(banner < 0, "no #maplesu-ir-spec banner anywhere in the file",
                 "absent" if banner < 0 else "found at file offset 0x%x" % banner)

    scanned, hits = [], []
    for section in elf.sections:
        if not section["flags"] & SHF_ALLOC or section["type"] == SHT_NOBITS:
            continue
        blob = elf.section_bytes(section)
        scanned.append((section["name"], len(blob)))
        for offset in range(max(0, len(blob) - 7)):
            value = struct.unpack_from("<Q", blob, offset)[0]
            if KERNEL_VA_LOW <= value <= KERNEL_VA_HIGH:
                hits.append("%s+0x%x = 0x%x" % (section["name"], offset, value))
    gate.require(not hits, "no kernel virtual address in any allocated section",
                 "0 hits in %d bytes across %d sections"
                 % (sum(size for _name, size in scanned), len(scanned))
                 if not hits else "a per-release address table looks like this: %s"
                 % hits[:5])

    addend_hits, type_counts, unknown = [], {}, set()
    for target, relocs in elf.all_relocations().items():
        for entry in relocs:
            type_counts[entry["type"]] = type_counts.get(entry["type"], 0) + 1
            if entry["type"] not in ARM64_MODULE_RELOCS:
                unknown.add(entry["type"])
            if KERNEL_VA_LOW <= entry["addend"] & 0xFFFFFFFFFFFFFFFF <= KERNEL_VA_HIGH:
                addend_hits.append("%s+0x%x -> 0x%x"
                                   % (target, entry["offset"], entry["addend"]))
    gate.require(not addend_hits, "no relocation addend is a kernel address",
                 "%s" % addend_hits[:5])
    gate.require(not unknown, "every relocation type is one arm64 implements",
                 "unknown: %s" % sorted(unknown))
    got = sorted(set(type_counts) & GOT_RELOC_TYPES)
    gate.require(not got, "no GOT relocation (no .got dependency)",
                 "none" if not got else
                 "types %s: no shipped module for this kernel has a .got"
                 % [ARM64_MODULE_RELOCS[value] for value in got])
    gate.extras["relocationTypes"] = {str(value): type_counts[value]
                                      for value in sorted(type_counts)}

    literals = l3_rodata_literals(elf)
    resolvable = {name for name in literals if name in ctx.symbols.rows}
    bridge = (ctx.l3_facts or {}).get("policyBridge", {})
    sdk = (ctx.l3_facts or {}).get("bootSdk", {})
    unexpected = sorted(resolvable - L3_LOOKUP_WHITELIST - set(bridge.get("lookupSymbols", [])) -
                        set(sdk.get("lookupSymbols", [])))
    gate.require(not unexpected,
                 "every resolvable kernel symbol name in .rodata is whitelisted",
                 "unexpected: %s -- a new runtime lookup is a design change, so "
                 "L3_LOOKUP_WHITELIST has to grow with it" % unexpected)
    present = sorted(resolvable)
    gate.extras["lookupNames"] = present
    gate.require("kallsyms_lookup_name" in literals
                 and L3_PRCTL_SYMBOL in literals,
                 "the resolver bootstrap and the registration target are carried "
                 "by the module",
                 "lookups found: %s" % present)

    stripped = strip_c_comments(ctx.source)
    named = sorted(name for name in L3_FORBIDDEN_SOURCE
                   if re.search(r"\b%s\b" % re.escape(name), stripped))
    gate.require(not named,
                 "the source names no kernel-image write primitive",
                 "found: %s -- this module must not be able to write the image"
                 % named)

    plan = {
        "schema": "maplesu-l3-kprobe-plan/1",
        "module": L3_MODULE,
        "kernelRelease": contract["kernelRelease"],
        "imageSha256": contract["imageSha256"],
        "writesKernelImage": False,
        "steps": [
            {"step": 1, "action": "resolve kallsyms_lookup_name",
             "via": "register_kprobe on symbol_name", "absoluteAddress": None},
            {"step": 2, "action": "look up the kernel objects this module uses",
             "names": sorted(L3_LOOKUP_WHITELIST), "absoluteAddress": None},
            {"step": 3, "action": "build the kernel-root cred pool",
             "count": L3_ABI["pool"],
             "source": ("init_task" if facts["credSource"]["source"]
                        == L3_CRED_INIT_TASK else "NULL"),
             "absoluteAddress": None},
            {"step": 4, "action": "install a kprobe on the syscall handler",
             "via": "register_kprobe(symbol_name=%s)" % L3_PRCTL_SYMBOL,
             "symbol": L3_PRCTL_SYMBOL, "newSymbol": "maplesu_l3_pre",
             "kcfiPreHandler": facts["kprobe"]["dispatch"]["preHandler"]["typeId"],
             "kcfiPreHandlerSource": "image pre_handler dispatch site",
             "absoluteAddress": None},
            {"step": 5, "action": "ARM: magic (option 4 & a non-zero token), read "
                                  "selinux_state->enforcing, then claim the gate "
                                  "with one CAS",
             "via": "__sync_bool_compare_and_swap", "absoluteAddress": None},
            {"step": 6, "action": "SU: commit one pre-allocated root cred to the "
                                  "calling task",
             "via": "commit_creds(pool)", "absoluteAddress": None},
            {"step": 7, "action": "unregister the kprobe (the complete undo)",
             "via": "unregister_kprobe", "restore": "cleanup_module",
             "absoluteAddress": None},
        ],
    }
    gate.require(len(plan["steps"]) == 7,
                 "the symbolic plan has the seven design steps",
                 "%d steps" % len(plan["steps"]))
    gate.require(plan["writesKernelImage"] is False,
                 "the plan writes no kernel-image byte",
                 "a kprobe is installed through the kernel's own text machinery; "
                 "this module contains no write primitive")
    gate.require(all(step["absoluteAddress"] is None for step in plan["steps"]),
                 "no plan step carries an absolute address")
    gate.require(plan["steps"][3]["symbol"] == L3_PRCTL_SYMBOL,
                 "the plan's kprobe target is the symbol the module registers on",
                 "%r" % plan["steps"][3]["symbol"])
    plan_hits = [step for step in plan["steps"]
                 if any(isinstance(value, int) and KERNEL_VA_LOW <= value <= KERNEL_VA_HIGH
                        for value in step.values())]
    gate.require(not plan_hits, "the plan contains no kernel virtual address",
                 "%s" % plan_hits[:2])
    gate.extras["patchPlan"] = plan
    gate.extras["imageKprobe"] = {
        "symbol": facts["kprobe"]["symbol"],
        "address": facts["kprobe"]["address"],
        "extent": facts["kprobe"]["extent"],
        "cfiTypeIds": facts["kprobe"]["cfiTypeIds"],
        "callbackAnchors": facts["kprobe"]["callbackAnchors"],
        "callbackDispatch": facts["kprobe"]["dispatch"],
        "callbackPins": facts["callbackPins"],
        "callbackPrefixPolicy": facts["callbackPrefixPolicy"],
        "contractCallbacks": facts["contractCallbacks"],
        "referenceKcfi": {"preHandler": "0x%08x" % callbacks["preHandler"],
                          "postHandler": "0x%08x" % callbacks["postHandler"]},
        "probedSymbol": facts["probedSymbol"],
        "selinux": facts["selinux"],
        "credSource": facts["credSource"],
    }


def gate_l3_handler_prefix(ctx: VerifyContext, gate: Gate) -> None:
    """L3-1: the callback's 4-byte prefix is the image's pre-handler dispatch id.

    This is the difference between a hook and a panic. The kernel reaches a
    kprobe's pre_handler through a CONFIG_CFI_CLANG-instrumented indirect call,
    so the 4 bytes in front of `maplesu_l3_pre` must be the id the image compares
    at its own pre-handler dispatch site. Four independent statements meet here:

      * the *image* names the id itself: `derive_kprobe_handler_ids` attributes
        every compare site in the candidate anchor functions to the callback whose
        `struct kprobe` member the checked pointer was loaded from, and the
        attributed sites must agree on one id per callback;
      * the *image* says the two callbacks are different types (pre id != post id);
      * the *pinned toolchain* still derives the recorded pair for the kernel's two
        prototypes (a change here is a build failure, not a warning);
      * the *artifact* carries the image's pre id, and the -D the compiler saw is
        that same id, so the module's own load-time check
        (`maplesu_handler_prefix() == MAPLESU_L3_PRE_KCFI_ID`) passes.

    The builder writes the image's id into this slot after linking (the same
    `patch_kcfi_prefixes` mechanism init_module/cleanup_module use) when the family
    opts in with `l3.callbackPrefixPolicy`. Where clang's own hash already equals the
    image's id the write is a no-op; on 6.12/6.18 it is the difference between a hook
    and a trap.
    """
    elf = ctx.elf
    facts = ctx.l3_facts
    callbacks = ctx.callback_ids()
    dispatch = facts["kprobe"]["dispatch"]
    pre = dispatch["preHandler"]
    post = dispatch["postHandler"]
    image_pre = pre["typeId"]

    # -- the image's own dispatch, per callback ---------------------------- #
    for key, entry in (("preHandler", pre), ("postHandler", post)):
        gate.require(bool(entry["attributedSites"]),
                     "the image attributes at least one compare site to the %s"
                     % key,
                     "anchors present %s, absent %s"
                     % (entry["anchorsPresent"], entry["anchorsAbsent"]))
        gate.require(len({site["typeId"] for site in entry["attributedSites"]}) == 1,
                     "every %s dispatch site the image attributes agrees on one id"
                     % key,
                     "%s" % entry["attributedSites"])
        gate.require(entry["typeId"] in
                     {site["typeId"] for site in entry["attributedSites"]},
                     "the %s id in the build facts is the one those sites compare"
                     % key, "%s vs %s" % (entry["typeId"],
                                          entry["attributedSites"]))
    gate.require(pre["typeId"] != post["typeId"],
                 "the image compares a different id for pre and post",
                 "pre %s, post %s; two callback prototypes cannot hash to one id"
                 % (pre["typeId"], post["typeId"]))

    # -- the toolchain's own statement -------------------------------------- #
    for key, pin in L3_CALLBACK_PINS.items():
        gate.require(callbacks[key] == pin,
                     "the reference TU reproduces the pinned %s id 0x%08x"
                     % (key, pin),
                     "clang derives 0x%08x; L3_CALLBACK_PINS records 0x%08x. A "
                     "toolchain that hashes the prototype differently would change "
                     "the bytes this module is built from, so it is a build failure"
                     % (callbacks[key], pin))

    # -- the artifact -------------------------------------------------------- #
    symbol = elf.symbol("maplesu_l3_pre")
    gate.require(symbol is not None and symbol["shndx"] != 0,
                 "maplesu_l3_pre is defined")
    if symbol is None or symbol["shndx"] == 0:
        return
    offset = elf.symbol_file_offset(symbol)
    gate.require(offset is not None and offset >= 4,
                 "maplesu_l3_pre has room for its 4-byte KCFI prefix")
    if offset is None or offset < 4:
        return
    prefix = read_uint32(elf.raw, offset - 4)
    gate.require(prefix == int(image_pre, 16),
                 "the artifact's prefix == the id the image's pre-handler "
                 "dispatch site compares",
                 "artifact 0x%08x, image %s@%s (this is the assertion the prefix "
                 "write exists for: a mismatch here is a panic on the first "
                 "prctl())" % (prefix, image_pre,
                               ", ".join("%s:%s" % (site["function"], site["site"])
                                         for site in pre["attributedSites"])))
    gate.require(prefix != int(post["typeId"], 16),
                 "the artifact's prefix is not the post-handler id",
                 "artifact 0x%08x, post %s" % (prefix, post["typeId"]))

    injected = l3_injected_flags(ctx.cflags)
    gate.require(injected.get("preKcfiId") == prefix,
                 "-DMAPLESU_L3_PRE_KCFI_ID is the id the artifact carries",
                 "flags say %r, artifact carries 0x%08x (the module's own "
                 "load-time check compares the two)" % (injected.get("preKcfiId"),
                                                         prefix))

    measurement = ctx.l3_prefix_measurement
    if measurement:
        gate.require(measurement.get("emittedPrefix") == callbacks["preHandler"],
                     "the prefix clang emitted was the reference TU's id",
                     "build measured emitted=%r, reference=0x%08x"
                     % (measurement.get("emittedPrefix"), callbacks["preHandler"]))
        gate.require(measurement.get("shippedPrefix") == prefix,
                     "the build record's shipped prefix is the artifact's",
                     "record %r, artifact 0x%08x"
                     % (measurement.get("shippedPrefix"), prefix))
        if int(image_pre, 16) != callbacks["preHandler"]:
            gate.require(measurement.get("prefixPolicy") == L3_PREFIX_POLICY_PATCHED,
                         "this family's prefix write is the recorded policy",
                         "policy %r; the image id %s differs from the toolchain's "
                         "0x%08x, so the write has to be opted in"
                         % (measurement.get("prefixPolicy"), image_pre,
                            callbacks["preHandler"]))
            gate.require(measurement.get("rewritten") is True,
                         "the artifact was rewritten from the toolchain id to the "
                         "image id", "%r" % (measurement,))

    normalized = " ".join(ctx.source.split())
    gate.require(" ".join(L3_PRE_PROTOTYPE.split()) in normalized,
                 "the callback is declared with the kernel's own prototype",
                 "expected %r with opaque `struct kprobe`/`struct pt_regs` "
                 "forward declarations (clang hashes the spelling)" % L3_PRE_PROTOTYPE)
    gate.require(not nocfi_covers(ctx.source, "maplesu_l3_pre"),
                 "the callback is not marked MAPLESU_NOCFI",
                 "its prefix is mandatory and is the kernel-checked 4 bytes")
    gate.extras["prefix"] = {
        "handler": "0x%08x" % prefix,
        "image": image_pre,
        "reference": "0x%08x" % callbacks["preHandler"],
        "postImage": post["typeId"],
        "postReference": "0x%08x" % callbacks["postHandler"],
        "imageSet": sorted(facts["kprobe"]["cfiTypeIds"]),
        "anchors": facts["kprobe"]["callbackAnchors"],
        "dispatch": {key: {"typeId": entry["typeId"],
                           "member": entry["member"],
                           "memberOffset": entry["memberOffset"],
                           "anchorsPresent": entry["anchorsPresent"],
                           "anchorsAbsent": entry["anchorsAbsent"],
                           "attributedSites": entry["attributedSites"]}
                     for key, entry in (("preHandler", pre),
                                        ("postHandler", post))},
        "policy": facts["callbackPrefixPolicy"],
        "measurement": measurement,
        "prototype": L3_PRE_PROTOTYPE,
    }


def gate_l3_imports(ctx: VerifyContext, gate: Gate) -> None:
    """L3-2: the undefined set is exactly the three the design names.

    G3 already checks that each import has a __ksymtab row in the right range;
    this gate states the set itself, positively and negatively, because "the
    imports are what the design says" is the property the loader depends on.
    """
    imports = ctx.elf.imports()
    expected = ctx.expected_imports()
    gate.require(sorted(imports) == sorted(expected),
                 "the undefined set is exactly the measured three-import set",
                 "found %s, expected %s"
                 % (sorted(imports), sorted(expected)))
    extra = sorted(set(imports) - expected)
    gate.require(not extra, "no fourth import",
                 "extra: %s -- an import is a new dependency, and this module's "
                 "whole kernel dependency list is three names" % extra)
    gate.extras["imports"] = imports


def gate_l3_no_data_literal(ctx: VerifyContext, gate: Gate) -> None:
    """L3-3: zero `/data/` literals, and no write primitive by name.

    L3 never execs, never mounts, never names a path: it is entered through a
    kprobe and does all of its work in kernel memory. A `/data/` string here
    would mean a second, unreviewed entry point came back. The primitive names
    (`sys_call_table`, `set_memory_rw`, `set_memory_ro`, `text_poke`) are
    forbidden for the other half of the same statement: this module must not be
    able to write a kernel-image byte, so it may not even name the tools.
    """
    elf = ctx.elf
    # The optional bridge changes protection only on its own bounded allocation.
    # Kernel-image patching primitives and paths remain forbidden everywhere.
    bridge = (ctx.l3_facts or {}).get("policyBridge")
    permitted = {b"set_memory_rw", b"set_memory_ro"} if bridge else set()
    present = [needle.decode("utf-8", "replace") for needle in L3_FORBIDDEN_STRINGS if needle not in permitted
               if needle in elf.raw]
    gate.require(not present, "no forbidden string anywhere in the module",
                 "found: %s" % present)
    rodata = b"".join(elf.section_bytes(section) for section in elf.sections
                      if section["flags"] & SHF_ALLOC
                      and section["name"].startswith(".rodata"))
    count = rodata.count(b"/data/")
    gate.require(count == 0, "0 \"/data/\" literals in .rodata",
                 "%d occurrence(s) in %d bytes of .rodata" % (count, len(rodata)))
    stripped = strip_c_comments(ctx.source)
    named = sorted(name for name in L3_FORBIDDEN_SOURCE
                   if re.search(r"\b%s\b" % re.escape(name), stripped))
    gate.require(not named, "the source names no kernel-image write primitive",
                 "found: %s" % named)
    gate.check(True, "the module names no path, no helper and no writer",
               "checked %s" % ", ".join(needle.decode() for needle
                                        in L3_FORBIDDEN_STRINGS), fatal=False)
    gate.extras["forbiddenStrings"] = present
    gate.extras["forbiddenSourceNames"] = named
    gate.extras["selfOwnedPolicyAllocation"] = bool(bridge)


def gate_l3_handler_text(ctx: VerifyContext, gate: Gate) -> None:
    """L3-4: the callback is in permanent `.text`, not in `.init.text`.

    init_module lives in `.init.text` and is freed after the module is loaded; a
    callback there would be freed memory behind a live kprobe. The section the
    symbol lands in is the fact, so it is checked by name and by flags.
    """
    elf = ctx.elf
    symbol = elf.symbol("maplesu_l3_pre")
    gate.require(symbol is not None and symbol["shndx"] != 0,
                 "maplesu_l3_pre is defined")
    if symbol is None or symbol["shndx"] == 0:
        return
    section = elf.symbol_section(symbol)
    gate.require(section is not None, "maplesu_l3_pre lives in a section")
    if section is None:
        return
    gate.require(section["name"] == ".text",
                 "the callback is in .text exactly",
                 "section is %r" % section["name"])
    gate.require(bool(section["flags"] & SHF_ALLOC)
                 and bool(section["flags"] & SHF_EXECINSTR),
                 "the callback's section is allocated and executable",
                 "flags=0x%x" % section["flags"])
    gate.require(not section["name"].startswith(".init")
                 and not section["name"].startswith(".exit"),
                 "the callback is not in an init/exit section",
                 section["name"])
    address = symbol["value"]
    extent = (section["addr"], section["addr"] + section["size"])
    gate.require(extent[0] <= address < extent[1],
                 "the callback's address is inside its section",
                 "value 0x%x, section [0x%x, 0x%x)" % (address, extent[0], extent[1]))
    gate.extras["handlerSection"] = {"name": section["name"], "offset": "0x%x"
                                     % (address - extent[0]),
                                     "size": "0x%x" % section["size"]}
    if (ctx.l3_facts or {}).get("policyBridge"):
        for name in ("maplesu_policy_control_pre", "msu_policy_pre", "msu_policy_ioctl"):
            callback = elf.symbol(name)
            gate.require(callback is not None and callback["shndx"] != 0, name + " is defined")
            gate.require(elf.symbol_section(callback)["name"] == ".text", name + " is resident")
            if name != "msu_policy_ioctl":
                at = elf.symbol_file_offset(callback)
                gate.require(read_uint32(elf.raw, at - 4) == int(ctx.l3_facts["callbackDispatch"]["preHandler"]["typeId"], 16),
                             name + " carries the measured native KCFI prefix")
    sdk = (ctx.l3_facts or {}).get("bootSdk")
    if sdk:
        callbacks = ["msu_boot_sdk_pre", "msu_boot_sdk_allocator_pre", "msu_boot_sdk_control", "sdk_execve",
                     "msu_sdk_call_template", "msu_sdk_call_original"]
        if sdk.get("environmentAbi"):
            callbacks += ["sdk_lookup", "sdk_module_free", "msu_boot_sdk_avc_redirect",
                          "msu_sdk_lookup_template", "msu_sdk_avc_template", "msu_sdk_avc_bridge"]
        for name in callbacks:
            callback = elf.symbol(name)
            gate.require(callback is not None and callback["shndx"] != 0, name + " is defined")
            if callback is None or callback["shndx"] == 0:
                continue
            gate.require(elf.symbol_section(callback)["name"] == ".text", name + " is resident")
            if name in ("msu_boot_sdk_pre", "msu_boot_sdk_allocator_pre"):
                at = elf.symbol_file_offset(callback)
                gate.require(read_uint32(elf.raw, at - 4) == int(ctx.l3_facts["callbackDispatch"]["preHandler"]["typeId"], 16),
                             name + " carries the measured native KCFI prefix")


def gate_l3_zero_traps(ctx: VerifyContext, gate: Gate) -> None:
    """L3-5: zero BRK and zero KCFI compare sites in module text.

    The same statement G6 makes for the root module: with CONFIG_PANIC_ON_OOPS=y a
    kernel BRK is a panic, not a log line, and every indirect call here goes
    through a runtime-resolved address or a NOCFI helper, so no check may
    survive -- the callback's own prefix is the only 4 bytes that are a type id.
    """
    elf = ctx.elf
    traps, sites, scanned = {}, [], 0
    for section in elf.sections:
        if not section["flags"] & SHF_EXECINSTR:
            continue
        blob = elf.section_bytes(section)
        scanned += len(blob)
        for index in range(len(blob) // 4):
            immediate = decode_brk(read_uint32(blob, index * 4))
            if immediate is not None:
                traps.setdefault(immediate, []).append("%s+0x%x"
                                                       % (section["name"], index * 4))
        for hit in kcfi_type_ids(blob):
            sites.append({"section": section["name"],
                          "offset": hit["instruction"] * 4,
                          "typeId": hit["typeId"]})
    gate.require(not traps, "zero BRK instructions in every SHF_EXECINSTR section",
                 "%d BRK(s): %s" % (sum(len(where) for where in traps.values()),
                                    traps) if traps else
                 "0 traps in %d bytes of module text" % scanned)
    gate.require(not sites, "no kCFI compare site is left in module text",
                 "%d site(s): %s" % (len(sites), sites[:4]) if sites else
                 "none (every indirect call is in a MAPLESU_NOCFI helper)")
    gate.extras["traps"] = {str(key): value for key, value in traps.items()}
    gate.extras["kcfiSites"] = sites
    gate.extras["scannedBytes"] = scanned


def gate_l3_cleanup_restore(ctx: VerifyContext, gate: Gate) -> None:
    """L3-6: the source registers the kprobe and the cleanup path unregisters it.

    This is a statement about the code, not the binary: "the hook is undone" is a
    data-flow property (the same object, the registration guard, that order) that
    no single instruction shows. The binary side contributes the two facts a
    source cannot state -- `this_module.exit` is `cleanup_module` (G4) and the
    registration object lives in `.bss`, so it outlives `.init.text`.
    """
    source = ctx.source
    cleanup = function_body(source, "cleanup_module")
    gate.require(bool(cleanup), "cleanup_module has a definition in the source",
                 "the glue's this_module.exit names it, so an absent definition is "
                 "an undefined symbol")
    if not cleanup:
        return
    install = function_body(source, "init_module")
    gate.require(bool(install), "init_module has a definition in the source")
    normalized_install = " ".join(install.split())
    normalized_cleanup = " ".join(cleanup.split())
    gate.require("register_kprobe(&maplesu_probe)" in normalized_install,
                 "init_module registers the gate's kprobe",
                 "the registration statement is the one the kernel's dispatch "
                 "depends on")
    gate.require("maplesu_probe.symbol_name = MAPLESU_L3_SYMBOL" in
                 normalized_install,
                 "the registration is by symbol name, not by address",
                 "no syscall index and no address is a build input")
    gate.require("maplesu_probe.pre_handler = maplesu_l3_pre" in normalized_install,
                 "init_module installs maplesu_l3_pre as the pre handler")
    gate.require("unregister_kprobe(&maplesu_probe)" in normalized_cleanup,
                 "cleanup_module unregisters the kprobe",
                 "body: %s" % normalized_cleanup[:220])
    unregister_at = normalized_cleanup.find("unregister_kprobe(&maplesu_probe)")
    guard_at = normalized_cleanup.find("if (!maplesu_registered)")
    clear_at = normalized_cleanup.find("maplesu_registered = 0")
    gate.require(guard_at >= 0, "cleanup does nothing when the hook is not installed",
                 "an unregister of a probe this module never registered is not an "
                 "undo")
    gate.require(0 <= guard_at < unregister_at < clear_at,
                 "the cleanup order is guard -> unregister -> clear",
                 "offsets guard=%d unregister=%d clear=%d"
                 % (guard_at, unregister_at, clear_at))
    gate.require("maplesu_pool_release(" in normalized_cleanup,
                 "cleanup gives the untaken creds back",
                 "the pool is kernel heap, not module data")
    # `unregister_kprobe(` contains `register_kprobe(`, so the two counts are
    # separated by a lookbehind rather than by substring arithmetic.
    registrations = len(re.findall(r"(?<!un)register_kprobe\(&", normalized_install))
    unregistrations = len(re.findall(r"unregister_kprobe\(&", normalized_install))
    gate.require(registrations == 3,
                 "init_module registers resolver, root-subject data path and gate",
                 "%d found; all are `register_kprobe(&<object>)` statements" 
                 % registrations)
    gate.require(unregistrations == 4,
                 "init_module undoes resolver and both gate failure paths",
                 "%d `unregister_kprobe(&<object>)` found" % unregistrations)
    gate.require(len(re.findall(r"unregister_kprobe\(&", normalized_cleanup)) == 2,
                 "cleanup unregisters exactly two resident probes",
                 "%d found" % len(re.findall(r"unregister_kprobe\(&",
                                             normalized_cleanup)))
    symbol = ctx.elf.symbol("maplesu_probe")
    gate.require(symbol is not None and symbol["shndx"] != 0,
                 "the registration object is a defined symbol")
    if symbol is not None and symbol["shndx"] != 0:
        section = ctx.elf.symbol_section(symbol)
        gate.require(section is not None and section["name"] == ".bss",
                     "the registration object lives in .bss, not .init.text",
                     "section is %r -- .init.text is freed while the hook is live"
                     % (section["name"] if section else None))
        gate.extras["probeObject"] = {"section": section["name"],
                                      "size": "0x%x" % section["size"]}
    gate.extras["cleanup"] = {"order": ["guard", "unregister_kprobe", "clear"],
                              "registrations": registrations,
                              "unregistrations": {"init": unregistrations,
                                                  "cleanup": len(re.findall(
                                                      r"unregister_kprobe\(&",
                                                      normalized_cleanup))}}


def gate_l3_abi(ctx: VerifyContext, gate: Gate) -> None:
    """L3-7: the ABI v2 the controller fixed, and the image facts it is built on.

    Two independent statements per number: the source carries the ABI v2 constant,
    and the -D the compiler saw equals what the image (or the reference TU) says.
    A build that silently fell back to a default, or a source edit that changed the
    option, an op code or an owner bound, fails here. The v1 option (0x4D535503)
    is required to be *gone*: this round replaced it, and a module that still
    carries it would be a module that answers an ABI the controller retired.
    """
    elf = ctx.elf
    source = ctx.source
    facts = ctx.l3_facts
    gate.require(facts is not None, "the L3 image facts were derived")
    if facts is None:
        return
    option = L3_ABI["option"]
    gate.require(option == 4 and L3_ABI["opArm"] == 0 and L3_ABI["opSu"] == 1,
                 "the ABI is v2: option 4 is PR_SET_DUMPABLE and its two accepted "
                 "arg2 values (0 = ARM, 1 = SU) are the ops",
                 "builder table says option %r, opArm %r, opSu %r"
                 % (option, L3_ABI["opArm"], L3_ABI["opSu"]))
    gate.require(L3_ABI["pool"] == 64,
                 "the pool is the ABI's N = 64 creds per load",
                 "builder table says %r" % (L3_ABI["pool"],))
    for name, value in (("OPTION", option), ("OP_ARM", L3_ABI["opArm"]),
                        ("OP_SU", L3_ABI["opSu"]),
                        ("POOL", L3_ABI["pool"]),
                        ("OWNER_MIN", L3_ABI["ownerRange"][0]),
                        ("OWNER_MAX", L3_ABI["ownerRange"][1])):
        gate.require(re.search(r"#define\s+MAPLESU_L3_%s\s+%du" % (name, value),
                               source) is not None,
                     "MAPLESU_L3_%s is %d" % (name, value),
                     "the ABI value is stated in the source, not only in this gate")
    gate.require(re.search(r"0x4D535503", source, re.IGNORECASE) is None,
                 "the retired v1 option 0x4D535503 is gone from the source",
                 "ABI v2 replaced it with option 4, and a source that still names "
                 "the old magic is a source that can answer the old ABI")
    for errno_name in ("EPERM", "EIO", "ENOMEM", "ENOSYS"):
        value = L3_ABI["errno"][errno_name]
        gate.require(re.search(r"#define\s+MAPLESU_%s\s+%d\b" % (errno_name, value),
                               source) is not None,
                     "MAPLESU_%s is the kernel's %d" % (errno_name, value))
    gate.require(L3_ABI["evidenceGateFormat"] in source,
                 "the source prints the ABI's ARM evidence line %r"
                 % L3_ABI["evidenceGateFormat"],
                 "the helper copies exactly this line out of the ring buffer")
    gate.require("MAPLESU_L3_KPROBE_SIZE" in source,
                 "the probe object's size is a named constant, not a literal",
                 "the builder injects the contract's sizeof(struct kprobe)")

    injected = l3_injected_flags(ctx.cflags)
    gate.require(injected.get("credSource") == facts["credSource"]["source"],
                 "-DMAPLESU_CRED_SOURCE is the source the image allows",
                 "flags %r, image %r (%s)"
                 % (injected.get("credSource"), facts["credSource"]["source"],
                    facts["credSource"]["reason"]))
    # The callback's id is injected from the image's own dispatch site, not from
    # the toolchain: the module's load-time self-check compares its shipped prefix
    # with this -D, so a build whose -D came from anywhere else would refuse to
    # register (and the static gates would already be red).
    dispatch_pre = int(facts["kprobe"]["dispatch"]["preHandler"]["typeId"], 16)
    gate.require(injected.get("preKcfiId") == dispatch_pre,
                 "-DMAPLESU_L3_PRE_KCFI_ID is the image's pre-handler dispatch id",
                 "flags %r, image 0x%08x (%s)"
                 % (injected.get("preKcfiId"), dispatch_pre,
                    facts["kprobe"]["dispatch"]["preHandler"]["attributedSites"]))
    gate.require(injected.get("kprobeSize") == facts["callbackSize"],
                 "-DMAPLESU_L3_KPROBE_SIZE is the contract's sizeof(struct kprobe)",
                 "flags %r, contract %r" % (injected.get("kprobeSize"),
                                            facts["callbackSize"]))
    gate.require(0 < facts["callbackSize"] <= 0x100,
                 "the kprobe size is inside the range a struct copy can land in",
                 "0x%x" % facts["callbackSize"])
    literals = l3_rodata_literals(elf)
    if facts["credSource"]["source"] == L3_CRED_INIT_TASK:
        gate.require("init_task" in literals,
                     "the module carries the init_task lookup it was built for",
                     "literals: %s" % sorted(literals))
    else:
        gate.require("init_task" not in literals or "init_task" in (facts.get("policyBridge") or {}).get("lookupSymbols", []),
                     "the module does not carry a lookup it was not built for",
                     "literals: %s" % sorted(literals))
    selinux = facts["selinux"]
    gate.require(selinux.get("enforcingOffset") == 0,
                 "the enforcing byte is the first byte of selinux_state",
                 "contract says %r -- this module reads only that byte"
                 % selinux.get("enforcingOffset"))
    gate.require(struct_selinux_read_only(ctx), "the source reads enforcing "
                 "through the contract's selinux_state shim and never writes it",
                 "the shim is `struct maplesu_selinux_state` from kernel_compat.h, "
                 "the same declaration maplesu_root uses")
    gate.extras["abi"] = {"schema": L3_ABI["schema"],
                          "version": L3_ABI["version"],
                          "option": "0x%08x" % option,
                          "optionName": L3_ABI["optionName"],
                          "opArm": L3_ABI["opArm"], "opSu": L3_ABI["opSu"],
                          "pool": L3_ABI["pool"],
                          "ownerRange": L3_ABI["ownerRange"],
                          "magic": L3_ABI["magic"],
                          "magicMacro": L3_ABI["magicMacro"],
                          "tokenMacro": L3_ABI["tokenMacro"],
                          "returnValue": L3_ABI["returnValue"],
                          "retiredV1Option": "0x4d535503",
                          "credSource": facts["credSource"],
                          "kprobeSize": "0x%x" % facts["callbackSize"],
                          "injected": injected,
                          "lookupNames": sorted(literals & L3_LOOKUP_WHITELIST)}


def struct_selinux_read_only(ctx: VerifyContext) -> bool:
    """The source reads `enforcing` through the shim and never assigns to it."""
    stripped = strip_c_comments(ctx.source)
    if "struct maplesu_selinux_state" not in stripped:
        return False
    if not re.search(r"->enforcing\b", stripped):
        return False
    return not re.search(r"(?:->|\.)enforcing\s*=(?!=)", stripped)


def gate_l3_no_token_printk(ctx: VerifyContext, gate: Gate) -> None:
    """L3-8: the token never reaches printk, the ring buffer or .rodata.

    The token is the only secret in the channel, and dmesg is readable far more
    widely than the two evidence documents. The helper already knows the token it
    sent, so `suToken` is a userspace JSON field, not a kernel format: this gate
    requires that no printk argument mentions it and that the artifact carries
    neither the field name nor the `%016llx` spelling.
    """
    source = ctx.source
    calls = printk_calls(source)
    gate.require(bool(calls), "the module has at least one printk (the evidence line)",
                 "%d call(s)" % len(calls))
    offenders = [call.strip()[:120] for call in calls
                 if re.search(r"token|suToken|%016", call, re.IGNORECASE)]
    gate.require(not offenders, "no printk argument mentions the token",
                 "offending call(s): %s" % offenders)
    gate.require(any(L3_ABI["evidenceGateFormat"] in call for call in calls),
                 "the ARM evidence line is one of those printks and carries "
                 "%r" % L3_ABI["evidenceGateFormat"])
    carried = [needle.decode("utf-8", "replace")
               for needle in (b"suToken", b"%016llx", b"token=")
               if needle in ctx.elf.raw]
    gate.require(not carried,
                 "the artifact carries no token format and no token field name",
                 "found: %s -- the token is printed nowhere, so it cannot be "
                 "copied out of the ring buffer" % carried)
    rodata = b"".join(ctx.elf.section_bytes(section) for section in ctx.elf.sections
                      if section["flags"] & SHF_ALLOC
                      and section["name"].startswith(".rodata"))
    gate.require(b"suToken" not in rodata and b"%016" not in rodata,
                 "the .rodata holds no token spelling",
                 "%d bytes of .rodata scanned" % len(rodata))
    gate.extras["printkCalls"] = len(calls)
    gate.extras["tokenCarriers"] = carried


def gate_l3_registration_name(ctx: VerifyContext, gate: Gate) -> None:
    """L3-9: the kprobe is registered on exactly `__arm64_sys_prctl`.

    The name is the whole interface: the loader resolves it by symbol, so a typo
    is a module that never hooks, and a *different* name would be a module that
    hooks another syscall. Both directions are checked -- the source's constant,
    the artifact's carried string, and the image's own symbol table.
    """
    source = ctx.source
    match = re.search(r'#define\s+MAPLESU_L3_SYMBOL\s+"([^"]+)"', source)
    gate.require(match is not None,
                 "the source defines MAPLESU_L3_SYMBOL as a string",
                 "the registration target is a named constant, not an inline "
                 "literal")
    if match is None:
        return
    gate.require(match.group(1) == L3_PRCTL_SYMBOL,
                 "MAPLESU_L3_SYMBOL is %r" % L3_PRCTL_SYMBOL,
                 "source says %r" % match.group(1))
    stripped = strip_c_comments(source)
    assignments = re.findall(r"symbol_name\s*=\s*([A-Za-z_0-9\"][^;\n]*)", stripped)
    gate.require(sorted(assignments) == ['"avc_denied"', '"kallsyms_lookup_name"', "MAPLESU_L3_SYMBOL"],
                 "the source registers exactly resolver, avc_denied and credential gate",
                 "%d found: %s" % (len(assignments), assignments))
    gate.require(any("MAPLESU_L3_SYMBOL" in value for value in assignments),
                 "the gate's registration names MAPLESU_L3_SYMBOL",
                 "assignments: %s" % assignments)
    literals = l3_rodata_literals(ctx.elf)
    gate.require(L3_PRCTL_SYMBOL in literals,
                 "the artifact carries %r in .rodata" % L3_PRCTL_SYMBOL,
                 "literals: %s" % sorted(literals))
    address = ctx.symbols.address(L3_PRCTL_SYMBOL)
    kind = ctx.symbols.kind(L3_PRCTL_SYMBOL)
    gate.require(address is not None,
                 "this image has a symbol named %r" % L3_PRCTL_SYMBOL,
                 "a name that does not resolve is a module that cannot register")
    gate.require((kind or "") == "TEXT",
                 "%r is a TEXT symbol in this image" % L3_PRCTL_SYMBOL,
                 "kind is %r (a data symbol would not be probeable)" % kind)
    gate.extras["registration"] = {"symbol": L3_PRCTL_SYMBOL,
                                   "imageAddress": "0x%x" % (address or 0),
                                   "imageKind": kind,
                                   "assignments": assignments}


def gate_l3_arm_gate(ctx: VerifyContext, gate: Gate) -> None:
    """L3-10: the ARM op is gated exactly as the ABI says.

    ABI v2's ARM is `prctl(4, 0, token_lo, token_hi, owner_uid)`. Five conditions,
    in the source, inside the callback, in the order that matters: the permissive
    window (`enforcing == 0`), uid 0, an app uid as owner, a non-zero token, and
    one CAS. And the complementary statement: this module never writes the
    enforcing byte -- reading it is the gate, writing it is maplesu_root's and the
    helper's job.
    """
    source = ctx.source
    handler = function_body(source, "maplesu_l3_pre")
    gate.require(bool(handler), "the callback has a definition in the source")
    if not handler:
        return
    normalized = " ".join(handler.split())
    gate.require("maplesu_selinux->enforcing != 0" in normalized,
                 "ARM requires the permissive window (enforcing == 0)",
                 "the deny is taken when the byte is not 0; the byte is read, never "
                 "written")
    gate.require("maplesu_caller_uid(frame)" in normalized and "uid != 0" in normalized,
                 "ARM requires the caller to be uid 0",
                 "the uid comes from the kernel's own __arm64_sys_getuid, so no "
                 "struct offset can be wrong")
    gate.require("owner < MAPLESU_L3_OWNER_MIN" in normalized
                 and "owner >= MAPLESU_L3_OWNER_MAX" in normalized,
                 "ARM requires owner_uid in [%d, %d)"
                 % (L3_ABI["ownerRange"][0], L3_ABI["ownerRange"][1]),
                 "an app uid, so SU can only mint root for the app that armed")
    gate.require("token == 0" in normalized,
                 "ARM refuses a zero token",
                 "a zero token would make SU's token check vacuous")
    gate.require("__sync_bool_compare_and_swap(&maplesu_armed, 0u, 1u)" in normalized,
                 "ARM claims the gate with one compare-and-swap 0 -> 1",
                 "exactly one ARM per load; a second one is denied, not applied")
    enforcing_at = normalized.find("maplesu_selinux->enforcing != 0")
    cas_at = normalized.find("__sync_bool_compare_and_swap")
    publish_at = normalized.find("maplesu_owner_uid = owner")
    gate.require(0 <= enforcing_at < cas_at < publish_at,
                 "the window is checked before the claim, and the pair is "
                 "published after it",
                 "offsets enforcing=%d cas=%d publish=%d (a pair published before "
                 "the claim could be overwritten by a losing ARM)"
                 % (enforcing_at, cas_at, publish_at))
    stripped = strip_c_comments(source)
    writes = re.findall(r"(?:->|\.)enforcing\s*=([^=;]*)", stripped)
    gate.require(not writes, "the source never assigns to selinux enforcing",
                 "found assignments: %s" % writes)
    gate.require("__atomic_store_n(&maplesu_ready, 1u, __ATOMIC_RELEASE)"
                 in normalized,
                 "the armed pair is published with a release store",
                 "SU reads it with an acquire load, so it cannot see a half-written "
                 "pair")
    gate.extras["armGate"] = {"enforcingCheck": "selinux_state->enforcing == 0",
                              "uidCheck": "uid == 0",
                              "ownerRange": L3_ABI["ownerRange"],
                              "tokenCheck": "token != 0",
                              "claim": "__sync_bool_compare_and_swap(0 -> 1)",
                              "writesEnforcing": False}


def l3_macro(collapsed: str, name: str):
    """(parameters, body) of `#define name(...)` in whitespace-collapsed source.

    `collapsed` is comment-free and newline-free, so a macro written across a
    backslash continuation reads as one line. The body stops at the next `#`
    (a macro body in this module contains no preprocessor token), and the
    trailing whitespace is stripped so a reformat that only moves whitespace
    cannot change what the gate compares.
    """
    match = re.search(r"#define\s+%s\s*\(([^)]*)\)\s*\\?\s*([^#]*)" % re.escape(name),
                      collapsed)
    if match is None:
        return None, None
    return match.group(1), match.group(2).strip()


def gate_l3_no_frame_write(ctx: VerifyContext, gate: Gate) -> None:
    """L3-11: the callback observes; it never writes the frame, never answers.

    ABI v2 does not need a rewritten return value: `prctl(PR_SET_DUMPABLE, 0|1)`
    returns 0 natively, so a granted ARM/SU and a denied one answer the same. The
    constraint is therefore absolute rather than stylistic -- a kprobe pre-handler
    must not store into any `regs`/`pt_regs` field. The probed frame is the task's
    live register file *and* the syscall frame whose word 0 is the return slot; a
    store there would change the syscall's arguments, its return value, or both,
    and a pre-handler cannot undo it.

    Four source-level, comment-free statements:
      * both frame pointers are declared `const` (a store would not compile), and
        no non-const cast of that layout exists anywhere in the module -- nor any
        other pointer cast applied straight to a frame pointer, which is the
        cast-away route to the same store;
      * no assignment operator reaches a frame field, and no memcpy/memset names
        one (that is "no write-back to any regs/pt_regs field");
      * every `return` in the callback is 0 ("carry on") or a denial whose own
        body returns 0 -- there is no path that hands the syscall a value;
      * the magic is `(arg3 | arg4) != 0` and is decided before the op dispatch,
        so a native PR_SET_DUMPABLE never enters the gate at all.
    """
    source = ctx.source
    handler = function_body(source, "maplesu_l3_pre")
    gate.require(bool(handler), "the callback has a definition in the source")
    if not handler:
        return
    normalized = " ".join(handler.split())
    stripped_handler = strip_c_comments(handler)
    collapsed = " ".join(strip_c_comments(source).split())

    # 1. The magic, as the ABI states it: the option test plus (arg3 | arg4) != 0.
    magic_name = L3_ABI["magicMacro"]
    token_name = L3_ABI["tokenMacro"]
    params, body = l3_macro(collapsed, magic_name)
    gate.require(params is not None, "%s is a macro definition" % magic_name,
                 "the magic is one statement the gate can read, not a comment")
    if params is not None:
        names = [part.strip() for part in params.split(",")]
        gate.require(names == ["arg3", "arg4"],
                     "%s names the ABI's two token arguments (arg3, arg4)"
                     % magic_name, "parameters are %r" % (names,))
        gate.require("|" in body and "!= 0u" in body,
                     "%s is `(arg3 | arg4) != 0`" % magic_name,
                     "%s body is %r" % (magic_name, body))
        gate.require("(u32)(arg3)" in body and "(u32)(arg4)" in body,
                     "%s tests the ABI's 32-bit words, not a wider token"
                     % magic_name,
                     "a token whose halves are non-zero is magic; the comparison "
                     "must be the ABI's (arg3 | arg4), body is %r" % body)
    gate.require("%s(argp->regs[2], argp->regs[3])" % magic_name in normalized,
                 "the callback applies %s to arg3/arg4 (the frame's regs[2], "
                 "regs[3])" % magic_name,
                 "a magic check on anything but the ABI's two arguments is a "
                 "different ABI")
    magic_at = normalized.find("%s(argp->regs[2]" % magic_name)
    op_at = normalized.find("op = (u32)argp->regs[1]")
    gate.require(0 <= magic_at < op_at,
                 "the magic is decided before the op is read or dispatched",
                 "offsets magic=%d op=%d" % (magic_at, op_at))

    params, body = l3_macro(collapsed, token_name)
    gate.require(params is not None, "%s is a macro definition" % token_name,
                 "the token is (arg4 << 32) | arg3, stated once")
    if params is not None:
        gate.require("<< 32" in body and "0xffffffffu" in body,
                     "%s is (arg4 << 32) | arg3 with arg3 masked to 32 bits"
                     % token_name, "%s body is %r" % (token_name, body))
    gate.require("%s(argp->regs[2], argp->regs[3])" % token_name in normalized,
                 "the callback forms the token from the frame's arg3/arg4",
                 "the token must be %s(argp->regs[2], argp->regs[3])"
                 % token_name)

    # 2. Both frame pointers are const, and no non-const view exists.
    gate.require(re.search(r"frame\s*=\s*\(const struct maplesu_pt_regs \*\)",
                           stripped_handler) is not None,
                 "the task's register file is read through a const pointer",
                 "`const` is what makes a write a compile error, not a code review")
    gate.require(re.search(r"argp\s*=\s*\(const struct maplesu_pt_regs \*\)",
                           stripped_handler) is not None,
                 "the syscall frame is read through a const pointer",
                 "`const` is what makes a write a compile error, not a code review")
    non_const = re.findall(r"\(\s*struct maplesu_pt_regs\s*\*\)",
                           strip_c_comments(source.replace(
                               function_body(source, "maplesu_avc_pre"), "")))
    gate.require(not non_const,
                 "no non-const cast of the pt_regs layout exists in the module",
                 "found: %s -- a single non-const cast would put the frame's "
                 "arguments and return slot one store away" % non_const)

    # 3. No store reaches a frame field, by any spelling. The one pointer cast
    #    the handler needs is the const-qualifying `(const void *)regs`; any
    #    other cast applied straight to a frame pointer is the cast-away route to
    #    a store, and is refused here so the two checks below mean something.
    patterns = (
        r"(?:->|\.)\s*regs\s*\[[^\]]*\]\s*(?:[-+*/%&|^]|<<|>>|\+\+|--)?=(?!=)",
        r"\b(?:regs|frame|argp)\s*->\s*[A-Za-z_]\w*\s*"
        r"(?:[-+*/%&|^]|<<|>>|\+\+|--)?=(?!=)",
        r"\b(?:memcpy|memmove|memset|copy_to_user|copy_from_user|strncpy)\s*\(",
    )
    offenders = []
    for pattern in patterns:
        offenders.extend(match.group(0) for match in
                         re.finditer(pattern, stripped_handler))
    gate.require(not offenders,
                 "no store reaches a regs/pt_regs field and no mem* names one",
                 "found: %s -- the frame is observed, never written" % offenders)
    casts = re.findall(r"\(\s*[^()]*\*+\s*\)\s*(?:regs|frame|argp)\b",
                       stripped_handler)
    gate.require(casts == ["(const void *)regs"],
                 "the only pointer cast applied to a frame pointer is the "
                 "const-qualifying (const void *)regs",
                 "casts applied to the frame: %s -- stripping const is how a "
                 "store gets past the compiler" % casts)

    # 4. Every return is 0 (or a deny that returns 0): no return value rewrite.
    returns = [" ".join(match.group(1).split()) for match in
               re.finditer(r"\breturn\b\s*([^;]*);", stripped_handler)]
    gate.require(bool(returns), "the callback returns explicitly")
    bad = [value for value in returns
           if value != "0" and not value.startswith("maplesu_deny(")]
    gate.require(not bad, "every return is 0 (continue the probed function) or a "
                 "denial", "returns that are not: %s" % bad)
    deny = function_body(source, "maplesu_deny")
    gate.require(bool(deny), "the deny helper has a definition in the source")
    deny_returns = []
    if deny:
        deny_returns = [" ".join(match.group(1).split()) for match in
                        re.finditer(r"\breturn\b\s*([^;]*);",
                                    strip_c_comments(deny))]
        gate.require(deny_returns == ["0"],
                     "the deny helper returns 0 as well", "returns %s"
                     % deny_returns)

    gate.extras["frameSafety"] = {
        "magic": L3_ABI["magic"],
        "magicMacro": magic_name,
        "tokenMacro": token_name,
        "framePointers": "const",
        "nonConstCasts": non_const,
        "frameCasts": casts,
        "stores": 0,
        "returns": sorted(set(returns)),
        "denyReturns": deny_returns,
        "returnValue": L3_ABI["returnValue"],
    }


def gate_l3_root_subject(ctx: VerifyContext, gate: Gate) -> None:
    facts = ctx.l3_facts["rootSubject"]
    defines = {flag[2:].split("=", 1)[0]: int(flag.split("=", 1)[1], 0)
               for flag in ctx.cflags if flag.startswith("-DMAPLESU_") and "=" in flag}
    for name, value in facts["defines"].items():
        gate.require(defines.get(name) == value,
                     name + " equals measured image ABI")
    symbol = ctx.elf.symbol("maplesu_avc_pre")
    gate.require(bool(symbol), "root-subject callback exists")
    if symbol and getattr(ctx, "legacy_cfi", False):
        gate.require(any(row["symbol"] == "maplesu_avc_pre" and
                         row["acceptsCorrectType"] for row in ctx.cfi_checks),
                     "root-subject callback passes the image CFI dispatch type")
    elif symbol:
        section = ctx.elf.sections[symbol["shndx"]]
        prefix = struct.unpack_from("<I", ctx.elf.section_bytes(section), symbol["value"] - 4)[0]
        expected = int(ctx.l3_facts["callbackDispatch"]["preHandler"]["typeId"], 16)
        gate.require(prefix == expected, "root-subject callback prefix equals image dispatch")
        gate.require(not section["name"].startswith(".init"), "root-subject callback is resident")
    subject = strip_c_comments(function_body(ctx.source, "maplesu_root_subject"))
    for name in ("maplesu_ready", "maplesu_groups", "MAPLESU_CRED_UID_OFF",
                 "MAPLESU_CRED_EUID_OFF", "MAPLESU_CRED_GROUPS_OFF"):
        gate.require(name in subject, "subject requires " + name)
    gate.require("== maplesu_groups" in subject, "subject uses exact private object identity")
    normalized_subject = " ".join(subject.split())
    gate.require("!__atomic_load_n(&maplesu_ready, __ATOMIC_ACQUIRE)" in normalized_subject,
                 "unarmed subjects keep policy denial")
    gate.require("MAPLESU_CRED_UID_OFF) == 0 &&" in subject and
                 "MAPLESU_CRED_EUID_OFF) == 0 &&" in subject,
                 "both real and effective UID must remain zero")
    callback = function_body(ctx.source, "maplesu_avc_pre")
    gate.require("!maplesu_root_subject(maplesu_current_task())" in callback,
                 "only a marked current root subject changes denial")
    gate.require("result->regs[0] = 0" in callback and
                 "result->pc = result->regs[30]" in callback,
                 "entry return writes only x0 and pc")
    gate.require(not nocfi_covers(ctx.source, "maplesu_avc_pre"), "callback keeps KCFI")
    init = function_body(ctx.source, "init_module")
    gate.require("maplesu_set_groups((void *)cred, (void *)maplesu_groups)" in init,
                 "each pooled root cred owns the marker reference")
    gate.require(init.index("register_kprobe(&maplesu_avc_probe)") <
                 init.index("register_kprobe(&maplesu_probe)"),
                 "data path is registered before credential gate")
    cleanup = function_body(ctx.source, "cleanup_module")
    gate.require("unregister_kprobe(&maplesu_avc_probe)" in cleanup,
                 "cleanup removes the data-path callback")
    checkout = function_body(ctx.source, "maplesu_pool_take")
    gate.require("__atomic_add_fetch" in checkout and "MAPLESU_CRED_USAGE_OFF" in checkout,
                 "each checkout gets a new credential reference")
    gate.require("count - 1" not in checkout and "__atomic_compare_exchange_n" not in checkout,
                 "successful requests preserve pool capacity")
    gate.extras["rootSubject"] = facts


GATE_FUNCTIONS = {
    "G1": gate_layout, "G2": gate_plt, "G3": gate_imports, "G4": gate_cleanup,
    "G5": gate_patch_plan, "G6": gate_kcfi, "G7": gate_struct_shim,
    # L2 (design rev-3 6.1). G8-G14 are fatal on `--module maplesu_l2`; the root
    # module never runs them (its gate set is G1-G7 and its artifact must not
    # change).
    "G8": gate_callback_abi, "G9": gate_hook_lifecycle,
    "G10": gate_atomic_safety, "G11": gate_control_channel,
    "G12": gate_no_residual_arm, "G13": gate_zero_check_sites,
    "G14": gate_write_scope,
    # L3 (ABI v2). The root module and L2 never run these: their gate sets are
    # G1-G7 and G1-G14, and their artifacts must not change.
    "L3-1": gate_l3_handler_prefix, "L3-2": gate_l3_imports,
    "L3-3": gate_l3_no_data_literal, "L3-4": gate_l3_handler_text,
    "L3-5": gate_l3_zero_traps, "L3-6": gate_l3_cleanup_restore,
    "L3-7": gate_l3_abi, "L3-8": gate_l3_no_token_printk,
    "L3-9": gate_l3_registration_name, "L3-10": gate_l3_arm_gate,
    "L3-11": gate_l3_no_frame_write,
    "L3-12": gate_l3_root_subject,
}


def run_internal_gates(ctx: VerifyContext) -> dict:
    gates = []
    for gate_id, name in ctx.gates:
        gate = Gate(gate_id, name)
        try:
            GATE_FUNCTIONS[gate_id](ctx, gate)
        except _CheckFailed:
            pass
        except GateFailure as error:
            gate.check(False, "gate could not be evaluated", str(error))
        gates.append(gate.result())
    ok = all(gate["ok"] for gate in gates)
    # The import set is computed independently of the gate that judges it, so a bug in
    # G3 cannot make a wrong set look right.
    imports = ctx.elf.imports()
    return {"ok": ok, "engine": "build_root_module internal", "gates": gates,
            "imports": imports,
            "result": "all gates pass" if ok else "gate failure: " + ", ".join(
                gate["id"] for gate in gates if not gate["ok"])}


# --------------------------------------------------------------------------- #
# The shared replica, when it has grown run_gates().
# --------------------------------------------------------------------------- #

def load_gate_runner():
    if not LOADER_CONTRACT.is_file():
        return None, "loader_contract.py not found at %s" % rel(LOADER_CONTRACT)
    try:
        spec = importlib.util.spec_from_file_location("maplesu_loader_contract",
                                                      LOADER_CONTRACT)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
    except Exception as error:                     # noqa: BLE001 - reported, not hidden
        return None, "importing loader_contract.py failed: %r" % error
    runner = getattr(module, "run_gates", None)
    if runner is None:
        return None, ("loader_contract.py has no run_gates() yet (7-gate interface is "
                      "being implemented in parallel)")
    return runner, ""


def run_external_gates(runner, ctx: VerifyContext) -> dict:
    result = runner(ctx.module, ctx.image_path, ctx.contract_path, root=True,
                    header_path=ctx.header, build_flags=ctx.cflags)
    if not isinstance(result, dict):
        raise GateFailure("run_gates() returned %r, not a dict" % type(result))
    for key in ("ok", "gates", "imports", "result"):
        if key not in result:
            raise GateFailure("run_gates() result has no %r key: keys=%s"
                              % (key, sorted(result)))
    return result


# --------------------------------------------------------------------------- #
# Report.
# --------------------------------------------------------------------------- #

def gate_line(gate: dict) -> str:
    detail = gate["detail"]
    if len(detail) > 150:
        detail = detail[:147] + "..."
    return "[%s] %-18s %s  %s" % (gate["id"], gate["name"],
                                  "PASS" if gate["ok"] else "FAIL", detail)


def verify(ctx: VerifyContext, log: Log, gate_engine: str) -> dict:
    internal = run_internal_gates(ctx)
    for gate in internal["gates"]:
        log(gate_line(gate))
        for entry in gate["checks"]:
            mark = ("ok  " if entry["ok"]
                    else ("FAIL" if entry["fatal"] else "note"))
            log("       %s %-62s %s" % (mark, entry["what"], entry["detail"]),
                detail=True)
    log("       imports: {%s}" % ", ".join(internal["imports"]))

    expected = ctx.expected_imports()
    imports_ok = set(internal["imports"]) == expected
    log("       %s import set equals {%s}"
        % ("ok  " if imports_ok else "FAIL", ", ".join(sorted(expected))))

    engines = {"internal": internal}
    external_error = ""
    runner, why = (None, "disabled by --gate-engine internal")
    if gate_engine != "internal":
        runner, why = load_gate_runner()
    if runner is None and gate_engine == "loader-contract":
        raise GateFailure("--gate-engine loader-contract was requested but the shared "
                          "replica cannot run: %s" % why)
    if runner is not None:
        try:
            external = run_external_gates(runner, ctx)
            engines["loader_contract"] = external
            log("       loader_contract.run_gates(root=True): %s (%s)"
                % ("PASS" if external["ok"] else "FAIL", external.get("result")))
            disagreements = []
            external_gates = {str(entry.get("id")): entry.get("ok")
                              for entry in external["gates"]}
            for gate in internal["gates"]:
                if gate["id"] in external_gates and \
                        bool(external_gates[gate["id"]]) != gate["ok"]:
                    disagreements.append("%s: module=%s replica=%s"
                                         % (gate["id"], gate["ok"],
                                            external_gates[gate["id"]]))
            if sorted(external.get("imports") or []) != internal["imports"]:
                disagreements.append("imports: module=%s replica=%s"
                                     % (internal["imports"], external.get("imports")))
            if disagreements:
                log("       FAIL the two gate engines disagree: %s"
                    % "; ".join(disagreements))
        except Exception:                          # noqa: BLE001 - fail closed, loudly
            external_error = traceback.format_exc()
            log("       FAIL loader_contract.run_gates() raised (gate engine error, "
                "not necessarily a module defect):")
            for line in external_error.strip().splitlines():
                log("         " + line)
    else:
        log("       gate engine: internal only -- %s" % why)

    ok = internal["ok"] and imports_ok
    if external_error:
        ok = False
    if "loader_contract" in engines:
        ok = ok and bool(engines["loader_contract"]["ok"])
        ok = ok and not (engines["loader_contract"]["ok"] != internal["ok"])
    return {"ok": ok, "internal": internal, "engines": engines,
            "externalError": external_error}


def _l2_build_record(outcome: dict, context: VerifyContext, contract: dict,
                     contract_path: Path, image_path: Path, module: Path,
                     cflags: list | None, built: dict | None,
                     clang: Path | None, ld: Path | None,
                     ndk_dir: str) -> dict:
    """build.json for maplesu_l2: the same shape as the root module's record."""
    all_relocs = context.elf.all_relocations()
    counts: dict = {}
    for relocs in all_relocs.values():
        for entry in relocs:
            counts[entry["type"]] = counts.get(entry["type"], 0) + 1
    gate_extras = {gate["id"]: gate.get("extras", {})
                   for gate in outcome["internal"]["gates"]}
    report = {
        "module": context.module_name,
        "modulePath": rel(module),
        "sha256": sha256_file(module),
        "bytes": module.stat().st_size,
        "kernelRelease": contract["kernelRelease"],
        "contract": {"path": rel(contract_path), "sha256": sha256_file(contract_path)},
        "image": {"path": rel(image_path), "sha256": sha256_file(image_path),
                  "bytes": image_path.stat().st_size},
        "tools": {
            "clang": tool_version(clang) if clang else "not used (--verify-only)",
            "ldLld": tool_version(ld) if ld else "not used (--verify-only)",
            "python": platform.python_version(),
            "platform": platform.platform(),
            "ndk": rel(Path(ndk_dir)),
        },
        "sources": {
            key: {"path": rel(path), "sha256": sha256_file(path),
                  "bytes": path.stat().st_size}
            for key, path in (("l2ModuleSource", L2_SOURCE),
                              ("kernelCompatHeader", MODULE_DIR / "kernel_compat.h"),
                              ("glueTemplate", L2_GLUE_TEMPLATE),
                              ("buildScript", Path(__file__)))
        },
        "sections": context.elf.section_table(),
        "undefinedSymbols": context.elf.imports(),
        "definedSymbols": sorted({symbol["name"]
                                  for symbol in context.elf.defined_symbols()}),
        "relocations": {
            "total": sum(counts.values()),
            "byType": {str(value): counts[value] for value in sorted(counts)},
            "byTypeName": {ARM64_MODULE_RELOCS[value]: counts[value]
                           for value in sorted(counts)},
            "bySection": {name: len(relocs)
                          for name, relocs in sorted(all_relocs.items())},
        },
        "facts": {
            "imports": context.elf.imports(),
            "importSetFull": sorted(L2_EXPECTED_IMPORTS),
            "cookieParam": L2_COOKIE_PARAM,
            "vermagic": contract["vermagic"],
            "thisModule": context.this_module_layout(),
            "kcfi": context.image_facts,
            "kcfiPins": gate_extras.get("G8", {}).get("prefix_l2_pre_ctl"),
            "kcfiImage": gate_extras.get("G8", {}).get("imageKcfi"),
            "kcfiShims": gate_extras.get("G8", {}).get("shimIds"),
            "kcfiTraps": gate_extras.get("G13", {}).get("traps"),
            "kcfiSites": gate_extras.get("G13", {}).get("kcfiSites"),
            "buildFlags": cflags or [],
            "jumpTableFlag": "-fno-jump-tables" in (cflags or []),
            "callbackClosure": gate_extras.get("G10", {}).get("callbackClosure"),
            "writeCensus": gate_extras.get("G14", {}).get("writeCensus"),
            "lookupNames": gate_extras.get("G5", {}).get("lookupNames"),
            "btf": gate_extras.get("G7", {}).get("btfFacts"),
        },
        "gates": outcome["internal"]["gates"],
        "gateResult": {
            "ok": outcome["ok"],
            "engines": {name: {"ok": result["ok"], "result": result.get("result")}
                        for name, result in outcome["engines"].items()},
            "externalError": outcome["externalError"],
        },
        "verifiedAt": datetime.datetime.now(datetime.timezone.utc).isoformat(
            timespec="seconds"),
    }
    if built:
        report["sources"]["glueRendered"] = {
            "path": rel(built["glue"]), "sha256": built["glue_sha256"]}
        report["commands"] = built["commands"]
        report["compileFlags"] = built["cflags"]
    report["cflags"] = report.get("compileFlags") or (cflags or [])
    return report


def run_l2(args) -> int:
    """`--module maplesu_l2`: build, gate and publish the L2 module.

    Fail-closed in two ways the root path does not need:
      * the artifact is produced in a staging directory and only copied to
        <out-dir>/maplesu_l2.ko after every gate passes, so a red gate never
        leaves a loadable-looking .ko behind;
      * the gate set is G1-G14 and every gate is fatal; there is no
        partial-credit path and no external engine (the shared replica's
        run_gates() is the root module's 7-gate contract).
    """
    log = Log(echo=not args.quiet, verbose=args.verbose)
    out_dir = Path(args.out_dir)
    contract_path = resolve_contract(args.contract)
    staging = out_dir / "l2-staging"
    status = 1
    built = None
    clang = ld = None
    try:
        contract = load_contract(contract_path)
        l2 = contract.get("l2") or {}
        if not l2:
            raise BuildError("contract %s carries no l2 block; C1 needs the C0 "
                             "contract (l2.module, l2.callbacks, l2.kcfiIds, "
                             "structOffsets.kretprobe/ptRegs/kernelParam)"
                             % rel(contract_path))
        image_path = Path(args.image) if args.image else PROJECT_ROOT / contract["imagePath"]

        log("%s -- second, window-scoped L2 module (design 2026-10-29 rev-3)"
            % L2_MODULE)
        log("  project    %s" % rel(PROJECT_ROOT))
        log("  contract   %s (sha256 %s)"
            % (rel(contract_path), sha256_file(contract_path)[:16] + "..."))
        log("  kernel     %s" % contract["kernelRelease"])
        log("  image      %s (%d bytes, sha256 %s)"
            % (rel(image_path), image_path.stat().st_size,
               sha256_file(image_path)[:16] + "..."))
        actual_image_sha = sha256_file(image_path)
        if actual_image_sha != contract["imageSha256"]:
            raise BuildError(
                "image sha256 is %s but the contract was derived from %s"
                % (actual_image_sha, contract["imageSha256"]))

        module_name = l2["module"]["name"]
        module = out_dir / (module_name + ".ko")
        if args.verify_only:
            if not module.is_file():
                # The red-first wording the C0 scaffolding established: the
                # failure is the artifact, not an unknown flag.
                print("RESULT: FAILED -- artifact missing: %s (build it with "
                      "--module maplesu_l2 and no --verify-only)" % rel(module))
                return 1
            clang = find_tool(Path(args.ndk_dir), "clang")
            ld = find_tool(Path(args.ndk_dir), "ld.lld")
            cflags = recorded_cflags(out_dir)
            log("verify-only: no compile, no link")
            log("  artifact   %s" % rel(module))
        else:
            if staging.exists():
                shutil.rmtree(staging)
            built = build(Path(args.ndk_dir), staging, contract, log,
                          args.extra_flag, module=L2_MODULE)
            module = built["module"]
            cflags = built["cflags"]
            clang, ld = built["clang"], built["ld"]
            log("  tools      %s" % tool_version(clang))
            log("             %s" % tool_version(ld))
            log("             python %s (%s)"
                % (platform.python_version(), platform.platform()))

        log("")
        log("module     %s (%d bytes, sha256 %s)"
            % (rel(module), module.stat().st_size, sha256_file(module)))
        context = VerifyContext(module, contract, contract_path, image_path, log,
                                cflags=cflags, module_key=L2_MODULE)
        log("")
        log("sections (%d)" % len(context.elf.sections), detail=True)
        for row in context.elf.section_table():
            flags = "".join(letter for letter, present in
                            (("A", row["alloc"]), ("W", row["write"]),
                             ("X", row["exec"])) if present) or "-"
            log("  [%2d] %-32s %-9s %-6s off=0x%06x size=0x%06x align=%d"
                % (row["index"], row["name"], row["type"], flags,
                   row["offset"], row["size"], row["align"]), detail=True)
        imports = context.elf.imports()
        log("undefined symbols (%d): %s" % (len(imports), ", ".join(imports)))

        log("")
        log("gates")
        outcome = verify(context, log, "internal")
        log("")
        log("imports: %s" % ", ".join(outcome["internal"]["imports"]))
        log("RESULT: %s" % ("all gates pass" if outcome["ok"] else
                            "FAILED -- " + outcome["internal"]["result"]))
        status = 0 if outcome["ok"] else 1

        report = _l2_build_record(outcome, context, contract, contract_path,
                                  image_path, module, cflags, built, clang, ld,
                                  args.ndk_dir)
        if status == 0 and built:
            # Publish only after every gate passed. The staging directory keeps
            # the whole build record (objects, rendered glue) for audit.
            out_dir.mkdir(parents=True, exist_ok=True)
            published = []
            for path in [built["module"], *built["objects"], built["glue"]]:
                target = out_dir / path.name
                shutil.copy2(path, target)
                published.append(rel(target))
            log("")
            log("published (all gates green): %s" % ", ".join(published))
        elif status != 0 and built:
            log("")
            log("NOT published: the gates are red, so %s was not written; the "
                "attempt's artifacts stay in %s" % (rel(out_dir / (module_name + ".ko")),
                                                    rel(staging)))
        (out_dir / "build.json").write_text(json.dumps(report, indent=2) + "\n",
                                           encoding="utf-8")
        log("")
        log("wrote %s" % rel(out_dir / "build.json"))
        return status
    except (BuildError, GateFailure) as error:
        log("")
        log("ERROR: %s" % error)
        log("RESULT: FAILED -- %s" % error)
        return 1
    finally:
        if args.transcript:
            log.save(Path(args.transcript))
        elif out_dir.is_dir():
            log.save(out_dir / "verify-build.txt")
        if not args.quiet:
            print("transcript: %s"
                  % rel(Path(args.transcript) if args.transcript
                        else out_dir / "verify-build.txt"))


# --------------------------------------------------------------------------- #
# L3: the second engine, its own record, and the run.
# --------------------------------------------------------------------------- #

# What the shared replica cannot say about maplesu_l3, and which L3 gate says the
# same thing instead. The replica's own docstring declares G3/G5/G7 as gates that
# encode the resident-root design and marks them skipped under root=False; this
# table is the mapping that keeps that skip from being a silent gap.
L3_EXTERNAL_COVERAGE = {
    "G3": "internal G3 (ksymtab row and export range per import) + L3-2 "
          "(the set is exactly three names)",
    "G5": "internal G5 with the L3 plan body (no kernel VA, no GOT, no write "
          "primitive, whitelisted lookups, the kprobe registration target)",
    "G7": "internal G7 (kernel_compat.h asserts and the glue's struct-module "
          "offsets against this image's BTF)",
}


def run_external_gates_l3(runner, ctx: VerifyContext, out_dir: Path) -> dict:
    """Run the shared G1-G7 replica against maplesu_l3 and record what it cannot say.

    Two things make a straight call wrong for this artifact, and both are recorded
    rather than worked around silently:

      * the replica's G1 compares this_module.name with `contract["module"]`, which
        is "maplesu_root". The L3 run therefore passes a *contract view*: the
        contract with exactly one field changed -- the artifact-name field -- and
        nothing else. The view is written beside the build and digested, and the
        difference is asserted to be exactly `module`, so the substitution is
        auditable instead of invisible;
      * the replica's own `root=False` semantics declare G3/G5/G7 to be the gates
        that encode the resident-root design, and mark them skipped. The skip
        reasons are copied into the result verbatim and mapped to the L3 gate that
        covers the same property. This builder skips nothing itself.

    Everything the replica does run must be green, or the L3 build fails.
    """
    contract = json.loads(ctx.contract_path.read_text(encoding="utf-8"))
    view = dict(contract)
    view["module"] = L3_MODULE
    changed = sorted(key for key in set(contract) | set(view)
                     if contract.get(key) != view.get(key))
    if changed != ["module"]:
        raise GateFailure(
            "the L3 contract view was meant to change exactly one field, but it "
            "changes %s" % changed)
    view_path = Path(out_dir) / "l3-contract-view.json"
    view_path.parent.mkdir(parents=True, exist_ok=True)
    view_path.write_text(json.dumps(view, indent=1) + "\n", encoding="utf-8")

    result = runner(ctx.module, ctx.image_path, view_path, root=False,
                    header_path=ctx.header, build_flags=ctx.cflags)
    if not isinstance(result, dict):
        raise GateFailure("run_gates() returned %r, not a dict" % type(result))
    for key in ("ok", "gates", "imports", "result"):
        if key not in result:
            raise GateFailure("run_gates() result has no %r key: keys=%s"
                              % (key, sorted(result)))

    skipped = {str(gate.get("id")): gate.get("detail", "")
               for gate in result["gates"] if gate.get("skipped")}
    if set(skipped) != set(L3_EXTERNAL_COVERAGE):
        raise GateFailure(
            "the replica skipped %s under root=False, but this build only knows "
            "how to account for %s; the coverage table has to move with it"
            % (sorted(skipped), sorted(L3_EXTERNAL_COVERAGE)))
    return {"result": result,
            "skipped": skipped,
            "coverage": dict(L3_EXTERNAL_COVERAGE),
            "contractView": {
                "path": rel(view_path),
                "sha256": sha256_file(view_path),
                "contractSha256": sha256_file(ctx.contract_path),
                "changedFields": changed,
                "reason": "the replica's G1 asserts this_module.name == "
                          "contract['module']; the L3 artifact's name is "
                          "maplesu_l3, and no other field differs",
            }}


def verify_l3(ctx: VerifyContext, log: Log, gate_engine: str,
              out_dir: Path) -> dict:
    """The L3 equivalent of verify(): internal gates, then the shared replica.

    The difference from verify() is the external call (an L3 contract view and the
    replica's declared skip set) and the agreement rule: only gates both engines
    actually evaluated are compared, and the ones the replica skipped are named
    with the L3 gate that covers them.
    """
    internal = run_internal_gates(ctx)
    for gate in internal["gates"]:
        log(gate_line(gate))
        for entry in gate["checks"]:
            mark = ("ok  " if entry["ok"]
                    else ("FAIL" if entry["fatal"] else "note"))
            log("       %s %-62s %s" % (mark, entry["what"], entry["detail"]),
                detail=True)
    log("       imports: {%s}" % ", ".join(internal["imports"]))

    expected = ctx.expected_imports()
    imports_ok = set(internal["imports"]) == expected
    log("       %s import set equals {%s}"
        % ("ok  " if imports_ok else "FAIL", ", ".join(sorted(expected))))

    engines = {"internal": internal}
    external_error = ""
    external_record = None
    runner, why = (None, "disabled by --gate-engine internal")
    if gate_engine != "internal":
        runner, why = load_gate_runner()
    if runner is None and gate_engine == "loader-contract":
        raise GateFailure("--gate-engine loader-contract was requested but the "
                          "shared replica cannot run: %s" % why)
    if runner is not None:
        try:
            external_record = run_external_gates_l3(runner, ctx, out_dir)
            external = external_record["result"]
            engines["loader_contract"] = external
            log("       loader_contract.run_gates(root=False, module=%s): %s (%s)"
                % (L3_MODULE, "PASS" if external["ok"] else "FAIL",
                   external.get("result")))
            for gate_id, reason in sorted(external_record["skipped"].items()):
                log("       [%s] not applicable to maplesu_l3: %s; covered by %s"
                    % (gate_id, reason, external_record["coverage"][gate_id]))
            internal_by_id = {gate["id"]: gate["ok"] for gate in internal["gates"]}
            disagreements = []
            for gate in external["gates"]:
                if gate.get("skipped"):
                    continue
                if gate["id"] in internal_by_id and \
                        bool(gate["ok"]) != internal_by_id[gate["id"]]:
                    disagreements.append("%s: module=%s replica=%s"
                                         % (gate["id"], internal_by_id[gate["id"]],
                                            gate["ok"]))
            if disagreements:
                log("       FAIL the two gate engines disagree: %s"
                    % "; ".join(disagreements))
                external_error = "the two gate engines disagree: " + \
                    "; ".join(disagreements)
            if not external["ok"]:
                external_error = external_error or (
                    "the shared replica is red: %s" % external.get("result"))
        except Exception:                          # noqa: BLE001 - fail closed, loudly
            external_error = traceback.format_exc()
            log("       FAIL loader_contract.run_gates() raised (gate engine error, "
                "not necessarily a module defect):")
            for line in external_error.strip().splitlines():
                log("         " + line)
    else:
        log("       gate engine: internal only -- %s" % why)

    ok = internal["ok"] and imports_ok and not external_error
    if "loader_contract" in engines:
        ok = ok and bool(engines["loader_contract"]["ok"])
    return {"ok": ok, "internal": internal, "engines": engines,
            "externalError": external_error, "externalRecord": external_record}


def _l3_build_record(outcome: dict, context: VerifyContext, contract: dict,
                     contract_path: Path, image_path: Path, module: Path,
                     cflags: list | None, built: dict | None,
                     clang: Path | None, ld: Path | None,
                     ndk_dir: str) -> dict:
    """build.json for maplesu_l3: the same shape as the two earlier records."""
    all_relocs = context.elf.all_relocations()
    counts: dict = {}
    for relocs in all_relocs.values():
        for entry in relocs:
            counts[entry["type"]] = counts.get(entry["type"], 0) + 1
    gate_extras = {gate["id"]: gate.get("extras", {})
                   for gate in outcome["internal"]["gates"]}
    external = outcome.get("externalRecord") or {}
    report = {
        "module": context.module_name,
        "modulePath": rel(module),
        "sha256": sha256_file(module),
        "bytes": module.stat().st_size,
        "kernelRelease": contract["kernelRelease"],
        "contract": {"path": rel(contract_path), "sha256": sha256_file(contract_path)},
        "image": {"path": rel(image_path), "sha256": sha256_file(image_path),
                  "bytes": image_path.stat().st_size},
        "tools": {
            "clang": tool_version(clang) if clang else "not used (--verify-only)",
            "ldLld": tool_version(ld) if ld else "not used (--verify-only)",
            "python": platform.python_version(),
            "platform": platform.platform(),
            "ndk": rel(Path(ndk_dir)),
        },
        "sources": {
            key: {"path": rel(path), "sha256": sha256_file(path),
                  "bytes": path.stat().st_size}
            for key, path in (("l3ModuleSource", L3_SOURCE),
                              ("statusHideSource", MODULE_DIR / "maplesu_selinux_hide.h"),
                              ("statusHideAbi", HERE / "selinux_hide_abi.h"),
                              *(("policyBridge:" + name, HERE / name) for name in policy_build.SOURCES),
                              *(("bootSdk:" + name, HERE / name) for name in boot_sdk_build.SOURCES),
                              ("kernelCompatHeader", MODULE_DIR / "kernel_compat.h"),
                              ("glueTemplate", L3_GLUE_TEMPLATE),
                              ("buildScript", Path(__file__)))
        },
        "sections": context.elf.section_table(),
        "undefinedSymbols": context.elf.imports(),
        "definedSymbols": sorted({symbol["name"]
                                  for symbol in context.elf.defined_symbols()}),
        "relocations": {
            "total": sum(counts.values()),
            "byType": {str(value): counts[value] for value in sorted(counts)},
            "byTypeName": {ARM64_MODULE_RELOCS[value]: counts[value]
                           for value in sorted(counts)},
            "bySection": {name: len(relocs)
                          for name, relocs in sorted(all_relocs.items())},
        },
        # The ABI, the image facts and the gates that judge them, in one block a
        # reviewer can diff between builds.
        "abi": {
            "schema": L3_ABI["schema"],
            "version": L3_ABI["version"],
            "option": "0x%08x" % L3_ABI["option"],
            "optionName": L3_ABI["optionName"],
            "opArm": L3_ABI["opArm"],
            "opSu": L3_ABI["opSu"],
            "pool": L3_ABI["pool"],
            "ownerRange": L3_ABI["ownerRange"],
            "errno": L3_ABI["errno"],
            "magic": L3_ABI["magic"],
            "magicMacro": L3_ABI["magicMacro"],
            "tokenMacro": L3_ABI["tokenMacro"],
            # The syscall's return value is not rewritten: the native
            # PR_SET_DUMPABLE already answers 0 for arg2 in {0, 1}, and the
            # callback adds no store to the frame (L3-11).
            "returnValue": L3_ABI["returnValue"],
            "evidenceTokenFormat": None,
            "evidenceTokenJsonField": L3_ABI["evidenceTokenJsonField"],
            "evidenceGateFormat": L3_ABI["evidenceGateFormat"],
            "imports": sorted(L3_EXPECTED_IMPORTS),
            "lookupWhitelist": sorted(L3_LOOKUP_WHITELIST),
            "entry": "kprobe",
            "frameSafety": gate_extras.get("L3-11", {}).get("frameSafety"),
        },
        "imageFacts": {
            "kprobe": context.l3_facts["kprobe"],
            "callbackAnchors": context.l3_facts["kprobe"]["callbackAnchors"],
            "callbackDispatch": context.l3_facts["callbackDispatch"],
            "callbackPrefixPolicy": context.l3_facts["callbackPrefixPolicy"],
            "callbackPins": context.l3_facts["callbackPins"],
            "contractCallbacks": context.l3_facts["contractCallbacks"],
            "probedSymbol": context.l3_facts["probedSymbol"],
            "selinux": context.l3_facts["selinux"],
            "credSource": context.l3_facts["credSource"],
            "rootSubject": context.l3_facts["rootSubject"],
            "callbackSize": "0x%x" % context.l3_facts["callbackSize"],
            "referenceKcfi": gate_extras.get("L3-1", {}).get("prefix"),
            "prefixMeasurement": context.l3_prefix_measurement,
            "referenceTu": ({"source": context.l3_callbacks["source"],
                             "object": context.l3_callbacks["object"],
                             "flags": context.l3_callbacks["flags"],
                             "commands": context.l3_callbacks["commands"],
                             "prototypes": context.l3_callbacks["prototypes"]}
                            if context.l3_callbacks else None),
            "injectedFlags": l3_injected_flags(cflags),
            "kcfi": context.image_facts,
        },
        "facts": {
            "imports": context.elf.imports(),
            "vermagic": contract["vermagic"],
            "thisModule": context.this_module_layout(),
            "prefix": gate_extras.get("L3-1", {}).get("prefix"),
            "modulePolicyBridge": context.l3_facts.get("policyBridge", {}),
            "handlerSection": gate_extras.get("L3-4", {}).get("handlerSection"),
            "kcfiTraps": gate_extras.get("L3-5", {}).get("traps"),
            "kcfiSites": gate_extras.get("L3-5", {}).get("kcfiSites"),
            "cleanup": gate_extras.get("L3-6", {}).get("cleanup"),
            "registration": gate_extras.get("L3-9", {}).get("registration"),
            "armGate": gate_extras.get("L3-10", {}).get("armGate"),
            "printkCalls": gate_extras.get("L3-8", {}).get("printkCalls"),
            "abiGate": gate_extras.get("L3-7", {}).get("abi"),
            "patchPlan": gate_extras.get("G5", {}).get("patchPlan"),
            "lookupNames": gate_extras.get("G5", {}).get("lookupNames"),
            "buildFlags": cflags or [],
            "btf": gate_extras.get("G7", {}).get("btfFacts"),
        },
        "gates": outcome["internal"]["gates"],
        "gateResult": {
            "ok": outcome["ok"],
            "engines": {name: {"ok": result["ok"], "result": result.get("result")}
                        for name, result in outcome["engines"].items()},
            "externalError": outcome["externalError"],
            "externalSkipped": external.get("skipped"),
            "externalCoverage": external.get("coverage"),
            "contractView": external.get("contractView"),
        },
        "verifiedAt": datetime.datetime.now(datetime.timezone.utc).isoformat(
            timespec="seconds"),
    }
    if built:
        report["sources"]["glueRendered"] = {
            "path": rel(built["glue"]), "sha256": built["glue_sha256"]}
        report["commands"] = built["commands"]
        report["compileFlags"] = built["cflags"]
    report["cflags"] = report.get("compileFlags") or (cflags or [])
    return report


def run_l3(args) -> int:
    """`--module maplesu_l3`: build, gate and publish the L3 module.

    Same fail-closed shape as the L2 run: the artifact is produced in a staging
    directory and only copied to <out-dir>/maplesu_l3.ko after every gate passes,
    so a red gate never leaves a loadable-looking .ko behind. The two differences
    are that L3 has an external engine (the shared G1-G7 replica, with the
    documented contract view) and that the -D facts it compiles with are measured
    before the compiler runs -- and, for the callback's KCFI id, measured by
    compiling a reference TU rather than read from a table.
    """
    log = Log(echo=not args.quiet, verbose=args.verbose)
    out_dir = Path(args.out_dir)
    contract_path = resolve_contract(args.contract)
    staging = out_dir / "l3-staging"
    status = 1
    built = None
    clang = ld = None
    reference = None
    try:
        contract = load_contract(contract_path)
        image_path = Path(args.image) if args.image else PROJECT_ROOT / contract["imagePath"]

        log("%s -- kprobe magic gate on prctl (ABI v2)" % L3_MODULE)
        log("  project    %s" % rel(PROJECT_ROOT))
        log("  contract   %s (sha256 %s)"
            % (rel(contract_path), sha256_file(contract_path)[:16] + "..."))
        log("  kernel     %s" % contract["kernelRelease"])
        log("  image      %s (%d bytes, sha256 %s)"
            % (rel(image_path), image_path.stat().st_size,
               sha256_file(image_path)[:16] + "..."))
        actual_image_sha = sha256_file(image_path)
        if actual_image_sha != contract["imageSha256"]:
            raise BuildError(
                "image sha256 is %s but the contract was derived from %s"
                % (actual_image_sha, contract["imageSha256"]))

        module = out_dir / (L3_MODULE + ".ko")
        # The image facts, before the compiler: the callback ids the image
        # compares, the registration target, the cred source, the enforcing
        # offset. All of them become -D, so the C file carries no per-release
        # constant.
        image = image_path.read_bytes()
        symbols = SymbolTable(PROJECT_ROOT / contract["symbolsPath"]
                              if not Path(contract["symbolsPath"]).is_absolute()
                              else Path(contract["symbolsPath"]))
        l3_facts = derive_l3_facts(image, symbols, contract)
        kprobe_facts = l3_facts["kprobe"]
        log("  l3 anchors %s pre=%s post=%s"
            % (kprobe_facts["callbackAnchors"],
               kprobe_facts["dispatch"]["preHandler"]["anchorsPresent"],
               kprobe_facts["dispatch"]["postHandler"]["anchorsPresent"]))
        for key in ("preHandler", "postHandler"):
            entry = kprobe_facts["dispatch"][key]
            log("             %-11s id=%s member=%s@%s attributed=%s absent=%s"
                % (key, entry["typeId"], entry["member"],
                   entry["memberOffset"],
                   ["%s@%s" % (site["function"], site["site"])
                    for site in entry["attributedSites"]],
                   entry["anchorsAbsent"]))
        log("             primary %s @ %s compares %d cfi id(s): %s"
            % (kprobe_facts["symbol"], kprobe_facts["address"],
               len(kprobe_facts["cfiTypeIds"]),
               ", ".join(kprobe_facts["cfiTypeIds"])))
        log("             probe target %s @ %s entry=%s"
            % (l3_facts["probedSymbol"]["symbol"],
               l3_facts["probedSymbol"]["address"],
               l3_facts["probedSymbol"]["entryInstruction"]))
        log("             credSource=%d (%s) selinux enforcing@%s kprobe.size=0x%x"
            % (l3_facts["credSource"]["source"],
               "init_task" if l3_facts["credSource"]["source"]
               == L3_CRED_INIT_TASK else "NULL",
               l3_facts["selinux"]["enforcingOffset"], l3_facts["callbackSize"]))

        # The callback's KCFI id, measured by the pinned toolchain rather than
        # asserted: a reference TU with the kernel's own prototype spelling is
        # compiled with this build's flags, and the two ids it emits are the
        # toolchain's statement about the two prototypes. They must be the pinned
        # pair (a toolchain that hashes differently fails the build); whether they
        # are also the *image's* ids is a separate fact the dispatch derivation
        # answers, and on 6.12/6.18 the answer is no.
        reference = kprobe_callback_ids(work_dir=out_dir / "l3-kcfi-reference",
                                        ndk=Path(args.ndk_dir))
        l3_facts["referenceKcfi"] = reference
        for key, pin in L3_CALLBACK_PINS.items():
            if reference[key] != pin:
                raise BuildError(
                    "the pinned toolchain derives the %s id 0x%08x, but "
                    "L3_CALLBACK_PINS records 0x%08x; the toolchain is not the one "
                    "this module's evidence was measured with"
                    % (key, reference[key], pin))
        log("  kcfi       reference TU: pre=%s post=%s (the pinned toolchain pair)"
            % (reference["preHandlerHex"], reference["postHandlerHex"]))
        log("             image dispatch: pre=%s post=%s"
            % (kprobe_facts["dispatch"]["preHandler"]["typeId"],
               kprobe_facts["dispatch"]["postHandler"]["typeId"]))

        if args.verify_only:
            if not module.is_file():
                print("RESULT: FAILED -- artifact missing: %s (build it with "
                      "--module maplesu_l3 and no --verify-only)" % rel(module))
                return 1
            clang = find_tool(Path(args.ndk_dir), "clang")
            ld = find_tool(Path(args.ndk_dir), "ld.lld")
            cflags = recorded_cflags(out_dir)
            log("verify-only: no compile, no link")
            log("  artifact   %s" % rel(module))
            emitted = emitted_handler_prefix(module)
            log("  kcfi       artifact carries 0x%08x in front of maplesu_l3_pre "
                "(L3-1 judges it)"
                % (emitted if emitted is not None else 0))
        else:
            if staging.exists():
                shutil.rmtree(staging)
            built = build(Path(args.ndk_dir), staging, contract, log,
                          args.extra_flag, module=L3_MODULE, l3_facts=l3_facts)
            module = built["module"]
            cflags = built["cflags"]
            clang, ld = built["clang"], built["ld"]
            log("  tools      %s" % tool_version(clang))
            log("             %s" % tool_version(ld))
            log("             python %s (%s)"
                % (platform.python_version(), platform.platform()))
            # Two separate statements about the prefix, in this order:
            #   1. what clang emitted must be the reference TU's id -- the callback
            #      really is compiled with the kernel's prototype spelling;
            #   2. what ships must be the image's dispatch id. It is written after
            #      linking with the same mechanism init_module/cleanup_module use,
            #      and only when the contract opts in: an image whose id differs
            #      from the emitted bytes without that opt-in is a design change
            #      nobody recorded, so it stops the build.
            extra_elf = Elf(module)
            extra_callback = extra_elf.symbol("maplesu_avc_pre")
            if not extra_callback:
                raise BuildError("root-subject callback is missing")
            extra_section = extra_elf.sections[extra_callback["shndx"]]
            extra_prefix = struct.unpack_from("<I", extra_elf.section_bytes(extra_section),
                                              extra_callback["value"] - 4)[0]
            if extra_prefix != reference["preHandler"]:
                raise BuildError("root-subject callback KCFI differs from reference TU")
            emitted = emitted_handler_prefix(module)
            log("  kcfi       maplesu_l3_pre emits 0x%08x (before the image write)"
                % (emitted if emitted is not None else 0))
            if emitted != reference["preHandler"]:
                raise BuildError(
                    "maplesu_l3_pre emits KCFI prefix %s but the reference TU "
                    "derives %s; the callback is not being compiled with the "
                    "kernel's prototype spelling"
                    % ("0x%08x" % emitted if emitted is not None else "none",
                       reference["preHandlerHex"]))
            dispatch_pre = int(kprobe_facts["dispatch"]["preHandler"]["typeId"], 16)
            policy = l3_facts["callbackPrefixPolicy"]
            if dispatch_pre != emitted and policy != L3_PREFIX_POLICY_PATCHED:
                raise BuildError(
                    "maplesu_l3_pre emits 0x%08x but this image's pre-handler "
                    "dispatch compares 0x%08x, and the contract's "
                    "l3.callbackPrefixPolicy is %r (expected %r): the prefix write "
                    "is a design change that has to be recorded for this family"
                    % (emitted, dispatch_pre, policy, L3_PREFIX_POLICY_PATCHED))
            # init_module/cleanup_module are the module's own entry points; the
            # callback is the function the kernel's kprobe machinery checks, so it
            # gets the same image-aligned treatment (a no-op where they agree).
            callbacks = {"maplesu_l3_pre": dispatch_pre, "maplesu_avc_pre": dispatch_pre}
            if l3_facts.get("statusView"):
                for callback in ("maplesu_status_read_pre",):
                    callbacks[callback] = dispatch_pre
            if l3_facts.get("policyBridge"):
                callbacks.update({name: dispatch_pre for name in ("maplesu_policy_control_pre", "msu_policy_pre")})
            if l3_facts.get("bootSdk"):
                callbacks.update({name: dispatch_pre for name in ("msu_boot_sdk_pre", "msu_boot_sdk_allocator_pre")})
            patch_kcfi_prefixes(module, image_path, contract, log, extra_prefixes=callbacks)
            shipped = emitted_handler_prefix(module)
            measured = {"emittedPrefix": emitted, "shippedPrefix": shipped,
                        "referencePre": reference["preHandler"],
                        "imagePre": dispatch_pre,
                        "prefixPolicy": policy,
                        "rewritten": shipped != emitted}
            l3_facts["prefixMeasurement"] = measured
            log("  kcfi       maplesu_l3_pre ships 0x%08x (image dispatch id %s; "
                "policy %s; rewritten=%s)"
                % (shipped if shipped is not None else 0,
                   kprobe_facts["dispatch"]["preHandler"]["typeId"], policy,
                   shipped != emitted))
            if shipped != dispatch_pre:
                raise BuildError(
                    "the shipped prefix is %s but the image's pre-handler dispatch "
                    "compares %s; nothing is published"
                    % ("0x%08x" % shipped if shipped is not None else "none",
                       kprobe_facts["dispatch"]["preHandler"]["typeId"]))

        log("")
        log("module     %s (%d bytes, sha256 %s)"
            % (rel(module), module.stat().st_size, sha256_file(module)))
        # G7 judges the header the compiler saw. A fresh build knows it; a
        # --verify-only run reads the one published beside the artifact (run_l3
        # copies it there) and falls back to the last rendering in this process.
        header_path = Path(built["header"]) if built and built.get("header") else None
        if header_path is None or not header_path.is_file():
            candidate = out_dir / "kernel_compat_family.h"
            if candidate.is_file():
                header_path = candidate
            elif LAST_RENDERED_HEADER and Path(LAST_RENDERED_HEADER).is_file():
                header_path = Path(LAST_RENDERED_HEADER)
        context = VerifyContext(module, contract, contract_path, image_path, log,
                                cflags=cflags, module_key=L3_MODULE,
                                header_path=header_path,
                                l3_callbacks=reference,
                                l3_prefix_measurement=l3_facts.get(
                                    "prefixMeasurement"))
        log("")
        log("sections (%d)" % len(context.elf.sections), detail=True)
        for row in context.elf.section_table():
            flags = "".join(letter for letter, present in
                            (("A", row["alloc"]), ("W", row["write"]),
                             ("X", row["exec"])) if present) or "-"
            log("  [%2d] %-32s %-9s %-6s off=0x%06x size=0x%06x align=%d"
                % (row["index"], row["name"], row["type"], flags,
                   row["offset"], row["size"], row["align"]), detail=True)
        imports = context.elf.imports()
        log("undefined symbols (%d): %s" % (len(imports), ", ".join(imports)))

        log("")
        log("gates")
        outcome = verify_l3(context, log, args.gate_engine, out_dir)
        log("")
        log("imports: %s" % ", ".join(outcome["internal"]["imports"]))
        log("RESULT: %s" % ("all gates pass" if outcome["ok"] else
                            "FAILED -- " + outcome["internal"]["result"]))
        status = 0 if outcome["ok"] else 1
        if status and args.quiet:
            print("L3 verification failed: %s; details: %s" %
                  (outcome["internal"]["result"], rel(out_dir / "build.json")),
                  file=sys.stderr)

        report = _l3_build_record(outcome, context, contract, contract_path,
                                  image_path, module, cflags, built, clang, ld,
                                  args.ndk_dir)
        if status == 0 and built:
            out_dir.mkdir(parents=True, exist_ok=True)
            published = []
            for path in [built["module"], *built["objects"], built["glue"],
                         Path(built["header"])]:
                target = out_dir / path.name
                shutil.copy2(path, target)
                published.append(rel(target))
            log("")
            log("published (all gates green): %s" % ", ".join(published))
        elif status != 0 and built:
            log("")
            log("NOT published: the gates are red, so %s was not written; the "
                "attempt's artifacts stay in %s"
                % (rel(out_dir / (L3_MODULE + ".ko")), rel(staging)))
        (out_dir / "build.json").write_text(json.dumps(report, indent=2) + "\n",
                                           encoding="utf-8")
        log("")
        log("wrote %s" % rel(out_dir / "build.json"))
        return status
    except (BuildError, GateFailure) as error:
        if args.quiet:
            print("L3 build failed: %s" % error, file=sys.stderr)
        log("")
        log("ERROR: %s" % error)
        log("RESULT: FAILED -- %s" % error)
        return 1
    finally:
        if args.transcript:
            log.save(Path(args.transcript))
        elif out_dir.is_dir():
            log.save(out_dir / "verify-build.txt")
        if not args.quiet:
            print("transcript: %s"
                  % rel(Path(args.transcript) if args.transcript
                        else out_dir / "verify-build.txt"))



def main(argv: list | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Build maplesu_root.ko with the Android NDK and run the 7 offline "
                    "loader gates. Host-side only.")
    parser.add_argument("--verify-only", action="store_true",
                        help="verify the existing .ko instead of building it")
    parser.add_argument("--module", default="maplesu_root",
                        choices=("maplesu_root", "maplesu_l2", "maplesu_l3"),
                        help="which artifact this run is a statement about (default: "
                             "maplesu_root). maplesu_l2 is the L2 module (gate set "
                             "G8-G14); maplesu_l3 is the L3 credential gate (G1-G7 "
                             "with an L3 G5, plus L3-1..L3-11), built from the same "
                             "image with the kprobe callback ids, the registration "
                             "target and the cred source measured from it")
    parser.add_argument("--out-dir", default=str(DEFAULT_OUT),
                        help="where the .ko, objects, build.json and transcript go "
                             "(default: %s)" % rel(DEFAULT_OUT))
    parser.add_argument("--contract", default=None,
                        help="contracts/<uname>.json (default: the single one present)")
    parser.add_argument("--image", default=None,
                        help="kernel image (default: contract imagePath)")
    parser.add_argument("--ndk-dir", default=str(DEFAULT_NDK))
    parser.add_argument("--gate-engine", default="auto",
                        choices=("auto", "internal", "loader-contract"),
                        help="auto (default) uses loader_contract.run_gates() when it "
                             "exists and requires both engines to agree")
    parser.add_argument("--helper-path", default=None,
                        help="helper path this verification is a statement about "
                             "(default: contract helperPathPolicy.policyPath). Step-3 "
                             "uses the default; the flag exists for the design's "
                             "'stage a second copy elsewhere and pass it in argv' "
                             "escape hatch, which is a contract change, not a build "
                             "tweak.")
    parser.add_argument("--extra-flag", action="append", default=[],
                        help="extra clang flag (bring-up experiments)")
    parser.add_argument("--verbose", action="store_true",
                        help="also echo every gate sub-check, the section table and "
                             "the relocation table (the transcript always has them)")
    parser.add_argument("--transcript", default=None,
                        help="write the transcript here (default for --verify-only: "
                             "<out-dir>/verify-build.txt)")
    parser.add_argument("--quiet", action="store_true", help="do not echo the transcript")
    parser.add_argument("--evidence", action="store_true",
                        help="run the whole step-1 host acceptance (this script's "
                             "--verify-only, check_traceless_invariants.py, both unit-test "
                             "suites, two clean rebuilds for determinism, llvm-readelf) and "
                             "write <out-dir>/%s with every command's verbatim output; "
                             "device-side is NOT_EXERCISED" % EVIDENCE_NAME)
    args = parser.parse_args(argv)

    if args.contract and legacy_cfi.enabled(load_contract(resolve_contract(args.contract))):
        return legacy_cfi.run(args, sys.modules[__name__])

    # `--module` selects the artifact, the gate set and the contract section:
    # maplesu_root keeps G1-G7 exactly as they were, maplesu_l2 runs G1-G14
    # (design rev-3 6.1), and maplesu_l3 runs G1-G7 with an L3 G5 plus L3-1..L3-11.
    # Each of the two later artifacts has its own build, its own staging directory
    # and its own fail-closed publish rule.
    if args.module == L2_MODULE:
        if args.evidence:
            print("RESULT: FAILED -- --evidence records the root module's step-1 "
                  "acceptance; it is not an L2 mode")
            return 1
        return run_l2(args)

    if args.module == L3_MODULE:
        if args.evidence:
            print("RESULT: FAILED -- --evidence records the root module's step-1 "
                  "acceptance; it is not an L3 mode")
            return 1
        return run_l3(args)

    if args.evidence:
        try:
            return run_evidence(args)
        except (BuildError, GateFailure) as error:
            print("ERROR: %s" % error)
            print("RESULT: FAILED -- %s" % error)
            return 1

    log = Log(echo=not args.quiet, verbose=args.verbose)
    out_dir = Path(args.out_dir)
    contract_path = resolve_contract(args.contract)
    transcript = Path(args.transcript) if args.transcript else (
        out_dir / "verify-build.txt" if args.verify_only else None)
    status = 1

    try:
        contract = load_contract(contract_path)
        image_path = Path(args.image) if args.image else PROJECT_ROOT / contract["imagePath"]

        log("maplesu_root -- in-kernel root channel, built and verified on the host")
        log("  project    %s" % rel(PROJECT_ROOT))
        log("  contract   %s (sha256 %s)"
            % (rel(contract_path), sha256_file(contract_path)[:16] + "..."))
        log("  kernel     %s" % contract["kernelRelease"])
        log("  image      %s (%d bytes, sha256 %s)"
            % (rel(image_path), image_path.stat().st_size,
               sha256_file(image_path)[:16] + "..."))

        actual_image_sha = sha256_file(image_path)
        if actual_image_sha != contract["imageSha256"]:
            raise BuildError(
                "image sha256 is %s but the contract was derived from %s; every "
                "number in it describes a different kernel"
                % (actual_image_sha, contract["imageSha256"]))

        build_record = {}
        if args.verify_only:
            module = out_dir / (contract["module"] + ".ko")
            if not module.is_file():
                raise BuildError("%s does not exist; run without --verify-only to "
                                 "build it" % rel(module))
            clang = find_tool(Path(args.ndk_dir), "clang")
            ld = find_tool(Path(args.ndk_dir), "ld.lld")
            log("verify-only: no compile, no link")
        else:
            built = build(Path(args.ndk_dir), out_dir, contract, log, args.extra_flag)
            module = built["module"]
            build_record = built
            clang, ld = built["clang"], built["ld"]
            log("  tools      %s" % tool_version(clang))
            log("             %s" % tool_version(ld))
            log("             python %s (%s)" % (platform.python_version(),
                                                 platform.platform()))

        log("")
        if not getattr(args, "verify_only", False):
            # Fresh build: align the compiler-emitted KCFI ids with this image's
            # own compare sites before any gate judges them. No-op where they agree.
            patch_kcfi_prefixes(module, image_path, contract, log)
        log("module     %s (%d bytes, sha256 %s)"
            % (rel(module), module.stat().st_size, sha256_file(module)))
        # G6 judges the *recorded* flags as well as the bytes: -fsanitize=kcfi is what
        # emits the mandatory init/exit prefixes, and a build that dropped it is not
        # the build the gates describe. On a fresh build they are in memory; on
        # --verify-only they come from the build record beside the artifact.
        cflags = build_record.get("cflags") if build_record else recorded_cflags(out_dir)
        rendered = None
        if build_record and build_record.get("header"):
            candidate = Path(build_record["header"])
            if candidate.is_file():
                rendered = candidate
        if rendered is None and LAST_RENDERED_HEADER:
            candidate = Path(LAST_RENDERED_HEADER)
            if candidate.is_file():
                rendered = candidate
        context = VerifyContext(module, contract, contract_path, image_path, log,
                                cflags=cflags, helper_path=args.helper_path,
                                header_path=rendered)

        log("")
        log("sections (%d)" % len(context.elf.sections), detail=True)
        for row in context.elf.section_table():
            flags = "".join(letter for letter, present in
                            (("A", row["alloc"]), ("W", row["write"]),
                             ("X", row["exec"])) if present) or "-"
            log("  [%2d] %-32s %-9s %-6s off=0x%06x size=0x%06x align=%d"
                % (row["index"], row["name"], row["type"], flags,
                   row["offset"], row["size"], row["align"]), detail=True)
        imports = context.elf.imports()
        log("undefined symbols (%d): %s" % (len(imports), ", ".join(imports)))
        all_relocs = context.elf.all_relocations()
        counts: dict = {}
        for relocs in all_relocs.values():
            for entry in relocs:
                counts[entry["type"]] = counts.get(entry["type"], 0) + 1
        log("relocations (%d): %s"
            % (sum(counts.values()),
               ", ".join("%s=%d" % (ARM64_MODULE_RELOCS[value], counts[value])
                         for value in sorted(counts))), detail=True)

        log("")
        log("gates")
        outcome = verify(context, log, args.gate_engine)
        log("")
        log("imports: %s" % ", ".join(outcome["internal"]["imports"]))
        log("RESULT: %s" % ("all gates pass" if outcome["ok"] else
                            "FAILED -- " + outcome["internal"]["result"]))
        status = 0 if outcome["ok"] else 1

        report = {}
        if args.verify_only:
            existing = out_dir / "build.json"
            if existing.is_file():
                try:
                    report = json.loads(existing.read_text(encoding="utf-8"))
                except json.JSONDecodeError:
                    report = {}
        gate_extras = {gate["id"]: gate.get("extras", {})
                       for gate in outcome["internal"]["gates"]}
        sources = dict(report.get("sources") or {})
        report.update({
            "module": contract["module"],
            "modulePath": rel(module),
            "sha256": sha256_file(module),
            "bytes": module.stat().st_size,
            "kernelRelease": contract["kernelRelease"],
            "contract": {"path": rel(contract_path),
                         "sha256": sha256_file(contract_path)},
            "image": {"path": rel(image_path), "sha256": actual_image_sha,
                      "bytes": image_path.stat().st_size},
            "tools": {
                "clang": tool_version(clang),
                "ldLld": tool_version(ld),
                "python": platform.python_version(),
                "platform": platform.platform(),
                "ndk": rel(Path(args.ndk_dir)),
            },
            "sources": sources,
            "sections": context.elf.section_table(),
            "undefinedSymbols": imports,
            "definedSymbols": sorted({s["name"] for s in context.elf.defined_symbols()}),
            "relocations": {
                "total": sum(counts.values()),
                "byType": {str(value): counts[value] for value in sorted(counts)},
                "byTypeName": {ARM64_MODULE_RELOCS[value]: counts[value]
                               for value in sorted(counts)},
                "bySection": {name: len(relocs)
                              for name, relocs in sorted(all_relocs.items())},
            },
            "facts": {
                "imports": imports,
                "importClasses": gate_extras.get("G3", {}).get("importClasses", {}),
                "vermagic": contract["vermagic"],
                "thisModule": contract["thisModule"],
                "kcfi": context.image_facts,
                "kcfiCompareSites": gate_extras.get("G6", {}).get("kcfiSites", []),
                "kcfiTraps": gate_extras.get("G6", {}).get("traps", {}),
                "buildFlags": context.cflags or [],
                "helperPath": gate_extras.get("G5", {}).get("helperPath"),
                "helperPathExpected": gate_extras.get("G5", {}).get("helperPathExpected"),
                "helperPathSource": gate_extras.get("G5", {}).get("helperPathSource"),
                "umhArgvStorage": gate_extras.get("G5", {}).get("umhArgvStorage"),
                "lookupNames": gate_extras.get("G5", {}).get("lookupNames", []),
                "umhSites": gate_extras.get("G5", {}).get("umhSites", []),
                "handoverMode": gate_extras.get("G5", {}).get("handoverMode"),
                "umhPrimary": gate_extras.get("G5", {}).get("umhPrimary", {}),
                "subprocessInfoPathOffset":
                    gate_extras.get("G5", {}).get("subprocessInfoPathOffset"),
                "btf": gate_extras.get("G7", {}).get("btfFacts", {}),
            },
            "gates": outcome["internal"]["gates"],
            "gateResult": {
                "ok": outcome["ok"],
                "engines": {name: {"ok": result["ok"], "result": result.get("result")}
                            for name, result in outcome["engines"].items()},
                "externalError": outcome["externalError"],
            },
            "verifiedAt": datetime.datetime.now(datetime.timezone.utc).isoformat(
                timespec="seconds"),
        })
        for key, path in (("moduleSource", MODULE_DIR / "maplesu_root.c"),
                          ("kernelCompatHeader", MODULE_DIR / "kernel_compat.h"),
                          ("glueTemplate", MODULE_DIR / "root_glue.S.in"),
                          ("buildScript", Path(__file__))):
            report["sources"][key] = {"path": rel(path), "sha256": sha256_file(path),
                                      "bytes": path.stat().st_size}
        if build_record:
            report["sources"]["glueRendered"] = {
                "path": rel(build_record["glue"]),
                "sha256": build_record["glue_sha256"]}
            report["commands"] = build_record["commands"]
            report["compileFlags"] = build_record["cflags"]
        # The recorded flags are an *input* to G6 on the next --verify-only run, so a
        # verify-only invocation keeps whatever the last real build recorded.
        if not report.get("compileFlags") and context.cflags:
            report["compileFlags"] = context.cflags
        report["cflags"] = report.get("compileFlags") or []
        report["deviations"] = [
            {"flag": flag, "why": why} for flag, why in FLAGS_WITHHELD.items()
            if flag not in (report.get("compileFlags") or [])
        ]
        report["advisories"] = [
            {"what": "helper path vs D-5 policy",
             "detail": contract["helperPathPolicy"]["note"]},
        ]
        (out_dir / "build.json").write_text(json.dumps(report, indent=2) + "\n",
                                           encoding="utf-8")
        log("")
        log("wrote %s" % rel(out_dir / "build.json"))
        return status
    except (BuildError, GateFailure) as error:
        log("")
        log("ERROR: %s" % error)
        log("RESULT: FAILED -- %s" % error)
        return 1
    finally:
        if transcript is not None:
            log.save(transcript)
            if not args.quiet:
                print("transcript: %s" % rel(transcript))


# --------------------------------------------------------------------------- #
# Host acceptance evidence.
#
# The gates above prove properties of *one* artifact. This mode records the
# acceptance itself: every command that decides step 1 is run here, its exit code and
# verbatim stdout/stderr are written to build/verify-evidence.txt together with the
# digests and a readelf summary, so a reviewer can audit the run without repeating it.
#
# Host-only by construction: the commands are the build script, the production gate,
# the two unit-test suites and llvm-readelf. No adb, no module load, no device.
# --------------------------------------------------------------------------- #

def capture(argv: list, cwd: Path | None = None) -> dict:
    """Run one command for the record.

    A non-zero exit is evidence, not an exception: the caller decides the verdict, so
    the real output is always kept verbatim.
    """
    argv = [str(part) for part in argv]
    started = time.time()
    result = subprocess.run(argv, cwd=str(cwd) if cwd else None,
                            capture_output=True, text=True, errors="replace")
    return {"argv": argv,
            "cwd": rel(cwd) if cwd else rel(PROJECT_ROOT),
            "exit": result.returncode,
            "stdout": result.stdout or "",
            "stderr": result.stderr or "",
            "seconds": round(time.time() - started, 3)}


def _transcript(record: dict) -> list:
    return ["$ " + " ".join(record["argv"]),
            "  cwd: %s" % record["cwd"],
            "--- stdout ---",
            record["stdout"].rstrip("\n") or "(empty)",
            "--- stderr ---",
            record["stderr"].rstrip("\n") or "(empty)",
            "--- exit: %d (%.3fs) ---" % (record["exit"], record["seconds"]),
            ""]


def _digest_manifest(contract_path: Path, module_path: Path, image_path: Path,
                     contract: dict) -> list:
    sources = [("contract", contract_path), ("module.ko", module_path),
               ("kernel image", image_path),
               ("module/maplesu_root.c", MODULE_DIR / "maplesu_root.c"),
               ("module/kernel_compat.h", MODULE_DIR / "kernel_compat.h"),
               ("module/root_glue.S.in", MODULE_DIR / "root_glue.S.in"),
               ("build_root_module.py", Path(__file__)),
               ("loader_contract.py (replica)", LOADER_CONTRACT),
               ("contracts/derive_contract.py", CONTRACTS_DIR / "derive_contract.py"),
               ("tests/test_root_contract.py", ROOT_TESTS / "test_root_contract.py"),
               ("tests/test_root_build.py", ROOT_TESTS / "test_root_build.py"),
               ("build/root_glue.s", module_path.parent / "root_glue.s")]
    lines = ["sha256 (python hashlib, read back from disk after every step)"]
    for label, path in sources:
        if Path(path).is_file():
            lines.append("  %-32s %s  %s" % (label, sha256_file(path), rel(path)))
        else:
            lines.append("  %-32s %-64s ABSENT %s" % (label, "-", rel(path)))
    lines.append("  %-32s %s  %s" % ("kernelRelease", contract["kernelRelease"], "-"))
    return lines


def run_evidence(args) -> int:
    """Run the step-1 host acceptance and write <out-dir>/verify-evidence.txt."""
    out_dir = Path(args.out_dir)
    contract_path = resolve_contract(args.contract)
    contract = load_contract(contract_path)
    image_path = Path(args.image) if args.image else PROJECT_ROOT / contract["imagePath"]
    ndk = Path(args.ndk_dir)
    module_path = out_dir / (contract["module"] + ".ko")

    steps: list[dict] = []

    def add(step_id: str, title: str, records: list, verdict: str | None = None,
            notes: list | None = None) -> None:
        worst = 0
        for record in records:
            worst = record["exit"] if record["exit"] != 0 else worst
        if verdict is None:
            verdict = "PASS" if worst == 0 else "FAIL"
        steps.append({"id": step_id, "title": title, "verdict": verdict,
                      "exit": worst, "records": records, "notes": list(notes or [])})

    # -- the acceptance commands, in the order a reviewer would run them ------- #
    add("s1", "build_root_module.py --verify-only (G1-G7, both gate engines)",
        [capture([sys.executable, str(Path(__file__)), "--verify-only",
                  "--out-dir", str(out_dir), "--contract", str(contract_path),
                  "--image", str(image_path), "--ndk-dir", str(ndk)], PROJECT_ROOT)])

    # s2/s3 used to gate the removed provider trees (check_traceless_invariants.py,
    # ir-loader/tests). Ultra-only keeps the kernel module gates above; the provider
    # acceptance record no longer exists, so both steps are gone.

    # s4 is self-referential: test_root_build.test_03 asserts that *this file*
    # describes the committed .ko and says OVERALL: PASS, so the suite cannot pass
    # before such a file exists. The recorded verdict is therefore finished in a
    # second pass (see below): the first write carries a provisional PASS with the
    # note that says so, the suite then runs against that complete document, and the
    # file is rewritten with the real verdict. A genuine failure replaces the
    # provisional line with FAIL before the process exits; only the identity check can
    # differ between the two attempts.
    add("s4", "unittest discover -s jailbreak-providers/root-loader/tests", [],
        verdict="PASS",
        notes=["provisional: this suite runs immediately after this first write, and "
               "its real verdict replaces this line in the second pass",
               "test_03 of the suite asserts that this file describes the committed "
               ".ko and is green, so the file has to exist first"])

    # -- determinism: two clean rebuilds, compared with the committed artifact -- #
    scratch = out_dir / "scratch" / "determinism"
    records = []
    for tag in ("clean-a", "clean-b"):
        target = scratch / tag
        if target.exists():
            shutil.rmtree(target)
        records.append(capture([sys.executable, str(Path(__file__)), "--out-dir",
                                str(target), "--contract", str(contract_path),
                                "--image", str(image_path), "--ndk-dir", str(ndk),
                                "--quiet"], PROJECT_ROOT))
    digests = {tag: (sha256_file(scratch / tag / (contract["module"] + ".ko"))
                     if (scratch / tag / (contract["module"] + ".ko")).is_file()
                     else "missing")
               for tag in ("clean-a", "clean-b")}
    committed = sha256_file(module_path) if module_path.is_file() else "missing"
    notes = ["committed   %s  %s" % (committed, rel(module_path))]
    notes += ["%-10s %s  %s" % (tag, digest, rel(scratch / tag))
              for tag, digest in digests.items()]
    identical = len({committed, *digests.values()}) == 1 and "missing" not in digests.values()
    if identical:
        notes.append("the two clean rebuilds and the committed artifact are byte-identical")
    else:
        # Localise the cause instead of guessing: same sources, so a differing object
        # names the step that is not deterministic.
        for name in ("maplesu_root.o", "root_glue.o", "root_glue.s"):
            hashes = {tag: (sha256_file(scratch / tag / name)
                            if (scratch / tag / name).is_file() else "missing")
                      for tag in ("clean-a", "clean-b")}
            notes.append("  %-16s %s" % (name, "IDENTICAL" if len(set(hashes.values())) == 1
                                         else "DIFFERS %s" % hashes))
    add("s5", "determinism: two clean rebuilds vs the committed artifact",
        records, verdict="PASS" if identical else "FAIL", notes=notes)

    # -- readelf summary ------------------------------------------------------ #
    readelf = find_tool(ndk, "llvm-readelf")
    add("s6", "readelf summary of the committed artifact",
        [capture([str(readelf), flags, str(module_path)], PROJECT_ROOT)
         for flags in ("-hSW", "-sW", "-rW")])

    add("s7", "sha256 manifest (everything this verdict is a statement about)",
        [], notes=_digest_manifest(contract_path, module_path, image_path, contract))

    # -- render --------------------------------------------------------------- #
    generated = datetime.datetime.now(datetime.timezone.utc).isoformat(
        timespec="seconds")

    def render() -> str:
        overall = "PASS" if all(step["verdict"] == "PASS" for step in steps) else "FAIL"
        lines = [
            "MapleSU step 1 (build edge) -- host acceptance evidence",
            "generated (UTC): %s" % generated,
            "project:         %s" % rel(PROJECT_ROOT),
            "python:          %s (%s)" % (platform.python_version(),
                                          platform.platform()),
            "contract:        %s" % rel(contract_path),
            "kernel release:  %s" % contract["kernelRelease"],
            "kernel image:    %s (%d bytes)" % (rel(image_path),
                                                image_path.stat().st_size
                                                if image_path.is_file() else -1),
            "module:          %s (%d bytes)"
            % (rel(module_path), module_path.stat().st_size
               if module_path.is_file() else -1),
            "",
            "DEVICE-SIDE ITEMS: NOT_EXERCISED. This file was produced on the host only. "
            "No",
            "adb command was run, no module was pushed, inserted or loaded, no device was",
            "rebooted and no partition or boot image was written. Every verdict below is a",
            "statement about bytes on this machine, not about a running kernel.",
            "",
            "== acceptance matrix ==",
        ]
        for step in steps:
            lines.append("%-3s %-74s %-5s exit=%d"
                         % (step["id"], step["title"], step["verdict"], step["exit"]))
        lines += ["OVERALL: %s" % overall, "", "== raw transcripts ==", ""]

        for step in steps:
            lines.append("### %s  %s" % (step["id"], step["title"]))
            lines.append("verdict: %s" % step["verdict"])
            lines.append("")
            for record in step["records"]:
                lines += _transcript(record)
            if step["notes"]:
                lines.append("--- computed ---")
                lines += step["notes"]
                lines.append("")
        lines += ["== machine-readable summary ==",
                  json.dumps({"schema": "maplesu-step1-acceptance/1",
                              "generatedAt": generated,
                              "overall": overall,
                              "deviceSide": "NOT_EXERCISED",
                              "contract": {"path": rel(contract_path),
                                           "sha256": sha256_file(contract_path)},
                              "module": {"path": rel(module_path),
                                         "sha256": committed,
                                         "bytes": module_path.stat().st_size
                                         if module_path.is_file() else None},
                              "steps": [{"id": step["id"], "title": step["title"],
                                         "verdict": step["verdict"],
                                         "exit": step["exit"]}
                                        for step in steps]},
                             indent=2),
                  ""]
        return "\n".join(lines)

    evidence = out_dir / EVIDENCE_NAME
    evidence.parent.mkdir(parents=True, exist_ok=True)

    # First pass: a file that describes this artifact, so the s4 suite can read it.
    evidence.write_text(render(), encoding="utf-8")

    # Second pass: run the root-loader suite now that the file it checks exists, and
    # record its real verdict, then write the file again with that verdict in it.
    s4_index = next(index for index, step in enumerate(steps) if step["id"] == "s4")
    record = capture([sys.executable, "-m", "unittest", "discover",
                      "-s", str(ROOT_TESTS), "-v"], PROJECT_ROOT)
    steps[s4_index] = {
        "id": "s4", "title": steps[s4_index]["title"], "records": [record],
        "verdict": "PASS" if record["exit"] == 0 else "FAIL",
        "exit": record["exit"],
        "notes": ["run after the first write: test_03 of this suite asserts that "
                  "verify-evidence.txt describes the committed .ko, so the file has "
                  "to exist before the verdict can be final"],
    }
    text = render()
    evidence.write_text(text, encoding="utf-8")
    overall = "PASS" if all(step["verdict"] == "PASS" for step in steps) else "FAIL"

    print("host acceptance (device-side: NOT_EXERCISED)")
    for step in steps:
        print("  %-3s %-62s %s" % (step["id"], step["title"][:62], step["verdict"]))
    print("RESULT: %s" % overall)
    print("evidence: %s" % rel(evidence))
    return 0 if overall == "PASS" else 1


if __name__ == "__main__":
    sys.exit(main())
