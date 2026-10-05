#!/usr/bin/env bash
# Stage 10: full automated device test over ADB (USB or wireless). Writes tests/full-<time>/report.txt
# with PASS / WARN / FAIL lines. The phone must be unlocked. Restores every setting it changes.
set -uo pipefail
P=$HOME/ProjectHyppie
export ANDROID_SERIAL=${ANDROID_SERIAL:-R58T42PC41R}
T=$P/tests/full-$(date +%m%d-%H%M); mkdir -p "$T"; R=$T/report.txt
A() { timeout 60 adb shell "$@" 2>/dev/null | tr -d '\r'; }
res() { printf '%-5s %-22s %s\n' "$1" "$2" "$3" | tee -a "$R"; }
chk() { if [ "${3:-0}" = 1 ]; then res PASS "$1" "$2"; else res FAIL "$1" "$2"; fi; }

echo "=== Project Hyppie full test $(date)  build $(A getprop ro.vendor.hyppie.build)" | tee "$R"
A svc power stayon true; A input keyevent KEYCODE_WAKEUP
[ "$(A dumpsys window | grep -m1 -o 'isKeyguardShowing=[a-z]*')" = "isKeyguardShowing=false" ] || { echo "unlock the phone first"; exit 1; }

# --- system
up=$(A cat /proc/uptime | cut -d. -f1); res INFO uptime "${up}s"
cr=$(A logcat -b crash -d | grep -c '>>> '); [ "$cr" = 0 ] && res PASS crashes "none since boot" || res WARN crashes "$cr native crashes: $(A logcat -b crash -d | grep -o '>>> [^ ]*' | sort | uniq -c | sort -rn | head -3 | tr '\n' ' ')"
ss=$(A logcat -d | grep -c 'FATAL EXCEPTION IN SYSTEM PROCESS'); chk system_server "fatal exceptions: $ss" $([ "$ss" = 0 ] && echo 1)
res INFO selinux "$(A getenforce)"
mf=$(A df /metadata | tail -1 | awk '{print $5}'); res INFO metadata "used $mf"

# --- display / touch / sensors
res INFO display "$(A wm size | tail -1) density $(A wm density | tail -1 | awk '{print $NF}')"
cut=$(A dumpsys window displays | grep -o 'boundingRect={[^}]*' | head -1); chk notch "${cut:-none}" $([ -n "$cut" ] && echo 1)
ns=$(A dumpsys sensorservice | grep -m1 -o 'Total [0-9]* h/w sensors'); chk sensors "$ns" $(echo "$ns" | grep -q -E 'Total (1[0-9]|[2-9][0-9])' && echo 1)
res INFO touch-fix "$(A getprop init.svc.hyppie_touch) (service), irq total $(A grep himax_tp /proc/interrupts | awk '{print $2}')"

# --- radios
wf=$(A cmd wifi status | grep -m1 -E 'Wifi is (connected|not connected)'); chk wifi "$wf" $(echo "$wf" | grep -q connected && echo 1)
inet=$(A ping -c 2 -W 3 8.8.8.8 | grep -c 'bytes from'); chk internet "ping 8.8.8.8: $inet/2" $([ "$inet" -ge 1 ] && echo 1)
bt0=$(A settings get global bluetooth_on)
A cmd bluetooth_manager enable; sleep 4; bt=$(A settings get global bluetooth_on)
chk bluetooth "enable -> bluetooth_on=$bt" $([ "$bt" = 1 ] && echo 1); [ "$bt0" = 0 ] && { A cmd bluetooth_manager disable; sleep 2; }
gps=$(A dumpsys location | grep -m1 -o 'gps provider \[u0\] enabled'); chk gps "${gps:-gps provider not enabled}" $([ -n "$gps" ] && echo 1)
sim=$(A getprop gsm.sim.state); res INFO sim "$sim (no SIM inserted = ABSENT)"
lte=$(A dumpsys telephony.registry | grep -m1 -o 'mLte=CellSignalStrengthLte: rssi=[-0-9]*'); res INFO modem "${lte:-no LTE signal reading}"

# --- camera / flashlight / audio / vibration / fingerprint
nc=$(A dumpsys media.camera | grep -m1 -o 'Number of camera devices: [0-9]*'); chk cameras "$nc" $(echo "$nc" | grep -q -E ': [1-9]' && echo 1)
ccr=$(A logcat -b crash -d | grep -c 'camera'); chk camera-hal "camera crashes since boot: $ccr" $([ "$ccr" = 0 ] && echo 1)
spk=$(A dumpsys media.audio_flinger | grep -c 'AUDIO_DEVICE_OUT_SPEAKER'); chk speaker "speaker output threads: $spk" $([ "$spk" -ge 1 ] && echo 1)
vib=$(A dumpsys vibrator_manager | grep -c -i 'capabilities'); chk vibrator "vibrator present: $vib" $([ "$vib" -ge 1 ] && echo 1)
fp=$(A dumpsys fingerprint | grep -o '"service":"[^"]*"' | head -1); chk fingerprint "registered: ${fp:-none}" $([ -n "$fp" ] && echo 1)
fpe=$(A logcat -d | grep -c 'isHardwareDetected is failed'); [ "$fpe" = 0 ] && res PASS fp-hardware "detected" || res WARN fp-hardware "framework reported 'isHardwareDetected failed' $fpe x (try enrolling in Settings)"

# --- storage: adoptable SD
vols=$(A sm list-volumes all | tr '\n' ' '); res INFO volumes "$vols"
sd=$(A sm list-volumes private | grep -o 'private:[0-9,]* mounted [0-9a-f-]*' | head -1)
chk sd-adopted "${sd:-no adopted SD}" $([ -n "$sd" ] && echo 1)
if [ -n "$sd" ]; then
  U=${sd##* }; res INFO sd-space "$(A df -h /mnt/expand/$U | tail -1 | awk '{print $2" total, "$4" free"}')"
  pri=$(A sm get-primary-storage-uuid); res INFO primary-storage "${pri} (null = files on internal; move needs every space unlocked once)"
  # an app can be moved onto the SD and back
  mv=$(A pm move-package org.lineageos.aperture $U); loc=$(A dumpsys package org.lineageos.aperture | grep -m1 -o "volumeUuid=[^ ]*")
  chk sd-app-move "move Aperture to SD: $mv, $loc" $(echo "$mv$loc" | grep -q -E "Success|$U" && echo 1)
  A pm move-package org.lineageos.aperture internal >/dev/null
fi

# --- root / tools
mg=$(A /debug_ramdisk/magisk -v); chk magisk "$mg" $(echo "$mg" | grep -q MAGISK && echo 1)
sz=$(A ps -A | grep -c shizuku_server); res INFO shizuku "server running: $sz (needs a start after each reboot unless granted root)"
res INFO memory "$(A free -m | sed -n 2p | awk '{print $2" MB RAM, "$3" used"}'); zram $(A cat /proc/swaps | tail -1 | awk '{print $3/1024" MB, "$4/1024" MB used"}'); backing $(A cat /sys/block/zram0/backing_dev)"

# --- assistant
ac=$(A dumpsys accessibility | grep -c 'dev.hyppie.ai/'); chk assistant-agent "accessibility bound: $ac" $([ "$ac" -ge 1 ] && echo 1)
role=$(A cmd role get-role-holders android.app.role.ASSISTANT); chk assistant-role "default assistant: $role" $([ "$role" = dev.hyppie.ai ] && echo 1)
isl=$(A dumpsys notification | grep -c 'pkg=dev.hyppie.ai'); chk super-island "island notification posted: $isl" $([ "$isl" -ge 1 ] && echo 1)
A am start -n dev.hyppie.ai/.AssistActivity --es task "What is 12 times 12?" >/dev/null; sleep 14
ans=$(A 'uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; cat /sdcard/ui.xml' | grep -o 'text="[^"]*144[^"]*"' | head -1)
chk assistant-answer "${ans:-no answer}" $([ -n "$ans" ] && echo 1); A input keyevent KEYCODE_HOME

# --- performance
pkill_bench() { :; }
A dumpsys gfxinfo com.android.systemui reset >/dev/null; A dumpsys gfxinfo com.miui.home reset >/dev/null
A 'for i in 1 2 3; do input swipe 900 5 900 1500 250; sleep 1.2; input keyevent KEYCODE_BACK; sleep 0.8; done; for i in 1 2 3; do input swipe 860 1200 100 1200 200; sleep 0.8; input swipe 100 1200 860 1200 200; sleep 0.8; done'
for p in com.android.systemui com.miui.home; do
  j=$(A dumpsys gfxinfo $p | grep -m1 'Janky frames:' | grep -o '([0-9.]*%)' | tr -d '()%')
  [ -n "$j" ] && { if [ "${j%.*}" -le 8 ]; then res PASS "smooth:$p" "${j}% janky"; else res WARN "smooth:$p" "${j}% janky"; fi; }
done
for p in com.android.settings org.lineageos.aperture; do
  A am force-stop $p; sleep 1
  c=$(A cmd package resolve-activity --brief -c android.intent.category.LAUNCHER $p | tail -1)
  t=$(A am start -W -n "$c" | grep TotalTime | awk '{print $2}'); A input keyevent KEYCODE_HOME; sleep 1
  res INFO "launch:$p" "cold start ${t} ms"
done

A svc power stayon usb
echo "=== $(grep -c '^PASS' "$R") PASS, $(grep -c '^WARN' "$R") WARN, $(grep -c '^FAIL' "$R") FAIL  -> $R" | tee -a "$R"
