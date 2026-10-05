#!/usr/bin/env bash
# Stage 3b: odm from the A21s donor, minus its precompiled sepolicy (it ignores
# vendor policy changes such as the boot guard), packed as labeled ext4.
set -euo pipefail
P=$HOME/ProjectHyppie
SRC=$P/work/odm/odm
OUT=$P/work/odm
rm -rf "$OUT" && mkdir -p "$SRC"
fsck.erofs --extract="$SRC" --no-preserve-owner "$P/donor-lineage-a21s/img/odm.img" >/dev/null 2>&1
rm -f "$SRC"/etc/selinux/precompiled_sepolicy*
# identity: the donor odm describes the Galaxy A21s; present it as the A13 (SM-A135M)
sed -i -E 's/^(ro\.product\.odm\.model)=.*/\1=SM-A135M/; s/^(ro\.product\.odm\.device)=.*/\1=a13/; s/^(ro\.product\.odm\.name)=.*/\1=a13ub/; s/SM-A217[A-Z]*/SM-A135M/g; s/a21snsxx/a13ub/g; s|lineage_a21s/a21s|a13ub/a13|g' "$SRC/etc/build.prop"
# HyperOS target: 64-bit-only app ABIs, matching the vendor (see 02 HyperOS step)
if [ "${HYPPIE_TARGET:-}" = hyperos ]; then
    sed -i -E 's/^(ro\.odm\.product\.cpu\.abilist)=.*/\1=arm64-v8a/; s/^(ro\.odm\.product\.cpu\.abilist32)=.*/\1=/' "$SRC/etc/build.prop"
fi
cat "$P/gsi/plat_file_contexts" "$SRC/etc/selinux/odm_file_contexts" > "$OUT/file_contexts"
python3 - "$SRC" > "$OUT/fs_config.txt" <<'PY'
import os, sys
root = sys.argv[1]
print(' 0 0 0755'); print('odm 0 0 0755')
for d, dirs, files in os.walk(root):
    for n in sorted(dirs + files):
        p = os.path.join(d, n); rel = 'odm/' + os.path.relpath(p, root)
        print(f'{rel} 0 0 {"0755" if os.path.isdir(p) else "0644"}')
PY
IMG=$OUT/odm.img; rm -f "$IMG"
mke2fs -q -t ext4 -b 4096 -L odm -O ^has_journal,^metadata_csum -m 0 "$IMG" 1024
e2fsdroid -e -T 1230768000 -C "$OUT/fs_config.txt" -S "$OUT/file_contexts" -a /odm -f "$SRC" "$IMG"
e2fsck -fn "$IMG" >/dev/null && echo "odm fsck clean"
debugfs -R 'ls -l /etc/selinux' "$IMG" 2>/dev/null | awk '{print $NF}' | tr '\n' ' '; echo
