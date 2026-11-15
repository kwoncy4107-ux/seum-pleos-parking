#!/usr/bin/env bash
# 시연 영상 장면 녹화: 에뮬레이터 화면(adb screenrecord)과 PC 소리(Pleos 음성)를 함께 녹음한다.
#   bash tools/record_scene.sh check  <out_dir>   # 장면 1: D단 정차 후 '이 자리 확인'
#   bash tools/record_scene.sh park   <out_dir>   # 장면 2: 내비를 띄운 채 정차 → P 자동 안내 → 상세 → 주차장 안내
# 결과: <out_dir>/<scene>.mp4 (화면), <scene>.wav (소리), <scene>.offset (소리 시작 대비 화면 시작 지연, 초)
set -uo pipefail
export MSYS_NO_PATHCONV=1
ADB="${ADB:-$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe}"
VC=http://127.0.0.1:8088
SCENE=$1; OUT=$2; mkdir -p "$OUT"
"$ADB" forward tcp:8088 tcp:8088 >/dev/null
beat() { curl -s -m 3 -o /dev/null -X POST "$VC/browser/session?client=seum-rec" || true; }
put() { for _ in 1 2 3; do beat; curl -s -m 5 -o /dev/null -X PUT "$VC/$1" && return 0; sleep 0.3; done; }
fix() { "$ADB" emu geo fix "$2" "$1" >/dev/null </dev/null; }   # lat lon
tap() { "$ADB" shell input tap "$1" "$2" </dev/null; }
now() { python -c "import time;print(time.time())"; }

# point d metres before (lat, lon) along heading h (degrees)
back() { python -c "import math;la,lo,h,d=$1,$2,$3,$4;r=math.radians(h);print(f'{la-d*math.cos(r)/111320:.7f} {lo-d*math.sin(r)/(111320*math.cos(math.radians(la))):.7f}')"; }

case $SCENE in
  check) LAT=37.638641; LON=127.024742; HEAD=129 ;;   # 수유 시장 골목
  park)  LAT=37.572498; LON=126.991473; HEAD=346 ;;   # 익선동
esac

# set up: demo clock 13:00, clean state, driving in D
"$ADB" shell am broadcast -n ai.seum.app/.service.DemoClockReceiver --ez clear true </dev/null >/dev/null
"$ADB" shell am broadcast -n ai.seum.app/.service.DemoClockReceiver --ez reset_tally true </dev/null >/dev/null
"$ADB" shell am broadcast -n ai.seum.app/.service.DemoClockReceiver --ei hour 13 --ei minute 0 </dev/null >/dev/null
put "vehicle/body?frunk=0&trunk=0&door_fl=0&door_fr=0&door_rl=0&door_rr=0"
put "vehicle/gear?gear=Drive"; put "vehicle/controls?speed=30&accelerator=20&brake=0"
read A B <<<"$(back $LAT $LON $HEAD 60)"; fix $A $B
if [[ $SCENE == check ]]; then
  "$ADB" shell am start -n ai.seum.app/.ui.MainActivity </dev/null >/dev/null 2>&1
else
  # open Seum once so its background guard is running (e.g. right after a reinstall), then the navigation
  "$ADB" shell am start -n ai.seum.app/.ui.MainActivity </dev/null >/dev/null 2>&1; sleep 4
  tap 822 1027   # navigation in front
fi
sleep 4

# start sound, then screen
rm -f "$OUT/$SCENE.wav"
# WASAPI loopback of the default speaker (the virtual DirectShow capture device added clicks)
rm -f "$OUT/$SCENE.wav.start"
python -I "$(dirname "$0")/loopback_rec.py" "$OUT/$SCENE.wav" 75 </dev/null &
FF=$!
until [ -s "$OUT/$SCENE.wav.start" ]; do sleep 0.1; done
TA=$(cat "$OUT/$SCENE.wav.start"); sleep 0.5
"$ADB" shell screenrecord --bit-rate 12000000 --time-limit 70 /sdcard/$SCENE.mp4 </dev/null &
SR=$!
TV=$(now)
python -c "print(round($TV-$TA+0.3,2))" > "$OUT/$SCENE.offset"   # +0.3: screenrecord start-up

# drive in: 60 m → 0 m over ~6 s, then brake to a stop
for d in 50 40 30 20 12 6; do read A B <<<"$(back $LAT $LON $HEAD $d)"; fix $A $B; sleep 1; done
put "vehicle/controls?speed=10&accelerator=0&brake=30"
fix $LAT $LON; sleep 1
put "vehicle/controls?speed=0&accelerator=0&brake=70"; sleep 3

if [[ $SCENE == check ]]; then
  tap 1270 563            # 이 자리 확인
  sleep 20                # voice warning + listening
else
  put "vehicle/gear?gear=Park"
  sleep 16                # automatic warning over the navigation
  tap 1097 1027           # Seum icon in the dock → details
  sleep 9
  tap 1520 849            # 주차장으로 안내
  sleep 8
fi

wait $SR 2>/dev/null; sleep 1
"$ADB" pull /sdcard/$SCENE.mp4 "$OUT/$SCENE.mp4" </dev/null >/dev/null
wait $FF 2>/dev/null
"$ADB" shell am broadcast -n ai.seum.app/.service.DemoClockReceiver --ez reset_tally true </dev/null >/dev/null
echo "done $SCENE: $(ls -la "$OUT/$SCENE.mp4" "$OUT/$SCENE.wav" | awk '{print $5, $9}' | tr '\n' ' ') offset $(cat "$OUT/$SCENE.offset")"
