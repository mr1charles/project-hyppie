#!/usr/bin/env bash
# Stage 4b: stock A13 kernel boot.img with the hybrid vendor fstab in its ramdisk.
set -euo pipefail
P=$HOME/ProjectHyppie
MB=$P/tools/magiskboot
B=$P/work/boot
FSTAB=$P/work/hybrid/vendor/etc/fstab.exynos850
rm -rf "$B" && mkdir -p "$B" && cd "$B"
lz4 -dqf "$P/stock/boot.img.lz4" boot.img
"$MB" unpack boot.img >/dev/null 2>&1
"$MB" cpio ramdisk.cpio "add 0640 fstab.exynos850 $FSTAB" >/dev/null 2>&1
"$MB" repack boot.img new-boot.img >/dev/null 2>&1
[ "$(stat -c %s new-boot.img)" -le 46137344 ] || { echo "boot too big"; exit 1; }
"$MB" cpio ramdisk.cpio "extract fstab.exynos850 check.fstab" >/dev/null 2>&1
cmp -s check.fstab "$FSTAB" && echo "boot ok: ramdisk fstab matches vendor fstab"
