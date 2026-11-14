<#
  세움 시연 자동화: Pleos Connect Emulator의 Virtual Console(REST)과 adb로
  "운전 → 정차 → 기어 P" 를 재현한다. 앱은 실제 기어 신호와 GPS를 받아 판정한다.

  사용:  powershell -ExecutionPolicy Bypass -File tools\demo.ps1 -Spot dosan_hydrant
  준비:  에뮬레이터 Cold boot, 앱 설치·1회 실행(권한 허용), adb가 PATH에 있을 것.

  REST 경로는 공식 문서에 없고, 다른 출품작들이 실측한 값이다
  (PUT /vehicle/gear?gear=..., POST /browser/session heartbeat, GET /vehicle/state).
#>
param(
    [ValidateSet("hongdae", "bukchon", "suyu_market", "ikseon", "yeonnam", "all")]
    [string]$Spot = "all",
    [int]$DriveSeconds = 4,
    [int]$PauseSeconds = 14
)

$ErrorActionPreference = "Stop"
$VC = "http://127.0.0.1:8088"
if (-not (Get-Command adb -ErrorAction SilentlyContinue)) {
    $sdkAdb = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools"
    if (Test-Path (Join-Path $sdkAdb "adb.exe")) { $env:Path = "$sdkAdb;$env:Path" } else { throw "adb를 찾을 수 없습니다." }
}

# Same coordinates as engine/src/main/kotlin/ai/seum/engine/DemoSpots.kt
$Spots = [ordered]@{
    hongdae     = @{ lat = 37.550911; lon = 126.920492; h = 13; m = 0; title = "홍대 앞 골목 (잔다리로 24)" }
    bukchon     = @{ lat = 37.581691; lon = 126.984999; h = 13; m = 0; title = "북촌 한옥마을 입구 (북촌로 53)" }
    suyu_market = @{ lat = 37.638641; lon = 127.024742; h = 13; m = 0; title = "수유 시장 골목 (한천로139길 46)" }
    ikseon      = @{ lat = 37.572498; lon = 126.991473; h = 13; m = 0; title = "익선동 (돈화문로 45)" }
    yeonnam     = @{ lat = 37.554329; lon = 126.912253; h = 13; m = 0; title = "연남동 (동교로 95)" }
}
}

function Beat { try { Invoke-WebRequest -Method Post -Uri "$VC/browser/session?client=seum-demo" -UseBasicParsing | Out-Null } catch {} }
function Put($path) { Beat; Invoke-WebRequest -Method Put -Uri "$VC/$path" -UseBasicParsing | Out-Null }
function Hold($sec) { for ($i = 0; $i -lt $sec; $i++) { Beat; Start-Sleep -Seconds 1 } }

function GeoFix($lat, $lon) {
    # adb emu geo fix takes longitude first
    adb emu geo fix $lon $lat | Out-Null
}

Write-Host "Virtual Console 연결 중..."
adb forward tcp:8088 tcp:8088 | Out-Null
$ready = $false
for ($i = 0; $i -lt 30 -and -not $ready; $i++) {
    Beat
    try {
        $s = (Invoke-WebRequest "$VC/vehicle/state" -UseBasicParsing).Content | ConvertFrom-Json
        $ready = [bool]$s.gear.bridge_ready
    } catch {}
    if (-not $ready) { Start-Sleep -Seconds 2 }
}
if (-not $ready) { throw "Virtual Console이 준비되지 않았습니다 (bridge_ready=false). 에뮬레이터를 Cold boot 하세요." }

$targets = if ($Spot -eq "all") { $Spots.Keys } else { @($Spot) }
foreach ($k in $targets) {
    $p = $Spots[$k]
    Write-Host ""
    Write-Host "▶ $($p.title)  ($($p.lat), $($p.lon))"
    # Judge at the spot's scripted time of day (DemoClockReceiver; demo builds only rely on this)
    adb shell am broadcast -n ai.seum.app/.service.DemoClockReceiver --ei hour $($p.h) --ei minute $($p.m) | Out-Null
    Put "vehicle/gear?gear=Drive"
    Put "vehicle/controls?speed=20&accelerator=20&brake=0"
    Hold $DriveSeconds
    GeoFix $p.lat $p.lon
    Put "vehicle/controls?speed=0&accelerator=0&brake=60"
    Hold 2
    Put "vehicle/gear?gear=Park"
    Write-Host "  기어 P. 앱이 판정하고 안내합니다."
    Hold $PauseSeconds
}

Put "vehicle/controls?speed=0&accelerator=0&brake=0"
adb shell am broadcast -n ai.seum.app/.service.DemoClockReceiver --ez clear true | Out-Null
try { Invoke-WebRequest -Method Post -Uri "$VC/browser/session?client=seum-demo&closing=1" -UseBasicParsing | Out-Null } catch {}
Write-Host ""
Write-Host "끝."
