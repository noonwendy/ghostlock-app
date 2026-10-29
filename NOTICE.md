# Third-party notices and modifications

This repository is licensed under the Apache License 2.0 (see [LICENSE](LICENSE)). It bundles
or reproduces work from the projects below. Per-artifact source, size, SHA-256 and license are
pinned in [`app/src/main/assets/h80gt/payloads.conf`](app/src/main/assets/h80gt/payloads.conf).

## Our modifications

The HONOR 80 GT specialized engine and its payload catalogue are additions to the upstream app.
The six exploit payloads are rebuilt from **GhostLock-H80GT** (`33541d1`) with **one source
change**: `DIRECT_MAP_END` in `exploit/src/targets/*/target.h` is widened from the 12GB bank
bound (phys `0xAB0000000`) to the 16GB bank top (phys `0xB80000000`), so `sysctl_do_walk()`
does not skip valid pages on 16GB parts.

- Change and device evidence: [`docs/analysis/h80gt-16gb-range-fix.md`](docs/analysis/h80gt-16gb-range-fix.md)
- The six per-firmware exploits are committed under `h80gt/exploits/`; `prepareH80gtPayload`
  stages them into the app assets (the shared set still comes from the pinned bundle) and every
  byte is verified against the catalogue. The rebuild command is recorded per artifact in
  `payloads.conf`.

The H80GT integration code in this repository is provided under the repository license.

## Components and attribution

| Component | Upstream | License | Use here |
|---|---|---|---|
| GhostLock-H80GT | <https://github.com/yakidango-official/GhostLock-H80GT> | Apache-2.0 | Exploit source, target offsets and per-firmware payloads; modified as described above |
| CyberMeowfia / IonStack | <https://github.com/NebuSec/CyberMeowfia> | See upstream | Original CVE research and PoC credited by the exploit |
| ghostlock-oneplus | <https://github.com/JoinChang/ghostlock-oneplus> | See upstream | App base credited in the README |
| CVE-2026-43499-popsicle | <https://github.com/x-spy/CVE-2026-43499-popsicle> | See upstream | App base credited in the README |
| KernelSU | <https://github.com/tiann/KernelSU> | GPL-2.0 | `kernelsu.ko`, `ksud`, loader and policy bits; H80GT's three patches |
| Magisk | <https://github.com/topjohnwu/Magisk> | GPL-3.0 | Unmodified `magiskpolicy` binary |
| Shizuku | <https://github.com/RikkaApps/Shizuku> | Apache-2.0 | Privileged execution service used by the app |

Copyright in each component remains with its authors. Nothing here changes or relaxes those
licenses.

## Source availability

The exploit payload source is the upstream GhostLock-H80GT repository plus the one `target.h`
change above; the rebuilt binaries and their hashes are recorded in `payloads.conf`. The GPL
components (KernelSU, Magisk) remain under their licenses; their sources are available from the
upstream projects, and the KernelSU build inputs are documented in GhostLock-H80GT's `ksu/`
directory.

## No endorsement

References to HONOR, Android, KernelSU, Magisk or Shizuku do not imply endorsement by those
projects. Use this software only on devices you own, for security research.
