#!/usr/bin/env bash
# 세움 시연: 내비가 떠 있는 상태에서 도산공원 쪽으로 천천히 접근 → 감속 → 소화전 앞 정차 → 기어 P.
# Virtual Console(REST)로 기어·속도를, adb emu geo fix로 위치를 바꾼다. 앱은 실제 신호로 판정한다.
#
#   bash tools/approach.sh            # 기본: 도산공원 소화전
#   RECORD=1 bash tools/approach.sh   # 화면 녹화(/sdcard/seum_demo.mp4)도 함께
set -euo pipefail
export MSYS_NO_PATHCONV=1   # Git Bash: keep /sdcard paths as device paths
ADB="${ADB:-$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe}"
VC=http://127.0.0.1:8088
"$ADB" forward tcp:8088 tcp:8088 >/dev/null

beat() { curl -s -o /dev/null -X POST "$VC/browser/session?client=seum-approach" || true; }
# The console sometimes drops a request right after a session beat; retry instead of aborting the run.
put() { for _ in 1 2 3; do beat; curl -s -m 5 -o /dev/null -X PUT "$VC/$1" && return 0; sleep 0.5; done; echo "  (VC 요청 실패: $1)"; }
hold() { for _ in $(seq "$1"); do beat; sleep 1; done; }
geo() { "$ADB" emu geo fix "$2" "$1" >/dev/null; }   # lat lon (adb takes lon first)

# Path: 도산대로 북쪽에서 남쪽으로 약 170 m, 마지막 점이 소화전 2 m 앞 (DemoSpots.dosan_hydrant)
PATH_PTS=(
  "37.52620 127.03372" "37.52603 127.03373" "37.52586 127.03374" "37.52570 127.03374"
  "37.52555 127.03375" "37.52540 127.03376" "37.52525 127.03376" "37.52512 127.03377"
  "37.52500 127.03377" "37.52489 127.03377" "37.52478 127.03378" "37.52470 127.03378"
  "37.52465 127.03378" "37.524622 127.033780"
)
SPEEDS=(30 30 28 25 22 20 18 15 12 10 7 4 2 0)

# Real GPS (clear any demo-panel position), then judge at 12:30 so the result is the same on every run.
"$ADB" shell am broadcast -n ai.seum.app/.service.DemoClockReceiver --ez clear true >/dev/null
"$ADB" shell am broadcast -n ai.seum.app/.service.DemoClockReceiver --ei hour 12 --ei minute 30 >/dev/null
put "vehicle/body?frunk=0&trunk=0&door_fl=0&door_fr=0&door_rl=0&door_rr=0"
"$ADB" shell monkey -p ai.umos.maps.android.navigation.app -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
geo ${PATH_PTS[0]}
hold 2

if [[ "${RECORD:-}" == "1" ]]; then
  "$ADB" shell rm -f /sdcard/seum_demo.mp4
  ("$ADB" shell screenrecord --time-limit 120--bit-rate 8000000 /sdcard/seum_demo.mp4 >/dev/null 2>&1 &)
  sleep 1
fi

echo "▶ 주행 시작 (내비 화면)"
put "vehicle/gear?gear=Drive"
for i in "${!PATH_PTS[@]}"; do
  s=${SPEEDS[$i]}
  if (( s > 0 )); then put "vehicle/controls?speed=$s&accelerator=15&brake=0"; else put "vehicle/controls?speed=0&accelerator=0&brake=70"; fi
  geo ${PATH_PTS[$i]}
  hold 1
done
echo "▶ 정차 → 기어 P"
hold 1
put "vehicle/gear?gear=Park"
hold 12   # 판정 + 음성 안내 + "안내할까요?" 듣기

echo "▶ 알림센터 → 세움 알림 열기"
"$ADB" shell cmd statusbar expand-notifications >/dev/null 2>&1 || true
hold 4
"$ADB" shell cmd statusbar collapse >/dev/null 2>&1 || true
"$ADB" shell am start -n ai.seum.app/.ui.MainActivity >/dev/null
hold 10

put "vehicle/controls?speed=0&accelerator=0&brake=0"
"$ADB" shell am broadcast -n ai.seum.app/.service.DemoClockReceiver --ez clear true >/dev/null
echo "끝"
