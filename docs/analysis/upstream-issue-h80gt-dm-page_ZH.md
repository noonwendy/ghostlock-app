# 上游问题：`prepare_dm_page()` 可能钉住一个越过 `DIRECT_MAP_END` 的页，卡死 sysctl 阶段

**项目：** GhostLock-H80GT（本 fork 随包托管的 H80GT 专用链）。
**观察到的构建：** v0.3.1 发版包（`ghostlock.tar.gz`
`a5a3cff2c3172bd5debe2a4e4f71c69402c00c8cffd37906b1354437b4c8810e`），target
`annap-AGT-AN00_9.0.0.230`，设备 荣耀 80 GT `AGT-AN00`，MagicOS `9.0.0.230`，
内核 `5.10.236-android12-9-gd671cf8c2b02`，16 GB。

## 症状

某些启动里 exploit 走到 sysctl 走查后无法推进：

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

同一类启动也会更早在 `sig_enforce` 走查上以完全相同的 `SKIP out-of-ram` 失败。由于劫持已经落地，
失败后 `boot_id` 仍被重定向，必须重启。本机连续三次启动都这样失败，分别卡在 `sig_enforce`、
`owner`、`restore-fail`，共同点是 `base(dm) >= 0xffffff8a30000000`。

## 原因

`exploit/src/util.c: prepare_dm_page()` 接受一页只检查两个条件——plant/phys oracle 稳定，以及
（设置 `W_VALUE_DM` 时）别名的 bit 16 为 1：

```c
if (base && (!getenv("W_VALUE_DM") || (base & 0x10000)))
  break;  // good page
```

而 `exploit/src/sysctl.c: sysctl_do_walk()` 又独立拒绝所有越过 target 记录 RAM 顶的尝试：

```c
if (page_base >= DIRECT_MAP_END) {
  pr_info("sysctl walk %s attempt=%d SKIP out-of-ram base=%016zx\n", ...);
  continue;
}
```

本 target 的 `DIRECT_MAP_END` 是 `0xffffff8a30000000`（`target.h` 记录的物理顶 `0xAB0000000`）。
当分配器给出高于该顶的页（观察到 `phys` 为 `0xac535f000`、`0xb57784000`、`0xabecac000`）时，
`prepare_dm_page()` 仍会返回它，`g_dm_page` 在整个进程生命周期里持续复用，于是全部 40 次 walk
（`SYSCTL_ROUTE_MAX_ATTEMPTS`）都在同一个不可用别名上空转。该阶段永远无法成功，permissive 之后的
恢复又被跳过，`ctl.data` 保持被劫持。

## 建议修法（已本地验证）

把越界页按 bit16 那样淘汰掉，让下一轮分配不同的物理页：

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

用 NDK r29（`-static -O2`）编译，2,221,760 字节，sha256
`1a67ba9cd46ba3113d3a3515f8384cab7013927b510b82a6ace947d94298077d`，在同一设备上运行：

- 干净启动、页在界内：模块加载、`Enforcing` 恢复、`boot_id` 未变；
- 干净启动、第一页越界：`dm: base 0xffffff8a47668000 past DIRECT_MAP_END … retiring page, taking a
  fresh one (round 0)`，随后 `KernelSU module loaded`、`Enforcing`、`boot_id` 可读。

第二次是决定性证据：它正好是打补丁前必然失败的条件。

## 备注

- 守卫本身是对的；只需让 `prepare_dm_page()` 的接受条件与它一致。
- 直接抬高 `DIRECT_MAP_END` 不对：观察到的越界别名（16 GB 设备上 43–48 GB）不是可用 RAM，而是
  oracle 给出的无效值。
- 完整日志、三次失败与两次补丁运行记录见本仓库 `docs/analysis/device-gates/H80GT-02_ZH.md`。
