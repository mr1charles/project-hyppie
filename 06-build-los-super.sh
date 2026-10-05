#!/usr/bin/env bash
# Stage 6: LineageOS 23.2 (A21s) system/system_ext/product + hybrid vendor + odm
# -> super, plus patched boot -> AP-hyppie-los.tar. ADB key is always injected.
# Pass --flash to wipe data in TWRP, flash with odin4 and reboot (phone must be in TWRP over adb).
set -euo pipefail
P=$HOME/ProjectHyppie
W=$P/work/hybrid
L=$P/donor-lineage-a21s/img
ODM=$P/work/odm/odm.img

[ "${HYPPIE_RELEASE:-0}" = 1 ] || "$P/05-inject-adbkey.sh" "$L/system.img" | tail -1   # dev builds: ADB pre-authorized

SS=$(stat -c %s "$L/system.img"); SE=$(stat -c %s "$L/system_ext.img")
PR=$(stat -c %s "$L/product.img"); VS=$(stat -c %s "$W/vendor.img"); OS=$(stat -c %s "$ODM")
cd "$W"
rm -f super-los.sparse.img
lpmake --metadata-size 65536 --super-name super --metadata-slots 2 -d 6308233216 --group group_basic:6304038912 \
  --partition "system:readonly:${SS}:group_basic"         --image "system=$L/system.img" \
  --partition "system_ext:readonly:${SE}:group_basic"     --image "system_ext=$L/system_ext.img" \
  --partition "product:readonly:${PR}:group_basic"        --image "product=$L/product.img" \
  --partition "vendor:readonly:${VS}:group_basic"         --image "vendor=$W/vendor.img" \
  --partition "odm:readonly:${OS}:group_basic"            --image "odm=$ODM" \
  --sparse --output super-los.sparse.img >/dev/null 2>&1
cp super-los.sparse.img super.img && lz4 -B6 --content-size -f -q super.img super.img.lz4 && rm super.img
cp $P/work/boot/new-boot.img boot.img && lz4 -B6 --content-size -f -q boot.img boot.img.lz4 && rm boot.img
cp $P/work/boot/dtbo.img dtbo.img && lz4 -B6 --content-size -f -q dtbo.img dtbo.img.lz4 && rm dtbo.img
rm -f AP-hyppie-los.tar && tar -cf AP-hyppie-los.tar boot.img.lz4 dtbo.img.lz4 super.img.lz4
ls -la AP-hyppie-los.tar

[[ ${1:-} == --flash ]] || exit 0
[[ $(adb devices | awk 'NR==2{print $2}') == recovery ]] || { echo "phone not in TWRP over adb"; exit 1; }
adb shell 'twrp format data' | tail -1
adb reboot download
D=""; for i in $(seq 1 30); do D=$(odin4 -l 2>&1); [[ $D == /dev* ]] && break; sleep 2; done
[[ $D == /dev* ]] || { echo "download mode not detected"; exit 1; }
odin4 -a AP-hyppie-los.tar --reboot -d "$D" > flash-los.log 2>&1 && echo "flash ok" || { echo "flash failed"; tail -5 flash-los.log; exit 1; }
