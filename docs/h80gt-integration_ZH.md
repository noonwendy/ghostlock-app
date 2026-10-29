简体中文 | [English](h80gt-integration.md)

# H80GT 接入：使用说明与边界

本文是 `engine = "h80gt"` 专用支持的操作说明，只覆盖一个设备组合。设计与证据见
[`h80gt-integration-plan_ZH.md`](analysis/h80gt-integration-plan_ZH.md)。

## 它做什么

GhostLock 随包提供一份荣耀 80 GT 的 profile，并从应用内驱动整条 H80GT 链：

1. profile（`engine = "h80gt"`）选择专用引擎并锁定精确目标。
2. 应用在运行前核对设备、profile 与随包载荷。
3. Shizuku UserService 把载荷集搬运到 `/data/local/tmp/ksu_run_<stamp>`，逐字节核对随包目录，
   渲染项目权威 loader，分离式启动 exploit 并监管整条链。
4. 模块、KernelSU 用户态与安全状态都核验通过后，应用前台打开匹配的 KernelSU 管理器。

首次 Shizuku 激活之后不需要电脑，也不需要手工输入任何 shell 命令。

## 支持的目标

选择只依据内核串——与 GhostLock-H80GT 自己的规则一致。应用拿设备 `uname -r` 与随包 profile 比对，
不需要也不携带机型名、系统指纹或其他设备唯一数据。

| 固件 | 内核（`uname -r`） |
|---|---|
| MagicOS `8.0.0.128` | `5.10.168-android12-9-g804a21a522f0` |
| MagicOS `8.0.0.160` | `5.10.209-android12-9-g516fa23f87fc` |
| MagicOS `9.0.0.157` | `5.10.209-android12-9-ga3ff7ae30334` |
| MagicOS `9.0.0.200SP1` | `5.10.236-android12-9-gd1575ab10b30` |
| MagicOS `9.0.0.220SP2` | `5.10.236-android12-9-ga88335e8db40` |
| MagicOS `9.0.0.230` | `5.10.236-android12-9-gd671cf8c2b02` |

这就是 GhostLock-H80GT 标注为真机验证过的 6 个固件，全部对应荣耀 80 GT（`AGT-AN00`）。模块是
H80GT 定制的 `kernelsu.ko`（KernelSU `v3.3.0`），管理器为 `3.3.0`。

六个 exploit 载荷从 GhostLock-H80GT 重新构建，带 [16GB dm 范围修复](analysis/h80gt-16gb-range-fix_ZH.md)；
来源与许可见 [NOTICE.md](../NOTICE.md)。

其他内核串会在搬运前被拒绝，并在消息里给出不符的值。其中只有 `9.0.0.230` 另外跑过本集成的门禁
（[`analysis/device-gates/H80GT-01_ZH.md`](analysis/device-gates/H80GT-01_ZH.md)）；其余 5 个是
H80GT 项目的验证结论，不是本集成的验证结论。

## 前置条件

1. **已安装 Shizuku 且服务在运行。** 首次需通过 ADB 激活
   （`adb shell sh /sdcard/Android/data/moe.shizuku.privileged.api/start.sh`，或用 Shizuku 应用的
   无线调试流程）。应用申请 Shizuku 权限时请授予。链以 shell 身份运行，应用自身做不到。
2. **已安装 KernelSU 管理器 `3.3.0`。** 不同的管理器构建会把已加载的模块显示成“未安装”——管理器
   会校验内核侧版本。GhostLock 不会打开它无法确认的管理器。
3. **设备状态干净**：没有已加载的 KernelSU 模块（`/proc/modules` 无 `kernelsu`）、SELinux 处于
   强制模式、`/data` 至少 64 MiB 可用。
4. **先备份。** 链会改写内核内存；失败的尝试可能让设备重启。

## 运行

1. 安装 APK。
2. 打开 GhostLock。内核匹配时会自动选中 H80GT profile；否则在**内置配置**里选择
   （`MagicOS 9.0.0.230 · AGT-AN00`），或导入 profile 文件。
3. 确认 Shizuku 状态卡显示就绪——该引擎必须使用 Shizuku。
4. 点击**运行**，并保持应用在前台。

执行面板会显示阶段与实时日志：

| 阶段 | 含义 |
|---|---|
| 预检 | 设备/载荷/管理器/状态检查 |
| 搬运产物 | 复制到运行目录并校验哈希 |
| 启动 | 停止管理器，分离式启动 exploit |
| 提权 | exploit 取得 uid 0 并翻转 `sig_enforce` |
| 内核模块 | `/proc/modules` 出现 `kernelsu` |
| 用户态 | `ksud` 各阶段已执行 |
| 状态恢复 | 恢复 enforcing、`sig_enforce`、卸载伪 kallsyms、`boot_id` 可读 |
| 管理器交接 | 打开了匹配的管理器 |

成功意味着八个阶段都显示“已完成”，并且日志以 `result: h80gt ready` 结束。

## 失败时

H80GT 链在模块恢复之前会让内核 `boot_id` 指针保持被劫持状态。因此：

- **启动阶段及之后的任何失败都需要重启。** 应用会明确提示、记录本次运行，并在同一次开机内拒绝
  下一次尝试。
- **不要重跑第二次。** 运行进行中、链仍存活、已有模块加载时，应用都会阻断。
- **不要杀链进程**（不要强制停止 GhostLock 的子进程）。杀掉停驻的 waiter 可能让内核 panic；应用
  自身也永不杀它。
- 失败的尝试可能自行重启设备。设备回来后重跑一次即可。

| 提示 | 处理 |
|---|---|
| 上次尝试未恢复到干净状态 | 重启设备后再运行 |
| 已有 KernelSU 模块加载 | 重启；旧模块会伪造成功 |
| 上一条 H80GT 链仍在运行 | 重启后再运行 |
| SELinux 未处于强制模式 | 重启并确认设备状态 |
| 载荷缺失/不完整 | 重新安装带载荷的 APK（`prepareH80gtPayload`） |
| 与 H80GT 目标不符 | 该 profile 属于其他机型/固件/内核 |
| 无兼容管理器/版本不符 | 安装 `3.3.0` 管理器并选中 |

## “就绪”不能证明什么

应用能证明模块已加载、用户态阶段已执行、安全状态已恢复，但读不到管理器界面。交接后请在管理器里
确认：

- 状态卡显示 `工作中 <LKM>`（不是“未安装”）；
- 设置入口与超级用户/授权列表可以进入；
- 普通应用能够申请并获得 root（无破坏性检查）。

如果模块已加载而状态卡仍显示“未安装”，说明管理器版本与模块的 KernelSU 版本不符；安装 `3.3.0`
管理器。

## 真机已验证（2026-10-29）

上表的目标组合已在参考设备上端到端跑通，证据见
[`analysis/device-gates/H80GT-01_ZH.md`](analysis/device-gates/H80GT-01_ZH.md)。运行前值得知道：

- **管理器保持在 `3.3.0`。** 管理器会提示有新版本，升级会重新引入上文所述的版本不匹配。
- **本固件的 shell 身份读不到 `sig_enforce` 与 `kptr_restrict`。** 因此应用的恢复校验依赖 SELinux
  强制模式、模块在列、`/proc/kallsyms` 不再被 bind-mount、`boot_id` 稳定，恢复值以 loader 日志为
  证（`sig_enforce=Y`、`kptr restored=2`）。
- **应用要显式授权；本会话没有“首次请求弹框”。** 未获授权的应用 `su` 返回 `ENOENT`，管理器也不会
  弹框。请到 超级用户 → 选中应用 → App Profile → 打开「超级用户」开关；该应用下一次 `su -c id`
  即返回 `uid=0(root) … context=u:r:ksu:s0`。MT 管理器就是现成例子（它的 `mtio` 守护进程以 root
  运行）。

## Shizuku 启动不了时

Shizuku 服务端可能在启动时崩溃，报
`UnsatisfiedLinkError: dlopen failed: … base.apk!/lib/arm64-v8a/librish.so`，此时 GhostLock 会把
Shizuku 显示为未运行（或显示需要授权但实际不可用）。这属于 Shizuku/平台问题，不是 GhostLock 的
问题。重装同一官方 APK（`adb install -r shizuku-*.apk`，或对已安装 APK 路径执行 `pm install -r`）
后重新运行 `start.sh`；管理器会显示“Shizuku 正在运行”，GhostLock 即可申请权限。

## 证据

每次运行都会写入 `Download/ghostlock-debug-log/<时间>/`：

- `ghostlock-h80gt-*.log` —— 阶段与转发的 exploit/loader 输出；
- `profile.conf` —— 本次实际生效的 profile；
- `payload.conf` —— 载荷 id、来源、许可证与每个产物的 SHA-256。

设备上保留运行目录 `/data/local/tmp/ksu_run_<stamp>/`（`gl.log`、`ksu_load.log`、
`kmsg_cap.txt`、`stage_*.log`），直到下一次运行清扫。恢复 enforcing 之后 shell 可能读不到这些
文件，因此应用是实时转发的。

## 构建带载荷的 APK

六个逐固件 exploit 已提交在 `h80gt/exploits/`；共享集（模块、工具、loader）构建时从固定发版包搬运：

```sh
./gradlew :app:assembleDebug            # 缺省 ../release/bundle
./gradlew :app:assembleDebug -Pghostlock.h80gt=/path/to/bundle
./gradlew :profile-core:verifyH80gtPayload   # 大小 + SHA-256 校验
```

`-Pghostlock.h80gt=off` 生成不带载荷的构建；此时应用在预检阶段拒绝 `engine = "h80gt"`。每个产物、
来源与许可证见 `app/src/main/assets/h80gt/payloads.conf`。

## 不在范围内

- 刷写、永久 root、OTA 处理、自动安装模块、开机持久化。
- 其他机型、其他 MagicOS 版本、其他 KernelSU 分支。
- 失败后自动重试（重启是契约的一部分）。
