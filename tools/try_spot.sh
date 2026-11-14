#!/usr/bin/env bash
# 임의의 좌표에서 "정차 → 기어 P"를 재현하고 세움 화면을 캡처한다.
#   bash tools/try_spot.sh <lat> <lon> <out.png> [hour minute]
# hour/minute를 주면 그 시각 기준으로 판정(시연 시계), 없으면 현재 시각.
set -uo pipefail
export MSYS_NO_PATHCONV=1
ADB="${ADB:-$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe}"
VC=http://127.0.0.1:8088
LAT=$1; LON=$2; OUT=$3; H=${4:-}; M=${5:-0}
"$ADB" forward tcp:8088 tcp:8088 >/dev/null
beat() { curl -s -m 3 -o /dev/null -X POST "$VC/browser/session?client=seum-try" || true; }
put() { for _ in 1 2 3; do beat; curl -s -m 5 -o /dev/null -X PUT "$VC/$1" && return 0; sleep 0.3; done; }

"$ADB" shell am broadcast -n ai.seum.app/.service.DemoClockReceiver --ez clear true >/dev/null
[[ -n "$H" ]] && "$ADB" shell am broadcast -n ai.seum.app/.service.DemoClockReceiver --ei hour "$H" --ei minute "$M" >/dev/null
"$ADB" shell cmd notification cancel_all >/dev/null 2>&1 || true
# roll in from ~25 m north so the car is "arriving", then stop and shift to P
"$ADB" emu geo fix "$LON" "$(python -c "print($LAT + 0.000225)")" >/dev/null
put "vehicle/gear?gear=Drive"; put "vehicle/controls?speed=10&accelerator=10&brake=0"
sleep 2
"$ADB" emu geo fix "$LON" "$LAT" >/dev/null
put "vehicle/controls?speed=0&accelerator=0&brake=70"
sleep 2
put "vehicle/gear?gear=Park"
sleep 5
"$ADB" shell am start -n ai.seum.app/.ui.MainActivity >/dev/null 2>&1
sleep 3
"$ADB" exec-out screencap -p > "$OUT"
"$ADB" logcat -d -s Seum/Guard | grep -E "verdict at" | tail -1
