#!/usr/bin/env bash
# Stage 3: pack work/hybrid/vendor into an ext4 vendor image with Android
# permissions (fs_config) and SELinux labels (vendor_file_contexts).
set -euo pipefail
P=$HOME/ProjectHyppie
SRC=$P/work/hybrid/vendor
OUT=$P/work/hybrid
CTX=$OUT/file_contexts.combined
cat $P/gsi/plat_file_contexts "$SRC/etc/selinux/vendor_file_contexts" > "$CTX"
IMG=$OUT/vendor.img

# fs_config: "path uid gid mode" relative to the vendor root.
# Executables under bin/ belong to root:shell like AOSP; everything else root:root.
python3 - "$SRC" > "$OUT/fs_config.txt" <<'PY'
import os, stat, sys
root = sys.argv[1]
print(' 0 2000 0755')
print('vendor 0 2000 0755')
for d, dirs, files in os.walk(root):
    for name in sorted(dirs + files):
        p = os.path.join(d, name)
        rel = 'vendor/' + os.path.relpath(p, root)
        st = os.lstat(p)
        gid = 2000 if ('/bin/' in rel + '/' or os.path.isdir(p)) else 0
        if os.path.isdir(p):
            mode = 0o751 if rel.endswith('/bin') else 0o755
        elif st.st_mode & 0o111 or '/bin/' in rel:
            mode = 0o755   # anything in a bin dir must be executable (git blobs lose +x)
        else:
            mode = 0o644
        print(f'{rel} 0 {gid} {mode:04o}')
PY

BYTES=$(du -sb "$SRC" | cut -f1)
SIZE=$(( (BYTES * 115 / 100) / 4096 * 4096 + 64*1024*1024 ))   # 15% + 64 MiB headroom
rm -f "$IMG"
mke2fs -q -t ext4 -b 4096 -L vendor -O ^has_journal,^metadata_csum -m 0 "$IMG" $((SIZE / 4096))
e2fsdroid -e -T 1230768000 -C "$OUT/fs_config.txt" -S "$CTX" -a /vendor -f "$SRC" "$IMG"
e2fsck -fn "$IMG" >/dev/null && echo "fsck clean"
ls -la "$IMG"
