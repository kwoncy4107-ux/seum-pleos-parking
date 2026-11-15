package ai.seum.app

import ai.seum.app.car.CarSignals
import ai.seum.app.data.MapDb
import ai.seum.app.loc.Fix
import ai.seum.app.loc.LocationSource
import ai.seum.app.navi.Navigator
import ai.seum.app.voice.Voice
import ai.seum.engine.Answer
import ai.seum.engine.AnswerParser
import ai.seum.engine.Engine
import ai.seum.engine.Gear
import ai.seum.engine.Level
import ai.seum.engine.ParkTrigger
import ai.seum.engine.ParkingLot
import ai.seum.engine.Speech
import ai.seum.engine.Verdict
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDateTime

sealed interface GuardState {
    data object Waiting : GuardState
    data object Checking : GuardState
    data class NoLocation(val reason: String) : GuardState
    /** GPS lost or weak at the P moment (underground / indoor car park): no voice, no alert. */
    data class Unreliable(val fixAgeMs: Long, val accuracyM: Double) : GuardState
    data class Result(
        val verdict: Verdict,
        val fix: Fix,
        val at: LocalDateTime,
        val routing: ParkingLot? = null,
        /** Driver said no (by voice or button); hide the offer. */
        val declined: Boolean = false,
        /** A spot the driver keeps using despite this warning: shown on screen, not spoken. */
        val muted: Boolean = false,
        /** Gear-settled-in-P to verdict, and the engine part of it (measured on the device). */
        val totalMs: Long = 0,
        val judgeMs: Long = 0,
        /** Surroundings for the site map; loaded right after the verdict (not part of the P-moment latency). */
        val site: ai.seum.app.ui.SiteData? = null,
    ) : GuardState
}

/**
 * The P-moment flow: gear settles in P → locate → judge → speak → (ask) → route.
 * Owns no Android lifecycle; GuardService starts and stops it.
 */
class Guard(
    private val car: CarSignals,
    private val location: LocationSource,
    private val voice: Voice,
    private val navi: Navigator,
    private val map: MapDb,
    private val store: Store,
    private val alert: ParkAlert,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val main = Handler(Looper.getMainLooper())
    private val engine by lazy { Engine(map.data) }

    private val _state = MutableStateFlow<GuardState>(GuardState.Waiting)
    val state: StateFlow<GuardState> = _state

    /** Demo mode: judge as if it were this time of day (time-dependent advice is then reproducible). */
    @Volatile var demoClock: LocalDateTime? = null

    /** Fine at stake if the driver stays; credited as "avoided" when they drive off soon after a warning. */
    private var atStakeWon = 0
    private var warnedAtMs = 0L

    private val trigger = ParkTrigger(settleMs = 1500) { onParked(it) }

    /** Repeated-warning memory (home, office). Small, on this device only. */
    private val memory = ai.seum.engine.SpotMemory().apply { load(store.spots) }

    /** The warning given at the current stop, until it is acknowledged or the car leaves. */
    private data class StopWarning(val pos: ai.seum.engine.LatLon, val severity: Int, val atMs: Long, var acked: Boolean = false)
    private var stopWarning: StopWarning? = null

    private fun today() = java.time.LocalDate.now().toEpochDay()

    /** Where and when the driver last asked with "이 자리 확인"; the P moment right after it stays quiet. */
    private var lastAsked: Pair<ai.seum.engine.LatLon, Long>? = null

    private fun justAskedHere(pos: ai.seum.engine.LatLon): Boolean {
        val (at, ms) = lastAsked ?: return false
        return System.currentTimeMillis() - ms < ASKED_QUIET_MS && ai.seum.engine.Geo.distanceM(at, pos) <= ASKED_SAME_SPOT_M
    }

    private fun acknowledgeStop(why: String) {
        val w = stopWarning ?: return
        if (w.acked) return
        w.acked = true
        memory.acknowledge(w.pos, w.severity, today())
        store.spots = memory.serialize()
        Log.i(TAG, "spot acknowledged ($why); remembered spots=${memory.size}")
    }

    private val ticker = object : Runnable {
        override fun run() {
            trigger.tick(System.currentTimeMillis())
            main.postDelayed(this, 500)
        }
    }

    fun start() {
        car.onGear = ::onGear
        car.onSpeed = { mps ->
            trigger.onSpeed(mps, System.currentTimeMillis())
            // A "이 자리 확인" answer (not in P) is about the spot the car just left once it drives on.
            if (mps != null && mps > DRIVE_ON_MPS && !trigger.isParked && state.value is GuardState.Result) {
                _state.value = GuardState.Waiting
            }
        }
        car.start()
        location.start()
        voice.start()
        navi.start()
        main.post(ticker)
        // Warm the database (first launch expands it) off the main thread.
        scope.launch(Dispatchers.IO) { runCatching { map.open() }.onFailure { Log.e(TAG, "map db", it) } }
        // While the car moves, keep the area around it warm so the P moment only does distance math.
        scope.launch {
            location.fix.collect { f -> if (f != null) prefetch(f.pos) }
        }
    }

    private var prefetchedAt: ai.seum.engine.LatLon? = null

    /** Reads the area around [pos] once the car has moved 50 m, so the P-moment queries hit a warm cache. */
    private fun prefetch(pos: ai.seum.engine.LatLon) {
        val last = prefetchedAt
        if (last != null && ai.seum.engine.Geo.distanceM(last, pos) < PREFETCH_STEP_M) return
        prefetchedAt = pos
        scope.launch(Dispatchers.IO) {
            val t0 = System.nanoTime()
            runCatching { engine.judge(pos, 10.0, LocalDateTime.now()) }
                .onSuccess { Log.d(TAG, "prefetch ${(System.nanoTime() - t0) / 1_000_000}ms") }
        }
    }

    fun stop() {
        main.removeCallbacks(ticker)
        car.stop(); location.stop(); voice.stop(); navi.stop()
    }

    private fun onGear(g: Gear) {
        val wasParked = trigger.isParked
        trigger.onGear(g, System.currentTimeMillis())
        if (wasParked && g in setOf(Gear.DRIVE, Gear.REVERSE, Gear.NEUTRAL)) onLeftPark()
    }

    private fun onLeftPark() {
        // Declined, or a muted usual spot: leaving later is not "moving because of the warning".
        if (stopWarning?.acked == true || (state.value as? GuardState.Result)?.muted == true) atStakeWon = 0
        // Stayed parked past the window despite the warning: this is one of the driver's usual spots.
        stopWarning?.let { if (System.currentTimeMillis() - it.atMs >= CREDIT_WINDOW_MS) acknowledgeStop("stayed") }
        stopWarning = null
        if (atStakeWon > 0 && System.currentTimeMillis() - warnedAtMs < CREDIT_WINDOW_MS) {
            store.addSaved(atStakeWon)
            Log.i(TAG, "driver moved after warning; avoided $atStakeWon")
        }
        atStakeWon = 0
        alert.clear()
        _state.value = GuardState.Waiting
    }

    fun clearAlert() = alert.clear()

    private fun loadSite(pos: ai.seum.engine.LatLon): ai.seum.app.ui.SiteData {
        val near = ai.seum.engine.Layer.entries.toSet() - ai.seum.engine.Layer.SCHOOL_ZONE
        return ai.seum.app.ui.SiteData(pos, map.data.featuresNear(pos, SITE_RADIUS_M, near), map.data.enforcementPointsNear(pos, SITE_RADIUS_M))
    }

    /**
     * "이 자리 확인" button: the driver asks about this spot without shifting to P (a short stop to check
     * before parking). Same judgement, but the driver asked, so there is always a spoken answer: also when
     * the spot is fine, inside a complex, at a usual spot, or when the location is too weak to judge.
     */
    fun checkHere() {
        if (!trigger.isStopped) {
            voice.speak("차를 멈춘 뒤에 다시 눌러 주세요.")
            return
        }
        onParked(manual = true)
    }

    /** Also called from the demo panel ("P 순간 다시 판정"). */
    fun onParked(firedAtMs: Long = System.currentTimeMillis(), manual: Boolean = false) {
        scope.launch {
            _state.value = GuardState.Checking
            val fix = location.current()
            if (fix == null) {
                _state.value = GuardState.NoLocation(
                    if (location.hasPermission()) "위치를 아직 받지 못했어요" else "위치 권한이 필요해요",
                )
                if (manual) voice.speak("위치를 아직 받지 못해서 확인하지 못했어요.")
                return@launch
            }
            val fixAgeMs = (firedAtMs - fix.atMs).coerceAtLeast(0)
            if (!engine.locationReliable(fixAgeMs, fix.accuracyM)) {
                // Car parks are not no-parking zones; the stale fix is the street at the entrance. Stay quiet.
                Log.i(TAG, "P with unreliable location (age ${fixAgeMs}ms, acc ${fix.accuracyM}m): not judging")
                _state.value = GuardState.Unreliable(fixAgeMs, fix.accuracyM)
                if (manual) voice.speak("위치 신호가 약해서 지금은 정확히 확인하기 어려워요.")
                return@launch
            }
            val now = demoClock ?: LocalDateTime.now()
            val (verdict, judgeMs) = withContext(Dispatchers.IO) {
                val t0 = System.nanoTime()
                val v = engine.judge(fix.pos, fix.accuracyM, now)
                v to (System.nanoTime() - t0) / 1_000_000
            }
            val totalMs = System.currentTimeMillis() - firedAtMs
            Log.i(TAG, "P→verdict ${totalMs}ms (engine ${judgeMs}ms, location ${fix.source}) level=${verdict.level}")
            Log.i(TAG, "verdict at ${fix.pos.lat},${fix.pos.lon}: ${verdict.headline} | speech=${verdict.speech ?: "(silent)"}")
            store.addCheck()
            val severity = ai.seum.engine.SpotMemory.severityOf(verdict)
            val warned = verdict.level >= Level.CAUTION
            // The driver asked: answer even at a usual spot.
            val muted = warned && !manual && memory.isMuted(fix.pos, severity, today())
            // Shifted to P where the driver has just asked: they already heard the answer, so say nothing again.
            val alreadyHeard = !manual && justAskedHere(fix.pos)
            if (manual) lastAsked = fix.pos to System.currentTimeMillis()
            stopWarning = if (warned) StopWarning(fix.pos, severity, System.currentTimeMillis()) else null
            _state.value = GuardState.Result(verdict, fix, now, totalMs = totalMs, judgeMs = judgeMs, muted = muted)
            // Navigation is usually in front at the P moment; the notification is the "look later" copy.
            if (warned && !muted && !alreadyHeard) alert.show(verdict)
            if (muted) Log.i(TAG, "usual spot: warning shown on screen only")
            // Site map data after the verdict, so it never delays the warning.
            val site = withContext(Dispatchers.IO) { runCatching { loadSite(fix.pos) }.getOrNull() }
            (_state.value as? GuardState.Result)?.takeIf { it.fix == fix }?.let { _state.value = it.copy(site = site) }

            if (verdict.level == Level.DANGER && verdict.fineWon > 0) {
                atStakeWon = verdict.fineWon
                warnedAtMs = System.currentTimeMillis()
            }

            if (muted) return@launch
            if (alreadyHeard) {
                Log.i(TAG, "P right after 이 자리 확인 at the same spot: not repeating the answer")
                return@launch
            }
            val speech = if (manual) manualAnswer(verdict) else (verdict.speech?.let { withFirstRunHint(it, verdict) } ?: return@launch)
            Log.i(TAG, "spoken: $speech")
            voice.speak(speech) {
                if (verdict.parking != null && verdict.level >= Level.CAUTION) askToRoute(verdict.parking!!.lot)
            }
        }
    }

    /** The spoken answer to "이 자리 확인": the usual warning, or a short "nothing found" that does not overclaim. */
    private fun manualAnswer(verdict: Verdict): String {
        verdict.speech?.let { return it }
        verdict.privateArea?.let {
            return if (it.kind == ai.seum.engine.AreaKind.APT) "아파트 단지 안이라 도로 주정차 규정 대상이 아니에요."
            else "주차장 안이라 도로 주정차 규정 대상이 아니에요."
        }
        return "여기는 확인된 주정차 금지 표시가 없어요. 표지판은 한 번 더 확인해 주세요."
    }

    /**
     * The first few spoken warnings also say where the details are (the dock icon), because the screen is
     * usually behind the navigation. Inserted before the parking question so "응/아니" still answers it.
     */
    private fun withFirstRunHint(speech: String, verdict: ai.seum.engine.Verdict): String {
        if (verdict.level < Level.CAUTION || store.hintsSpoken >= FIRST_RUN_HINTS) return speech
        store.hintsSpoken = store.hintsSpoken + 1
        val hint = "자세한 내용은 화면 아래 세움 아이콘에서 볼 수 있어요."
        val p = verdict.parking ?: return "$speech $hint"
        val at = speech.indexOf("${ai.seum.engine.Speech.distanceWords(p.distanceM)} 앞에")
        return if (at > 0) speech.substring(0, at) + hint + " " + speech.substring(at) else "$speech $hint"
    }

    private fun askToRoute(lot: ParkingLot) {
        voice.listen { heard ->
            when (AnswerParser.parse(heard)) {
                Answer.YES -> routeTo(lot)
                Answer.NO -> decline()
                Answer.UNKNOWN -> Unit // the on-screen button stays available
            }
        }
    }

    fun decline() {
        acknowledgeStop("declined")
        voice.speak("알겠어요. 조심히 다녀오세요.")
        (state.value as? GuardState.Result)?.let { _state.value = it.copy(declined = true) }
    }

    /** "다시 알려 줘" on a muted spot: forget it and speak the warning now. */
    fun unmuteHere() {
        val r = state.value as? GuardState.Result ?: return
        memory.unmute(r.fix.pos)
        store.spots = memory.serialize()
        _state.value = r.copy(muted = false)
        r.verdict.speech?.let { voice.speak(it) }
    }

    fun routeTo(lot: ParkingLot) {
        val ok = navi.route(lot)
        val name = Speech.lotName(lot)
        voice.speak(if (ok) "$name${Speech.josa(name, "으로", "로")} 안내할게요." else "내비게이션에 연결하지 못했어요. 화면의 주소를 확인해 주세요.")
        (state.value as? GuardState.Result)?.let { _state.value = it.copy(routing = lot) }
    }

    companion object {
        private const val TAG = "Seum/Guard"
        private const val CREDIT_WINDOW_MS = 15 * 60_000L
        private const val PREFETCH_STEP_M = 50.0
        private const val FIRST_RUN_HINTS = 3
        private const val SITE_RADIUS_M = 50.0
        private const val DRIVE_ON_MPS = 3.0f
        /** A P moment within this time and distance of an "이 자리 확인" is the same stop. */
        private const val ASKED_QUIET_MS = 5 * 60_000L
        private const val ASKED_SAME_SPOT_M = 15.0
    }
}
