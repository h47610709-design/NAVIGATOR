package uz.uznav.navigator.routing

import org.mapsforge.core.model.Tag

/**
 * OpenStreetMap teglarini avtomobil marshruti uchun tahlil qilish.
 *
 * Mapsforge `.map` fayllarida teglarning faqat bir qismi saqlanadi
 * (highway, oneway, access, motor_vehicle, name, ref, tunnel, bridge ...),
 * shuning uchun tezlik cheklovlari yo'l turiga qarab aniqlanadi.
 */
object RoadTags {

    /** Yo'l sinflari (avtomobil uchun). */
    const val CLASS_MOTORWAY: Byte = 1
    const val CLASS_TRUNK: Byte = 2
    const val CLASS_PRIMARY: Byte = 3
    const val CLASS_SECONDARY: Byte = 4
    const val CLASS_TERTIARY: Byte = 5
    const val CLASS_UNCLASSIFIED: Byte = 6
    const val CLASS_RESIDENTIAL: Byte = 7
    const val CLASS_SERVICE: Byte = 8
    const val CLASS_LIVING_STREET: Byte = 9
    const val CLASS_TRACK: Byte = 10
    const val CLASS_LINK: Byte = 11

    /**
     * Yo'l turi bo'yicha harakat tezligi (km/soat) — O'zbekiston sharoitiga moslangan.
     * Bu qiymatlar marshrut vaqtini hisoblashda ishlatiladi.
     */
    private val SPEEDS: Map<String, Int> = mapOf(
        "motorway" to 100,
        "motorway_link" to 60,
        "trunk" to 90,
        "trunk_link" to 50,
        "primary" to 70,
        "primary_link" to 45,
        "secondary" to 60,
        "secondary_link" to 40,
        "tertiary" to 55,
        "tertiary_link" to 35,
        "unclassified" to 40,
        "residential" to 30,
        "living_street" to 15,
        "service" to 20,
        "road" to 30,
        "track" to 15
    )

    /**
     * Yo'l turi bo'yicha odatdagi tezlik cheklovi (km/soat).
     * `maxspeed` tegi xarita faylida bo'lmasa, shu qiymat ko'rsatiladi.
     * 0 — cheklov noma'lum.
     */
    private val DEFAULT_LIMITS: Map<String, Int> = mapOf(
        "motorway" to 110,
        "motorway_link" to 70,
        "trunk" to 100,
        "trunk_link" to 60,
        "primary" to 70,
        "secondary" to 70,
        "tertiary" to 70,
        "unclassified" to 70,
        "residential" to 70,
        "living_street" to 20
    )

    /** Avtomobil yura olmaydigan yo'l turlari. */
    private val EXCLUDED = setOf(
        "footway", "path", "cycleway", "steps", "pedestrian", "bridleway",
        "construction", "proposed", "raceway", "corridor", "platform",
        "bus_guideway", "elevator", "escape", "via_ferrata"
    )

    /** Yo'l haqida marshrut uchun kerakli ma'lumotlar. */
    class RoadInfo(
        val name: String?,
        val ref: String?,
        val roadClass: Byte,
        val speedKmh: Int,
        val speedLimitKmh: Int,
        val onewayForward: Boolean,
        val onewayBackward: Boolean,
        val toll: Boolean,
        val roundabout: Boolean
    ) {
        /** Ekranda ko'rsatiladigan nom: nomi bo'lmasa yo'l raqami. */
        val displayName: String? get() = name ?: ref
    }

    /**
     * Teglar ro'yxatidan avtomobil yo'li ma'lumotini ajratib oladi.
     * Agar bu avtomobil yo'li bo'lmasa `null` qaytaradi.
     */
    fun parse(tags: List<Tag>): RoadInfo? {
        var highway: String? = null
        var name: String? = null
        var ref: String? = null
        var oneway: String? = null
        var access: String? = null
        var motorVehicle: String? = null
        var junction: String? = null
        var toll = false
        var maxspeed: String? = null
        var area = false

        for (tag in tags) {
            when (tag.key) {
                "highway" -> highway = tag.value
                "name" -> name = tag.value
                "ref" -> ref = tag.value
                "oneway" -> oneway = tag.value
                "access" -> access = tag.value
                "motor_vehicle", "motorcar", "vehicle" -> motorVehicle = tag.value
                "junction" -> junction = tag.value
                "toll" -> toll = tag.value == "yes" || tag.value == "true"
                "maxspeed" -> maxspeed = tag.value
                "area" -> area = tag.value == "yes"
            }
        }

        if (highway == null || area) return null
        if (highway in EXCLUDED) return null
        val baseSpeed = SPEEDS[highway] ?: return null

        // Kirish taqiqlari
        if (motorVehicle == "no" || motorVehicle == "private") return null
        if ((access == "no" || access == "private") && motorVehicle != "yes") return null

        val isRoundabout = junction == "roundabout" || junction == "circular"
        var forward = true
        var backward = true
        when {
            oneway == "yes" || oneway == "1" || oneway == "true" -> backward = false
            oneway == "-1" || oneway == "reverse" -> forward = false
            oneway == "no" || oneway == "0" || oneway == "false" -> { /* ikki tomonlama */ }
            isRoundabout -> backward = false // aylanma yo'llar bir tomonlama
        }

        val parsedMax = parseMaxSpeed(maxspeed)
        val limit = parsedMax ?: (DEFAULT_LIMITS[highway] ?: 0)
        // Haqiqiy harakat tezligi cheklovdan oshmasin
        val speed = if (parsedMax != null) minOf(baseSpeed, parsedMax) else baseSpeed

        val roadClass = when (highway) {
            "motorway" -> CLASS_MOTORWAY
            "trunk" -> CLASS_TRUNK
            "primary" -> CLASS_PRIMARY
            "secondary" -> CLASS_SECONDARY
            "tertiary" -> CLASS_TERTIARY
            "unclassified", "road" -> CLASS_UNCLASSIFIED
            "residential" -> CLASS_RESIDENTIAL
            "service" -> CLASS_SERVICE
            "living_street" -> CLASS_LIVING_STREET
            "track" -> CLASS_TRACK
            else -> CLASS_LINK
        }

        return RoadInfo(
            name = name?.takeIf { it.isNotBlank() },
            ref = ref?.takeIf { it.isNotBlank() },
            roadClass = roadClass,
            speedKmh = speed,
            speedLimitKmh = limit,
            onewayForward = forward,
            onewayBackward = backward,
            toll = toll,
            roundabout = isRoundabout
        )
    }

    /** `maxspeed` tegini km/soat ga aylantiradi ("60", "50 mph", "RU:urban"). */
    private fun parseMaxSpeed(value: String?): Int? {
        if (value.isNullOrBlank()) return null
        val v = value.trim().lowercase()
        val number = Regex("\\d+").find(v)?.value?.toIntOrNull() ?: return null
        return if (v.contains("mph")) (number * 1.609).toInt() else number
    }

    /**
     * Berilgan yo'l sinfi uzoq masofali marshrut uchun mos keladimi?
     * Uzoq yo'llarda faqat asosiy magistrallar hisobga olinadi — bu
     * telefonning xotirasini tejaydi va hisobni tezlashtiradi.
     */
    fun isMajor(roadClass: Byte): Boolean = roadClass <= CLASS_TERTIARY || roadClass == CLASS_LINK
}
