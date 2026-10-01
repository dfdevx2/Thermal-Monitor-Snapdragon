#!/system/bin/sh

set +e

PATTERNS=(
    "com.siliconfet.thermalmonitor.snapdragon:cmd"
    "siliconfet.thermalmonitor.snapdragon:cmd"
    "thermalmonitor.*cmd"
    "shizuku.*thermalmonitor"
)

for p in "${PATTERNS[@]}"; do
    pkill -9 -f "$p" 2>/dev/null
    killall -9 "$p" 2>/dev/null
done

for pid in $(ps -A -o PID,CMD 2>/dev/null | grep -E "thermalmonitor.*cmd|thermalmonitor:.*cmd" | grep -v grep | awk '{print $1}'); do
    kill -9 "$pid" 2>/dev/null
done

atrace --async_stop 2>/dev/null

echo "killed"
exit 0
