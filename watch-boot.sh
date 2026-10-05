#!/usr/bin/env bash
# Wait for the phone after a flash. Ends when Android finishes booting, or when the phone
# is in TWRP (boot guard / timer), in which case all logs are pulled to logs/<time>/.
P=$HOME/ProjectHyppie; start=$(date +%s); last=""; state=""
while [ $(( $(date +%s) - start )) -lt ${1:-2700} ]; do
    state=$(adb devices | awk 'NR==2{print $2}')
    u=$(lsusb -d 04e8: | sed 's/.*Ltd //' | cut -c1-40)
    cur="adb=$state usb=$u"; [ "$cur" != "$last" ] && echo "$(( $(date +%s) - start ))s: $cur" && last="$cur"
    if [ "$state" = device ]; then
        bc=$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')
        [ "$bc" = 1 ] && { echo "BOOT COMPLETED after $(( $(date +%s) - start ))s"; break; }
    fi
    [ "$state" = recovery ] && break
    sleep 10
done
if [ "$state" = recovery ]; then
    D=$P/logs/$(date +%m%d-%H%M); mkdir -p "$D"
    adb shell 'cat /proc/last_kmsg' > "$D/last_kmsg.bin" 2>/dev/null
    strings -n 6 "$D/last_kmsg.bin" > "$D/last_kmsg.str"
    adb shell 'mount /metadata 2>/dev/null'; adb pull /metadata/hyppie "$D/" >/dev/null 2>&1
    echo "IN TWRP: logs in $D"; ls "$D" "$D/hyppie" 2>/dev/null | tr '\n' ' '; echo
    for f in "$D"/hyppie/logcat.1.txt "$D/last_kmsg.str"; do
        [ -f "$f" ] && grep -a -E 'CRITICAL|InitFatal|Fatal|FATAL EXCEPTION|reboot,|Got shutdown_command|status [1-9].*waiting|hyppie-bootguard' "$f" | grep -v WDT | tail -12 | cut -c1-200
    done
fi
echo "final: $state"
