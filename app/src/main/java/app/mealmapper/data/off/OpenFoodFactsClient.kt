package app.mealmapper.data.off

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

sealed interface LookupResult {
    data class Found(val result: ParseResult) : LookupResult
    data class Failed(val reason: String) : LookupResult
}

/** Looks up a barcode on Open Food Facts. No API key. Only the barcode number leaves the phone. */
class OpenFoodFactsClient(private val http: OkHttpClient) {

    suspend fun lookup(barcode: String): LookupResult = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$BASE_URL/api/v2/product/$barcode?fields=$FIELDS")
            // OFF asks every app to identify itself.
            .header("User-Agent", USER_AGENT)
            .build()
        try {
            http.newCall(request).execute().use { response ->
                // OFF answers 404 with a JSON body when the product is unknown.
                if (!response.isSuccessful && response.code != 404) {
                    return@withContext LookupResult.Failed("Open Food Facts returned ${response.code}. Try again.")
                }
                LookupResult.Found(OpenFoodFactsParser.parse(barcode, response.body.string()))
            }
        } catch (e: IOException) {
            LookupResult.Failed("No connection to Open Food Facts. Check your internet and try again.")
        }
    }

    companion object {
        fun create(): OpenFoodFactsClient = OpenFoodFactsClient(
            OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build(),
        )

        private const val BASE_URL = "https://world.openfoodfacts.org"
        private const val USER_AGENT = "MealMapper/0.2 (Android; personal app)"
        private const val FIELDS =
            "product_name,product_name_en,brands,nutriments,serving_quantity,quantity," +
                "product_quantity,product_quantity_unit"
    }
}
