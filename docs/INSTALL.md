# Installing HyperOS 3.1 or LineageOS 23.2 on the Galaxy A13 (SM-A135M)

> Unofficial, experimental. Read everything first. Tested only on SM-A135M (Exynos 850).
> You can always go back to stock with the Samsung firmware (step 0).

## What you need
- Galaxy A13 **SM-A135M** with the **bootloader unlocked** (Download Mode shows `OEM LOCK: OFF (U)`).
- A Linux PC with `odin4` (or Windows with Odin 3.14+) and `adb`.
- The files from the website / GitHub Releases:
  - `1-twrp-vbmeta.tar` — TWRP 3.7.1 + Samsung vbmeta with verification disabled
  - `2-hyperos-3.1-magisk.tar.7z.001` / `.002` / `.003` (download **all three**, keep them in one folder) **or**
    `2-lineageos-23.2.tar.7z` — extract with 7-Zip (open the `.001` file) to get the `.tar` to flash:
    boot (kernel 4.19.325), dtbo, super. The HyperOS package comes **with Magisk v30.7 root already installed**
    and shows a short **startup screen** (credits, what works, what is not supported) before the HyperOS logo.
  - `Magisk-v30.7.apk` — install it after first boot to manage root
  - `Aperture.apk` — working camera app (Xiaomi Camera is locked to Xiaomi phones)
  - `AIAssistant.apk` — optional AI assistant (glow edge, step-by-step agent, offline quick commands; needs your own free Groq key for the AI part)
  - `SHA256SUMS` — check the downloads with `sha256sum -c SHA256SUMS` before extracting
  - To go back to stock: Samsung **A135MUBSDDZB3** (BL, AP, CP, HOME_CSC) from a firmware site

On Linux run once: `sudo rmmod cdc_acm` (or blacklist it) — otherwise odin4 times out.

## Steps
1. **Back up** your EFS/IMEI in TWRP (Backup → EFS) once TWRP is installed. Keep it off the phone.
2. **Download Mode**: power off, hold **Vol Up + Vol Down**, plug in the USB cable, press **Vol Up**.
3. **Flash TWRP + vbmeta**: `odin4 -a 1-twrp-vbmeta.tar` (Odin: AP slot). Do not let it boot Android.
4. **Boot TWRP**: unplug, hold **Vol Down + Power** until the screen goes black, then immediately
   **Vol Up + Power** until TWRP opens.
5. In TWRP: **Wipe → Format Data → type `yes`**. (Required: the old data is encrypted for the old system.)
6. TWRP → Reboot → **Download**.
7. **Flash the ROM**: `odin4 -a 2-hyperos-3.1-magisk.tar --reboot` (or `2-lineageos-23.2.tar`), the file you extracted from the `.7z`.
8. **First boot is slow**: LineageOS ~3 min, HyperOS ~2–10 min. HyperOS first shows the Project Hyppie startup
   pages (credits and what works / what does not), then the normal HyperOS boot animation. Do not interrupt.
   If something fails, the phone reboots itself into TWRP (built-in fail-safe).
9. **Root**: HyperOS is already rooted — install `Magisk-v30.7.apk` to manage it. On LineageOS: rename the APK
   to `.zip`, TWRP → Install it → reboot, then install the APK.
10. **First boot after setup**: a notification "Compiling apps" appears for a few minutes — that's the
   phone pre-compiling every app so they open fast. Let it finish.
11. **USB debugging is normal Android**: Settings → About phone → tap the OS version 7×, then Developer options.

## Going back to stock
Download Mode → `odin4 -b BL_*.tar.md5 -a AP_*.tar.md5 -c CP_*.tar.md5 -s HOME_CSC_*.tar.md5 --reboot`.

## Known issues (HyperOS 3.1 package)
- **SELinux is permissive** (the kernel forces it during development). Treat this as a test build:
  don't store anything you can't afford to lose, and don't use banking apps on it.
- **No light sensor** (the A13 has none) → no auto-brightness. Proximity is Samsung's touchscreen
  "hover proximity".
- **Xiaomi Camera app is model-locked**: install the included `Aperture.apk` (LineageOS camera) for photos, or Open Camera (F-Droid) for video.
- **USB data can stay off after flashing**: unplug and replug the cable once.
- **HyperAI**: AI Writing / Translate / Interpreter / Subtitles work; China-only features (Super XiaoAi) don't.
- **Phone calls / SMS / mobile data**: not yet tested with a SIM.
- **LineageOS** package has no startup pages (only HyperOS does).
- **Beta**: SELinux permissive, fingerprint enrolling unverified. Flash at your own risk.

See `PROJECT_HYPPIE_WRITEUP.md` for the full story and every fix.
