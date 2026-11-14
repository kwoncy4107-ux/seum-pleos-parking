#!/usr/bin/env bash
# 짧은 시연 클립(약 25초): 내비 화면에서 감속하며 자리 찾기 → 홍대 앞 골목 정차 → 기어 P →
# 세움 음성 안내(알림센터) → 하단 세움 아이콘 눌러 상세 화면.
# 녹화는 별도로: adb shell screenrecord --time-limit 30 /sdcard/seum_clip.mp4 (이 스크립트와 동시에)
#
# 준비(clip.sh가 직접 함): 시연 위치 해제, 판정 시각 13:00 고정, 피한 과태료 0원, 프렁크 닫기.
set -uo pipefail
export MSYS_NO_PATHCONV=1
ADB="${ADB:-$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe}"
VC=http://127.0.0.1:8088
"$ADB" forward tcp:8088 tcp:8088 >/dev/null

beat() { curl -s -m 3 -o /dev/null -X POST "$VC/browser/session?client=seum-clip" || true; }
put() { for _ in 1 2 3; do beat; curl -s -m 5 -o /dev/null -X PUT "$VC/$1" && return 0; sleep 0.3; done; }
geo() { "$ADB" emu geo fix "$2" "$1" >/dev/null; }

case "${1:-}" in
  prepare)
    "$ADB" shell am broadcast -n ai.seum.app/.service.DemoClockReceiver --ez reset_tally true --ez clear true >/dev/null
    "$ADB" shell am broadcast -n ai.seum.app/.service.DemoClockReceiver --ei hour 13 --ei minute 0 >/dev/null
    put "vehicle/body?frunk=0&trunk=0&door_fl=0&door_fr=0&door_rl=0&door_rr=0"
    put "vehicle/gear?gear=Park"
    put "vehicle/controls?speed=0&accelerator=0&brake=0"
    "$ADB" shell cmd notification cancel_all >/dev/null 2>&1 || true
    "$ADB" shell monkey -p ai.umos.maps.android.navigation.app -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
    geo 37.551711 126.920492
    exit 0 ;;
esac

# Seconds 0-1: navigation in front, start rolling
put "vehicle/gear?gear=Drive"
# 홍대 앞 골목(잔다리로 24, 2025년 단속 4,244건)으로 천천히 다가가 정차 (DemoSpots.hongdae)
PTS=("37.551711 126.920492" "37.551551 126.920492" "37.551391 126.920492" "37.551271 126.920492" "37.551171 126.920492" "37.551081 126.920492" "37.551011 126.920492" "37.550951 126.920492" "37.550911 126.920492")
SPD=(25 22 18 15 12 9 6 3 0)
for i in "${!PTS[@]}"; do
  if (( SPD[i] > 0 )); then put "vehicle/controls?speed=${SPD[$i]}&accelerator=10&brake=0"; else put "vehicle/controls?speed=0&accelerator=0&brake=70"; fi
  geo ${PTS[$i]}
  sleep 0.9
done
sleep 0.8
put "vehicle/gear?gear=Park"          # ~9 s: P → verdict + voice + notification
sleep 7
# ~17 s: tap the 세움 icon on the bottom bar (1920x1080 screen)
"$ADB" shell input tap 1140 1026
sleep 7
put "vehicle/controls?speed=0&accelerator=0&brake=0"
