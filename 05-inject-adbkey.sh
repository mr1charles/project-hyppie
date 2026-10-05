#!/usr/bin/env bash
# Stage 5: pre-authorize this computer's ADB key in an ext4 system image (/adb_keys).
# Usage: 05-inject-adbkey.sh path/to/system.img
set -euo pipefail
IMG=$1
KEY=$HOME/.android/adbkey.pub
[[ -f $KEY ]] || { echo "no $KEY (run adb once to create it)"; exit 1; }
e2fsck -fy "$IMG" >/dev/null 2>&1 || true
resize2fs "$IMG" $(( $(stat -c %s "$IMG") / 4096 + 2048 )) >/dev/null 2>&1   # +8 MiB room
debugfs -w -R "rm /adb_keys" "$IMG" >/dev/null 2>&1 || true
debugfs -w -R "write $KEY adb_keys" "$IMG" >/dev/null 2>&1
debugfs -w -R "sif /adb_keys mode 0100644" "$IMG" >/dev/null 2>&1
debugfs -w -R "ea_set /adb_keys security.selinux u:object_r:system_file:s0" "$IMG" >/dev/null 2>&1
e2fsck -fn "$IMG" >/dev/null && echo "fsck clean"
debugfs -R "stat /adb_keys" "$IMG" 2>/dev/null | grep -E 'Mode|Size' | head -2
debugfs -R "ea_get /adb_keys security.selinux" "$IMG" 2>/dev/null
cmp <(debugfs -R "cat /adb_keys" "$IMG" 2>/dev/null) "$KEY" && echo "key matches $KEY"
