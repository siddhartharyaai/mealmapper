package app.mealmapper.data.fooddb

import android.content.Context
import app.mealmapper.domain.DbFood
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** The offline databank (about 1,300 foods, 250 KB). Loaded once, on first use, off the main thread. */
class FoodDb(private val context: Context) {
    private val lock = Mutex()
    private var cache: List<DbFood>? = null

    suspend fun all(): List<DbFood> = lock.withLock {
        cache ?: withContext(Dispatchers.IO) {
            FoodDbParser.parse(context.assets.open("foods.json").bufferedReader().use { it.readText() })
        }.also { cache = it }
    }

    suspend fun byId(id: String): DbFood? = all().firstOrNull { it.id == id }
}
