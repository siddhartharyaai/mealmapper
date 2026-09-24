package app.mealmapper

import android.content.Context
import app.mealmapper.data.health.HealthConnectGateway
import app.mealmapper.data.off.OpenFoodFactsClient

/** Manual dependency wiring. The app is small; a DI framework is not necessary. */
class AppContainer(context: Context) {
    val healthConnect = HealthConnectGateway(context.applicationContext)
    val openFoodFacts: OpenFoodFactsClient by lazy { OpenFoodFactsClient.create() }
}
