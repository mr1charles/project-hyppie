#!/usr/bin/env bash
# Project Hyppie: full rebuild + (optional) flash. Usage: build-all.sh [--flash]
# 02 hybrid vendor -> 03 pack vendor -> 03b odm -> 04c boot (A13 4.19.325 kernel) + dtbo
# -> 06 super (LineageOS 23.2 system, ADB key injected) -> flash from TWRP over adb.
set -euo pipefail
P=$HOME/ProjectHyppie
cd "$P"
python3 02-build-hybrid-vendor.py | grep -v 'blob not found'
./03-pack-vendor.sh | tail -1
./03b-pack-odm.sh | tail -2
./04c-build-boot-kernel.sh
./06-build-los-super.sh "${1:-}"
