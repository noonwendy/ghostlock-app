English | [简体中文](h80gt-integration_ZH.md)

# H80GT integration: usage and limits

This document is the how-to guide for the specialized `engine = "h80gt"` support. It covers one
device combination only. The design and its evidence are in
[`h80gt-integration-plan.md`](analysis/h80gt-integration-plan.md).

## What it does

GhostLock ships one profile for the HONOR 80 GT and drives the whole H80GT chain from the app:

1. The profile (`engine = "h80gt"`) selects the specialized engine and pins the exact target.
2. The app checks the device, the profile and the bundled payload before anything runs.
3. The Shizuku UserService stages a payload set into `/data/local/tmp/ksu_run_<stamp>`, verifies
   every byte against the packaged catalogue, renders the project's authoritative loader, launches
   the exploit detached and supervises the chain.
4. After the module, the KernelSU userspace and the security state are verified, the app opens the
   matching KernelSU manager in the foreground.

Nothing is typed into a shell, and no PC is needed after the first Shizuku activation.

## Supported targets

Selection is by kernel string alone — the same rule GhostLock-H80GT uses. The app compares the
device's `uname -r` with the bundled profile; no model name, build fingerprint or other
device-unique data is required or shipped.

| Firmware | Kernel (`uname -r`) |
|---|---|
| MagicOS `8.0.0.128` | `5.10.168-android12-9-g804a21a522f0` |
| MagicOS `8.0.0.160` | `5.10.209-android12-9-g516fa23f87fc` |
| MagicOS `9.0.0.157` | `5.10.209-android12-9-ga3ff7ae30334` |
| MagicOS `9.0.0.200SP1` | `5.10.236-android12-9-gd1575ab10b30` |
| MagicOS `9.0.0.220SP2` | `5.10.236-android12-9-ga88335e8db40` |
| MagicOS `9.0.0.230` | `5.10.236-android12-9-gd671cf8c2b02` |

These are the six firmwares GhostLock-H80GT marks device-verified, all for the HONOR 80 GT
(`AGT-AN00`). Module: H80GT custom `kernelsu.ko` built from KernelSU `v3.3.0`; manager: `3.3.0`.

The six exploit payloads are rebuilt from GhostLock-H80GT with the
[16GB dm-range fix](analysis/h80gt-16gb-range-fix.md); provenance and licenses are in
[NOTICE.md](../NOTICE.md).

Any other kernel string is refused before staging, with the mismatching value in the message. Only
`9.0.0.230` has additionally been run through the GhostLock integration gate
([`analysis/device-gates/H80GT-01.md`](analysis/device-gates/H80GT-01.md)); the other five are
verified by the H80GT project, not by this integration.

## Prerequisites

1. **Shizuku installed and its service running.** Activate it once over ADB
   (`adb shell sh /sdcard/Android/data/moe.shizuku.privileged.api/start.sh` or the wireless-debugging
   flow of the Shizuku app). Grant GhostLock the Shizuku permission when asked. The chain runs as the
   shell identity; the app cannot do it alone.
2. **KernelSU manager `3.3.0` installed.** A different manager build can report the module as
   "not installed", because the manager version-checks the kernel side. GhostLock refuses to open a
   manager it cannot vouch for.
3. **Clean device state**: no KernelSU module loaded (`/proc/modules` has no `kernelsu`), SELinux
   enforcing, and at least 64 MiB free under `/data`.
4. **A recent backup.** The chain changes kernel memory; a failed attempt can reboot the device.

## Run it

1. Install the APK.
2. Open GhostLock. The H80GT profile is selected automatically when the kernel matches; otherwise
   pick it in **Built-in profiles** (`MagicOS 9.0.0.230 · AGT-AN00`) or import the profile file.
3. Make sure the Shizuku status card shows ready. The engine requires it.
4. Tap **Run** and keep the app in the foreground.

The execution sheet shows the stages and the live log:

| Stage | What it means |
|---|---|
| Preflight | device/payload/manager/state checks |
| Stage payload | files copied to the run directory and hash-verified |
| Launch | manager stopped, exploit started detached |
| Privilege escalation | the exploit reached uid 0 and flipped `sig_enforce` |
| KernelSU module | `/proc/modules` reports `kernelsu` |
| KernelSU userspace | the `ksud` stages ran |
| State restore | enforcing back on, `sig_enforce` restored, fake kallsyms unmounted, `boot_id` readable |
| Manager handoff | the matching manager was opened |

Success means all eight stages reported `Done` and the log ends with `result: h80gt ready`.

## When it fails

The H80GT chain redirects the kernel `boot_id` pointer until the module restores it. Therefore:

- **Any failure at or after Launch requires a reboot.** The app says so, records the run and refuses
  the next attempt on the same boot.
- **Do not run twice.** The app blocks a second run while one is active, while a chain is still
  alive, and while a loaded module is present.
- **Never kill the chain** (no force-stop of GhostLock's child processes). A killed parked waiter can
  panic the kernel; the app itself never kills it either.
- A failed attempt may reboot the device by itself. After it comes back, run again once.

| Message | Action |
|---|---|
| previous attempt did not reach a clean state | reboot the device, then run again |
| KernelSU module already loaded | reboot; a stale module fakes success |
| a previous H80GT chain is still running | reboot, then run again |
| SELinux is not enforcing | reboot and check the device state |
| payload missing / incomplete | reinstall an APK built with the payload (`prepareH80gtPayload`) |
| does not match the H80GT target | the profile is for another model/firmware/kernel |
| no compatible manager / version does not match | install the `3.3.0` manager and select it |

## What "ready" does not prove

The app can prove the module is loaded, the userspace stages ran and the security state is back.
It cannot read the manager's UI. After the handoff, confirm in the manager:

- the status card says `working <LKM>` (not "not installed");
- the settings entry and the superuser/authorization list are reachable;
- a normal app can request and receive root (a non-destructive check).

If the card says "not installed" with a loaded module, the manager's version does not match the
module's KernelSU version; install the `3.3.0` manager.

## Verified on a device (2026-10-29)

The target combination above was run end to end on the reference device; the evidence is in
[`analysis/device-gates/H80GT-01.md`](analysis/device-gates/H80GT-01.md). Facts worth knowing before
you run it:

- **Keep the manager at `3.3.0`.** The manager shows an update banner for a newer build; upgrading
  it re-introduces the version mismatch described above.
- **`sig_enforce` and `kptr_restrict` are not readable by the shell identity** on this firmware. The
  app's restore check therefore relies on SELinux enforcing, the module being live, `/proc/kallsyms`
  no longer being bind-mounted and `boot_id` staying stable, and the loader log prints the restored
  values (`sig_enforce=Y`, `kptr restored=2`).
- **Grant apps explicitly; this session has no first-use prompt.** An app that has not been granted
  gets `ENOENT` from `su` and KernelSU shows no dialog. Open 超级用户 → pick the app → App Profile →
  turn on 「超级用户」; the app's next `su -c id` returns `uid=0(root) … context=u:r:ksu:s0`. MT
  Manager is a live example (its `mtio` daemons run as root).

## If Shizuku does not start

The Shizuku server can crash on start with
`UnsatisfiedLinkError: dlopen failed: … base.apk!/lib/arm64-v8a/librish.so`, after which GhostLock
shows Shizuku as not running (or as permission-required but unusable). This is a Shizuku/platform
issue, not a GhostLock one. Reinstall the same official APK (`adb install -r shizuku-*.apk`, or
`pm install -r` on the installed APK path) and run `start.sh` again; the manager then reports
"Shizuku is running" and GhostLock can request the permission.

## Evidence

Every run writes `Download/ghostlock-debug-log/<timestamp>/`:

- `ghostlock-h80gt-*.log` — the staged stages and the relayed exploit/loader output;
- `profile.conf` — the resolved profile that ran;
- `payload.conf` — the payload id, source, license and the SHA-256 of every artifact.

The device keeps the run directory `/data/local/tmp/ksu_run_<stamp>/` (`gl.log`,
`ksu_load.log`, `kmsg_cap.txt`, `stage_*.log`) until the next run sweeps it. After enforcing is
restored those files may be unreadable to the shell, which is why the app relays them live.

## Building an APK with the payload

The six per-firmware exploits are committed under `h80gt/exploits/`; the shared set (module,
tools, loader) is staged from the pinned release bundle:

```sh
./gradlew :app:assembleDebug            # default: ../release/bundle
./gradlew :app:assembleDebug -Pghostlock.h80gt=/path/to/bundle
./gradlew :profile-core:verifyH80gtPayload   # size + SHA-256 check
```

`-Pghostlock.h80gt=off` builds without the payload; the app then refuses `engine = "h80gt"` at
preflight. Every artifact, its source and its license are listed in
`app/src/main/assets/h80gt/payloads.conf`.

## Not covered

- Flashing, permanent root, OTA handling, automatic module installation, boot persistence.
- Other devices, other MagicOS builds, other KernelSU branches.
- Automatic retry after a failure (a reboot is part of the contract).
