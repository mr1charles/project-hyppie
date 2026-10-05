#!/bin/bash
# Install the AI Assistant on the phone and restore everything a reinstall can reset:
# runtime + special permissions, HyperOS app-start permissions, the agent (accessibility) service.
# Usage: install.sh [adb-serial]   (default: wireless ADB over Tailscale)
set -e
export ANDROID_SERIAL=${1:-100.115.67.125:5555}
cd "$(dirname "$0")"
adb install -r -g app/build/outputs/apk/debug/app-debug.apk | tail -1
adb shell '
P=dev.hyppie.ai
pm grant $P android.permission.POST_NOTIFICATIONS
pm grant $P android.permission.RECORD_AUDIO
appops set $P WRITE_SETTINGS allow                         # brightness
for op in 10021 10008 10020; do appops set $P $op allow; done   # HyperOS: start other apps, autostart
A=com.miui.screenshot/com.miui.screenshot.accessibility.ScreenshotAccessibilityService
settings put secure enabled_accessibility_services $A; sleep 2
settings put secure enabled_accessibility_services $A:$P/$P.AgentAccessibilityService
cmd role add-role-holder android.app.role.ASSISTANT $P 0 2>/dev/null
sleep 2
appops set $P WRITE_SETTINGS allow     # again: the package manager can reset these right after an install
pm grant $P android.permission.POST_NOTIFICATIONS; appops set --uid $P POST_NOTIFICATION allow
settings put secure enabled_accessibility_services $A; sleep 2   # rebind so the Super Island is re-posted
settings put secure enabled_accessibility_services $A:$P/$P.AgentAccessibilityService; sleep 3
echo "agent: $(dumpsys accessibility | grep -c "$P/")  write_settings: $(appops get $P WRITE_SETTINGS | cut -d";" -f1)  island: $(dumpsys notification | grep -c "pkg=$P")"'
