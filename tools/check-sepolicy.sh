#!/usr/bin/env bash
# Compile the split SELinux policy the way Android init does, on the PC.
# Usage: check-sepolicy.sh SYSTEM_ROOT SYSTEM_EXT_ROOT PRODUCT_ROOT VENDOR_ROOT [ODM_ROOT]
# (each *_ROOT is the partition root, e.g. .../system/system for a system-as-root image)
set -euo pipefail
S=$1; SE=$2; PR=$3; V=$4; O=${5:-}
SECILC=$HOME/ProjectHyppie/tools/secilc
VER=$(cat "$V/etc/selinux/plat_sepolicy_vers.txt")
args=("$S/etc/selinux/plat_sepolicy.cil" "$S/etc/selinux/mapping/$VER.cil")
[ -f "$S/etc/selinux/mapping/$VER.compat.cil" ] && args+=("$S/etc/selinux/mapping/$VER.compat.cil")
[ -f "$S/etc/selinux/plat_sepolicy_genfs_$VER.cil" ] && args+=("$S/etc/selinux/plat_sepolicy_genfs_$VER.cil")
for d in "$SE" "$PR"; do
    for f in "$d"/etc/selinux/{system_ext,product}_sepolicy.cil "$d/etc/selinux/mapping/$VER.cil"; do
        [ -f "$f" ] && args+=("$f")
    done
done
args+=("$V/etc/selinux/plat_pub_versioned.cil" "$V/etc/selinux/vendor_sepolicy.cil")
[ -n "$O" ] && [ -f "$O/etc/selinux/odm_sepolicy.cil" ] && args+=("$O/etc/selinux/odm_sepolicy.cil")
echo "vendor policy version $VER, ${#args[@]} cil files"
"$SECILC" -m -M true -G -N -c 30 -o /tmp/hyppie-policy.bin -f /dev/null "${args[@]}" && echo "POLICY OK ($(stat -c %s /tmp/hyppie-policy.bin) bytes)"
