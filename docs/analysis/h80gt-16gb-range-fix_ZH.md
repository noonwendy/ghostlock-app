[English](h80gt-16gb-range-fix.md) | 简体中文

# H80GT 16GB dm 范围修复（随包 payload 改动）

日期：2026-10-30。范围：随包的六个 `exploit` payload。上游：
[GhostLock-H80GT](https://github.com/yakidango-official/GhostLock-H80GT)。

> **取代：** [upstream-issue-h80gt-dm-page_ZH.md](upstream-issue-h80gt-dm-page_ZH.md) 里的
> retire 方案，以及 [H80GT-02](device-gates/H80GT-02_ZH.md) 的载荷记录。那两份文档描述的是
> 早期的尝试；随包载荷使用下面的范围修复。

## 现象

16GB 的 `AGT-AN00` 上，有些启动会在 sysctl 阶段、第一次 walk 之前就失败：`prepare_dm_page()`
返回的 dm 页别名达到或超过 `DIRECT_MAP_END`，`sysctl_do_walk()` 于是每次都以 `SKIP out-of-ram`
跳过：

```text
dm: anon=0x7b4339c000 phys=0xb57784000 base(dm)=0xffffff8ad7784000 ...
sysctl walk owner attempt=1 SKIP out-of-ram base=ffffff8ad7784000
sysctl walk owner attempt=2 SKIP out-of-ram base=ffffff8ad7784000
...
sysctl ctl.data restore failed (boot_id stays hijacked; ... reboot to clear)
sysctl stage failed
```

## 根因

`DIRECT_MAP_END` 原来是 `0xffffff8a30000000`（phys `0xAB0000000`），是 **12GB** 版本的 bank 边界。
荣耀 80 GT 还有 16GB 版本，其高 bank 延伸到 phys `0xB80000000`（2 GiB low + 14 GiB high = 16 GiB）。
KernelSnitch 窗口已经覆盖这段，但 dm walk 的边界没有跟着改。失败启动里
的 `phys`（`0xb57784000` 是 45.38 GiB）就在 16GB bank 内，高于旧边界。

早期的 retire 方案（`prepare_dm_page()` 发现越界就淘汰该页、不再钉住）能让阶段换页重试，但它仍然
丢弃了合法的 16GB 页。这次改动直接从源头消除了不一致。

## 改动

上游源码把 `DIRECT_MAP_END` 从 phys `0xAB0000000`（12GB bank 边界）改到 `0xB80000000`（16GB bank 顶），
涉及全部 18 个 target.h。12GB 版本不受影响：它没有高于 `0xA80000000` 的页，放宽边界只会改变“真正越界
的坏别名”在哪里被拒绝。

App 仓库不携带 exploit 源码。六个重新构建的 payload 已提交在
`h80gt/exploits/<firmware>/exploit`，并在 `payloads.conf` 里按大小和 SHA-256 固定；
`prepareH80gtPayload` 优先使用它们而不是 bundle 里的副本，共享集（模块、工具、loader）仍从 pin 的
bundle 取。

## 真机证据（2026-10-30）

设备：荣耀 80 GT `AGT-AN00`，MagicOS `9.0.0.230`，内核
`5.10.236-android12-9-gd671cf8c2b02`，16GB（`MemTotal` 15,665,584 kB）。

构建：`GhostLock-v1.2(4)-arm64-v8a-debug.apk`，36,792,057 字节，sha256
`883354e3caf8368e3f1c1f32f164b044179e90a17516fa775cacbcddc4064ede`。干净启动（无 `kernelsu`
模块、SELinux enforcing）、Shizuku 运行、走 app 路径，单次运行：

```text
dm: anon=0x77a9e1c000 phys=0xae57d1000 base(dm)=0xffffff8a657d1000 ...
sysctl walk hijack attempt=1 base=ffffff8a657d1000 ...
[+] sysctl walk hijack landed attempt=1
...
KernelSU module loaded
=== re-enable SELinux enforcing (last step) ===
```

首页 `phys=0xae57d1000`（43.6 GiB）在旧边界之外、16GB bank 之内；walk 在 attempt=1 直接接受，没有
`SKIP out-of-ram`（这次运行的 `gl.log` 里该行计数为 0）。八个 walk 全部 attempt=1 landed。整链完成：
模块 live、`getenforce` 回到 `Enforcing`、app 结果 `H80GT result: ready stage=- reboot=false`、
manager handoff 到 `me.weishu.kernelsu`。

- run dir：`/data/local/tmp/ksu_run_20261030_040257`
- app 归档：`/sdcard/Download/ghostlock-debug-log/20261030-040256`
- payload 校验：`verifyH80gtPayload` — 6 payload / 13 unique artifact

限制：16GB 的精确 top 是从容量（2 GiB low + 14 GiB high）、KernelSnitch 窗口和观察到的高位页推出来的；
上面的启动确认了这段可被正常 walk，但没有单独钉死边界值。16GB 机器的 `/proc/iomem` 可以最终确认。

## 致谢与许可

exploit 与 target offset 来自 GhostLock-H80GT，Apache-2.0；本次只移动了 `DIRECT_MAP_END` 边界。
KernelSU、Magisk、Shizuku 等组件各自保留原许可证。归属和完整改动列表见
[NOTICE.md](../../NOTICE.md)。
