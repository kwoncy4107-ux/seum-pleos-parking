package ai.seum.app.car

import ai.seum.engine.Gear
import ai.seum.engine.ParkTrigger
import android.car.Car
import android.car.VehiclePropertyIds
import android.car.drivingstate.CarUxRestrictions
import android.car.drivingstate.CarUxRestrictionsManager
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Gear and driving-restriction signals from AAOS.
 *
 * - Gear: GEAR_SELECTION (CAR_POWERTRAIN, a normal permission). Callbacks from the Virtual Console were
 *   seen to go missing by other teams, so the value is also polled every 2 s.
 * - Restrictions: CarUxRestrictionsManager. If the state is unknown we assume the car is moving (fail-safe).
 */
class CarSignals(context: Context) {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var car: Car? = null
    private var props: CarPropertyManager? = null
    private var ux: CarUxRestrictionsManager? = null

    private val _gear = MutableStateFlow(Gear.UNKNOWN)
    val gear: StateFlow<Gear> = _gear

    /** true while the system requires distraction optimisation (driving). Unknown counts as driving. */
    /** Vehicle speed in m/s (PERF_VEHICLE_SPEED); null until known or when not permitted. */
    private val _speed = MutableStateFlow<Float?>(null)
    val speed: StateFlow<Float?> = _speed

    private val _restricted = MutableStateFlow(true)
    val restricted: StateFlow<Boolean> = _restricted

    private val _status = MutableStateFlow("차량 서비스 연결 전")
    val status: StateFlow<String> = _status

    /** Invoked on the main thread for every gear reading (event or poll). */
    var onGear: ((Gear) -> Unit)? = null

    /** Invoked on the main thread for every speed reading. */
    var onSpeed: ((Float?) -> Unit)? = null

    private val gearCallback = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) {
            when (value.propertyId) {
                VehiclePropertyIds.GEAR_SELECTION -> publish(value.value as? Int)
                VehiclePropertyIds.PERF_VEHICLE_SPEED -> publishSpeed((value.value as? Float))
            }
        }

        override fun onErrorEvent(propId: Int, zone: Int) {
            Log.w(TAG, "gear error event prop=$propId zone=$zone")
        }
    }

    private val poll = object : Runnable {
        override fun run() {
            readGear()
            main.postDelayed(this, POLL_MS)
        }
    }

    fun start() {
        if (car != null) return
        try {
            car = Car.createCar(app, null, Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) { c, ready ->
                if (ready) connect(c) else {
                    _status.value = "차량 서비스 연결 끊김"
                    _restricted.value = true
                }
            }
        } catch (e: Exception) {
            _status.value = "차량 서비스 연결 실패: ${e.message}"
            Log.e(TAG, "createCar failed", e)
        }
        main.postDelayed(poll, POLL_MS)
    }

    private fun connect(c: Car) {
        props = (c.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager)?.also { pm ->
            val ok = try {
                @Suppress("DEPRECATION")
                pm.registerCallback(gearCallback, VehiclePropertyIds.GEAR_SELECTION, CarPropertyManager.SENSOR_RATE_ONCHANGE)
            } catch (e: Exception) {
                Log.e(TAG, "gear subscribe failed", e); false
            }
            _status.value = if (ok) "기어 신호 수신 중" else "기어 구독 거부 (CAR_POWERTRAIN 확인)"
            subscribeSpeed(pm)
        }
        readGear()
        ux = (c.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as? CarUxRestrictionsManager)?.also { m ->
            m.currentCarUxRestrictions?.let(::onUx)
            m.registerListener(::onUx)
        }
    }

    /** Speed needs CAR_SPEED (a runtime permission); without it the P trigger falls back to gear only. */
    fun subscribeSpeed(pm: CarPropertyManager? = props) {
        val m = pm ?: return
        if (speedSubscribed) return
        speedSubscribed = try {
            @Suppress("DEPRECATION")
            m.registerCallback(gearCallback, VehiclePropertyIds.PERF_VEHICLE_SPEED, CarPropertyManager.SENSOR_RATE_NORMAL)
        } catch (e: Exception) {
            Log.w(TAG, "speed subscribe failed: ${e.message}"); false
        }
    }

    private var speedSubscribed = false

    private fun publishSpeed(v: Float?) {
        _speed.value = v
        onSpeed?.invoke(v)
    }

    private fun onUx(r: CarUxRestrictions) {
        _restricted.value = r.isRequiresDistractionOptimization
    }

    private fun readGear() {
        val pm = props ?: return
        try {
            publish(pm.getIntProperty(VehiclePropertyIds.GEAR_SELECTION, 0))
        } catch (e: Exception) {
            Log.w(TAG, "gear read failed: ${e.message}")
        }
    }

    private fun publish(raw: Int?) {
        val g = raw?.let(ParkTrigger::fromVehicleGear) ?: Gear.UNKNOWN
        _gear.value = g
        onGear?.invoke(g)
    }

    fun stop() {
        main.removeCallbacks(poll)
        try {
            @Suppress("DEPRECATION")
            props?.unregisterCallback(gearCallback)
        } catch (_: Exception) {
        }
        try {
            ux?.unregisterListener()
        } catch (_: Exception) {
        }
        try {
            car?.disconnect()
        } catch (_: Exception) {
        }
        car = null
    }

    companion object {
        private const val TAG = "Seum/Car"
        private const val POLL_MS = 2000L
    }
}
