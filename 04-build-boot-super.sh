#!/usr/bin/env bash
# Stage 4: patch fstab (vendor + boot ramdisk), repack vendor, build boot.img
# and a super image (system=arm64 GSI, vendor=hybrid, odm=A21s), then the odin tar.
set -euo pipefail
P=$HOME/ProjectHyppie
W=$P/work/hybrid
MB=$P/tools/magiskboot
GSI=$P/gsi/system-td-arm64-vanilla.img
ODM=$P/donor-lineage-a21s/img/odm.img
FSTAB=$W/vendor/etc/fstab.exynos850

# 1. partitions the GSI does not ship become optional
python3 - "$FSTAB" <<'PY'
import re, sys
p = sys.argv[1]; out = []
for line in open(p):
    f = line.split()
    if len(f) >= 5 and f[0] in ('product', 'system_ext') and 'nofail' not in f[4]:
        line = line.replace(f[4], f[4] + ',nofail', 1)
    out.append(line)
open(p, 'w').writelines(out)
PY
grep -E '^(product|system_ext)' "$FSTAB" | head -4
"$P/03-pack-vendor.sh" | tail -2

# 2. boot.img: stock A13 kernel, ramdisk fstab replaced by the hybrid one
B=$W/boot && rm -rf "$B" && mkdir -p "$B" && cd "$B"
lz4 -dqf "$P/stock/boot.img.lz4" boot.img
"$MB" unpack boot.img >/dev/null 2>&1
"$MB" cpio ramdisk.cpio "add 0640 fstab.exynos850 $FSTAB" >/dev/null
"$MB" repack boot.img new-boot.img >/dev/null 2>&1
ls -la new-boot.img
[ "$(stat -c %s new-boot.img)" -le 46137344 ] || { echo "boot too big"; exit 1; }

# 3. super: same geometry as stock (A-only, group_basic)
GS=$(stat -c %s "$GSI"); VS=$(stat -c %s "$W/vendor.img"); OS=$(stat -c %s "$ODM")
cd "$W"
rm -f super.sparse.img
lpmake --metadata-size 65536 --super-name super --metadata-slots 2 \
  -d 6308233216 --group group_basic:6304038912 \
  --partition "system:readonly:${GS}:group_basic" --image "system=$GSI" \
  --partition "vendor:readonly:${VS}:group_basic" --image "vendor=$W/vendor.img" \
  --partition "odm:readonly:${OS}:group_basic" --image "odm=$ODM" \
  --sparse --output super.sparse.img 2>&1 | grep -v 'sparse file format' || true
ls -la super.sparse.img

# 4. odin tar: super + boot (vbmeta/recovery already patched on the phone)
cp super.sparse.img super.img && lz4 -B6 --content-size -f -q super.img super.img.lz4 && rm super.img
cp "$B/new-boot.img" boot.img && lz4 -B6 --content-size -f -q boot.img boot.img.lz4 && rm boot.img
rm -f AP-hyppie-64.tar && tar -cf AP-hyppie-64.tar boot.img.lz4 super.img.lz4
tar tvf AP-hyppie-64.tar
