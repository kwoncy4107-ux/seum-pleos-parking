package ai.seum.app.loc

import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.model.LocationData
import ai.seum.engine.Geo
import ai.seum.engine.LatLon
import ai.seum.engine.LocalProjection
import kotlin.math.atan2
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withTimeoutOrNull

/** [headingDeg]: direction the car was last driving (0 = north, clockwise); null when not known yet. */
data class Fix(val pos: LatLon, val accuracyM: Double, val source: String, val atMs: Long, val headingDeg: Double? = null)

/**
 * Where the car is. Order: demo override → Pleos FusedLocation → Android LocationManager (GPS, which is
 * what `adb emu geo fix` feeds on the emulator). The freshest fix wins.
 */
class LocationSource(context: Context) {
    private val app = context.applicationContext
    private val fused = runCatching { FusedLocation(app) }.getOrNull()
    private var fusedOn = false
    private var osOn = false

    private val _fix = MutableStateFlow<Fix?>(null)
    val fix: StateFlow<Fix?> = _fix

    /** Demo mode: a fixed position chosen on screen. Null = use real sources. */
    @Volatile var demoOverride: LatLon? = null
    @Volatile var demoHeading: Double? = null

    /**
     * Last driving direction. Kept through the stop, so at the P moment it is the way the car was facing
     * when it pulled in (wrong only if it reversed into the spot). From Pleos FusedLocation's vehicle
     * heading when it gives one, otherwise from the track between fixes at least 5 m apart.
     */
    @Volatile private var heading: Double? = null
    private var trackFrom: LatLon? = null

    private fun track(p: LatLon, sdkHeading: Double?) {
        if (sdkHeading != null && !sdkHeading.isNaN() && sdkHeading != 0.0) {
            heading = (sdkHeading % 360 + 360) % 360; trackFrom = p; return
        }
        val from = trackFrom
        if (from == null) { trackFrom = p; return }
        if (Geo.distanceM(from, p) >= 5.0) {
            val proj = LocalProjection(from)
            heading = (Math.toDegrees(atan2(proj.x(p), proj.y(p))) + 360) % 360
            trackFrom = p
        }
    }

    private val fusedListener = object : FusedLocationListener {
        override fun onChanged(location: LocationData?) {
            location ?: return
            val p = LatLon(location.latitude, location.longitude)
            if (!gpsLost) track(p, location.heading.toDouble())
            offer(Fix(p, DEFAULT_ACC, "Pleos FusedLocation", System.currentTimeMillis(), heading))
        }

        override fun onError(exception: Exception) {
            Log.w(TAG, "fused error: ${exception.message}")
        }
    }

    private val osListener = LocationListener { l: Location ->
        val p = LatLon(l.latitude, l.longitude)
        if (!gpsLost) track(p, if (l.hasBearing() && l.speed > 1.5f) l.bearing.toDouble() else null)
        offer(Fix(p, if (l.hasAccuracy()) l.accuracy.toDouble() else DEFAULT_ACC, "GPS", System.currentTimeMillis(), heading))
    }

    /** Demo: pretend the GPS signal is gone (entering an underground car park); new fixes are ignored. */
    @Volatile var gpsLost = false

    private fun offer(f: Fix) {
        if (gpsLost) return
        _fix.value = f
    }

    fun hasPermission(): Boolean =
        app.checkSelfPermission(FUSED_PERMISSION) == PackageManager.PERMISSION_GRANTED ||
            app.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun start() {
        val f = fused
        if (!fusedOn && f != null && app.checkSelfPermission(FUSED_PERMISSION) == PackageManager.PERMISSION_GRANTED) {
            runCatching {
                if (f.isSupported()) {
                    f.initialize()
                    f.registerFusedLocationCallback(fusedListener)
                    f.getLastKnownLocation(fusedListener)
                    fusedOn = true
                }
            }.onFailure { Log.w(TAG, "fused start failed: ${it.message}") }
        }
        if (!osOn && app.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            runCatching {
                val lm = app.getSystemService(LocationManager::class.java)
                lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let { osListener.onLocationChanged(it) }
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, osListener, Looper.getMainLooper())
                osOn = true
            }.onFailure { Log.w(TAG, "os location failed: ${it.message}") }
        }
    }

    /**
     * Best position for the P moment. Location is tracked all the time while the service runs, so the
     * last fix is normally seconds old and is used immediately; only without one do we wait briefly.
     */
    suspend fun current(maxWaitMs: Long = 3000): Fix? {
        demoOverride?.let { return Fix(it, 3.0, "시연 위치", System.currentTimeMillis(), demoHeading) }
        _fix.value?.let { if (System.currentTimeMillis() - it.atMs < FRESH_MS) return it }
        start()
        val fresh = withTimeoutOrNull(maxWaitMs) {
            while (true) {
                val f = _fix.value
                if (f != null && System.currentTimeMillis() - f.atMs < FRESH_MS) return@withTimeoutOrNull f
                delay(200)
            }
            @Suppress("UNREACHABLE_CODE") null
        }
        return fresh ?: _fix.value
    }

    fun stop() {
        if (fusedOn) runCatching { fused?.unregisterFusedLocationCallback(fusedListener); fused?.release() }
        if (osOn) runCatching { app.getSystemService(LocationManager::class.java).removeUpdates(osListener) }
        fusedOn = false
        osOn = false
    }

    companion object {
        private const val TAG = "Seum/Loc"
        const val FUSED_PERMISSION = "pleos.car.permission.FUSED_LOCATION"
        private const val DEFAULT_ACC = 8.0
        /** Same as Engine.Config.staleFixMs: older fixes are not used for the P moment. */
        private const val FRESH_MS = 10_000L
    }
}
