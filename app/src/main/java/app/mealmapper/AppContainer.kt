package app.mealmapper

import android.content.Context
import app.mealmapper.data.health.HealthConnectGateway

/** Manual dependency wiring. The app is small; a DI framework is not necessary. */
class AppContainer(context: Context) {
    val healthConnect = HealthConnectGateway(context.applicationContext)
}
