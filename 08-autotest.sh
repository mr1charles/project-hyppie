#!/usr/bin/env bash
# Stage 8: automated on-phone test run over ADB (no human needed).
# Drives the HyperOS UI with simulated input, screenshots every step, measures animation jank,
# checks hardware services and collects crashes/ANRs. Output: tests/<time>/report.txt + PNGs.
set -uo pipefail
P=$HOME/ProjectHyppie
T=$P/tests/$(date +%m%d-%H%M); mkdir -p "$T"
A="adb shell"
log() { echo "$*" | tee -a "$T/report.txt"; }
shot() { adb exec-out screencap -p > "$T/$1.png" 2>/dev/null; }
W=1080; H=2408

log "=== Project Hyppie autotest $(date)"
log "build: $($A getprop ro.build.version.incremental | tr -d '\r') kernel: $($A uname -r | tr -d '\r') selinux: $($A getenforce | tr -d '\r')"
log "boot_completed=$($A getprop sys.boot_completed | tr -d '\r')  uptime: $($A uptime | tr -d '\r')"

# keep the screen on and unlocked for the run
$A svc power stayon true; $A input keyevent KEYCODE_WAKEUP; sleep 1
$A wm dismiss-keyguard 2>/dev/null; $A input swipe $((W/2)) $((H*9/10)) $((W/2)) $((H/4)) 250; sleep 1
$A dumpsys gfxinfo --reset >/dev/null 2>&1
shot 01-home

# UI interactions that exercise the animations
$A input swipe $((W/2)) $((H*99/100)) $((W/2)) $((H/2)) 300; sleep 2; shot 02-recents   # swipe up and hold -> recents
$A input keyevent KEYCODE_HOME; sleep 1
$A input swipe $((W*9/10)) 5 $((W*9/10)) $((H*6/10)) 250; sleep 2; shot 03-control-center
$A input keyevent KEYCODE_BACK; sleep 1
$A input swipe $((W/10)) 5 $((W/10)) $((H*6/10)) 250; sleep 2; shot 04-notifications
$A input keyevent KEYCODE_HOME; sleep 1
for i in 1 2 3; do $A input swipe $((W*8/10)) $((H/2)) $((W/10)) $((H/2)) 200; sleep 0.6; done; shot 05-homepages
for i in 1 2 3; do $A input swipe $((W/10)) $((H/2)) $((W*8/10)) $((H/2)) 200; sleep 0.6; done
$A am start -a android.settings.SETTINGS >/dev/null 2>&1; sleep 2; shot 06-settings
for i in 1 2 3; do $A input swipe $((W/2)) $((H*8/10)) $((W/2)) $((H/4)) 200; sleep 0.5; done; shot 07-settings-scrolled
$A input keyevent KEYCODE_HOME; sleep 1
$A am start -a android.media.action.STILL_IMAGE_CAMERA >/dev/null 2>&1; sleep 4; shot 08-camera
$A input keyevent KEYCODE_HOME; sleep 1

log "--- animation smoothness (since reset)"
$A dumpsys gfxinfo 2>/dev/null | grep -E '^\*\* Graphics info|Total frames rendered|Janky frames|50th percentile|90th percentile|99th percentile' | tr -d '\r' | tee -a "$T/report.txt" >/dev/null
log "--- SurfaceFlinger"
$A dumpsys SurfaceFlinger --timestats -dump 2>/dev/null | grep -E 'totalFrames|missedFrames|clientCompositionFrames|averageFPS' | head -6 | tr -d '\r' | tee -a "$T/report.txt" >/dev/null
log "--- touch"; $A cat /sys/class/sec/tsp/cmd_result 2>&1 | tr -d '\r' | tee -a "$T/report.txt" >/dev/null
log "--- proximity / sensors"
$A dumpsys sensorservice 2>/dev/null | grep -i -E 'proximity|light' | head -6 | tr -d '\r' | tee -a "$T/report.txt" >/dev/null
for svc in "wifi:cmd wifi status" "bluetooth:dumpsys bluetooth_manager" "telephony:dumpsys telephony.registry" \
           "camera:dumpsys media.camera" "fingerprint:dumpsys fingerprint" "audio:dumpsys media.audio_flinger" \
           "usb:dumpsys usb" "location:dumpsys location"; do
    name=${svc%%:*}; cmd=${svc#*:}
    log "--- $name"
    $A $cmd 2>&1 | grep -E 'enabled|state|Number of camera|Device [0-9]|mServiceState|mCurrentFunctions|Output thread|sensor|Wifi is|gps' | head -5 | tr -d '\r' | tee -a "$T/report.txt" >/dev/null
done
log "--- crashes"; $A logcat -b crash -d 2>/dev/null | grep -E 'Cmdline|Abort message|FATAL EXCEPTION|Process:' | sort | uniq -c | sort -rn | head -12 | tr -d '\r' | tee -a "$T/report.txt" >/dev/null
log "--- ANRs"; $A logcat -d 2>/dev/null | grep 'ANR in' | sort | uniq -c | head -8 | tr -d '\r' | tee -a "$T/report.txt" >/dev/null
log "--- top CPU"; $A top -b -n 1 -m 10 2>/dev/null | tail -11 | tr -d '\r' | tee -a "$T/report.txt" >/dev/null
$A svc power stayon false
log "screenshots: $(ls "$T"/*.png 2>/dev/null | wc -l) in $T"
