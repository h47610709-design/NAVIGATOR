package uz.uznav.navigator.model

import uz.uznav.navigator.util.GeoUtils

/** Marshrut varianti (foydalanuvchi tanlaydi). */
enum class RouteProfile(val titleUz: String, val descriptionUz: String) {
    ENG_TEZ("Eng tez", "Vaqt bo'yicha eng qisqa yo'l"),
    ENG_QISQA("Eng qisqa", "Masofa bo'yicha eng qisqa yo'l"),
    PULLIK_YOQ("Pullik yo'lsiz", "Pullik yo'llardan qochadi");

    /** Pullik yo'llardan qochish kerakmi? */
    val avoidToll: Boolean get() = this == PULLIK_YOQ

    /** Vaqt bo'yicha optimallashtirish (aks holda masofa bo'yicha). */
    val optimizeTime: Boolean get() = this != ENG_QISQA
}

/** Burilish turi. */
enum class ManeuverType {
    YOLGA_CHIQISH,       // marshrut boshlanishi
    TOGRIGA,             // to'g'riga davom etish
    CHAPGA,              // chapga burilish
    ONGGA,               // o'ngga burilish
    BIROZ_CHAPGA,        // biroz chapga
    BIROZ_ONGGA,         // biroz o'ngga
    KESKIN_CHAPGA,       // keskin chapga
    KESKIN_ONGGA,        // keskin o'ngga
    ORQAGA_QAYTISH,      // 180 daraja burilish
    AYLANMA,             // aylanma yo'l (roundabout)
    MANZIL               // manzilga yetib kelish
}

/**
 * Marshrutning bitta bosqichi (bitta burilishdan keyingisigacha).
 *
 * @param pointIndex [Route.points] ro'yxatidagi burilish nuqtasi indeksi
 * @param distanceM shu bosqichning uzunligi (metr)
 * @param durationS shu bosqichni bosib o'tish vaqti (soniya)
 * @param roundaboutExit aylanma yo'ldan nechanchi chiqish (0 — aylanma emas)
 */
data class RouteStep(
    val maneuver: ManeuverType,
    val pointIndex: Int,
    val point: GeoPoint,
    val roadName: String?,
    val distanceM: Double,
    val durationS: Double,
    val roundaboutExit: Int = 0
)

/**
 * Hisoblangan marshrut: geometriya, bosqichlar, umumiy masofa va vaqt.
 *
 * @param points marshrut chizig'ining barcha nuqtalari
 * @param speedLimits har bir segment uchun tezlik cheklovi (km/soat), 0 — noma'lum
 */
class Route(
    val points: List<GeoPoint>,
    val steps: List<RouteStep>,
    val distanceM: Double,
    val durationS: Double,
    val profile: RouteProfile,
    val speedLimits: IntArray,
    val destination: Place?
) {
    /** Har bir nuqtaga qadar bosib o'tilgan masofa (metr). Progress hisoblash uchun. */
    val cumulativeDistance: DoubleArray = DoubleArray(points.size)

    init {
        for (i in 1 until points.size) {
            cumulativeDistance[i] = cumulativeDistance[i - 1] +
                    GeoUtils.distance(points[i - 1], points[i])
        }
    }

    /** Marshrut bo'ylab berilgan nuqtadan boshlab qolgan masofa. */
    fun remainingDistanceFrom(pointIndex: Int, distanceIntoSegment: Double): Double {
        if (points.isEmpty()) return 0.0
        val idx = pointIndex.coerceIn(0, points.size - 1)
        val passed = cumulativeDistance[idx] + distanceIntoSegment
        return (cumulativeDistance.last() - passed).coerceAtLeast(0.0)
    }

    /** Qolgan masofaga mos ravishda qolgan vaqtni baholaydi. */
    fun remainingDurationFor(remainingM: Double): Double {
        if (distanceM <= 0) return 0.0
        return durationS * (remainingM / distanceM)
    }

    /** Berilgan nuqta indeksidagi tezlik cheklovi (km/soat) yoki 0. */
    fun speedLimitAt(pointIndex: Int): Int {
        if (speedLimits.isEmpty()) return 0
        val idx = pointIndex.coerceIn(0, speedLimits.size - 1)
        return speedLimits[idx]
    }
}
