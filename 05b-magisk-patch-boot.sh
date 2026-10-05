#!/usr/bin/env bash
# Stage 5b: Magisk v30.7 root, applied to work/boot/new-boot.img on the PC.
# Mirrors assets/boot_patch.sh from the Magisk APK (same cpio layout, config and Samsung
# kernel hexpatches), using the x86_64 magiskboot and the arm64 on-device binaries.
# Output: work/boot/magisk-boot.img
set -euo pipefail
P=$HOME/ProjectHyppie
M=$P/tools/magisk
W=$P/work/boot/magisk
IN=$P/work/boot/new-boot.img
rm -rf "$W" && mkdir -p "$W" && cd "$W"

cp "$M/lib/x86_64/libmagiskboot.so" magiskboot && chmod +x magiskboot
cp "$M/lib/arm64-v8a/libmagiskinit.so" magiskinit
cp "$M/lib/arm64-v8a/libmagisk.so" magisk
cp "$M/lib/arm64-v8a/libinit-ld.so" init-ld
cp "$M/assets/stub.apk" stub.apk
cp "$IN" boot.img

./magiskboot unpack boot.img >/dev/null 2>&1
./magiskboot cpio ramdisk.cpio test >/dev/null 2>&1 && status=0 || status=$?
[ "$status" = 0 ] || { echo "ramdisk is not stock (status $status), refusing"; exit 1; }
SHA1=$(./magiskboot sha1 boot.img)
cp ramdisk.cpio ramdisk.cpio.orig

./magiskboot compress=xz magisk magisk.xz >/dev/null 2>&1
./magiskboot compress=xz stub.apk stub.xz >/dev/null 2>&1
./magiskboot compress=xz init-ld init-ld.xz >/dev/null 2>&1
cat > config <<E
KEEPVERITY=false
KEEPFORCEENCRYPT=true
RECOVERYMODE=false
VENDORBOOT=false
SHA1=$SHA1
E
./magiskboot cpio ramdisk.cpio \
  "add 0750 init magiskinit" \
  "mkdir 0750 overlay.d" \
  "mkdir 0750 overlay.d/sbin" \
  "add 0644 overlay.d/sbin/magisk.xz magisk.xz" \
  "add 0644 overlay.d/sbin/stub.xz stub.xz" \
  "add 0644 overlay.d/sbin/init-ld.xz init-ld.xz" \
  "patch" \
  "backup ramdisk.cpio.orig" \
  "mkdir 000 .backup" \
  "add 000 .backup/.magisk config" >/dev/null 2>&1

for dt in dtb kernel_dtb extra; do
  [ -f $dt ] && ./magiskboot dtb $dt patch >/dev/null 2>&1 && echo "patched fstab in $dt" || true
done

patched=false
./magiskboot hexpatch kernel \
  49010054011440B93FA00F71E9000054010840B93FA00F7189000054001840B91FA00F7188010054 \
  A1020054011440B93FA00F7140020054010840B93FA00F71E0010054001840B91FA00F7181010054 \
  >/dev/null 2>&1 && { patched=true; echo "removed Samsung RKP"; }
./magiskboot hexpatch kernel 821B8012 E2FF8F12 >/dev/null 2>&1 && { patched=true; echo "removed Samsung defex"; }
./magiskboot hexpatch kernel 70726F63615F636F6E66696700 70726F63615F6D616769736B00 \
  >/dev/null 2>&1 && { patched=true; echo "disabled Samsung PROCA"; }
$patched || rm -f kernel

./magiskboot repack boot.img magisk-boot.img >/dev/null 2>&1
[ "$(stat -c %s magisk-boot.img)" -le 46137344 ] || { echo "patched boot too big"; exit 1; }
cp magisk-boot.img "$P/work/boot/magisk-boot.img"
./magiskboot cpio "$P/work/boot/magisk-boot.img" 2>/dev/null || true
./magiskboot unpack -h "$P/work/boot/magisk-boot.img" >/dev/null 2>&1 && ./magiskboot cpio ramdisk.cpio ls 2>/dev/null | grep -E 'overlay.d|\.backup|^.* init$' | head -8
ls -la "$P/work/boot/magisk-boot.img"
