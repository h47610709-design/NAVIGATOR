package uz.uznav.navigator.model

import org.mapsforge.core.model.LatLong

/**
 * Oddiy geografik nuqta (kenglik/uzunlik).
 * Mapsforge kutubxonasining [LatLong] sinfidan mustaqil — ilova ichida shu model ishlatiladi.
 */
data class GeoPoint(
    val lat: Double,
    val lon: Double
) {
    fun toLatLong(): LatLong = LatLong(lat, lon)

    /** Mikro-gradusdagi (E6) kalit — nuqtalarni taqqoslash uchun. */
    fun key(): Long = (Math.round(lat * 1e6) shl 32) or (Math.round(lon * 1e6) and 0xFFFFFFFFL)

    companion object {
        fun from(latLong: LatLong): GeoPoint = GeoPoint(latLong.latitude, latLong.longitude)

        /** O'zbekiston markazi (standart xarita pozitsiyasi — Toshkent). */
        val TOSHKENT = GeoPoint(41.311081, 69.240562)
    }
}
