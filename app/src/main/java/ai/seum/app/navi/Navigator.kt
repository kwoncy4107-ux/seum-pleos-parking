package ai.seum.app.navi

import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.seum.engine.ParkingLot
import ai.seum.engine.Speech
import android.content.Context
import android.util.Log

/** Hands a parking lot to the built-in Pleos navigation as the new destination. */
class Navigator(context: Context) {
    private val app = context.applicationContext
    private var navi: NaviHelper? = null
    private var connected = false
    private var queued: ParkingLot? = null

    fun start() {
        if (navi != null) return
        try {
            val n = NaviHelper(app)
            navi = n
            n.initialize(
                onConnected = {
                    connected = true
                    queued?.let { queued = null; route(it) }
                },
                onFailed = { t -> Log.w(TAG, "navi connect failed: ${t.message}") },
            )
        } catch (e: Throwable) {
            Log.w(TAG, "navi create failed: ${e.message}")
        }
    }

    /** Returns false when the navigation service is not reachable at all. */
    fun route(lot: ParkingLot): Boolean {
        val n = navi ?: return false
        if (!connected) { queued = lot; return true }
        return try {
            n.requestRoute(
                RouteInfo(
                    latitude = lot.pos.lat,
                    longitude = lot.pos.lon,
                    displayLatitude = null,
                    displayLongitude = null,
                    poiName = Speech.lotName(lot),
                    poiId = "",
                    poiSubId = "0",
                    address = lot.address.ifBlank { lot.name },
                ),
            )
            true
        } catch (e: Throwable) {
            Log.w(TAG, "requestRoute failed: ${e.message}")
            false
        }
    }

    fun stop() {
        runCatching { navi?.release() }
        navi = null
        connected = false
    }

    companion object {
        private const val TAG = "Seum/Navi"
    }
}
