package uz.uznav.navigator.ui

import android.content.Intent
import uz.uznav.navigator.model.GeoPoint
import uz.uznav.navigator.model.Place
import uz.uznav.navigator.model.PlaceKind

/**
 * Tanlangan joyni ekranlar o'rtasida uzatish uchun yordamchi.
 * (Qidiruv, sevimlilar va tarix ekranlari natijani shu ko'rinishda qaytaradi.)
 */
object PlaceIntent {

    private const val EXTRA_NAME = "uznav.joy.nomi"
    private const val EXTRA_ADDRESS = "uznav.joy.manzil"
    private const val EXTRA_LAT = "uznav.joy.kenglik"
    private const val EXTRA_LON = "uznav.joy.uzunlik"
    private const val EXTRA_KIND = "uznav.joy.turi"

    /** Joyni Intent ichiga joylaydi. */
    fun put(intent: Intent, place: Place): Intent = intent.apply {
        putExtra(EXTRA_NAME, place.name)
        putExtra(EXTRA_ADDRESS, place.address)
        putExtra(EXTRA_LAT, place.point.lat)
        putExtra(EXTRA_LON, place.point.lon)
        putExtra(EXTRA_KIND, place.kind.name)
    }

    /** Intent ichidan joyni o'qiydi (bo'lmasa null). */
    fun read(intent: Intent?): Place? {
        if (intent == null) return null
        if (!intent.hasExtra(EXTRA_LAT) || !intent.hasExtra(EXTRA_LON)) return null
        val lat = intent.getDoubleExtra(EXTRA_LAT, Double.NaN)
        val lon = intent.getDoubleExtra(EXTRA_LON, Double.NaN)
        if (lat.isNaN() || lon.isNaN()) return null
        return Place(
            name = intent.getStringExtra(EXTRA_NAME) ?: "Tanlangan nuqta",
            address = intent.getStringExtra(EXTRA_ADDRESS) ?: "",
            point = GeoPoint(lat, lon),
            kind = runCatching {
                PlaceKind.valueOf(intent.getStringExtra(EXTRA_KIND) ?: PlaceKind.OBYEKT.name)
            }.getOrDefault(PlaceKind.OBYEKT)
        )
    }
}
