#!/usr/bin/env bash
set -euo pipefail

SERIAL="${SERIAL:-emulator-5554}"

echo "=== CI Device Reset (serial=$SERIAL) ==="

# Verify device connected
if ! adb -s "$SERIAL" get-state >/dev/null 2>&1; then
    echo "ERROR: Device $SERIAL not found"
    adb devices
    exit 1
fi

# Set SELinux permissive (required for dumpsys tyfo service registration)
adb -s "$SERIAL" shell setenforce 0 || true

# Force stop and clear app data
adb -s "$SERIAL" shell am force-stop com.shiro.tyfo || true
adb -s "$SERIAL" shell pm clear com.shiro.tyfo 2>/dev/null || true

# Return to home screen
adb -s "$SERIAL" shell input keyevent HOME || true
sleep 1

# Clear logcat
adb -s "$SERIAL" logcat -c

# Grant overlay permission
adb -s "$SERIAL" shell appops set com.shiro.tyfo SYSTEM_ALERT_WINDOW allow 2>/dev/null || true

echo "=== Device reset complete ==="
