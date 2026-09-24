package app.mealmapper

import android.content.Context
import app.mealmapper.data.health.HealthConnectGateway
import app.mealmapper.data.off.OpenFoodFactsClient
import app.mealmapper.data.settings.ProfileStore

/** Manual dependency wiring. The app is small; a DI framework is not necessary. */
class AppContainer(context: Context) {
    val healthConnect = HealthConnectGateway(context.applicationContext)
    val openFoodFacts: OpenFoodFactsClient by lazy { OpenFoodFactsClient.create() }
    val profile = ProfileStore(context.applicationContext)
}
