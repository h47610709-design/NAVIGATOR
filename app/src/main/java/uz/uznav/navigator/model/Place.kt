package uz.uznav.navigator.model

import org.json.JSONObject

/** Joy turi — ro'yxatda tegishli belgini ko'rsatish uchun. */
enum class PlaceKind {
    SHAHAR,      // shahar yoki qishloq
    TUMAN,       // tuman / mahalla
    KOCHA,       // ko'cha
    MANZIL,      // uy raqami bilan manzil
    OBYEKT,      // bozor, kasalxona, benzin shoxobchasi va h.k.
    SEVIMLI,     // sevimli joy
    TARIX,       // qidiruv tarixidan
    XARITA       // xaritada tanlangan nuqta
}

/**
 * Qidiruv natijasi yoki saqlangan joy.
 */
data class Place(
    val name: String,
    val address: String = "",
    val point: GeoPoint,
    val kind: PlaceKind = PlaceKind.OBYEKT,
    /** Foydalanuvchidan masofa (metr). Ro'yxatni saralashda ishlatiladi. */
    val distanceM: Double = -1.0,
    /** Ichki moslik bahosi (qidiruv tartibi uchun). */
    val score: Double = 0.0
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("name", name)
        put("address", address)
        put("lat", point.lat)
        put("lon", point.lon)
        put("kind", kind.name)
    }

    companion object {
        fun fromJson(json: JSONObject): Place = Place(
            name = json.optString("name", "Nomsiz joy"),
            address = json.optString("address", ""),
            point = GeoPoint(json.optDouble("lat", 0.0), json.optDouble("lon", 0.0)),
            kind = runCatching { PlaceKind.valueOf(json.optString("kind", "OBYEKT")) }
                .getOrDefault(PlaceKind.OBYEKT)
        )
    }
}

/** Sevimli joy turi: Uy, Ish yoki boshqa nomli joy. */
enum class FavoriteType { UY, ISH, BOSHQA }

/**
 * Foydalanuvchi saqlagan sevimli joy.
 */
data class Favorite(
    val id: String,
    val title: String,
    val type: FavoriteType,
    val place: Place,
    val createdAt: Long = System.currentTimeMillis()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("title", title)
        put("type", type.name)
        put("place", place.toJson())
        put("createdAt", createdAt)
    }

    companion object {
        fun fromJson(json: JSONObject): Favorite = Favorite(
            id = json.optString("id", System.nanoTime().toString()),
            title = json.optString("title", "Sevimli joy"),
            type = runCatching { FavoriteType.valueOf(json.optString("type", "BOSHQA")) }
                .getOrDefault(FavoriteType.BOSHQA),
            place = Place.fromJson(json.optJSONObject("place") ?: JSONObject()),
            createdAt = json.optLong("createdAt", System.currentTimeMillis())
        )
    }
}
