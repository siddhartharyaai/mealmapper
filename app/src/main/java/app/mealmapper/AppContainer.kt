package app.mealmapper

import android.content.Context
import app.mealmapper.data.cache.ProductCache
import app.mealmapper.data.ai.AiSettings
import app.mealmapper.data.ai.GeminiClient
import app.mealmapper.data.ai.NutritionLookup
import app.mealmapper.data.health.HealthConnectGateway
import app.mealmapper.data.log.LogStore
import app.mealmapper.data.fooddb.FoodDb
import app.mealmapper.data.voice.DeepgramClient
import app.mealmapper.data.voice.DeepgramSettings
import app.mealmapper.data.off.OpenFoodFactsClient
import app.mealmapper.data.settings.ProfileStore

/** Manual dependency wiring. The app is small; a DI framework is not necessary. */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    val healthConnect = HealthConnectGateway(appContext)
    val openFoodFacts: OpenFoodFactsClient by lazy { OpenFoodFactsClient.create() }
    val profile = ProfileStore(appContext)
    val aiSettings = AiSettings(appContext)
    val gemini: GeminiClient by lazy { GeminiClient(aiSettings) }
    val nutritionLookup: NutritionLookup by lazy { NutritionLookup(gemini) }
    val productCache: ProductCache by lazy { ProductCache(appContext) }
    val log: LogStore by lazy { LogStore(appContext) }
    val foodDb: FoodDb by lazy { FoodDb(appContext) }
    val deepgramSettings = DeepgramSettings(appContext)
    val deepgram: DeepgramClient by lazy { DeepgramClient(deepgramSettings) }
}
