# Project Hyppie — HyperOS 3 and LineageOS 23.2 on the Samsung Galaxy A13 (SM-A135M)

A record of how a stock, 32-bit Galaxy A13 ended up booting a 64-bit Android 16 (LineageOS 23.2)
and Xiaomi HyperOS 3.1, every wall hit on the way, and how each one was solved.

Device: Galaxy A13 4G, SM-A135M, Exynos 850 (codename `a13`, platform `universal3830`), 4 GB RAM,
stock firmware A135MUBSDDZB3 (Android 14 / One UI 6.1), bootloader unlocked.

---

## 1. Getting a recovery onto the phone (TWRP)

| Attempt | What happened | Cause | Fix |
|---|---|---|---|
| Flash TWRP with odin4 | USB handshake timeouts | The `cdc_acm` kernel module grabs Samsung's Download Mode interface | `rmmod cdc_acm`, then blacklisted it permanently |
| TWRP flashes, phone refuses to boot it | `recovery: No footer detected` / `invalid vbmeta header` | Android Verified Boot (AVB) rejects an unsigned recovery | Needs a `vbmeta` with verification disabled |
| Flash a `vbmeta` built with avbtool (flags=2) | `SW REV. CHECK FAIL (VBMETA) DEVICE: 0xd, BINARY: 0x0` | Samsung's download mode reads a *SignerVer03* trailer in its own vbmeta; an avbtool image has none, so it counts as revision 0 (device is 13) | Take Samsung's own `vbmeta.img` from the firmware and change **one field**: the AVB flags (byte offset 120) to `2`. Trailer intact, revision check passes |
| Rebuilding vbmeta with rollback index 4, then 13 | Still rejected | Same trailer problem (I first misread the blurry `0xd` as `0x4`) | As above |
| Bootloader looked "locked" | Wrong theory | `OEM LOCK: OFF (U)` already meant unlocked | — |
| Footered recovery, 64 MiB partition size | `DM_FAIL_IMAGE_SIZE_TOO_BIG` | The PIT block count misled me; the real recovery partition is **55,574,528 bytes** (stock recovery image size) | `avbtool add_hash_footer --partition_size 55574528` |
| Phone in a bad state after experiments | — | — | Flashed full stock firmware (BL/AP/CP/HOME_CSC) from samfw.com, re-did the above |

**Working recipe:** odin4 `-a` a tar with Samsung's vbmeta (flags patched to 2) + TWRP footered at
55,574,528 bytes. After any system change: TWRP → Format Data.

## 2. The 32-bit wall

The SM-A135M ships a **32-bit userspace** on a 64-bit CPU: every vendor HAL in `vendor/bin/hw` is
ELF 32-bit, `ro.vendor.product.cpu.abilist64` is empty, and the vendor manifest is frozen at an
Android 12 compatibility level. HyperOS 3 (Android 16) is 64-bit only.

- The community 64-bit conversion files (vendor/odm/boot from an XDA thread) were **taken down**.
- A 32-bit Android 16 GSI (TrebleDroid `arm32_binder64`) booted on the stock vendor, but was very
  slow and touch was unreliable — not a usable base.
- Building a vendor from source needs a full AOSP tree (~300 GB); the laptop had ~50 GB free.

**Breakthrough:** LineageOS officially ships Android 16 (23.2) for the **Galaxy A21s**, which uses
the *same Exynos 850 chip*. Its signed build contains a finished **64-bit Exynos 850 vendor**
(HWC3, 64-bit mapper/allocator, `ro.zygote=zygote64_32`).

### The hybrid vendor (`02-build-hybrid-vendor.py`)
1. Base: LineageOS 23.2 A21s `vendor.img` (checksum-verified official build).
2. Overlay the A13's own pieces from the A13 community device tree
   (github.com/Samsung-Galaxy-A13-A135F-Development): 18 config files (audio mixer paths, fstab,
   init scripts, media codecs, sensors…) and 190 proprietary blobs (firmware, camera, modem…).
3. Identity props set to `a13` / `SM-A135M`, density 450.
4. Packed as ext4 with `e2fsdroid`, Android permissions (fs_config) and SELinux labels.

## 3. Booting LineageOS 23.2 (64-bit Android 16)

| Round | Symptom | Cause | Fix |
|---|---|---|---|
| GSI + hybrid vendor | init reboots to recovery: `Unable to open SELinux policy` | Vendor policy (Android 16.1, uses `nlmsg`) newer than the GSI's platform policy | Use the **matching LineageOS system** from the same build instead of a GSI |
| LineageOS system | Hangs on Samsung logo ~11 min, then watchdog panic | `tz_service` exits 127 → TrustZone daemon never starts → encryption waits forever | My packer lost the executable bit on 8 git-sourced blobs (`tzdaemon`, `vaultkeeperd`, GPS…). Everything in a `bin/` dir is now 0755 |
| Next round | Boot loop at ~17 s: `reboot: bpfloader-failed` | `NetBpfLoad: Android 25Q2 requires kernel 5.4` — stock kernel is Samsung 4.19.198 | **Compiled the A13 community kernel 4.19.325** (Android-patched 4.19, same as LineageOS uses on the A21s) with clang/LLD 23 on the laptop (~15 min) |
| Kernel 4.19.325 | **Boots to the LineageOS setup screen** | — | Display "stretched" (A21s density 280 → set **450**), fingerprint not working, USB drops after boot |

## 4. Never needing the buttons: fail-safes

The phone kept getting stuck where it could not be reached. Layers added:

- **Boot guard** (`/vendor/bin/hyppie_bootguard.sh`, own permissive SELinux domain): counts boot
  attempts in `/metadata/hyppie`, reboots to TWRP after 3 failed boots, saves logcat there.
  First version never ran — a stale `precompiled_sepolicy` in `odm` ignored the new rules (removed).
- **Hang timer**: 12 minutes without `sys.boot_completed` → TWRP (13 more if `system_server` is up).
- **Kernel fail-safe** (patched `drivers/samsung/sec_reboot.c`): any reboot whose reason contains
  `failed` (e.g. `bpfloader-failed`) or a panic goes to recovery; a 25-minute kernel timer catches
  early hangs. Bug found the hard way: it once rebooted a *fully booted* HyperOS because the
  "boot done" signal never arrived — the timer now stands down when `system_server` is running.
- **ADB always on**: this computer's key baked into `/adb_keys`; `ro.adb.secure=0` on test builds;
  early-boot ADB binding script.

## 5. HyperOS 3.1 on top of the same base

Base ROM: xiaomi.eu `HOUJI OS3.0.307.0` (Xiaomi 14, Android 16, product `missi`). Only
`system`, `system_ext`, `product`, `mi_ext` are taken from Xiaomi; vendor/odm/kernel are ours.

| Round | Symptom | Cause | Fix |
|---|---|---|---|
| Policy check (on the PC, before flashing) | `Failed to resolve permission nlmsg` | HyperOS's Android 16 base predates `nlmsg` | Drop the 3 vendor rules using it (OpenThread radio, unused). Built `secilc` locally + `check-sepolicy.sh` so policy is verified before every flash |
| 1 | Reboot loop, `bpfloader failed` | Xiaomi's `osrtpPolicy.o` BPF program needs an **XSK map** (AF_XDP) | Enabled `CONFIG_XDP_SOCKETS`; Android's frozen 4.19 removed the `umem` fields from the queue structs, re-added them (no loadable modules, so safe) |
| 2 | Only the Samsung logo ever shows | **SurfaceFlinger crashes every 5 s**: `qtigralloc::get` (Qualcomm gralloc metadata) on Mali buffers, via Dolby code | Patched `libgralloc.system.qti.so`: `qtigralloc::get` → `mov w0,#7; ret` (UNSUPPORTED). Bind-mounted from `/vendor/lib64/hyppie` so Xiaomi's image stays untouched |
| 3 | **"Xiaomi HyperOS" boot animation appears** | — | — |
| 3 (cont.) | Stuck on the animation | Zygote 436× denied reading vendor props; logs too short | Bigger logcat (Android buffers only, 8 MB ×3) |
| 4 | Zygote aborts on start | `MediaProfiles.cpp CHECK(fopen(xml))`: Xiaomi's libmedia wants Qualcomm-named `/vendor/etc/media_profiles_vendor.xml` | Provide the A13 media profiles under that name + `media.settings.xml` |
| 4 | SELinux still enforcing | Samsung's bootloader ignores/replaces the boot image cmdline, so `androidboot.selinux=permissive` never arrives | Permissive default compiled into the **bring-up** kernel (`hyppie_permissive`) — must be 0 for release |
| 5 | SurfaceFlinger aborts: `stoi: no conversion` | Qualcomm `libskewknob_system.so IsFeatureEnabled` reads a Snapdragon value | Patched `IsFeatureEnabled` → return 0 (same bind-mount method) |
| 6 | **`system_server` starts, reaches boot phase 550** | `com.android.phone` ANR loop (Xiaomi `HyperOSCustFeatureResolve`, missing `continuity_sdk_app` library) | Under investigation |
| 7 | **`sys.boot_completed=1` — HyperOS fully booted (~5 min)** | Then killed at 25 min by my own kernel timer | Kernel timer fixed (see §4) |
| 8 | Boots, USB goes quiet ~28 s after boot | Fresh Android defaults USB to charge-only and ADB off | Guard turns on `adb_enabled` in settings (being verified) |
| 9 | Apps freeze, touches dropped ("Pruning the input queue … unresponsive") | HyperOS multi-arch apps (LyraSdkApp → continuity SDK → Phone/SystemUI) refuse to load when the device advertises 32-bit ABIs | Apps 64-bit only like the Xiaomi 14: `abilist=arm64-v8a`, empty `abilist32`, `zygote64` (vendor + odm). The 32-bit *vendor HALs* still run fine |
| 10 | Touch screen sometimes dead after screen-off | Himax HX83112F needs `check_connection` after wake | Guard writes it once at boot and after the backlight was off ≥1 s. (An earlier version reacted to every input event → reset loop + lag; removed) |
| 11 | Home swipes 9–12 % janky frames | HWUI on Vulkan is slower than GL on the Mali-G52 | `debug.hwui.renderer=skiagl` → ~1 % janky on home swipes. HyperOS perf level `persist.sys.computility.*=6` measured best (4 and 2 were worse) |
| 12 | Camera provider segfaults every 5 s (null `CameraModule`) | `camera.exynos850.so` never loaded: the A13 Lineage blob `libexynoscamera3.so` needs **`libshim_camera.so`** (Lineage `GraphicBufferMapper::unlock(handle)` shim) | Shim ported to C and cross-compiled on the PC for 32-bit ARM against the phone's own `libui`/`libsync` (`shims/libshim_camera`). 4 cameras, 0 crashes, 12.5 MP photos. Xiaomi's Camera app is model-locked, so LineageOS **Aperture** is installed |
| 13 | **Random** `system_server` crash loop: `Missing permission definition for android.permission.RANGING` (app op 151) | **My own logs filled the 24 MB `/metadata`** (logcat 8 MB × 3). `aconfigd platform-init` then failed with *No space left on device*, so the platform flag storage was never created; the package parser read every platform flag as false and skipped `RANGING` (its `featureFlag` permission). It looked random because the parsed framework is cached between boots | Logs capped at 3 × 1 MB and old files deleted at boot. Two innocent overlays were blamed (and removed) before the real cause was found — lesson: read the `aconfigd` error first |
| 14 | — | — | A13 framework overlay back (`HyppieA13Framework.apk`, built with aapt2 + an OpenSSL v1 signer since there is no Java): teardrop cutout for the status bar / **Super Island**, rounded corners, and `config_biometric_sensors 0:2:15` so the **fingerprint** HIDL HAL is registered |
| 15 | Crash loops never reached TWRP for 25 min | the hang timer stands down while `system_server` exists | Guard counts `system_server` restarts: 4 before boot completes → TWRP |

## 6. Status (2026-10-05, public beta 1.0)

Working on HyperOS 3: boot in about 2 minutes, touch (including after screen off, via Samsung's `tsp/enabled`
protocol), swipe-up home, Liquid Glass blur, Super Island and Always-On Display, Control Center ~12 % janky frames
(after removing the theme's heavy SystemUI layer and precompiling), home screen ~3 %, 4 cameras through Aperture,
16 sensors, Wi-Fi, Bluetooth, GPS, speaker, vibration, adoptable microSD, Magisk 30.7, Xiaomi themes applied with
root, wireless debugging over Tailscale, automatic recovery on hangs and crash loops. A 23-check automated test
(`10-fulltest.sh`) passes 21 with 2 warnings.

New since the first write-up:
- **Touch fix** (round 10 above) finally solved by telling the Himax driver the screen state through
  `/sys/class/sec/tsp/enabled` after the backlight is on.
- **AI Assistant** (`hyppie-ai/`): hold the side button; a Groq-based planner turns a long request into steps and an
  accessibility service performs them with a live checklist; offline quick commands.
- **Startup pages**: the HyperOS package shows credits, what works and what is not supported before the HyperOS boot
  animation (a `bootanimation.zip` bind-mounted over `/product/media`, built by `bootanim/make_bootanim.py`).

Open:
- **Fingerprint**: the HAL is registered but HyperOS reports the hardware as not detected; enrolling is untested.
- **SELinux is permissive** in this release (bring-up kernel switch); enforcing needs every logged denial turned into a rule.
- **Calls, SMS, mobile data** are untested (no SIM available).
- Xiaomi's Camera app is model-locked (use Aperture); China-only HyperAI features need Xiaomi cloud services.

## 7. Tooling built along the way (`~/ProjectHyppie`)

`02-build-hybrid-vendor.py` (vendor, `HYPPIE_TARGET=hyperos` for HyperOS fixes) ·
`03-pack-vendor.sh` · `03b-pack-odm.sh` · `04c-build-boot-kernel.sh` (`HYPPIE_SELINUX=permissive`) ·
`06-build-los-super.sh` (LineageOS) · `07-build-hyperos-super.sh` (HyperOS, policy-checked) ·
`build-all.sh` · `watch-boot.sh` (auto log pull) · `tools/check-sepolicy.sh` + `tools/secilc` ·
`kernel-hyppie.patch` + `kernel-hyppie.config` · `restore-kit/` · `backup-20261003/` (EFS etc.).
