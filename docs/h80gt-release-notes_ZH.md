# 发布说明 — H80GT 第二批（fork）

**产物：** `GhostLock-v1.2(1)-arm64-v8a-debug.apk`，36,443,696 字节，
sha256 `c793f7bea6811b0639ab70a8961cb86e4b87de927578793a1c924fe546410804`。
**基础：** 上游 GhostLock（native 引擎未改动）+ GhostLock-H80GT v0.3.1 发版包
（`ghostlock.tar.gz` `a5a3cff2…c8810e`）。

## 这一版加了什么

| 方面 | 第一批 | 第二批 |
|---|---|---|
| 固件 | 1 个（`9.0.0.230`） | **6 个**——GhostLock-H80GT 标注为真机验证过的全部固件 |
| profile 匹配键 | 内核串 + 机型 + 构建指纹 | **只按内核串**，与 H80GT 自己的清单一致；机型/指纹字段仍保留可用，但随包 profile 不声明 |
| 载荷布局 | 每固件一个目录、8 个文件 | 每固件**只放 exploit** + 共享去重集（模块、用户态工具、loader 模板） |
| exploit 二进制 | 上游 v0.3.1 | **fork 补丁**：`prepare_dm_page()` 淘汰越界页（见[问题报告](analysis/upstream-issue-h80gt-dm-page_ZH.md)）；6 个全部用 NDK r29 重编 |
| 清单 | schema v1、单载荷 | schema v2、`shared` 段、6 个载荷、13 个唯一产物 |
| native 引擎 | 未变 | 未变（无 GLK1/路由/native 改动） |

支持的内核（`uname -r`）：

| 固件 | 内核 |
|---|---|
| MagicOS 8.0.0.128 | `5.10.168-android12-9-g804a21a522f0` |
| MagicOS 8.0.0.160 | `5.10.209-android12-9-g516fa23f87fc` |
| MagicOS 9.0.0.157 | `5.10.209-android12-9-ga3ff7ae30334` |
| MagicOS 9.0.0.200SP1 | `5.10.236-android12-9-gd1575ab10b30` |
| MagicOS 9.0.0.220SP2 | `5.10.236-android12-9-ga88335e8db40` |
| MagicOS 9.0.0.230 | `5.10.236-android12-9-gd671cf8c2b02` |

不需要、不保存、也不比较序列号、账号、机型名或构建指纹。

## 产物与许可证

每个载荷的文件、大小、SHA-256、许可证与可复现构建命令都固定在
`app/src/main/assets/h80gt/payloads.conf`（schema v2）。摘要：

| 角色 | 文件 | 许可证 | 说明 |
|---|---|---|---|
| exploit | 每固件 `exploit`（约 2.2 MB × 6） | Apache-2.0 | GhostLock-H80GT，按 target 编译 |
| 模块 | `kernelsu.ko` | GPL-2.0 | KernelSU v3.3.0 + H80GT 的 3 个补丁，用荣耀内核树编译 |
| loader | `load_ko`、`kmsg_dumper` | GPL-2.0 | H80GT `ksu/tools` |
| 策略 | `magiskpolicy` | GPL-3.0 | 未修改的 Magisk 二进制（topjohnwu/Magisk） |
| 策略 | `ksu_rules` | GPL-2.0 | H80GT 策略规则 |
| 用户态 | `ksud` | GPL-2.0 | KernelSU 管理器发行版 `lib/arm64-v8a/libksud.so` |
| 模板 | `ksu_loader.tmpl` | GPL-2.0 | 每次运行时渲染（断言无残留 `@TOKEN@`） |

二进制**不**入库；`prepareH80gtPayload` 从固定发版包搬运，`verifyH80gtPayload`（以及应用、
Shizuku 服务）逐字节核对清单。缺失或不符时拒绝运行，不会执行。

## 构建

```sh
# 主机：JDK 21、Android SDK（platform 37.2、build-tools 36.0.0）、NDK r29、PATH 里有 Git 的 sh
./gradlew exportKernelProfiles            # 可选：导出器检查
./gradlew :profile-core:test :app:testDebugUnitTest
./gradlew :app:assembleDebug              # 搬运+校验载荷，产出 APK
```

`-Pghostlock.h80gt=off` 可构建不带载荷的 APK；此时应用会在预检阶段拒绝 `engine = "h80gt"`，
不会运行任何东西。

## 验证状态

| 检查 | 结果 |
|---|---|
| `:profile-core:test` | 26/26 |
| `:app:testDebugUnitTest` | 129 个测试、124 通过、2 个既有基线失败（`ExporterAgreementTest`、`ProfileMigrationEquivalenceTest`，干净检出上同样失败） |
| `verifyH80gtPayload` | 6 个载荷 / 13 个唯一产物全部一致 |
| APK 内容抽查 | 所有载荷条目都在；`kernelsu.ko` 与清单哈希一致 |
| `9.0.0.230` 应用路径门禁（未打补丁的上游二进制） | **失败 3/3**——上游 `prepare_dm_page()` bug（见 [H80GT-02](analysis/device-gates/H80GT-02_ZH.md)） |
| `9.0.0.230` 应用路径门禁（**本构建**，补丁载荷） | **通过 2/2**——两次启动都碰到越界页仍完成；模块加载、`Enforcing` 恢复、管理器自动打开 |
| 第一批门禁（同一条链，2026-10-29） | **通过**——从界面走完 8 个阶段 |

## 已知限制

1. **先授权应用。** 本会话没有首次请求弹框：未列入的应用 `su` 返回 `ENOENT`。到 超级用户 →
   应用 → App Profile → 打开「超级用户」，之后 `su -c id` 返回 `uid=0 … u:r:ksu:s0`。
2. **Shizuku 必须活着。** ADB 启动的 Shizuku 不跨重启。本机自带的 `start.sh` 可能静默失败；
   用 `CLASSPATH` + `LD_LIBRARY_PATH`（指向应用解包出的 `lib/arm64`）直接拉 `app_process` 可用。
3. **失败即需重启。** permissive 阶段之后的失败会让 `boot_id` 保持被重定向，只有模块 init 能恢复。
   应用会标记 `reboot=true` 并在本次开机内拒绝再跑；搬运/启动之前的失败不需要重启。
4. **上游 exploit 可靠性（本构建已修）。** 未打补丁时，`9.0.0.230` 的应用路径三次尝试都失败在
   exploit 的物理页走查（`SKIP out-of-ram`）。本 fork 在全部 6 个 exploit 里带上一条条件式修复：
   直接映射别名越过 `DIRECT_MAP_END` 的页会被淘汰而不是被钉住。根因、日志与 diff 见
   [上游问题报告](analysis/upstream-issue-h80gt-dm-page_ZH.md)。
5. **其余 5 个固件只经 H80GT 验证，未经本集成门禁。** 只有 `9.0.0.230` 走过本集成的真机门禁；
   其余固件运行同一套集成代码，但本项目没有在真机上验证过。

## 升级与回退

- 覆盖安装保留应用数据；每次运行都会重新校验载荷。
- 应用除了自己的偏好设置、`/data/local/tmp/ksu_run_*`（`shell` 所有）每次运行的临时目录、以及
  `Download/ghostlock-debug-log/` 里的调试归档之外，不在设备上持久化任何东西。
- 卸载模块用 `ksud unload`（需要 root）或重启；应用自身从不卸载模块。
