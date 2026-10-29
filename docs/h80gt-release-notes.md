# Release notes — H80GT batch 2 (fork)

**Artefact:** `GhostLock-v1.2(1)-arm64-v8a-debug.apk`, 36,443,696 bytes,
sha256 `c793f7bea6811b0639ab70a8961cb86e4b87de927578793a1c924fe546410804`.
**Basis:** upstream GhostLock (native engine untouched) + the GhostLock-H80GT v0.3.1 release bundle
(`ghostlock.tar.gz` `a5a3cff2…c8810e`).

## What this build adds

| Area | Batch 1 | Batch 2 |
|---|---|---|
| Firmwares | 1 (`9.0.0.230`) | **6** — every firmware GhostLock-H80GT marks device-verified |
| Profile keying | kernel string + model + build fingerprint | **kernel string only**, like the H80GT manifest; model/fingerprint fields remain available but are not shipped |
| Payload layout | one per-firmware directory with 8 files | per-firmware **exploit** + a shared, deduplicated set (module, userspace tools, loader template) |
| Exploit binaries | upstream v0.3.1 | **fork-patched**: `prepare_dm_page()` retires out-of-range pages (see [the issue report](analysis/upstream-issue-h80gt-dm-page.md)); all six rebuilt with NDK r29 |
| Catalogue | schema v1, one payload | schema v2, `shared` section, six payloads, 13 unique artifacts |
| Native engine | unchanged | unchanged (no GLK1/route/native changes) |

Supported kernels (`uname -r`):

| Firmware | Kernel |
|---|---|
| MagicOS 8.0.0.128 | `5.10.168-android12-9-g804a21a522f0` |
| MagicOS 8.0.0.160 | `5.10.209-android12-9-g516fa23f87fc` |
| MagicOS 9.0.0.157 | `5.10.209-android12-9-ga3ff7ae30334` |
| MagicOS 9.0.0.200SP1 | `5.10.236-android12-9-gd1575ab10b30` |
| MagicOS 9.0.0.220SP2 | `5.10.236-android12-9-ga88335e8db40` |
| MagicOS 9.0.0.230 | `5.10.236-android12-9-gd671cf8c2b02` |

No serial, account, model name or build fingerprint is required, stored or compared.

## Artefacts and licences

Each payload's files, sizes, SHA-256, licence and reproducible-build command are pinned in
`app/src/main/assets/h80gt/payloads.conf` (schema v2). Summary:

| Role | File | Licence | Notes |
|---|---|---|---|
| exploit | per-firmware `exploit` (~2.2 MB × 6) | Apache-2.0 | GhostLock-H80GT, per-target build |
| module | `kernelsu.ko` | GPL-2.0 | KernelSU v3.3.0 + H80GT's three patches, built against the HONOR kernel tree |
| loader | `load_ko`, `kmsg_dumper` | GPL-2.0 | H80GT `ksu/tools` |
| policy | `magiskpolicy` | GPL-3.0 | unmodified Magisk binary (topjohnwu/Magisk) |
| policy | `ksu_rules` | GPL-2.0 | H80GT policy rules |
| userspace | `ksud` | GPL-2.0 | KernelSU manager release `lib/arm64-v8a/libksud.so` |
| template | `ksu_loader.tmpl` | GPL-2.0 | rendered per run (no leftover `@TOKEN@`) |

The binaries are **not** committed; `prepareH80gtPayload` stages them from the pinned bundle,
`verifyH80gtPayload` (and the app, and the Shizuku service) verify every byte against the manifest.
A missing or mismatching byte refuses the run instead of executing.

## Build

```sh
# host: JDK 21, Android SDK (platform 37.2, build-tools 36.0.0), NDK r29, Git's sh on PATH
./gradlew exportKernelProfiles            # optional: exporter check
./gradlew :profile-core:test :app:testDebugUnitTest
./gradlew :app:assembleDebug              # stages + verifies the payload, builds the APK
```

`-Pghostlock.h80gt=off` builds an APK without the payload; the app then refuses `engine = "h80gt"` at
preflight instead of running anything.

## Verification status

| Check | Result |
|---|---|
| `:profile-core:test` | 26/26 |
| `:app:testDebugUnitTest` | 129 tests, 124 pass, 2 pre-existing baseline failures (`ExporterAgreementTest`, `ProfileMigrationEquivalenceTest` — identical on a pristine checkout) |
| `verifyH80gtPayload` | 6 payloads / 13 unique artifacts verified |
| APK content spot check | all payload entries present; `kernelsu.ko` hash matches the manifest |
| App-path device gate, `9.0.0.230`, unpatched upstream binary | **fail 3/3** — upstream `prepare_dm_page()` bug (see [H80GT-02](analysis/device-gates/H80GT-02.md)) |
| App-path device gate, `9.0.0.230`, **this build** (patched payload) | **pass 2/2** — both boots hit out-of-range pages and still completed; module loaded, `Enforcing` restored, manager auto-opened |
| Batch-1 gate (same chain, 2026-10-29) | **pass** — all eight stages from the UI |

## Known limitations

1. **Grant apps first.** This session has no first-use prompt: an unlisted app's `su` returns `ENOENT`.
   Open 超级用户 → app → App Profile → enable 「超级用户」; then `su -c id` returns `uid=0 … u:r:ksu:s0`.
2. **Shizuku must be running.** ADB-started Shizuku does not survive a reboot. On this device the
   stock `start.sh` can fail silently; starting `app_process` with `CLASSPATH` + `LD_LIBRARY_PATH`
   set to the app's extracted `lib/arm64` works.
3. **Failure means reboot.** A failure at or after the permissive stage leaves `boot_id` redirected;
   only the module's init restores it. The app marks the run `reboot=true` and refuses further runs
   until the boot instant changes. Failures before staging/launch do not need a reboot.
4. **Upstream exploit reliability (fixed in this build).** Unpatched, three of three app-path attempts on `9.0.0.230` failed inside the
   physical-page walk (`SKIP out-of-ram`). This fork ships the one-condition fix in all six exploits:
   a page whose direct-map alias is past `DIRECT_MAP_END` is retired instead of pinned.
   Root cause, logs and the diff are in [the upstream issue report](analysis/upstream-issue-h80gt-dm-page.md).
5. **Five firmwares are H80GT-verified, not gated here.** Only `9.0.0.230` has been exercised through
   this integration. The others run the same integration code but have not been device-gated by this
   project.

## Upgrade / rollback

- Installing over an older build keeps app data; the payload is re-verified at every run.
- Nothing is persisted on the device by this app besides its own preferences and per-run temp dirs
  under `/data/local/tmp/ksu_run_*` (owned by `shell`) and the debug archives under
  `Download/ghostlock-debug-log/`.
- Unloading the module is `ksud unload` (needs root) or a reboot; the app itself never unloads.
