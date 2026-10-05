# Project Hyppie

**Xiaomi HyperOS 3 and LineageOS 23.2 (Android 16, 64-bit) for the Samsung Galaxy A13 4G (SM-A135M).**

A 32-bit Samsung budget phone running Xiaomi's newest system, with Super Island, Liquid Glass, Magisk root, adoptable SD storage and its own AI assistant.

- **Website, downloads and install guide:** https://mr1charles.github.io/project-hyppie/
- **Downloads:** [Releases](../../releases/latest)
- **The full story** (every wall we hit and how each was solved): [docs/PROJECT_HYPPIE_WRITEUP.md](docs/PROJECT_HYPPIE_WRITEUP.md)
- **Install steps:** [docs/INSTALL.md](docs/INSTALL.md)

> **Beta. Flash at your own risk.** Flashing erases all data. SELinux runs in *permissive* mode in this release. Only for the SM-A135M.

## What's in this repo

| Path | What it is |
|---|---|
| `01`–`10-*.sh/py` | The build pipeline: unpack bases, build the hybrid 64-bit vendor, pack images, build kernel boot images, build HyperOS / LineageOS `super`, make the release, run the device test |
| `kernel-hyppie.patch`, `kernel-hyppie.config` | Changes to the community A13 kernel 4.19.325 (XDP sockets, fail-safes, permissive bring-up switch) |
| `overlay*/` | Boot guard, touch fix, SELinux additions, HyperOS and camera overlays, framework resource overlays |
| `shims/` | `libshim_camera` (camera HAL) and `libshim_idiv0` (32-bit division helpers), C sources |
| `hyppie-ai/` | Source of the **AI Assistant** app (Kotlin) |
| `docs/` | The website, the write-up and the install guide |

The ROM images themselves are not in git; they are attached to the [Releases](../../releases/latest). The base images (HyperOS from xiaomi.eu, LineageOS A21s vendor) are downloaded by `01-unpack-base.sh`.

## Credits and legal

Built on LineageOS (Galaxy A21s vendor), the Samsung-Galaxy-A13 community device tree and kernel, Magisk, and xiaomi.eu's HyperOS. HyperOS is a trademark of Xiaomi, Galaxy of Samsung; this is an unofficial community port and is not affiliated with either. Provided as is, without warranty.
