#!/usr/bin/env bash
# Stage 4c: boot.img with the compiled A13 4.19.325 kernel + its DTB (header v2, stock
# offsets/cmdline), ramdisk = stock A13 ramdisk with the hybrid fstab. Also builds dtbo.img.
set -euo pipefail
P=$HOME/ProjectHyppie
K=$P/a13src/kernel/out
CFG=$P/a13src/device-common/configs/kernel
MB=$P/tools/magiskboot
MKDTBO="python3 $P/tools/mkdtboimg.py"
B=$P/work/boot
FSTAB=$P/work/hybrid/vendor/etc/fstab.exynos850

IMAGE=$K/arch/arm64/boot/Image
[ -f "$IMAGE" ] || { echo "kernel Image not built"; exit 1; }
DTS=$(dirname "$(find "$K/arch/arm64/boot/dts" -name exynos3830.dtb | head -1)")
OVL=$(dirname "$(find "$K/arch/arm64/boot/dts" -name 'a13_eur_open_w00_r00.dtbo' | head -1)")
echo "dtb dir: $DTS"; echo "dtbo dir: $OVL"

rm -rf "$B" && mkdir -p "$B" && cd "$B"
$MKDTBO cfg_create dtb.img "$CFG/exynos850.cfg" -d "$DTS"
$MKDTBO cfg_create dtbo.img "$CFG/a13.cfg" -d "$OVL"
[ "$(stat -c %s dtbo.img)" -le 8388608 ] || { echo "dtbo too big"; exit 1; }

lz4 -dqf "$P/stock/boot.img.lz4" boot.img
"$MB" unpack -h boot.img >/dev/null 2>&1
# init fatal errors reboot into recovery (TWRP) instead of the bootloader
sed -i 's/^cmdline=\(.*\)$/cmdline=\1 androidboot.init_fatal_reboot_target=recovery/' header
# bring-up builds: HYPPIE_SELINUX=permissive (log denials, do not block); release builds stay enforcing
[ "${HYPPIE_SELINUX:-enforce}" = permissive ] && sed -i 's/androidboot.selinux=enforce/androidboot.selinux=permissive hyppie_permissive=1 enforcing=0/' header
cp "$IMAGE" kernel
cp dtb.img dtb
"$MB" cpio ramdisk.cpio "add 0640 fstab.exynos850 $FSTAB" >/dev/null 2>&1
"$MB" repack boot.img new-boot.img >/dev/null 2>&1
[ "$(stat -c %s new-boot.img)" -le 46137344 ] || { echo "boot too big"; exit 1; }
"$MB" unpack -h new-boot.img 2>&1 | grep -E 'KERNEL_SZ|DTB_SZ|CMDLINE' || true
strings kernel | grep -m1 'Linux version' || true
ls -la new-boot.img dtbo.img
