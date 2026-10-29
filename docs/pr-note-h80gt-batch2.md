# PR：H80GT 专用引擎（第二批）——六固件、可选指纹、补丁版 exploit

`feat(h80gt): HONOR 80 GT specialized engine with six firmware payloads`

## 目的

让荣耀 80 GT（`AGT-AN00`）用户在上游 GhostLock 里**选一个配置、点一次执行**就完成越狱，
不需要电脑、终端或手打脚本。上行攻击管线（native/GLK1/route）不做任何改动；H80GT 的专用链作为
第二个执行引擎被托管进来。

## 内容

### 选择与配置

- profile 新增两个 Kotlin-only 顶层键：`engine = "native" | "h80gt"` 与 `h80gt { payload, firmware,
  manager_version, device?, fingerprint? }`。
- 选择只依据内核串（`uname -r`），与 GhostLock-H80GT 自己的清单一致；`device`/`fingerprint` 是
  可选收紧项，随包 profile 都不声明——不携带、不比较任何设备唯一数据。
- 未声明字段不参与比较，已声明字段仍然严格执行；未知 `engine`、半 native profile、缺失必填字段
  一律 fail-closed。H80GT profile 不产生 GLK1 文档，任何代码路径都不会把它交给 `libghostlock.so`。

### 载荷（schema v2）

- 6 个 H80GT 已验证固件：`8.0.0.128`、`8.0.0.160`、`9.0.0.157`、`9.0.0.200SP1`、`9.0.0.220SP2`、
  `9.0.0.230`。
- 每固件只带自己的 exploit；`kernelsu.ko`、`load_ko`、`kmsg_dumper`、`magiskpolicy`、`ksud`、
  `ksu_rules`、`ksu_loader.tmpl` 作为共享集只存一份（6 载荷共 13 个唯一产物）。
- 每个产物在 `payloads.conf` 里固定大小、SHA-256、许可证、来源与复现命令；构建时、打包后、每次
  运行前各校验一次，不符即拒绝运行。

### exploit 补丁（本 PR 的关键修复）

上游 `exploit/src/util.c: prepare_dm_page()` 会接受一个直接映射别名越过 `DIRECT_MAP_END` 的页；
`sysctl_do_walk()` 又会跳过这种页，且该页从不被淘汰，于是这类启动上整条 sysctl 阶段空转 40 次后
失败（`SKIP out-of-ram` → `sysctl stage failed`，若劫持已落地则 `boot_id` 残留重定向）。本 PR
在 `prepare_dm_page()` 中把越界页与 bit16 一样淘汰，6 个逐固件 exploit 用 NDK r29
（`-static -O2`）重编并固定新哈希。根因、日志与 diff 见
`docs/analysis/upstream-issue-h80gt-dm-page.md`。

### 应用侧

- Shizuku UserService 新增 `runH80gt`（AIDL id 4），执行 8 阶段：
  预检 → 搬运 → 启动 → 提权 → 模块 → 用户态 → 恢复 → 管理器交接。
- 失败即失败：启动之后任何失败标记 `needs_reboot`，本次开机内拒绝再跑；从不接管 exploit 的生死。
- 成功后自动前台打开匹配的管理器（版本不匹配则拒绝交接）。
- UI 展示逐阶段状态与失败原因；调试归档保留本次 profile/payload 与链日志。

## 验证

| 项目 | 结果 |
|---|---|
| `:profile-core:test` | 26/26 |
| `:app:testDebugUnitTest` | 129 项、124 通过，2 项为既有基线失败（`ExporterAgreementTest`、`ProfileMigrationEquivalenceTest`，干净检出同样失败） |
| `verifyH80gtPayload` | 6 载荷 / 13 唯一产物全部一致 |
| APK | `GhostLock-v1.2(1)-arm64-v8a-debug.apk`，36,443,696 字节，sha256 `c793f7bea6811b0639ab70a8961cb86e4b87de927578793a1c924fe546410804`；包内载荷与清单逐一相符 |
| 真机（`9.0.0.230`，**应用路径**，补丁载荷） | **2/2 干净启动 PASS**：两次启动都出现越界页（分别弃 3 页、1 页），模块加载、`Enforcing` 恢复、管理器自动打开、应用结果 `ready` |
| 真机（同设备，未打补丁） | 3/3 失败于上述上游 bug（历史记录，见 `H80GT-02`） |
| 批次一门禁 | PASS（2026-10-29，同一条链走完 8 个阶段） |

证据：`docs/analysis/device-gates/H80GT-01*.md`、`H80GT-02*.md`。

## 兼容性 / 风险

- native 代码、GLK1、route、既有 profile、loader 渲染与恢复契约均未改动。
- 其余 5 个固件只经 H80GT 验证，未经本项目的真机门禁；它们运行同一套集成代码并携带同一个补丁。
- 二进制载荷不入库；构建时从固定发版包（或本 fork 生成的 `bundle-patched`）搬运。
- 失败后必须重启（`boot_id` 可能被劫持），这是链的物理约束，文档与 UI 都已写明。

## 复核建议

1. `./gradlew :profile-core:test :app:testDebugUnitTest`；
2. `./gradlew :profile-core:verifyH80gtPayload`；
3. `./gradlew :app:assembleDebug` 后用 `H80gtPayloadCatalog` 的哈希核对包内字节；
4. 真机：安装管理器 3.3.0、启动 Shizuku、选配置、执行；成功后到管理器给应用授权并验证 `su -c id`。

## 后续（不在本 PR）

- 把同一补丁提交给 GhostLock-H80GT 上游（本 PR 已附问题报告与 diff）；
- 其余 5 个固件的真机门禁记录；
- 未验证固件的运行前确认弹窗（本批只随包 `verified` 载荷，因此未启用）。
