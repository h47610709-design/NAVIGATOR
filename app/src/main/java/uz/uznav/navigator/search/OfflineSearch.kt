package uz.uznav.navigator.search

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.mapsforge.core.model.LatLong
import org.mapsforge.core.model.Tag
import org.mapsforge.core.model.Tile
import org.mapsforge.core.util.MercatorProjection
import uz.uznav.navigator.data.MapManager
import uz.uznav.navigator.model.GeoPoint
import uz.uznav.navigator.model.Place
import uz.uznav.navigator.model.PlaceKind
import uz.uznav.navigator.util.GeoUtils
import uz.uznav.navigator.util.UzText
import kotlin.coroutines.coroutineContext
import kotlin.math.abs

/**
 * Internetsiz manzil qidirish.
 *
 * Qidiruv ikki manbadan foydalanadi:
 *  1. Ichki ma'lumotnoma ([Gazetteer]) — shahar, viloyat, aeroport, mashhur joylar.
 *  2. Yuklangan `.map` xarita fayli — ko'chalar, mahallalar, do'konlar,
 *     shifoxonalar, benzin shoxobchalari va uy raqamlari.
 *
 * Hech qanday server yoki internet talab qilinmaydi.
 */
class OfflineSearch(
    context: Context,
    private val mapManager: MapManager
) {

    private val gazetteer = Gazetteer.get(context)

    /**
     * Qidiruvni bajaradi.
     *
     * @param query foydalanuvchi so'rovi, masalan "Amir Temur ko'chasi 108, Toshkent"
     * @param near joriy joylashuv (natijalarni yaqinlik bo'yicha saralash uchun)
     */
    suspend fun search(
        query: String,
        near: GeoPoint?,
        limit: Int = 30
    ): List<Place> = withContext(Dispatchers.Default) {
        val trimmed = query.trim()
        if (trimmed.length < 2) return@withContext emptyList()

        val results = ArrayList<Place>(limit * 2)

        // 1) Ichki ma'lumotnoma
        results.addAll(gazetteer.search(trimmed, near))

        // 2) Xarita faylidan qidirish
        val store = mapManager.store()
        if (store != null) {
            val city = gazetteer.detectCity(trimmed)
            val center = city?.point ?: near ?: results.firstOrNull()?.point
            if (center != null) {
                val streetQuery = buildStreetQuery(trimmed, city?.name)
                val houseNumber = UzText.extractHouseNumber(trimmed)
                results.addAll(
                    scanMap(
                        store = store,
                        center = center,
                        query = streetQuery,
                        houseNumber = houseNumber,
                        near = near ?: center,
                        limit = limit
                    )
                )
            }
        }

        coroutineContext.ensureActive()

        // 3) Takrorlarni olib tashlash va saralash
        val unique = LinkedHashMap<String, Place>()
        for (place in results.sortedWith(
            compareByDescending<Place> { it.score }.thenBy {
                if (it.distanceM < 0) Double.MAX_VALUE else it.distanceM
            }
        )) {
            val key = UzText.normalize(place.name) + "@" +
                    Math.round(place.point.lat * 2000) + "," + Math.round(place.point.lon * 2000)
            if (!unique.containsKey(key)) unique[key] = place
        }
        unique.values.take(limit)
    }

    /** So'rovdan shahar nomi va uy raqamini olib tashlab, ko'cha/obyekt nomini qoldiradi. */
    private fun buildStreetQuery(query: String, cityName: String?): String {
        var text = query
        if (cityName != null) {
            text = Regex(Regex.escape(cityName), RegexOption.IGNORE_CASE).replace(text, " ")
        }
        text = text.replace(Regex("\\d{1,4}[a-zA-Z]?"), " ")
        text = text.replace(Regex("[,;]"), " ")
        return text.trim().ifEmpty { query }
    }

    // ------------------------------------------------------------------
    // Xarita faylini skanerlash
    // ------------------------------------------------------------------

    private class StreetCandidate(
        val name: String,
        var bestPoint: GeoPoint,
        var bestDistance: Double,
        val geometry: ArrayList<GeoPoint>
    )

    private suspend fun scanMap(
        store: org.mapsforge.map.datastore.MapDataStore,
        center: GeoPoint,
        query: String,
        houseNumber: String?,
        near: GeoPoint,
        limit: Int
    ): List<Place> {
        val found = ArrayList<Place>(limit)
        val streets = HashMap<String, StreetCandidate>()
        val houses = ArrayList<Pair<String, GeoPoint>>()
        val startTime = System.currentTimeMillis()

        val centerTileX = MercatorProjection.longitudeToTileX(center.lon, ZOOM)
        val centerTileY = MercatorProjection.latitudeToTileY(center.lat, ZOOM)
        val centerChunkX = centerTileX / CHUNK
        val centerChunkY = centerTileY / CHUNK
        val maxTileNumber = Tile.getMaxTileNumber(ZOOM)

        outer@ for (ring in 0..MAX_RINGS) {
            for (dx in -ring..ring) {
                for (dy in -ring..ring) {
                    if (ring > 0 && abs(dx) != ring && abs(dy) != ring) continue
                    coroutineContext.ensureActive()
                    if (System.currentTimeMillis() - startTime > TIME_BUDGET_MS) break@outer

                    val chunkX = centerChunkX + dx
                    val chunkY = centerChunkY + dy
                    val minTileX = chunkX * CHUNK
                    val minTileY = chunkY * CHUNK
                    if (minTileX < 0 || minTileY < 0) continue
                    if (minTileX + CHUNK - 1 > maxTileNumber || minTileY + CHUNK - 1 > maxTileNumber) continue

                    val upperLeft = Tile(minTileX, minTileY, ZOOM, TILE_SIZE)
                    val lowerRight = Tile(minTileX + CHUNK - 1, minTileY + CHUNK - 1, ZOOM, TILE_SIZE)
                    val result = try {
                        store.readNamedItems(upperLeft, lowerRight)
                    } catch (e: Exception) {
                        Log.w(TAG, "Xaritadan o'qishda xato", e)
                        null
                    } ?: continue

                    // --- Nuqtali obyektlar (do'kon, shifoxona, bekat ...) ---
                    for (poi in result.pois) {
                        val name = tagValue(poi.tags, "name")
                        val number = tagValue(poi.tags, "addr:housenumber")
                        val point = GeoPoint(poi.position.latitude, poi.position.longitude)
                        if (number != null) houses.add(number to point)
                        if (name.isNullOrBlank()) continue
                        val score = UzText.matchScore(query, name)
                        if (score <= 0.0) continue
                        found.add(
                            Place(
                                name = name,
                                address = describeTags(poi.tags),
                                point = point,
                                kind = PlaceKind.OBYEKT,
                                distanceM = GeoUtils.distance(near, point),
                                score = score
                            )
                        )
                    }

                    // --- Chiziqli va yopiq obyektlar (ko'chalar, binolar) ---
                    for (way in result.ways) {
                        val blocks = way.latLongs ?: continue
                        val first = blocks.firstOrNull()?.firstOrNull() ?: continue
                        val point = way.labelPosition?.let { GeoPoint(it.latitude, it.longitude) }
                            ?: GeoPoint(first.latitude, first.longitude)

                        val number = tagValue(way.tags, "addr:housenumber")
                        if (number != null) houses.add(number to point)

                        val name = tagValue(way.tags, "name") ?: continue
                        val isStreet = tagValue(way.tags, "highway") != null
                        val score = UzText.matchScore(query, name)
                        if (score <= 0.0) continue

                        if (isStreet) {
                            val distance = GeoUtils.distance(near, point)
                            val candidate = streets.getOrPut(UzText.normalize(name)) {
                                StreetCandidate(name, point, distance, ArrayList(32))
                            }
                            if (distance < candidate.bestDistance) {
                                candidate.bestPoint = point
                                candidate.bestDistance = distance
                            }
                            collectGeometry(blocks, candidate.geometry)
                        } else {
                            found.add(
                                Place(
                                    name = name,
                                    address = describeTags(way.tags),
                                    point = point,
                                    kind = PlaceKind.OBYEKT,
                                    distanceM = GeoUtils.distance(near, point),
                                    score = score
                                )
                            )
                        }
                    }

                    if (found.size + streets.size >= limit * 3 && ring >= 1) break@outer
                }
            }
        }

        // --- Ko'chalar va uy raqamlari ---
        for (street in streets.values) {
            val score = UzText.matchScore(query, street.name)
            if (houseNumber != null) {
                val house = findHouse(street, houses, houseNumber)
                if (house != null) {
                    found.add(
                        Place(
                            name = "${street.name}, $houseNumber",
                            address = "Uy raqami bo'yicha topildi",
                            point = house,
                            kind = PlaceKind.MANZIL,
                            distanceM = GeoUtils.distance(near, house),
                            score = score + 0.2
                        )
                    )
                    continue
                }
            }
            found.add(
                Place(
                    name = street.name,
                    address = if (houseNumber != null) "Uy raqami topilmadi — ko'chaning o'zi" else "Ko'cha",
                    point = street.bestPoint,
                    kind = PlaceKind.KOCHA,
                    distanceM = street.bestDistance,
                    score = score
                )
            )
        }

        return found
    }

    /** Ko'cha yaqinidagi mos uy raqamini topadi. */
    private fun findHouse(
        street: StreetCandidate,
        houses: List<Pair<String, GeoPoint>>,
        houseNumber: String
    ): GeoPoint? {
        val target = houseNumber.lowercase()
        var best: GeoPoint? = null
        var bestDistance = HOUSE_MAX_DISTANCE_M
        for ((number, point) in houses) {
            if (number.lowercase() != target) continue
            for (vertex in street.geometry) {
                val distance = GeoUtils.fastDistance(point.lat, point.lon, vertex.lat, vertex.lon)
                if (distance < bestDistance) {
                    bestDistance = distance
                    best = point
                }
            }
        }
        return best
    }

    private fun collectGeometry(blocks: Array<Array<LatLong>>, target: ArrayList<GeoPoint>) {
        if (target.size > MAX_STREET_VERTICES) return
        for (block in blocks) {
            for (coordinate in block) {
                target.add(GeoPoint(coordinate.latitude, coordinate.longitude))
                if (target.size > MAX_STREET_VERTICES) return
            }
        }
    }

    private fun tagValue(tags: List<Tag>?, key: String): String? =
        tags?.firstOrNull { it.key == key }?.value?.takeIf { it.isNotBlank() }

    /** Obyekt turini o'zbekcha tasvirlaydi. */
    private fun describeTags(tags: List<Tag>?): String {
        if (tags == null) return ""
        for (tag in tags) {
            val description = when (tag.key) {
                "amenity" -> amenityName(tag.value)
                "shop" -> "Do'kon"
                "tourism" -> "Turistik obyekt"
                "leisure" -> "Dam olish maskani"
                "office" -> "Ofis"
                "healthcare" -> "Tibbiyot muassasasi"
                "aeroway" -> "Aeroport obyekti"
                "railway" -> "Temir yo'l obyekti"
                "place" -> placeName(tag.value)
                "building" -> "Bino"
                else -> null
            }
            if (description != null) return description
        }
        return ""
    }

    private fun amenityName(value: String): String = when (value) {
        "fuel" -> "Yoqilg'i quyish shoxobchasi"
        "hospital" -> "Shifoxona"
        "clinic", "doctors" -> "Poliklinika"
        "pharmacy" -> "Dorixona"
        "school" -> "Maktab"
        "university", "college" -> "Oliy o'quv yurti"
        "kindergarten" -> "Bolalar bog'chasi"
        "restaurant" -> "Restoran"
        "cafe" -> "Kafe"
        "bank" -> "Bank"
        "atm" -> "Bankomat"
        "police" -> "Militsiya bo'limi"
        "marketplace" -> "Bozor"
        "place_of_worship" -> "Diniy obyekt"
        "bus_station" -> "Avtovokzal"
        "parking" -> "Avtoturargoh"
        "car_wash" -> "Avtomoyka"
        "fire_station" -> "Yong'in bo'limi"
        "post_office" -> "Pochta"
        else -> "Jamoat obyekti"
    }

    private fun placeName(value: String): String = when (value) {
        "city" -> "Shahar"
        "town" -> "Shaharcha"
        "village" -> "Qishloq"
        "suburb", "neighbourhood", "quarter" -> "Mahalla"
        "hamlet" -> "Ovul"
        else -> "Aholi punkti"
    }

    companion object {
        private const val TAG = "UzNav/Qidiruv"

        /** Qidiruvda ishlatiladigan tile darajasi (barcha obyektlar ko'rinadi). */
        private const val ZOOM: Byte = 17
        private const val TILE_SIZE = 256

        /** Bir marta o'qiladigan kataklar soni (8x8 ≈ 1,8 km). */
        private const val CHUNK = 8

        /** Markazdan necha halqa uzoqlikkacha qidiriladi (~1,8 km x halqa). */
        private const val MAX_RINGS = 6

        /** Qidiruvga ajratilgan vaqt (ms) — interfeys qotib qolmasligi uchun. */
        private const val TIME_BUDGET_MS = 3500L

        private const val HOUSE_MAX_DISTANCE_M = 150.0
        private const val MAX_STREET_VERTICES = 4000
    }
}
