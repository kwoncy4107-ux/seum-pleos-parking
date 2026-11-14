package ai.seum.app

import ai.seum.app.car.CarSignals
import ai.seum.app.data.MapDb
import ai.seum.app.loc.LocationSource
import ai.seum.app.navi.Navigator
import ai.seum.app.voice.Voice
import android.app.Application

/** Single object graph for the process; the service and the screen share it. */
class SeumApp : Application() {
    lateinit var store: Store; private set
    lateinit var car: CarSignals; private set
    lateinit var location: LocationSource; private set
    lateinit var voice: Voice; private set
    lateinit var map: MapDb; private set
    lateinit var guard: Guard; private set

    /** The seum screen is visible; then the heads-up alert is not needed. */
    @Volatile var inForeground = false

    override fun onCreate() {
        super.onCreate()
        store = Store(this)
        car = CarSignals(this)
        location = LocationSource(this)
        voice = Voice(this)
        map = MapDb(this)
        guard = Guard(car, location, voice, Navigator(this), map, store, ParkAlert(this) { inForeground })
    }
}
