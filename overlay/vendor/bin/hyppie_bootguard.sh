#!/vendor/bin/sh
export PATH=/system/bin:/system/xbin:/vendor/bin:/vendor/xbin:$PATH
# Project Hyppie boot guard: if Android fails to finish booting 3 times in a row,
# reboot into recovery (TWRP). Keeps the last logcats in /metadata/hyppie for TWRP.
D=/metadata/hyppie
mkdir -p $D
case "$1" in
start)
    n=$(cat $D/count 2>/dev/null)
    n=$(( ${n:-0} + 1 ))
    echo $n > $D/count
    echo "hyppie-bootguard: boot attempt $n" > /dev/kmsg
    [ -f $D/logcat.2.txt ] && mv -f $D/logcat.2.txt $D/logcat.3.txt
    [ -f $D/logcat.1.txt ] && mv -f $D/logcat.1.txt $D/logcat.2.txt
    [ -f $D/logcat.txt ] && mv -f $D/logcat.txt $D/logcat.1.txt
    if [ $n -gt 3 ]; then
        echo 0 > $D/count
        echo "hyppie-bootguard: $((n - 1)) boots never completed, rebooting to recovery" > /dev/kmsg
        sync
        setprop sys.powerctl reboot,recovery
    fi
    ;;
timer)
    # system_server crash loop (e.g. a bad framework overlay): the kernel never reboots, so count
    # system_server restarts and go to recovery after 4 of them before boot completes
    (
        last=""; restarts=0
        while [ "$(getprop sys.boot_completed)" != "1" ]; do
            pid=$(pidof system_server)
            if [ -n "$pid" ] && [ -n "$last" ] && [ "$pid" != "$last" ]; then
                restarts=$((restarts + 1))
                echo "hyppie-bootguard: system_server restarted ($restarts)" > /dev/kmsg
            fi
            [ -n "$pid" ] && last=$pid
            if [ $restarts -ge 4 ]; then
                echo "hyppie-bootguard: system_server crash loop, rebooting to recovery" > /dev/kmsg
                echo 0 > $D/count; sync
                setprop sys.powerctl reboot,recovery
            fi
            sleep 3
        done
    ) &
    # a boot that hangs without rebooting would never trip the counter: give up after 12 minutes
    sleep 720
    if [ "$(getprop sys.boot_completed)" != "1" ] && pidof system_server >/dev/null; then
        echo "hyppie-bootguard: system_server is up, giving it 13 more minutes" > /dev/kmsg
        echo 1 > /sys/module/sec_reboot/parameters/hyppie_boot_done   # guard takes over from the kernel timer
        sleep 780
    fi
    if [ "$(getprop sys.boot_completed)" != "1" ]; then
        echo "hyppie-bootguard: boot not completed after 12 min, rebooting to recovery" > /dev/kmsg
        echo 0 > $D/count
        sync
        setprop sys.powerctl reboot,recovery
    fi
    ;;
earlyadb)
    # ADB over USB from early boot (before system_server and the USB gadget HAL take over).
    # adbd resets its FunctionFS every second until bound, so keep (re)binding the UDC.
    [ "$(stat -c %s $D/earlyadb.log 2>/dev/null || echo 0)" -gt 262144 ] && : > $D/earlyadb.log
    exec >> $D/earlyadb.log 2>&1
    echo "=== earlyadb start $(date)"
    # adbd only looks for its USB FunctionFS once at startup: start (or restart) it only after
    # /dev/usb-ffs/adb is mounted, otherwise it serves TCP only and USB ADB never works.
    i=0; while [ ! -e /dev/usb-ffs/adb/ep0 ] && [ $i -lt 120 ]; do sleep 1; i=$((i + 1)); done
    echo "ffs ep0 present after ${i}s"
    if pidof adbd >/dev/null; then setprop ctl.restart adbd; else setprop ctl.start adbd; fi
    G=/config/usb_gadget/g1
    i=0; S=""
    while [ $i -lt 1500 ]; do
        U=$(ls /sys/class/udc 2>/dev/null | head -1)
        R=$(getprop sys.usb.ffs.ready)
        B=$(cat $G/UDC 2>/dev/null)
        [ $((i % 15)) = 0 ] && echo "t=$i udc=$U ffs=$R ep1=$(ls /dev/usb-ffs/adb/ep1 2>&1 | head -c 40) bound=$B config=$(getprop sys.usb.config) adbd=$(pidof adbd) boot=$(getprop sys.boot_completed)"
        # once system_server runs, its UsbDeviceManager + the gadget HAL own the USB port: binding it here
        # as well raced the HAL's mode switch (~17 s in) and left USB stuck until a reboot
        SS=$(pidof system_server)
        if [ -z "$SS" ] && [ -n "$U" ] && { [ "$R" = "1" ] || [ -e /dev/usb-ffs/adb/ep1 ]; } && [ -z "$B" ] && [ -d $G ]; then
            echo 0x18d1 > $G/idVendor
            echo 0x4ee7 > $G/idProduct
            rm -f $G/configs/b.1/f1 $G/configs/b.1/f2 $G/configs/b.1/f3
            echo adb > $G/configs/b.1/strings/0x409/configuration
            ln -s $G/functions/ffs.adb $G/configs/b.1/f1
            echo "$U" > $G/UDC && echo "t=$i bound adb to $U"
        fi
        if [ -z "$S" ] && /system/bin/service check settings 2>/dev/null | grep -q found; then
            if /system/bin/cmd settings put global adb_enabled 1 && /system/bin/cmd settings put global development_settings_enabled 1; then
                S=1; setprop persist.sys.usb.config mtp,adb
                echo "t=$i adb_enabled=1 written to settings"
            fi
        fi
        [ "$(getprop sys.boot_completed)" = "1" ] && [ -n "$S" ] && { echo "boot completed, gadget HAL takes over"; break; }
        sleep 1
        i=$((i + 1))
    done
    ;;
touch)
    # A13 touchscreen (Himax HX83112F, TDDI). Its driver is built for Samsung's framework, which writes the
    # screen state to /sys/class/sec/tsp/enabled ("LCD_STATE,EVENT": 1=off 2=on, 0=early 1=late); that is
    # what properly suspends/resumes the chip (dts: himax,enable_sysinput_enabled). HyperOS never writes it,
    # so the chip got stuck after the first screen-off. Follow the display driver's own power messages and
    # send the same signals. (Earlier check_connection resets only papered over this and could wedge it.)
    E=/sys/class/sec/tsp/enabled
    i=0; while [ ! -e $E ] && [ $i -lt 60 ]; do sleep 1; i=$((i + 1)); done
    sleep 3; echo check_connection > /sys/class/sec/tsp/cmd 2>/dev/null   # once at boot (always worked)
    cat /sys/class/sec/tsp/cmd_result >/dev/null 2>&1
    echo "hyppie-touch: following screen state for the touch driver" > /dev/kmsg
    n=$(dmesg | wc -l)
    # Touch and display share one chip (TDDI): never signal touch while the display is powering up/down.
    # Screen off: nothing. Screen on: wait until the backlight is lit (display fully up), then send only the
    # late "LCD on" event -> the driver's clean late_resume.
    dmesg -w | tail -n +$((n + 1)) | while read -r line; do
        case "$line" in
            *"panel_power_on panel_state:OFF -> ON"*)
                j=0
                while [ $j -lt 30 ]; do
                    read -r b < /sys/class/backlight/panel/brightness 2>/dev/null; [ "${b:-0}" -gt 0 ] && break
                    sleep 0.1; j=$((j + 1))
                done
                sleep 0.2; echo 2,1 > $E; echo "hyppie-touch: display up -> touch late_resume" > /dev/kmsg ;;
        esac
    done
    ;;
selftest)
    # On-device self test after every boot: what works, what crashed, how fast it is.
    R=$D/selftest.txt
    echo "selftest service started $(date), waiting 90s" > $R
    sleep 90
    {
    echo "=== Project Hyppie self-test $(date)"
    echo "build: $(getprop ro.build.version.incremental) android $(getprop ro.build.version.release) kernel $(uname -r)"
    echo "boot_completed=$(getprop sys.boot_completed) uptime: $(uptime)"
    echo "selinux: $(getenforce)  abi: $(getprop ro.product.cpu.abilist)"
    echo "--- memory"; head -3 /proc/meminfo
    echo "--- touch"; cat /sys/class/sec/tsp/cmd_result 2>&1; grep -A2 -i 'sec_touchscreen' /proc/bus/input/devices | head -3
    echo "--- display"; dumpsys SurfaceFlinger 2>/dev/null | grep -E 'activeMode|refresh' | head -3
    echo "--- jank (all apps since boot)"; dumpsys gfxinfo 2>/dev/null | grep -E 'Total frames rendered|Janky frames' | head -6
    echo "--- wifi"; cmd wifi status 2>&1 | head -3
    echo "--- bluetooth"; dumpsys bluetooth_manager 2>/dev/null | grep -E 'enabled|state:' | head -3
    echo "--- telephony"; dumpsys telephony.registry 2>/dev/null | grep -E 'mServiceState|mDataConnectionState' | head -3
    echo "--- camera"; dumpsys media.camera 2>/dev/null | grep -E 'Number of camera devices|Camera ID|Device [0-9]' | head -6
    echo "--- fingerprint"; dumpsys fingerprint 2>/dev/null | head -5
    echo "--- sensors"; dumpsys sensorservice 2>/dev/null | grep -c -E 'handle=' ; dumpsys sensorservice 2>/dev/null | grep -E 'Sensor List' -A12 | head -13
    echo "--- audio"; dumpsys media.audio_flinger 2>/dev/null | grep -E 'Output thread|Input thread' | head -4
    echo "--- gps"; dumpsys location 2>/dev/null | grep -i -E 'gps.*(enabled|provider)' | head -3
    echo "--- vibrator"; dumpsys vibrator_manager 2>/dev/null | head -3
    echo "--- usb"; dumpsys usb 2>/dev/null | grep -E 'mCurrentFunctions|mConnected|mConfigured' | head -3
    echo "--- crashes since boot"; logcat -b crash -d 2>/dev/null | grep -E 'Cmdline|Abort message|FATAL EXCEPTION|Process:' | sort | uniq -c | sort -rn | head -15
    echo "--- ANRs"; logcat -d 2>/dev/null | grep -E 'ANR in' | sort | uniq -c | head -10
    echo "--- top CPU"; top -b -n 1 -m 12 2>/dev/null | tail -13
    echo "--- thermal"; for z in /sys/class/thermal/thermal_zone*; do echo "$(cat $z/type 2>/dev/null)=$(cat $z/temp 2>/dev/null)"; done | head -8
    echo "--- selinux denials (top)"; logcat -d 2>/dev/null | grep 'avc:  denied' | grep -o 'scontext=u:r:[a-z0-9_]*' | sort | uniq -c | sort -rn | head -10
    } >> $R 2>&1
    echo "hyppie-selftest: report written to $R" > /dev/kmsg
    sleep 90; setprop ctl.stop hyppie_logcat   # boot is logged; stop writing logs to eMMC (causes lag)
    ;;
keepalive)
    # Permanent ADB watchdog (USB + Wi-Fi port 5555): every 20 s check that ADB really works and
    # repair only what is broken. Also turns off Xiaomi proximity lock / accidental-touch blocking.
    [ "$(stat -c %s $D/keepalive.log 2>/dev/null || echo 0)" -gt 131072 ] && : > $D/keepalive.log
    exec >> $D/keepalive.log 2>&1
    echo "=== keepalive $(date)"
    while [ "$(getprop sys.boot_completed)" != "1" ]; do sleep 2; done
    sleep 10
    i=0; while ! /system/bin/service check settings 2>/dev/null | grep -q found && [ $i -lt 60 ]; do sleep 2; i=$((i + 1)); done
    for kv in "global adb_enabled 1" "global development_settings_enabled 1" \
              "system enable_screen_on_proximity_sensor 0" "system pocket_mode_enable 0" \
              "secure pocket_mode_enable 0" "global pocket_mode 0" "system proximity_sensor_enabled 0"; do
        /system/bin/cmd settings put $kv && echo "settings put $kv"
    done
    setprop service.adb.tcp.port 5555
    G=/config/usb_gadget/g1
    unbound=0
    while true; do
        fixed=""
        if ! pidof adbd >/dev/null; then setprop ctl.start adbd; fixed="adbd started"; sleep 3
        elif [ -e /dev/usb-ffs/adb/ep0 ] && [ ! -e /dev/usb-ffs/adb/ep1 ]; then
            setprop ctl.restart adbd; fixed="adbd restarted (no FunctionFS)"; sleep 3
        fi
        # only touch USB when the gadget really is unbound (sys.usb.state is often empty on HyperOS
        # even while USB ADB works); Android 16 rejects "mtp,adb" here, adb is added automatically
        if [ -e /dev/usb-ffs/adb/ep0 ] && [ -z "$(cat $G/UDC 2>/dev/null)" ]; then
            unbound=$((unbound + 1))
            if [ $unbound -ge 2 ]; then
                # stuck for 40 s: the A21s gadget HAL silently fails to bind on this phone, so bind the
                # port ourselves exactly as Samsung does: MTP (kernel mtp.gs0) + ADB, 04e8:6860
                rm -f $G/configs/b.1/f1 $G/configs/b.1/f2 $G/configs/b.1/f3
                echo 0x04e8 > $G/idVendor; echo 0x6860 > $G/idProduct
                ln -s $G/functions/mtp.gs0 $G/configs/b.1/f1
                ln -s $G/functions/ffs.adb $G/configs/b.1/f2
                ls /sys/class/udc | head -1 > $G/UDC
                fixed="$fixed; usb bound by hand (mtp + adb)"; unbound=0
            fi
        else
            unbound=0
        fi
        [ -n "$fixed" ] && echo "$(date +%T) repaired: $fixed"
        sleep 20
    done
    ;;
logcat)
    # /metadata is only 24 MB and Android needs it (aconfig flag storage, encryption keys): a full
    # /metadata made aconfigd fail -> missing platform flags -> system_server crash loop (RANGING).
    # Keep at most 3 x 1 MB here and drop anything older logcat setups left behind.
    rm -f $D/logcat.txt.* $D/logcat.[0-9]*.txt
    exec /system/bin/logcat -b main,system,crash,events,kernel -v threadtime -f $D/logcat.txt -r 1024 -n 2
    ;;
done)
    echo 0 > $D/count
    echo 1 > /sys/module/sec_reboot/parameters/hyppie_boot_done 2>/dev/null   # cancel kernel fail-safe timer
    echo "hyppie-bootguard: boot completed" > /dev/kmsg
    ;;
compile)
    # First boot of a new build: compile every app once (speed-profile = only the code each app really uses,
    # so it stays light on RAM/storage) and show it as a notification. Marker is one line in /metadata.
    build="$(getprop ro.vendor.hyppie.build)"
    [ "$(cat $D/compiled 2>/dev/null)" = "$build" ] && exit 0
    sleep 60   # let the launcher and first-boot jobs settle first
    N=/system/bin/cmd
    $N notification post -S bigtext -t "Compiling apps" hyppie_compile \
        "Making every app open faster. This runs once after an update; the phone stays usable." >/dev/null 2>&1
    start=$(date +%s)
    $N package compile -m speed-profile -a >/dev/null 2>&1
    $N package bg-dexopt-job >/dev/null 2>&1
    secs=$(( $(date +%s) - start ))
    $N notification post -S bigtext -t "Apps optimised" hyppie_compile \
        "All apps are optimised (took $((secs / 60)) min). Enjoy!" >/dev/null 2>&1
    echo "$build" > $D/compiled
    echo "hyppie-bootguard: apps compiled in ${secs}s" > /dev/kmsg
    ;;
esac
