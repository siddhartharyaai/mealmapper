package app.mealmapper

import android.content.Context
import app.mealmapper.data.cache.ProductCache
import app.mealmapper.data.ai.AiSettings
import app.mealmapper.data.ai.GroqClient
import app.mealmapper.data.ai.NutritionLookup
import app.mealmapper.data.health.HealthConnectGateway
import app.mealmapper.data.off.OpenFoodFactsClient
import app.mealmapper.data.settings.ProfileStore

/** Manual dependency wiring. The app is small; a DI framework is not necessary. */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    val healthConnect = HealthConnectGateway(appContext)
    val openFoodFacts: OpenFoodFactsClient by lazy { OpenFoodFactsClient.create() }
    val profile = ProfileStore(appContext)
    val aiSettings = AiSettings(appContext)
    val groq: GroqClient by lazy { GroqClient(aiSettings) }
    val nutritionLookup: NutritionLookup by lazy { NutritionLookup(groq) }
    val productCache: ProductCache by lazy { ProductCache(appContext) }
}
