package app.mealmapper.data.cache

import android.content.Context
import app.mealmapper.data.ai.CachedLookup
import app.mealmapper.data.ai.LookupCache
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Every value found online, kept the moment it is found (before the user logs anything). The next time the same
 * product or dish comes up it is instant and costs no search. Entries older than 180 days are searched again.
 */
class WebCache(context: Context) : LookupCache {
    private val file = File(context.filesDir, "web-lookups.json")
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = MapSerializer(String.serializer(), CachedLookup.serializer())
    private val _entries = MutableStateFlow(load())
    val entries: StateFlow<Map<String, CachedLookup>> = _entries.asStateFlow()

    @Synchronized
    override fun get(key: String): CachedLookup? =
        _entries.value[key]?.takeIf { System.currentTimeMillis() - it.at < MAX_AGE_MS }

    @Synchronized
    override fun put(key: String, value: CachedLookup) = save(_entries.value + (key to value))

    @Synchronized
    fun clear() = save(emptyMap())

    private fun save(map: Map<String, CachedLookup>) {
        _entries.value = map
        runCatching { file.writeText(json.encodeToString(serializer, map)) }
    }

    private fun load(): Map<String, CachedLookup> =
        runCatching { json.decodeFromString(serializer, file.readText()) }.getOrElse { emptyMap() }

    private companion object {
        const val MAX_AGE_MS = 180L * 24 * 3600 * 1000
    }
}
