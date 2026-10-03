package uz.uznav.navigator.data

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import uz.uznav.navigator.model.Favorite
import uz.uznav.navigator.model.FavoriteType
import uz.uznav.navigator.model.Place
import uz.uznav.navigator.model.PlaceKind
import java.io.File

/**
 * Sevimli joylar ("Uy", "Ish", "Onamning uyi" ...) ni telefonda saqlash.
 * Ma'lumotlar oddiy JSON fayl ko'rinishida ilovaning ichki papkasida turadi.
 */
class FavoritesStore private constructor(context: Context) {

    private val file = File(context.filesDir, "uznav/sevimli.json").apply {
        parentFile?.mkdirs()
    }
    private val items = ArrayList<Favorite>()

    init {
        load()
    }

    @Synchronized
    fun all(): List<Favorite> = items.sortedWith(
        compareBy({ it.type.ordinal }, { -it.createdAt })
    )

    /** "Uy" sifatida saqlangan joy (bo'lmasa null). */
    @Synchronized
    fun home(): Favorite? = items.firstOrNull { it.type == FavoriteType.UY }

    /** "Ish" sifatida saqlangan joy (bo'lmasa null). */
    @Synchronized
    fun work(): Favorite? = items.firstOrNull { it.type == FavoriteType.ISH }

    @Synchronized
    fun save(title: String, type: FavoriteType, place: Place): Favorite {
        // "Uy" va "Ish" bittadan bo'ladi — eskisi almashtiriladi
        if (type != FavoriteType.BOSHQA) {
            items.removeAll { it.type == type }
        }
        val favorite = Favorite(
            id = System.currentTimeMillis().toString(),
            title = title.ifBlank { place.name },
            type = type,
            place = place.copy(kind = PlaceKind.SEVIMLI)
        )
        items.add(favorite)
        persist()
        return favorite
    }

    @Synchronized
    fun delete(id: String) {
        if (items.removeAll { it.id == id }) persist()
    }

    private fun load() {
        if (!file.exists()) return
        runCatching {
            val array = JSONArray(file.readText())
            for (i in 0 until array.length()) {
                items.add(Favorite.fromJson(array.getJSONObject(i)))
            }
        }.onFailure { Log.w(TAG, "Sevimli joylarni o'qishda xato", it) }
    }

    private fun persist() {
        runCatching {
            val array = JSONArray()
            items.forEach { array.put(it.toJson()) }
            file.writeText(array.toString())
        }.onFailure { Log.w(TAG, "Sevimli joylarni saqlashda xato", it) }
    }

    companion object {
        private const val TAG = "UzNav/Sevimli"

        @Volatile
        private var instance: FavoritesStore? = null

        fun get(context: Context): FavoritesStore =
            instance ?: synchronized(this) {
                instance ?: FavoritesStore(context.applicationContext).also { instance = it }
            }
    }
}

/**
 * Qidiruv tarixi — oxirgi qidirilgan manzillar (eng ko'pi 60 ta).
 */
class HistoryStore private constructor(context: Context) {

    private val file = File(context.filesDir, "uznav/tarix.json").apply {
        parentFile?.mkdirs()
    }
    private val items = ArrayList<HistoryEntry>()

    /** Tarix yozuvi: joy va qidirilgan vaqt. */
    data class HistoryEntry(val place: Place, val time: Long) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("place", place.toJson())
            put("time", time)
        }

        companion object {
            fun fromJson(json: JSONObject) = HistoryEntry(
                place = Place.fromJson(json.optJSONObject("place") ?: JSONObject()),
                time = json.optLong("time", System.currentTimeMillis())
            )
        }
    }

    init {
        load()
    }

    @Synchronized
    fun all(): List<HistoryEntry> = items.sortedByDescending { it.time }

    @Synchronized
    fun add(place: Place) {
        // Bir xil joy takrorlanmasin — eskisi o'chirilib, yangisi qo'shiladi
        items.removeAll {
            it.place.name.equals(place.name, ignoreCase = true) &&
                    Math.abs(it.place.point.lat - place.point.lat) < 1e-5 &&
                    Math.abs(it.place.point.lon - place.point.lon) < 1e-5
        }
        items.add(HistoryEntry(place.copy(kind = PlaceKind.TARIX), System.currentTimeMillis()))
        while (items.size > MAX_ITEMS) {
            val oldest = items.minByOrNull { it.time } ?: break
            items.remove(oldest)
        }
        persist()
    }

    @Synchronized
    fun clear() {
        items.clear()
        persist()
    }

    @Synchronized
    fun delete(place: Place) {
        if (items.removeAll {
                it.place.name == place.name &&
                        Math.abs(it.place.point.lat - place.point.lat) < 1e-6
            }) persist()
    }

    private fun load() {
        if (!file.exists()) return
        runCatching {
            val array = JSONArray(file.readText())
            for (i in 0 until array.length()) {
                items.add(HistoryEntry.fromJson(array.getJSONObject(i)))
            }
        }.onFailure { Log.w(TAG, "Tarixni o'qishda xato", it) }
    }

    private fun persist() {
        runCatching {
            val array = JSONArray()
            items.forEach { array.put(it.toJson()) }
            file.writeText(array.toString())
        }.onFailure { Log.w(TAG, "Tarixni saqlashda xato", it) }
    }

    companion object {
        private const val TAG = "UzNav/Tarix"
        private const val MAX_ITEMS = 60

        @Volatile
        private var instance: HistoryStore? = null

        fun get(context: Context): HistoryStore =
            instance ?: synchronized(this) {
                instance ?: HistoryStore(context.applicationContext).also { instance = it }
            }
    }
}
