#!/usr/bin/env bash
# Stage 9: public installer packages for anyone with an SM-A135M.
# Builds both ROMs in RELEASE mode (normal ADB with authorization, no pre-authorized key, no Wi-Fi ADB)
# and collects everything into release/<date>/ with checksums:
#   1-twrp-vbmeta.tar            TWRP + Samsung vbmeta with verification disabled
#   2-hyperos-3.1-magisk.tar     HyperOS 3.1 (Magisk v30.7 pre-installed)
#   2-lineageos-23.2.tar         LineageOS 23.2
#   AIAssistant.apk, Magisk-v30.7.apk, INSTALL.md, PROJECT_HYPPIE_WRITEUP.md, SHA256SUMS
# Your own phone keeps using the developer builds (ADB always on); just rebuild without HYPPIE_RELEASE.
set -euo pipefail
P=$HOME/ProjectHyppie
R=$P/release/$(date +%Y%m%d)
mkdir -p "$R"
cd "$P"
# same flags as the working developer build (HYPPIE_SENSORS=a13 breaks touch + screen wake: never use it)
export HYPPIE_RELEASE=1 HYPPIE_FPSHIM=1

echo "== HyperOS 3.1 (release, Magisk)"
HYPPIE_TARGET=hyperos HYPPIE_RRO=1 python3 02-build-hybrid-vendor.py | grep -E 'sensors:|hyperos:' || true
./03-pack-vendor.sh | tail -1
HYPPIE_TARGET=hyperos ./03b-pack-odm.sh | tail -1
HYPPIE_SELINUX=permissive ./04c-build-boot-kernel.sh >/dev/null
HYPPIE_MAGISK=1 ./07-build-hyperos-super.sh | tail -1
cp work/hyperos/AP-hyppie-hyperos.tar "$R/2-hyperos-3.1-magisk.tar"

echo "== LineageOS 23.2 (release)"
python3 02-build-hybrid-vendor.py | grep -E 'sensors:' || true
./03-pack-vendor.sh | tail -1
./03b-pack-odm.sh | tail -1
./04c-build-boot-kernel.sh >/dev/null
./06-build-los-super.sh | tail -1
cp work/hybrid/AP-hyppie-los.tar "$R/2-lineageos-23.2.tar" 2>/dev/null || cp "$(ls -t $(find work -name 'AP-hyppie-los.tar') | head -1)" "$R/2-lineageos-23.2.tar"

echo "== extras"
cp restore-kit/AP-twrp-v2.tar "$R/1-twrp-vbmeta.tar"
cp hyppie-ai/app/build/outputs/apk/debug/app-debug.apk "$R/AIAssistant.apk"
cp tools/magisk/Magisk-v30.7.apk "$R/Magisk-v30.7.apk"
cp apps/Aperture.apk "$R/Aperture.apk"
cp docs/INSTALL.md docs/PROJECT_HYPPIE_WRITEUP.md "$R/"
(cd "$R" && sha256sum *.tar *.apk > SHA256SUMS)
ls -la "$R"
echo "release ready: $R"

# put the developer HyperOS build back (vendor, odm, boot): the LineageOS steps above left a LineageOS boot
# image in work/boot, and flashing that with HyperOS reboots straight to recovery (no mi_ext mount)
echo "== restoring the developer HyperOS build in work/"
unset HYPPIE_RELEASE; export HYPPIE_FPSHIM=1
HYPPIE_TARGET=hyperos HYPPIE_RRO=1 python3 02-build-hybrid-vendor.py >/dev/null
./03-pack-vendor.sh >/dev/null 2>&1
HYPPIE_TARGET=hyperos ./03b-pack-odm.sh >/dev/null 2>&1
HYPPIE_SELINUX=permissive ./04c-build-boot-kernel.sh >/dev/null
echo "work/ is back to the developer HyperOS build"
