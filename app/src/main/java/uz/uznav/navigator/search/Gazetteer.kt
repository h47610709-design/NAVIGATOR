package uz.uznav.navigator.search

import android.content.Context
import android.util.Log
import org.json.JSONObject
import uz.uznav.navigator.model.GeoPoint
import uz.uznav.navigator.model.Place
import uz.uznav.navigator.model.PlaceKind
import uz.uznav.navigator.util.GeoUtils
import uz.uznav.navigator.util.UzText

/**
 * Ichki ma'lumotnoma: O'zbekiston shaharlari, viloyat markazlari,
 * aeroportlar va mashhur obyektlar.
 *
 * Bu ro'yxat ilova ichida (assets/uz_joylar.json) saqlanadi, shuning uchun
 * xarita fayli hali yuklanmagan bo'lsa ham qidiruv ishlaydi va
 * "Samarqand" kabi so'rovlar bir zumda topiladi.
 */
class Gazetteer private constructor(context: Context) {

    /** Bitta ma'lumotnoma yozuvi. */
    class Entry(
        val name: String,
        val type: String,
        val description: String,
        val point: GeoPoint,
        val searchKeys: List<String>
    )

    private val entries: List<Entry> = load(context)

    /** Barcha yozuvlar (sozlamalar yoki boshlang'ich ro'yxat uchun). */
    fun all(): List<Entry> = entries

    /**
     * So'rov bo'yicha joylarni topadi.
     *
     * @param near shu nuqtaga yaqinlari yuqoriroq turadi
     */
    fun search(query: String, near: GeoPoint? = null, limit: Int = 12): List<Place> {
        val normalized = UzText.normalize(query)
        if (normalized.isEmpty()) return emptyList()

        val found = ArrayList<Place>(limit)
        for (entry in entries) {
            var best = 0.0
            for (key in entry.searchKeys) {
                val score = UzText.matchScore(normalized, key)
                if (score > best) best = score
            }
            if (best <= 0.0) continue
            val distance = near?.let { GeoUtils.distance(it, entry.point) } ?: -1.0
            found.add(
                Place(
                    name = entry.name,
                    address = entry.description,
                    point = entry.point,
                    kind = kindOf(entry.type),
                    distanceM = distance,
                    // Yaqinroq joylar biroz ustunroq
                    score = best + if (distance in 0.0..50_000.0) 0.05 else 0.0
                )
            )
        }
        return found.sortedWith(compareByDescending<Place> { it.score }.thenBy {
            if (it.distanceM < 0) Double.MAX_VALUE else it.distanceM
        }).take(limit)
    }

    /**
     * So'rovda shahar/viloyat nomi bor-yo'qligini aniqlaydi.
     * Masalan "Amir Temur ko'chasi, Samarqand" -> Samarqand markazi.
     */
    fun detectCity(query: String): Entry? {
        val normalized = UzText.normalize(query)
        if (normalized.isEmpty()) return null
        var best: Entry? = null
        var bestScore = 0.0
        for (entry in entries) {
            if (entry.type != "shahar" && entry.type != "viloyat") continue
            for (key in entry.searchKeys) {
                val normalizedKey = UzText.normalize(key)
                if (normalizedKey.length < 4) continue
                if (normalized.contains(normalizedKey)) {
                    val score = normalizedKey.length.toDouble()
                    if (score > bestScore) {
                        bestScore = score
                        best = entry
                    }
                }
            }
        }
        return best
    }

    private fun kindOf(type: String): PlaceKind = when (type) {
        "shahar" -> PlaceKind.SHAHAR
        "viloyat" -> PlaceKind.TUMAN
        else -> PlaceKind.OBYEKT
    }

    private fun load(context: Context): List<Entry> {
        return try {
            val text = context.assets.open(ASSET_NAME).bufferedReader().use { it.readText() }
            val array = JSONObject(text).getJSONArray("joylar")
            val result = ArrayList<Entry>(array.length())
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                val name = item.optString("n")
                if (name.isBlank()) continue
                val keys = ArrayList<String>(4)
                keys.add(name)
                val alt = item.optJSONArray("alt")
                if (alt != null) {
                    for (j in 0 until alt.length()) keys.add(alt.optString(j))
                }
                result.add(
                    Entry(
                        name = name,
                        type = item.optString("t", "obyekt"),
                        description = item.optString("v", ""),
                        point = GeoPoint(item.optDouble("lat"), item.optDouble("lon")),
                        searchKeys = keys.filter { it.isNotBlank() }
                    )
                )
            }
            Log.i(TAG, "Ma'lumotnoma yuklandi: ${result.size} ta joy")
            result
        } catch (e: Exception) {
            Log.e(TAG, "Ma'lumotnomani o'qib bo'lmadi", e)
            emptyList()
        }
    }

    companion object {
        private const val TAG = "UzNav/Malumotnoma"
        private const val ASSET_NAME = "uz_joylar.json"

        @Volatile
        private var instance: Gazetteer? = null

        fun get(context: Context): Gazetteer =
            instance ?: synchronized(this) {
                instance ?: Gazetteer(context.applicationContext).also { instance = it }
            }
    }
}
