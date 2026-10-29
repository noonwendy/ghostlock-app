# H80GT-02 — 第二批：六固件、可选指纹、打包复验

**日期：** 2026-10-30（首次 01:31，末次 01:59）。
**设备：** 荣耀 80 GT `AGT-AN00`，MagicOS `9.0.0.230`，内核
`5.10.236-android12-9-gd671cf8c2b02`，`MemTotal` 15,665,584 kB。
**受测构建：** `GhostLock-v1.2(1)-arm64-v8a-debug.apk`，36,443,696 字节，
sha256 `c793f7bea6811b0639ab70a8961cb86e4b87de927578793a1c924fe546410804`（内含补丁版 exploit 载荷）。
**范围：** 第二批——6 个 H80GT 已验证载荷进同一个 APK，选择只按内核串（`h80gt.device` /
`h80gt.fingerprint` 现为可选，随包 profile 都没有声明）。
**选择依据：** 仅 `uname -r`，与 GhostLock-H80GT 自己的清单一致；不比较也不记录设备唯一字符串。

## 构建与产物证据（主机侧）

| 检查 | 结果 |
|---|---|
| `:profile-core:test` | 26 个测试，0 失败 |
| `:app:testDebugUnitTest` | 129 个测试，2 个失败——均为既有基线失败（`ExporterAgreementTest`、`ProfileMigrationEquivalenceTest`，在干净工作树上同样失败） |
| `:profile-core:verifyH80gtPayload` | `6 payload(s), 13 unique artifact(s) verified`（6 个 exploit + 7 个共享） |
| APK 内容 | 6 × `assets/h80gt/payload/annap-AGT-AN00_*/exploit`、7 × `assets/h80gt/shared/*`、1 × `payloads.conf`、6 个 profile；第一批遗留在根目录的 `ksu_loader.tmpl` 已不再打包 |
| APK 哈希抽查 | `assets/h80gt/shared/kernelsu.ko` = `7b153982…5643037`（与清单一致） |
| 载荷搬运 | 6 个 exploit 都是**补丁版 fork 构建**（`prepare_dm_page()` 弃越界页，NDK r29 `-static -O2`）并与清单一致：8.0.0.128/160 为 `2219312` 字节，9.0.0.157/200SP1/220SP2/230 为 `2221760` 字节 |

6 个 profile（`8.0.0.128`、`8.0.0.160`、`9.0.0.157`、`9.0.0.200SP1`、`9.0.0.220SP2`、`9.0.0.230`）
都能解析成 `engine = "h80gt"`，无非法字段、不产生 GLK1 文档、不含机型/指纹绑定（`H80gtProfileTest`）。

## 真机尝试——应用路径（未修改的上游二进制）

三次都是干净启动（`MOD=0`、`Enforcing`）、Shizuku 服务在跑、管理器 `3.3.0`，从界面点执行。

| # | 时间 | 应用报告阶段 | exploit 日志特征 | 模块 |
|---|---|---|---|---|
| 1 | 01:31:08 | `failed at module`（300 秒） | `sysctl walk sig_enforce attempt=1..4 SKIP out-of-ram base=ffffff8a4535f000` | 未加载 |
| 2 | 01:43:46 | `failed at exploit` | `sysctl walk owner attempt=1..4 SKIP out-of-ram base=ffffff8ad7784000` → `ctl.data restore failed` | 未加载 |
| 3 | 01:51:45 | `failed at exploit`（loader 等 `sig_enforce` 超时） | `sysctl walk sig_enforce attempt=1..4 SKIP out-of-ram base=ffffff8a6819b000` | 未加载 |

每次都被标记 `reboot=true`、拒绝再次运行，并把证据归档到
`/sdcard/Download/ghostlock-debug-log/2026103*`，原始链日志在 `/data/local/tmp/ksu_run_2026*`。
搬运/启动/上报路径与批次一的 PASS 运行逐字节相同，应用对每次失败的分类都正确（没有把失败当成功，
也没有并发重跑）。

### 根因（上游 exploit，不是集成）

`exploit/src/util.c: prepare_dm_page()` 接受一页时用 `page_base = PAGE_OFFSET + phys`；
`sysctl.c: sysctl_do_walk()` 对 `page_base >= DIRECT_MAP_END`（本 target 记录为
`0xffffff8a30000000`，即物理顶 `0xAB0000000`）的尝试直接跳过。没有任何代码淘汰这一页，而
`g_dm_page` 会持续复用，于是当分配器给出高于记录 RAM 顶的页时，walk 会拿同一页空转最多 40 次，
该阶段永远无法推进。失败启动上观察到的 `phys`：`0xac535f000`、`0xb57784000`、`0xabecac000`；
批次一成功时是 `0xd7742000`（在界内）。

## 补丁版 exploit（本构建已随包）

一条条件式修复：在 `prepare_dm_page()` 中淘汰越界页（见
[`../upstream-issue-h80gt-dm-page_ZH.md`](../upstream-issue-h80gt-dm-page_ZH.md)）；6 个逐固件
exploit 全部用 NDK r29（`-static -O2`）重编并固定进清单。`9.0.0.230` 上两次干净启动、**从应用
界面**运行：

| # | 运行目录 | `gl.log` 里的淘汰行 | 结果 |
|---|---|---|---|
| 1 | `ksu_run_20261030_021400` | **3** 行（`base=ffffff8a74dd5000`、`ffffff8a77d89000` …） | `KernelSU module loaded`、`Enforcing` 恢复、管理器自动打开、应用 `H80GT result: ready` |
| 2 | `ksu_run_20261030_021751` | **1** 行（`base=ffffff8a4afbc000`） | 同上：模块加载、`Enforcing`、管理器交接、应用 `ready` |

两次启动都碰到了越界页——正是未打补丁三次尝试全部失败的条件——两次都完成。此前同一二进制的两次
外挂运行也通过，其中一次首屏就是越界页。
## 结论

| 项目 | 状态 |
|---|---|
| 第二批打包（6 载荷、共享集、profile、哈希、测试） | **PASS** |
| `9.0.0.230` 应用路径门禁，补丁载荷（随包） | **PASS 2/2** |
| 未打补丁的上游二进制，同设备（历史尝试 1–3） | **FAIL 3/3**——上游 `prepare_dm_page()` bug |
| 其余 5 个固件 | 未在此验证；仅 H80GT 已验证（其 exploit 同样带补丁） |

集成层没有责任：搬运、渲染、启动、监管、失败分类与重启契约在每次尝试中都按设计工作，包括三次失败。
## 证据路径

- 应用归档：`/sdcard/Download/ghostlock-debug-log/20261030-013107`、`20261030-014345`、
  `20261030-015145`。
- 链日志：`/data/local/tmp/ksu_run_20261030_013108`、`…_014346`、`…_015145`。
- 补丁运行：`/data/local/tmp/pt/`（`gl.log`、`ksu_load.log`）。
