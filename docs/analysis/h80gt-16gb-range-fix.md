English | [简体中文](h80gt-16gb-range-fix_ZH.md)

# H80GT 16GB dm-range fix (shipped payload change)

Date: 2026-10-30. Scope: the six bundled `exploit` payloads. Upstream:
[GhostLock-H80GT](https://github.com/yakidango-official/GhostLock-H80GT).

> **Supersedes:** the retire-page workaround in
> [upstream-issue-h80gt-dm-page.md](upstream-issue-h80gt-dm-page.md) and the payload row of
> [H80GT-02](device-gates/H80GT-02.md). Those records describe the earlier attempt; the
> shipped payloads carry the range fix below.

## Symptom

On 16GB `AGT-AN00` parts some boots fail inside the sysctl stage before the first walk can
fire: `prepare_dm_page()` returns a dm page whose alias is at or above `DIRECT_MAP_END`, and
`sysctl_do_walk()` skips every attempt with `SKIP out-of-ram`:

```text
dm: anon=0x7b4339c000 phys=0xb57784000 base(dm)=0xffffff8ad7784000 ...
sysctl walk owner attempt=1 SKIP out-of-ram base=ffffff8ad7784000
sysctl walk owner attempt=2 SKIP out-of-ram base=ffffff8ad7784000
...
sysctl ctl.data restore failed (boot_id stays hijacked; ... reboot to clear)
sysctl stage failed
```

## Root cause

`DIRECT_MAP_END` was `0xffffff8a30000000` (phys `0xAB0000000`), the **12GB** bank bound. The
HONOR 80 GT also ships 16GB parts, whose high bank extends to phys `0xB80000000` (2 GiB low +
14 GiB high = 16 GiB). The KernelSnitch window already covers that bank, but the dm walk bound
was not updated. The failing `phys` values (`0xb57784000` is
45.38 GiB) are inside the 16GB bank, above the old bound.

The earlier retire-page workaround (`prepare_dm_page()` retiring the page instead of pinning
it) made the stage retry with a different page, but it still discarded valid 16GB pages. This
change removes the mismatch at its source.

## Change

The upstream source moves `DIRECT_MAP_END` from phys `0xAB0000000` (the 12GB bank bound) to
`0xB80000000` (the 16GB bank top) in all 18 target headers. 12GB parts are unaffected: they have
no pages above `0xA80000000`, so the wider bound only changes where a genuinely bogus alias is
rejected.

The app does not carry the exploit source. The six rebuilt payloads are committed under
`h80gt/exploits/<firmware>/exploit` and pinned in `payloads.conf` by size and SHA-256;
`prepareH80gtPayload` uses them in preference to the bundle's copies, while the shared set
(module, tools, loader) still comes from the pinned bundle.

## Device evidence (2026-10-30)

Device: HONOR 80 GT `AGT-AN00`, MagicOS `9.0.0.230`, kernel
`5.10.236-android12-9-gd671cf8c2b02`, 16GB (`MemTotal` 15,665,584 kB).

Build: `GhostLock-v1.2(4)-arm64-v8a-debug.apk`, 36,792,057 bytes, sha256
`883354e3caf8368e3f1c1f32f164b044179e90a17516fa775cacbcddc4064ede`. Clean boot (no `kernelsu`
module, SELinux enforcing), Shizuku running, app path, single run:

```text
dm: anon=0x77a9e1c000 phys=0xae57d1000 base(dm)=0xffffff8a657d1000 ...
sysctl walk hijack attempt=1 base=ffffff8a657d1000 ...
[+] sysctl walk hijack landed attempt=1
...
KernelSU module loaded
=== re-enable SELinux enforcing (last step) ===
```

The first page is `phys=0xae57d1000` (43.6 GiB), above the old bound and inside the 16GB bank;
the walk accepted it on attempt 1 with no `SKIP out-of-ram` (the run's `gl.log` has zero such
lines). All eight walks landed on attempt 1. The chain completed with the module live,
`getenforce` back to `Enforcing`, app result `H80GT result: ready stage=- reboot=false`, and
manager handoff to `me.weishu.kernelsu`.

- run dir: `/data/local/tmp/ksu_run_20261030_040257`
- app archive: `/sdcard/Download/ghostlock-debug-log/20261030-040256`
- payload verify: `verifyH80gtPayload` — 6 payloads / 13 unique artifacts

Limitation: the exact 16GB top is derived from the capacity (2 GiB low + 14 GiB high), the
KernelSnitch window and the observed pages; the boot above confirms the band is walkable but does
not by itself pin the boundary. A `/proc/iomem` dump from a 16GB unit would confirm it.

## Credits and license

The exploit and target offsets are from GhostLock-H80GT, Apache-2.0; this change only moves
the `DIRECT_MAP_END` bound. KernelSU, Magisk, Shizuku and the other components keep their own
licenses. See [NOTICE.md](../../NOTICE.md) for attribution and the full list of modifications.
