#!/usr/bin/env bash
# Stage 7: HyperOS 3.1 (system, system_ext, product, mi_ext from the Xiaomi base, untouched)
# + hybrid vendor + odm -> super, plus kernel boot + dtbo -> AP-hyppie-hyperos.tar.
# Run after: HYPPIE_TARGET=hyperos 02 -> 03 -> 03b -> 04c. Pass --flash to flash from TWRP.
set -euo pipefail
P=$HOME/ProjectHyppie
W=$P/work/hybrid
H=$P/work/hos/parts
ODM=$P/work/odm/odm.img
OUT=$P/work/hyperos
mkdir -p "$OUT" && cd "$OUT"

# policy must compile before anything is flashed
"$P/tools/check-sepolicy.sh" "$P/work/hos/tree/system/system" "$P/work/hos/tree/system_ext" \
    "$P/work/hos/tree/product_sel" "$W/vendor" "$P/work/odm/odm" | tail -1 | grep -q 'POLICY OK' \
    || { echo "HyperOS policy does not compile, not flashing"; exit 1; }
echo "policy check: OK"

sz() { stat -c %s "$1"; }
args=()
for p in system system_ext product mi_ext; do
    args+=(--partition "${p}:readonly:$(sz $H/${p}_a.img):group_basic" --image "${p}=$H/${p}_a.img")
done
args+=(--partition "vendor:readonly:$(sz $W/vendor.img):group_basic" --image "vendor=$W/vendor.img")
args+=(--partition "odm:readonly:$(sz $ODM):group_basic" --image "odm=$ODM")
total=0; for f in $H/{system,system_ext,product,mi_ext}_a.img $W/vendor.img $ODM; do total=$((total + $(sz $f))); done
echo "partitions total: $((total / 1048576)) MiB of $((6304038912 / 1048576)) MiB"
rm -f super.sparse.img
lpmake --metadata-size 65536 --super-name super --metadata-slots 2 -d 6308233216 \
    --group group_basic:6304038912 "${args[@]}" --sparse --output super.sparse.img >/dev/null 2>&1
cp super.sparse.img super.img && lz4 -B6 --content-size -f -q super.img super.img.lz4 && rm super.img
BOOTSRC=$P/work/boot/new-boot.img
if [ "${HYPPIE_MAGISK:-0}" = 1 ]; then "$P/05b-magisk-patch-boot.sh" >/dev/null && BOOTSRC=$P/work/boot/magisk-boot.img && echo "boot: Magisk v30.7 rooted"; fi
cp "$BOOTSRC" boot.img && lz4 -B6 --content-size -f -q boot.img boot.img.lz4 && rm boot.img
cp $P/work/boot/dtbo.img dtbo.img && lz4 -B6 --content-size -f -q dtbo.img dtbo.img.lz4 && rm dtbo.img
rm -f AP-hyppie-hyperos.tar && tar -cf AP-hyppie-hyperos.tar boot.img.lz4 dtbo.img.lz4 super.img.lz4
ls -la AP-hyppie-hyperos.tar

[[ ${1:-} == --flash ]] || exit 0
[[ $(adb devices | awk 'NR==2{print $2}') == recovery ]] || { echo "phone not in TWRP over adb"; exit 1; }
[ "${HYPPIE_KEEP_DATA:-0}" = 1 ] && echo 'keeping data (no format)' || adb shell 'twrp format data' | tail -1
adb reboot download
D=""; for i in $(seq 1 30); do D=$(odin4 -l 2>&1); [[ $D == /dev* ]] && break; sleep 2; done
[[ $D == /dev* ]] || { echo "download mode not detected"; exit 1; }
odin4 -a AP-hyppie-hyperos.tar --reboot -d "$D" > flash.log 2>&1 && echo "flash ok" || { echo "flash failed"; tail -5 flash.log; exit 1; }
