package uz.uznav.navigator.util

import uz.uznav.navigator.model.GeoPoint
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Geografik hisob-kitoblar: masofa, yo'nalish (bearing), nuqtani kesmaga proyeksiyalash.
 * Barcha funksiyalar internetsiz, faqat matematik hisob asosida ishlaydi.
 */
object GeoUtils {

    /** Yer radiusi (metr). */
    const val EARTH_RADIUS_M = 6371008.8

    /** Ikki nuqta orasidagi masofa (metr), haversine formulasi. */
    fun distance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2) * sin(dLon / 2)
        return 2 * EARTH_RADIUS_M * asin(min(1.0, sqrt(a)))
    }

    fun distance(a: GeoPoint, b: GeoPoint): Double = distance(a.lat, a.lon, b.lat, b.lon)

    /**
     * Tez (taxminiy) masofa — kichik masofalar uchun ekvirektangulyar yaqinlashtirish.
     * Marshrut hisoblashda millionlab marta chaqirilgani uchun tezligi muhim.
     */
    fun fastDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val meanLat = Math.toRadians((lat1 + lat2) * 0.5)
        val x = Math.toRadians(lon2 - lon1) * cos(meanLat)
        val y = Math.toRadians(lat2 - lat1)
        return EARTH_RADIUS_M * sqrt(x * x + y * y)
    }

    /** A nuqtadan B nuqtaga yo'nalish burchagi (gradus, 0 = shimol). */
    fun bearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val dLon = Math.toRadians(lon2 - lon1)
        val y = sin(dLon) * cos(phi2)
        val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(dLon)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    fun bearing(a: GeoPoint, b: GeoPoint): Double = bearing(a.lat, a.lon, b.lat, b.lon)

    /** Burchakni -180..180 oralig'iga keltiradi. */
    fun normalizeAngle(angle: Double): Double {
        var a = angle % 360.0
        if (a > 180.0) a -= 360.0
        if (a < -180.0) a += 360.0
        return a
    }

    /** Ikki yo'nalish orasidagi eng kichik farq (0..180). */
    fun angleDifference(a: Double, b: Double): Double = abs(normalizeAngle(a - b))

    /** Proyeksiya natijasi: kesmadagi eng yaqin nuqta va unga masofa. */
    data class Projection(
        val lat: Double,
        val lon: Double,
        /** Nuqtadan kesmaga bo'lgan masofa (metr). */
        val distanceM: Double,
        /** Kesma bo'ylab nisbiy joylashuv: 0.0 = boshi, 1.0 = oxiri. */
        val t: Double
    )

    /**
     * [plat]/[plon] nuqtasini (lat1,lon1)-(lat2,lon2) kesmasiga proyeksiyalaydi.
     * Marshrutga "yopishtirish" (map matching) uchun ishlatiladi.
     */
    fun projectOnSegment(
        plat: Double, plon: Double,
        lat1: Double, lon1: Double,
        lat2: Double, lon2: Double
    ): Projection {
        // Kichik masofalarda tekis koordinatalarga o'tamiz (metrlarda)
        val latScale = 111320.0
        val lonScale = 111320.0 * cos(Math.toRadians((lat1 + lat2) * 0.5))

        val ax = lon1 * lonScale
        val ay = lat1 * latScale
        val bx = lon2 * lonScale
        val by = lat2 * latScale
        val px = plon * lonScale
        val py = plat * latScale

        val dx = bx - ax
        val dy = by - ay
        val segLenSq = dx * dx + dy * dy
        val t = if (segLenSq <= 1e-9) 0.0 else {
            max(0.0, min(1.0, ((px - ax) * dx + (py - ay) * dy) / segLenSq))
        }
        val projX = ax + t * dx
        val projY = ay + t * dy
        val dist = sqrt((px - projX) * (px - projX) + (py - projY) * (py - projY))
        val projLat = projY / latScale
        val projLon = if (lonScale == 0.0) plon else projX / lonScale
        return Projection(projLat, projLon, dist, t)
    }

    /** Berilgan nuqtadan [bearingDeg] yo'nalishda [distanceM] metr uzoqlikdagi nuqta. */
    fun destination(lat: Double, lon: Double, bearingDeg: Double, distanceM: Double): GeoPoint {
        val delta = distanceM / EARTH_RADIUS_M
        val theta = Math.toRadians(bearingDeg)
        val phi1 = Math.toRadians(lat)
        val lambda1 = Math.toRadians(lon)
        val sinPhi2 = sin(phi1) * cos(delta) + cos(phi1) * sin(delta) * cos(theta)
        val phi2 = asin(min(1.0, max(-1.0, sinPhi2)))
        val lambda2 = lambda1 + atan2(
            sin(theta) * sin(delta) * cos(phi1),
            cos(delta) - sin(phi1) * sinPhi2
        )
        return GeoPoint(Math.toDegrees(phi2), Math.toDegrees(lambda2))
    }

    /** Metrdagi masofani kenglik (latitude) gradusiga aylantiradi. */
    fun metersToLatDegrees(meters: Double): Double = meters / 110574.0

    /** Metrdagi masofani uzunlik (longitude) gradusiga aylantiradi. */
    fun metersToLonDegrees(meters: Double, atLatitude: Double): Double {
        val scale = 111320.0 * cos(Math.toRadians(atLatitude))
        return if (abs(scale) < 1.0) 0.01 else meters / scale
    }
}
