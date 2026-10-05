#!/usr/bin/env bash
# Stage 1: unpack the Xiaomi.eu HyperOS 3 base ROM and report what is inside.
# Safe: only reads the zip and writes into ~/ProjectHyppie/work/base-img
set -euo pipefail
P=~/ProjectHyppie
ZIP=${1:-$P/base/xiaomi.eu_HOUJI_OS3.0.307.0.zip}
OUT=$P/work/base-img
mkdir -p "$OUT"
[[ -f $ZIP ]] || { echo "missing $ZIP"; exit 1; }
unzip -tqq "$ZIP" >/dev/null || { echo "zip is incomplete or corrupt"; exit 1; }
echo "== zip contents"; unzip -l "$ZIP" | awk 'NR>3{print $1, $4}' | sort -k2 | head -60

if unzip -l "$ZIP" | grep -q payload.bin; then
  echo "== payload.bin ROM: extracting"
  mkdir -p "$P/work/zip" && unzip -o -q "$ZIP" payload.bin payload_properties.txt -d "$P/work/zip"
  "$P/tools/pv/bin/payload_dumper" --out "$OUT" "$P/work/zip/payload.bin"
else
  echo "== image-style ROM: extracting *.img"
  unzip -o -q "$ZIP" '*.img' 'images/*' -d "$OUT" || true
fi
echo "== extracted"; ls -la "$OUT"
