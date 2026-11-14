package ai.seum.app.ui

import ai.seum.app.SeumApp
import ai.seum.app.loc.LocationSource
import ai.seum.app.service.GuardService
import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts

class MainActivity : ComponentActivity() {

    private val permissions = arrayOf(
        LocationSource.FUSED_PERMISSION,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.POST_NOTIFICATIONS,
        "android.car.permission.CAR_SPEED",
    )

    private val ask = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        app.location.start()
        app.car.subscribeSpeed()
    }

    private val app get() = application as SeumApp

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app.store.onboarded = true
        GuardService.start(this)
        val missing = permissions.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) ask.launch(missing.toTypedArray())

        setContent { SeumScreen(app) }
    }

    override fun onResume() {
        super.onResume()
        app.inForeground = true
        app.guard.clearAlert()
    }

    override fun onPause() {
        app.inForeground = false
        super.onPause()
    }
}
