package ai.seum.app.ui

import ai.seum.app.GuardState
import ai.seum.app.SeumApp
import ai.seum.engine.AreaKind
import ai.seum.engine.Coverage
import ai.seum.engine.DemoSpots
import ai.seum.engine.EnforcementRisk
import ai.seum.engine.Engine
import ai.seum.engine.Finding
import ai.seum.engine.Gear
import ai.seum.engine.Level
import ai.seum.engine.PeakTiming
import ai.seum.engine.Rule
import ai.seum.engine.Speech
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.LocalDateTime
import kotlin.math.roundToInt

// Pleos built-in app look (sampled from the vehicle-control screen): white surfaces, grey hairlines,
// square tiles, black fill for the selected / primary item, colour only for state.
// Sizes follow in-car reading distance (~70 cm): body ≥ 24sp, labels ≥ 18sp, touch targets ≥ 76dp.
private val Bg = Color(0xFFFFFFFF)
private val Bar = Color(0xFFF7F7F7)
private val Tint = Color(0xFFF0F0F0)
private val Line = Color(0xFFC5C5C5)
private val Ink = Color(0xFF000000)
private val Muted = Color(0xFF8A8A8A)
private val Accent = Color(0xFF1F6FEB)
private val TouchMin = 76.dp
private val Hair = 1.dp

fun levelColor(l: Level) = when (l) {
    Level.DANGER -> Color(0xFFE5302F)
    Level.CAUTION -> Color(0xFFE09A00)
    Level.INFO -> Color(0xFF1F6FEB)
    Level.CLEAR -> Color(0xFF14A05A)
}

fun levelWord(l: Level) = when (l) {
    Level.DANGER -> "세우면 안 되는 자리"
    Level.CAUTION -> "주의가 필요한 자리"
    Level.INFO -> "참고할 정보가 있어요"
    Level.CLEAR -> "확인된 문제 없음"
}

private fun levelTag(l: Level) = when (l) {
    Level.DANGER -> "주정차 금지"
    Level.CAUTION -> "주의"
    Level.INFO -> "참고"
    Level.CLEAR -> "문제 없음"
}

/** Short tile name for a rule (the full legal title stays in the detail line). */
private fun shortName(r: Rule) = when (r) {
    Rule.HYDRANT -> "소화전"
    Rule.CROSSWALK, Rule.CROSSWALK_PT -> "횡단보도"
    Rule.INTERSECTION, Rule.JUNCTION -> "교차로 모퉁이"
    Rule.BUS_STOP -> "버스정류장"
    Rule.SCHOOL_ZONE -> "어린이보호구역"
    Rule.NO_STOP_LINE -> "황색 실선"
    Rule.NO_PARK_LINE -> "황색 점선"
    Rule.NO_STOP_BOX -> "정차금지지대"
    Rule.CCTV -> "고정 단속 CCTV"
}

@Composable
private fun HLine() = Box(Modifier.fillMaxWidth().height(Hair).background(Line))

@Composable
private fun VLine() = Box(Modifier.fillMaxHeight().width(Hair).background(Line))

@Composable
fun SeumScreen(app: SeumApp) {
    val state by app.guard.state.collectAsStateWithLifecycle()
    val gear by app.car.gear.collectAsStateWithLifecycle()
    val fix by app.location.fix.collectAsStateWithLifecycle()
    val voiceStatus by app.voice.status.collectAsStateWithLifecycle()
    val listening by app.voice.listening.collectAsStateWithLifecycle()
    val restricted by app.car.restricted.collectAsStateWithLifecycle()
    val speed by app.car.speed.collectAsStateWithLifecycle()
    var showDemo by remember { mutableStateOf(false) }
    var showIntro by remember { mutableStateOf(!app.store.introSeen) }
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(state) { tick++ }

    MaterialTheme(colorScheme = lightColorScheme(background = Bg, surface = Bg, primary = Ink)) {
        if (restricted) {
            // Driving (in Pleos terms): one big button and one line, nothing to read.
            DrivingScreen(state, moving = (speed ?: 0f) > 0.5f) { app.guard.checkHere() }
            return@MaterialTheme
        }
        if (showIntro) {
            Intro { app.store.introSeen = true; showIntro = false }
            return@MaterialTheme
        }
        Column(Modifier.fillMaxSize().background(Bg)) {
            TopBar(gear, fix != null, voiceStatus.startsWith("Pleos"), onDemo = { showDemo = !showDemo }, demoOpen = showDemo,
                onCheck = { showDemo = false; app.guard.checkHere() })
            HLine()
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1.1f).fillMaxHeight()) { VerdictPane(state) }
                VLine()
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    if (showDemo) DemoPanel(app, Modifier.weight(1f)) { showDemo = false }
                    else DetailPane(app, state, listening, Modifier.weight(1f))
                    HLine()
                    Tally(app.store.savedWon, app.store.savedCount, app.store.checks, tick)
                }
            }
        }
    }
}

/**
 * Screen while the system restricts the UI (driving, or stopped in D). Only the "이 자리 확인" button and a
 * short status line; the answer itself is spoken. Large text, one target, no map or lists.
 */
@Composable
private fun DrivingScreen(state: GuardState, moving: Boolean, onCheck: () -> Unit) {
    val line = when {
        moving -> "멈추면 이 자리를 확인할 수 있어요"
        state is GuardState.Checking -> "확인하는 중…"
        state is GuardState.Result -> state.verdict.privateArea?.let { "도로 주정차 규정 대상이 아니에요" } ?: levelWord(state.verdict.level)
        else -> "잠깐 멈췄다면 눌러서 확인하세요"
    }
    val color = (state as? GuardState.Result)?.takeIf { !moving && it.verdict.privateArea == null }?.let { levelColor(it.verdict.level) } ?: Ink
    Column(
        Modifier.fillMaxSize().background(Bg).padding(48.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("세움", color = Muted, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(28.dp))
        Text(line, color = color, fontSize = 44.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(48.dp))
        Box(
            Modifier.width(560.dp).heightIn(min = 160.dp).background(if (moving) Line else Ink)
                .clickable(enabled = !moving, onClick = onCheck),
            contentAlignment = Alignment.Center,
        ) { Text("이 자리 확인", color = Color.White, fontSize = 44.sp, fontWeight = FontWeight.Bold) }
        Spacer(Modifier.height(24.dp))
        Text("자세한 내용은 P에 넣으면 볼 수 있어요", color = Muted, fontSize = 22.sp)
    }
}

/** First-run intro: what Seum does, when it stays quiet, and where to look later. One screen, one button. */
@Composable
private fun Intro(onStart: () -> Unit) {
    Column(Modifier.fillMaxSize().background(Bg)) {
        Column(Modifier.fillMaxWidth().background(Bar).padding(horizontal = 48.dp, vertical = 36.dp)) {
            Text("세움", color = Ink, fontSize = 48.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text("세우기 전엔 물어보고, 그냥 세워도 먼저 알려 드려요", color = Muted, fontSize = 26.sp)
        }
        HLine()
        Row(Modifier.weight(1f).fillMaxWidth()) {
            IntroTile(Modifier.weight(1f), Ink, "1", "세우기 전엔 '이 자리 확인'",
                "여기 세워도 될지 궁금하면 잠깐 멈추고 버튼을 누르세요. 소화전·횡단보도·황색 실선·단속 카메라를 확인해 바로 음성으로 답해 드려요.")
            VLine()
            IntroTile(Modifier.weight(1f), levelColor(Level.DANGER), "2", "그냥 세워도 P에서 알려 드려요",
                "확인 없이 세웠더라도 기어를 P에 넣는 순간 자동으로 확인해요. 문제가 있을 때만 말하고, 가까운 공영주차장도 안내해 드려요.")
            VLine()
            IntroTile(Modifier.weight(1f), levelColor(Level.CLEAR), "3", "괜찮으면 조용히",
                "문제없는 자리, 아파트 단지·주차장 안, 지하처럼 위치가 불확실한 곳에서는 말하지 않아요. 매일 세우는 자리는 두 번 넘기면 음성을 생략해요.")
        }
        HLine()
        Row(Modifier.fillMaxWidth().heightIn(min = 120.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.width(380.dp).heightIn(min = 120.dp).background(Ink).clickable(onClick = onStart),
                contentAlignment = Alignment.Center,
            ) { Text("시작하기", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold) }
            Text("위치는 이 차 안에서만 쓰고 어디로도 보내지 않아요. 판정은 공공데이터 기준 참고 정보예요.",
                color = Muted, fontSize = 20.sp, modifier = Modifier.weight(1f).padding(horizontal = 32.dp))
        }
    }
}

@Composable
private fun IntroTile(modifier: Modifier, color: Color, num: String, title: String, body: String) {
    Column(modifier.fillMaxHeight().padding(36.dp)) {
        Box(Modifier.size(52.dp).background(color), contentAlignment = Alignment.Center) {
            Text(num, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(28.dp))
        Text(title, color = Ink, fontSize = 32.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(14.dp))
        Text(body, color = Muted, fontSize = 23.sp, lineHeight = 34.sp)
    }
}

@Composable
private fun TopBar(gear: Gear, hasFix: Boolean, pleosVoice: Boolean, onDemo: () -> Unit, demoOpen: Boolean, onCheck: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).background(Bar).padding(start = 32.dp).heightIn(min = 84.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("세움", color = Ink, fontSize = 32.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(16.dp))
        Text("세우기 전엔 물어보고, 그냥 세워도 먼저 알려 드려요", color = Muted, fontSize = 20.sp)
        Spacer(Modifier.weight(1f))
        Status("기어 ${gearLabel(gear)}", gear == Gear.PARK)
        Status("위치", hasFix)
        Status("음성", pleosVoice)
        Spacer(Modifier.width(24.dp))
        // Ask about this spot without shifting to P: stop, tap, hear the answer.
        Box(
            Modifier.heightIn(min = 84.dp).width(230.dp).background(Ink).clickable(onClick = onCheck),
            contentAlignment = Alignment.Center,
        ) { Text("이 자리 확인", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold) }
        VLine()
        Box(
            Modifier.heightIn(min = 84.dp).width(150.dp).background(if (demoOpen) Ink else Bar).clickable(onClick = onDemo),
            contentAlignment = Alignment.Center,
        ) { Text(if (demoOpen) "시연 닫기" else "시연", color = if (demoOpen) Color.White else Ink, fontSize = 24.sp) }
    }
}

private fun gearLabel(g: Gear) = when (g) {
    Gear.PARK -> "P"; Gear.REVERSE -> "R"; Gear.NEUTRAL -> "N"; Gear.DRIVE -> "D"; Gear.UNKNOWN -> "?"
}

@Composable
private fun Status(text: String, ok: Boolean) {
    Text(text, color = if (ok) Ink else Line, fontSize = 20.sp, modifier = Modifier.padding(start = 22.dp))
}

/** Left pane: the verdict in one glance, then the range view. */
@Composable
private fun VerdictPane(state: GuardState) {
    val (color, title, sub) = when (state) {
        GuardState.Waiting -> Triple(Muted, "여기 세워도 될지\n궁금하면 물어보세요", "위의 '이 자리 확인'을 누르세요. 그냥 세워도 P에 넣는 순간 알려 드려요")
        GuardState.Checking -> Triple(Accent, "이 자리를 확인하는 중…", "위치와 주변 규칙을 보고 있어요")
        is GuardState.NoLocation -> Triple(Muted, "위치를 확인하지 못했어요", state.reason)
        is GuardState.Unreliable -> Triple(
            Muted, "위치 신호가 약해\n판정하지 않았어요",
            "지하·실내 주차장으로 보여요. 주차장은 주정차 금지구역이 아니라 따로 알리지 않았어요.",
        )
        is GuardState.Result -> state.verdict.privateArea?.let { a ->
            val t = if (a.kind == AreaKind.APT) "아파트 단지 안이라\n확인하지 않았어요" else "주차장 안이라\n확인하지 않았어요"
            Triple(levelColor(Level.CLEAR), t, state.verdict.headline)
        } ?: Triple(levelColor(state.verdict.level), levelWord(state.verdict.level), state.verdict.headline)
    }
    val r = state as? GuardState.Result
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 36.dp, vertical = 30.dp)) {
            if (r != null) {
                val tag = if (r.verdict.privateArea != null) "도로 아님" else levelTag(r.verdict.level)
                Box(Modifier.background(color).padding(horizontal = 14.dp, vertical = 6.dp)) {
                    Text(tag, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(16.dp))
            }
            Text(title, color = Ink, fontSize = 52.sp, fontWeight = FontWeight.Bold, lineHeight = 62.sp)
            Spacer(Modifier.height(12.dp))
            val v = r?.verdict
            val main = v?.findings?.firstOrNull()?.takeIf { v.privateArea == null }
            val line = when {
                main != null && v.fineWon > 0 -> "${main.rule.title} · 예상 과태료 ${Engine.won(v.fineWon)}"
                main != null -> main.rule.title
                else -> sub
            }
            Text(line, color = Muted, fontSize = 28.sp, lineHeight = 38.sp)
            v?.peak?.let { Spacer(Modifier.height(16.dp)); PeakLine(it) }
        }
        val site = r?.site?.takeIf { r.verdict.privateArea == null }
        if (site != null && r != null) {
            HLine()
            Box(Modifier.weight(1f).fillMaxWidth().clipToBounds()) { SiteMap(site, r.verdict.findings, r.verdict.parking, headingDeg = r.fix.headingDeg) }
            HLine()
            Row(Modifier.fillMaxWidth().padding(horizontal = 36.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(coverageText(r.verdict.coverage), color = Ink, fontSize = 18.sp)
                Spacer(Modifier.width(16.dp))
                Text(
                    "오차 약 ${r.fix.accuracyM.roundToInt()}m · ${r.at.hour}:${"%02d".format(r.at.minute)} 판정 · P 후 ${r.totalMs}ms",
                    color = Muted, fontSize = 18.sp,
                )
            }
        }
    }
}

private fun coverageText(c: Coverage) = when (c) {
    Coverage.PRECISE -> "정밀 확인 · 서울"
    Coverage.BASIC -> "기본 확인 · 전국 공통"
}

@Composable
private fun PeakLine(p: PeakTiming) {
    val text = if (p.inPeak) "지금이 단속 집중 시간 · ${Speech.span(p.startHour, p.endHourExclusive)}"
    else "단속 집중 · ${Speech.span(p.startHour, p.endHourExclusive)}"
    Box(Modifier.border(Hair, Ink).padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(text, color = Ink, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** Right pane: reasons as tiles, the enforcement record, then the one action. */
@Composable
private fun DetailPane(app: SeumApp, state: GuardState, listening: Boolean, modifier: Modifier) {
    val r = state as? GuardState.Result
    Column(modifier.fillMaxWidth()) {
        if (r == null || r.verdict.privateArea != null) {
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(36.dp)) {
                Text("무엇을 확인하나요", color = Muted, fontSize = 20.sp)
                Spacer(Modifier.height(16.dp))
                listOf(
                    Rule.HYDRANT to "소화전 5m", Rule.CROSSWALK to "횡단보도 10m", Rule.BUS_STOP to "버스정류장 10m",
                    Rule.INTERSECTION to "교차로 모퉁이", Rule.NO_STOP_LINE to "황색 실선·점선", Rule.CCTV to "고정 단속 CCTV",
                    Rule.SCHOOL_ZONE to "어린이보호구역",
                ).forEach { (rule, label) ->
                    Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        RuleIcon(rule, Ink, 30.dp)
                        Spacer(Modifier.width(18.dp))
                        Text(label, color = Ink, fontSize = 24.sp)
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text("서울 2025년 단속 기록 214만 건의 시간대도 함께 봐요", color = Muted, fontSize = 20.sp)
            }
            return@Column
        }
        val v = r.verdict
        // 2 × 2 tiles: up to three reasons, then the enforcement record.
        val tiles: List<Any> = v.findings.take(3) + listOfNotNull(v.risk)
        Column(Modifier.weight(1f).fillMaxWidth()) {
            tiles.chunked(2).forEachIndexed { i, row ->
                if (i > 0) HLine()
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    row.forEachIndexed { j, t ->
                        if (j > 0) VLine()
                        val m = Modifier.weight(1f).fillMaxHeight()
                        when (t) {
                            is Finding -> FindingTile(t, m)
                            is EnforcementRisk -> RiskTile(t, r.at, m)
                        }
                    }
                    if (row.size == 1) { VLine(); Spacer(Modifier.weight(1f)) }
                }
            }
            if (tiles.size <= 2) Spacer(Modifier.weight(1f))
        }
        if (r.muted) {
            HLine()
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).background(Tint).padding(start = 32.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("자주 세우는 자리라 음성은 생략했어요", color = Ink, fontSize = 22.sp, modifier = Modifier.weight(1f))
                VLine()
                Box(Modifier.heightIn(min = TouchMin).width(200.dp).clickable { app.guard.unmuteHere() }, contentAlignment = Alignment.Center) {
                    Text("다시 알려 줘", color = Ink, fontSize = 22.sp)
                }
            }
        }
        if (listening) {
            HLine()
            Text("듣고 있어요 · “응” 또는 “아니”", color = Accent, fontSize = 24.sp, modifier = Modifier.padding(horizontal = 32.dp, vertical = 14.dp))
        }
        val p = v.parking
        when {
            p != null && r.routing == p.lot -> {
                HLine()
                Text("${Speech.lotName(p.lot)}으로 안내 중", color = levelColor(Level.CLEAR), fontSize = 28.sp,
                    fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(32.dp))
            }
            p != null && !r.declined -> {
                HLine()
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).heightIn(min = 120.dp)) {
                    Row(
                        Modifier.weight(1f).heightIn(min = 120.dp).background(Ink).clickable { app.guard.routeTo(p.lot) }
                            .padding(horizontal = 28.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ParkingIcon(Color.White, 40.dp)
                        Spacer(Modifier.width(20.dp))
                        Column(Modifier.weight(1f)) {
                            Text("주차장으로 안내", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                            Text("${Speech.lotName(p.lot)} · ${Speech.distanceWords(p.distanceM)} · ${p.feeText}",
                                color = Color(0xFFB9B9B9), fontSize = 20.sp)
                        }
                        Text("→", color = Color.White, fontSize = 32.sp)
                    }
                    Box(
                        Modifier.width(190.dp).heightIn(min = 120.dp).clickable { app.guard.decline() },
                        contentAlignment = Alignment.Center,
                    ) { Text("괜찮아요", color = Ink, fontSize = 26.sp) }
                }
            }
        }
    }
}

@Composable
private fun FindingTile(f: Finding, modifier: Modifier) {
    val col = levelColor(f.level)
    val dist = if (f.rule == Rule.CCTV || f.rule == Rule.SCHOOL_ZONE) "${f.distanceM.roundToInt()}m" else "${"%.1f".format(f.distanceM)}m"
    Column(modifier.padding(30.dp)) {
        RuleIcon(f.rule, Ink, 40.dp)
        Spacer(Modifier.weight(1f))
        Text(shortName(f.rule), color = Ink, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(10.dp))
        // Two plain rows: how far the car is, and how far the ban reaches. Then what that means.
        TileRow("지금 거리", "${distanceTarget(f.rule)} $dist")
        TileRow("금지 범위", banRange(f.rule))
        Spacer(Modifier.height(8.dp))
        Text(meaning(f), color = col, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        Text(f.rule.basis, color = Muted, fontSize = 16.sp)
    }
}

@Composable
private fun TileRow(label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Muted, fontSize = 19.sp, modifier = Modifier.width(110.dp))
        Text(value, color = Ink, fontSize = 23.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** What the distance is measured to. */
private fun distanceTarget(r: Rule) = when (r) {
    Rule.NO_STOP_LINE, Rule.NO_PARK_LINE -> "선까지"
    Rule.NO_STOP_BOX -> "지대까지"
    Rule.CCTV -> "카메라까지"
    Rule.SCHOOL_ZONE -> "시설까지"
    Rule.INTERSECTION, Rule.JUNCTION -> "교차로 중심까지"
    Rule.HYDRANT -> "소화전까지"
    Rule.CROSSWALK, Rule.CROSSWALK_PT -> "횡단보도까지"
    Rule.BUS_STOP -> "정류장까지"
}

/** How far the ban reaches, in the words of the rule. */
private fun banRange(r: Rule) = when (r) {
    Rule.HYDRANT -> "5m 안"
    Rule.CROSSWALK, Rule.CROSSWALK_PT, Rule.BUS_STOP -> "10m 안"
    Rule.INTERSECTION, Rule.JUNCTION -> "모퉁이 5m 안"
    Rule.NO_STOP_LINE -> "선이 그어진 길가 전체"
    Rule.NO_PARK_LINE -> "선 구간 · 5분 정차만"
    Rule.NO_STOP_BOX -> "지대 안 (정차도 금지)"
    Rule.CCTV -> "카메라 주변 약 50m"
    Rule.SCHOOL_ZONE -> "보호구역 도로"
}

/** The verdict for this reason, in one short line. */
private fun meaning(f: Finding) = when {
    f.level == Level.INFO -> "참고 정보예요"
    f.rule == Rule.INTERSECTION || f.rule == Rule.JUNCTION -> "모퉁이에 가까울 수 있어요"
    f.rule == Rule.NO_PARK_LINE -> "5분 넘게 세우면 단속돼요"
    f.rule == Rule.CCTV && f.certain -> "카메라 단속 구역 안이에요"
    f.certain -> "금지 범위 안이에요"
    else -> "위치 오차를 감안하면 걸릴 수 있어요"
}

@Composable
private fun RiskTile(risk: EnforcementRisk, at: LocalDateTime, modifier: Modifier) {
    Column(modifier.padding(30.dp)) {
        RuleIcon(null, Ink, 40.dp)
        Spacer(Modifier.height(14.dp))
        Text("작년 이 근처 단속 ${"%,d".format(risk.yearly)}건", color = Ink, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
        Text(
            "${risk.peakStartHour}~${risk.peakEndHourExclusive % 24}시에 가장 많아요 · ${(risk.peakShare * 100).roundToInt()}%",
            color = Ink, fontSize = 20.sp,
        )
        Text("서울시 2025년 단속 기록", color = Muted, fontSize = 16.sp)
        Spacer(Modifier.height(12.dp))
        val max = (risk.byHour.maxOrNull() ?: 1).coerceAtLeast(1)
        Canvas(Modifier.fillMaxWidth().weight(1f)) {
            val gap = 3f
            val w = (size.width - gap * 23) / 24f
            for (h in 0 until 24) {
                val bh = (size.height - 2f) * risk.byHour[h].toFloat() / max
                val inPeak = ((h - risk.peakStartHour + 24) % 24) < 3
                val col = when {
                    h == at.hour -> Accent
                    inPeak -> Ink
                    else -> Line
                }
                drawRect(col, Offset(h * (w + gap), size.height - bh), Size(w, bh.coerceAtLeast(2f)))
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth()) {
            listOf("0시", "6시", "12시", "18시").forEach { Text(it, color = Muted, fontSize = 16.sp, modifier = Modifier.weight(1f)) }
        }
        Text("검정: 단속 많은 시간 · 파랑: 지금 시각", color = Muted, fontSize = 16.sp)
    }
}

/** Monochrome line icons in the Pleos style, drawn so no icon library is needed. null = enforcement chart. */
@Composable
private fun RuleIcon(rule: Rule?, color: Color, size: Dp) {
    Canvas(Modifier.size(size)) {
        val s = this.size.minDimension
        val st = Stroke(width = s * 0.07f)
        when (rule) {
            Rule.CROSSWALK, Rule.CROSSWALK_PT -> for (i in 0 until 4) drawRect(color, Offset(s * (0.08f + i * 0.23f), s * 0.15f), Size(s * 0.13f, s * 0.7f))
            Rule.HYDRANT -> {
                drawRect(color, Offset(s * 0.3f, s * 0.3f), Size(s * 0.4f, s * 0.6f), style = st)
                drawArc(color, 180f, 180f, false, Offset(s * 0.3f, s * 0.1f), Size(s * 0.4f, s * 0.4f), style = st)
                drawLine(color, Offset(s * 0.12f, s * 0.55f), Offset(s * 0.88f, s * 0.55f), st.width)
            }
            Rule.NO_STOP_LINE, Rule.NO_STOP_BOX -> {
                drawLine(color, Offset(s * 0.35f, s * 0.05f), Offset(s * 0.35f, s * 0.95f), st.width)
                drawLine(color, Offset(s * 0.65f, s * 0.05f), Offset(s * 0.65f, s * 0.95f), st.width)
            }
            Rule.NO_PARK_LINE -> {
                val dash = PathEffect.dashPathEffect(floatArrayOf(s * 0.14f, s * 0.1f))
                drawLine(color, Offset(s * 0.35f, s * 0.05f), Offset(s * 0.35f, s * 0.95f), st.width, pathEffect = dash)
                drawLine(color, Offset(s * 0.65f, s * 0.05f), Offset(s * 0.65f, s * 0.95f), st.width, pathEffect = dash)
            }
            Rule.INTERSECTION, Rule.JUNCTION -> {
                drawLine(color, Offset(s * 0.5f, s * 0.05f), Offset(s * 0.5f, s * 0.95f), s * 0.22f)
                drawLine(color, Offset(s * 0.05f, s * 0.5f), Offset(s * 0.95f, s * 0.5f), s * 0.22f)
            }
            Rule.BUS_STOP -> {
                drawRect(color, Offset(s * 0.15f, s * 0.12f), Size(s * 0.7f, s * 0.66f), style = st)
                drawLine(color, Offset(s * 0.15f, s * 0.48f), Offset(s * 0.85f, s * 0.48f), st.width)
                drawCircle(color, s * 0.07f, Offset(s * 0.3f, s * 0.88f))
                drawCircle(color, s * 0.07f, Offset(s * 0.7f, s * 0.88f))
            }
            Rule.CCTV -> {
                drawRect(color, Offset(s * 0.12f, s * 0.22f), Size(s * 0.6f, s * 0.3f), style = st)
                drawLine(color, Offset(s * 0.72f, s * 0.37f), Offset(s * 0.92f, s * 0.27f), st.width)
                drawLine(color, Offset(s * 0.42f, s * 0.52f), Offset(s * 0.42f, s * 0.92f), st.width)
            }
            Rule.SCHOOL_ZONE -> {
                val p = androidx.compose.ui.graphics.Path().apply {
                    moveTo(s * 0.5f, s * 0.08f); lineTo(s * 0.94f, s * 0.9f); lineTo(s * 0.06f, s * 0.9f); close()
                }
                drawPath(p, color, style = st)
            }
            null -> {
                val hs = listOf(0.4f, 0.75f, 0.55f, 0.9f)
                hs.forEachIndexed { i, h -> drawRect(color, Offset(s * (0.08f + i * 0.23f), s * (0.95f - h)), Size(s * 0.15f, s * h)) }
            }
        }
    }
}

@Composable
private fun ParkingIcon(color: Color, size: Dp) {
    Box(Modifier.size(size).border(2.dp, color), contentAlignment = Alignment.Center) {
        Text("P", color = color, fontSize = (size.value * 0.55f).sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun Tally(saved: Int, count: Int, checks: Int, @Suppress("UNUSED_PARAMETER") tick: Int) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("경고 뒤 자리를 옮겨 피한 과태료", color = Muted, fontSize = 20.sp)
            Text(if (saved > 0) Engine.won(saved) else "0원", color = Ink, fontSize = 34.sp, fontWeight = FontWeight.Bold)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text("옮긴 횟수 ${count}번", color = Muted, fontSize = 20.sp)
            Text("확인한 주차 ${checks}번", color = Muted, fontSize = 20.sp)
        }
    }
}

@Composable
private fun DemoPanel(app: SeumApp, modifier: Modifier, onClose: () -> Unit) {
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        Text("시연 · 실제 데이터 속 지점에서 P 순간 재현", color = Muted, fontSize = 20.sp,
            modifier = Modifier.padding(horizontal = 32.dp, vertical = 18.dp))
        DemoSpots.all.forEach { s ->
            HLine()
            Column(
                Modifier.fillMaxWidth().heightIn(min = TouchMin)
                    .clickable {
                        app.location.demoOverride = s.pos
                        app.location.demoHeading = s.headingDeg
                        app.guard.demoClock = LocalDateTime.now().withHour(s.hour).withMinute(s.minute)
                        app.guard.onParked()
                        onClose()
                    }.padding(horizontal = 32.dp, vertical = 14.dp),
            ) {
                Text(s.title, color = Ink, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
                Text(s.story, color = Muted, fontSize = 18.sp)
            }
        }
        HLine()
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            DemoAction("실제 위치로") {
                app.location.demoOverride = null
                app.location.demoHeading = null
                app.guard.demoClock = null
                onClose()
            }
            VLine()
            DemoAction("다시 판정") { app.guard.onParked(); onClose() }
        }
    }
}

@Composable
private fun RowScope.DemoAction(text: String, onClick: () -> Unit) {
    Box(Modifier.weight(1f).heightIn(min = TouchMin).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Text(text, color = Ink, fontSize = 22.sp)
    }
}
