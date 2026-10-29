简体中文 | [English](H80GT-01.md)

# H80GT-01 真机门禁：从 GhostLock 界面跑通专用链（engine = h80gt），PASS

工作树 `437b3b2` + 未提交的 H80GT 批次（设计见
[`../h80gt-integration-plan_ZH.md`](../h80gt-integration-plan_ZH.md)）。
受测 APK：`GhostLock-v1.2(1)-arm64-v8a-debug.apk`，30,967,655 字节，SHA-256
`013139c05ee56fdb507ef5832cd82074766ebad0c2fe1e907b591b728d36b741`
（仓库是浅克隆，versionCode 为 1；该构建为全新安装）。

## 设备与入口

| 项目 | 值 |
|---|---|
| 机型 | 荣耀 80 GT，`AGT-AN00`（`Build.MODEL`、`getprop ro.product.model`） |
| 固件 | MagicOS `9.0.0.230`（`ro.build.display.id` = `AGT-AN00 9.0.0.230(CHNC00E230R2P5)`） |
| 选择依据 | 仅内核串（`uname -r`），与 GhostLock-H80GT 自己的清单一致；不比较也不记录任何设备唯一数据 |
| 内核 | `5.10.236-android12-9-gd671cf8c2b02` |
| 入口 | GhostLock 界面，H80GT profile 自动选中（**执行** 按钮），`engine = "h80gt"` |
| shell 身份 | Shizuku 13.6.0 管理器，服务端 13.5 经 ADB 启动（`start.sh`），已授予 GhostLock 权限 |
| 管理器 | `me.weishu.kernelsu` `v3.3.0`（versionCode 32601） |
| 前置状态 | SELinux `Enforcing`，`/proc/modules` 无 `kernelsu`，uptime 108814 秒，`boot_id` 可读，且整轮前后未变 |
| 本固件下 shell 读不到 | `/sys/module/module/parameters/sig_enforce` 与 `/proc/sys/kernel/kptr_restrict` 对 uid 2000 拒绝（见“改动说明”） |

## 结果

运行时间 `2026-10-29 23:54:19` → `/data/local/tmp/ksu_run_20261029_235420`。

| 检查 | 证据 |
|---|---|
| 八个阶段全部完成 | App 执行面板：预检 / 搬运产物 / 启动 / 提权 / 内核模块 / 用户态 / 状态恢复 / 管理器交接 全部 `已完成` |
| 产物搬运与校验 | 运行目录内 8 个产物大小与清单一致；`ksu_loader.sh` 由 11457 字节模板渲染为 11449 字节 |
| exploit | `gl.log`：`sysctl-route-ok`；loader：`sig_enforce=N after 3s` |
| `hisecd` 窗口 | `hisecd frozen pid=1884` → `hisecd resumed pid=1884` |
| 策略注入 | `policy injected OK (ctx=u:r:kernel:s0)` |
| 模块加载 | `load_ko_exit=0`；`kernelsu 212992 0 - Live 0xffffffe4fcbd3000 (OE)` |
| 用户态 | `post-fs-data exit=0`、`services exit=0`、`boot-completed exit=0`、`install exit=0` |
| 状态恢复 | `panic_on_oops=1`、`sig_enforce=Y`、`umount OK`、`kptr restored=2`、`KernelSU module loaded`、`=== re-enable SELinux enforcing (last step) ===` |
| App 结论 | `<b> H80GT result: ready stage=- reboot=false`、`<k> result: h80gt ready`、`exit code=0` |
| 交接 | `<k> manager handoff: me.weishu.kernelsu` → 前台 `me.weishu.kernelsu/.ui.MainActivity` |
| 运行后状态 | 模块在列、`Enforcing`、`boot_id` 未变、无 `/proc/kallsyms` 挂载、`hisecd` 在跑、未重启（uptime 109350 秒） |
| 管理器状态 | `工作中 [越狱模式]`、版本 `32601-2`、`LKM`、管理器 `v3.3.0 (32601-2)`、内核与本机一致、SELinux 强制执行 |
| 管理入口 | 超级用户列表可渲染（搜索 + 每项 UMOUNT）；设置可渲染（检查更新、主题、传统 SU 开关） |
| 重复运行拒绝 | 第二次运行：`h80gt blocked: MODULE_ALREADY_LOADED`、`h80gt refused at preflight: MODULE_ALREADY_LOADED`、`H80GT result: refused stage=preflight reboot=false`；未搬运、无新运行目录 |
| 普通应用 root 请求 | **显式授权后 PASS**：未列入的应用首次 exec `su` 得到 `ENOENT` 且无弹框；在管理器 App Profile 页打开该应用的「超级用户」开关后，`su -c id` 返回 `exit=0 uid=0(root) gid=0(root) context=u:r:ksu:s0`。用户手动授权的 MT 管理器（`bin.mt.plus`）同样如此：两个 `mtio` 守护进程以 uid 0 运行 |

运行期间未出现 panic、重启或数据丢失。

### 本会话没有“首次请求弹框”：请在管理器里预先授权

late-load 会话下，未列入的应用 exec `su` 时 KernelSU **不会**询问，exec 在到达管理器之前就以
`ENOENT` 失败。授权是管理器侧的动作：

1. 超级用户标签页 → 搜索应用 → 进入 App Profile 页；
2. 打开「超级用户」开关；
3. 该应用下一次 `su` 即返回 `uid=0(root) … context=u:r:ksu:s0`。

因此“管理器显示 工作中 [越狱模式]”与“应用能拿到 root”是两个结果，但都成立：前者在首页卡片可见，
后者需要显式授权。MT 管理器此前已经有这个授权，所以它的 `mtio` 守护进程以 root 运行。授权之前的
失败是“没有授权 + 本会话不弹框”，而不是模块缺少 `su` 能力。

## 日志

设备归档：`/sdcard/Download/ghostlock-debug-log/20261029-235419/`

- `ghostlock-h80gt-0.log.txt`（15984 字节）—— 全部阶段与转发的 exploit/loader 输出；
- `payload.conf` —— 载荷 id、来源、发版包 SHA-256 与每个产物哈希；
- `profile.conf` —— 本次实际运行的 `engine = "h80gt"` profile。

设备运行目录：`/data/local/tmp/ksu_run_20261029_235420/`（`gl.log`、`ksu_load.log`、
`gl_anchor.log`、`ksu_runtime.env`、`kmsg_cap.txt`、`stage_*.log`）。

## 改动说明

本门禁覆盖整条专用路径：profile 选择、应用侧与 shell 侧 preflight、产物搬运与哈希校验、loader
渲染、分离式启动、阶段监管、H80GT 恢复契约、管理器交接与重复运行拒绝。

有两处行为是**因为**本次门禁而修改，并已被本记录覆盖：

1. `H80gtExecutor.verifyRestore` 不再把“读不到 `sig_enforce`”当作失败。荣耀拒绝 uid 2000 读取该
   项（以及 `kptr_restrict`），否则成功运行会被误报为 `failed_restore`；显式的 `false` 仍然失败，
   恢复值以 loader 日志为证（`sig_enforce=Y`、`kptr restored=2`）。
2. 拒绝原因现在会打开执行面板，而不只是弹 toast，失败阶段与原因在 toast 消失后仍可读（上面的
   重复运行检查用它取证）。

门禁执行前本机的 Shizuku 需要修复：服务端以
`UnsatisfiedLinkError: dlopen failed: … base.apk!/lib/arm64-v8a/librish.so` 崩溃，重装同一官方 APK
（`pm install -r`）后重新运行 `start.sh` 即恢复。这属于平台前置条件，不是集成的一部分，已记录在
使用说明中。
