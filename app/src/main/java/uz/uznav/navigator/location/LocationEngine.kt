package uz.uznav.navigator.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import uz.uznav.navigator.model.GeoPoint

/**
 * GPS joylashuvini kuzatish.
 *
 * Google Play xizmatlariga bog'liq emas — faqat Android tizimining
 * [LocationManager] sinfidan foydalaniladi. Shu sababli ilova
 * Google xizmatlari o'rnatilmagan telefonlarda ham, internetsiz ham ishlaydi.
 * Joylashuv ma'lumoti hech qayerga yuborilmaydi (maxfiylik).
 */
class LocationEngine private constructor(private val context: Context) {

    /** Bitta GPS o'lchovi. */
    data class Fix(
        val point: GeoPoint,
        /** Tezlik, m/s. */
        val speedMps: Float,
        /** Harakat yo'nalishi (gradus) yoki null. */
        val bearing: Float?,
        /** Aniqlik (metr). */
        val accuracyM: Float,
        val timeMs: Long,
        val provider: String
    )

    /** GPS holati. */
    enum class GpsStatus {
        /** Joylashuv xizmati o'chirilgan. */
        OCHIRILGAN,

        /** Signal qidirilmoqda. */
        QIDIRILMOQDA,

        /** Signal bor. */
        YAXSHI
    }

    private val manager: LocationManager? =
        context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    private val _location = MutableStateFlow<Fix?>(null)
    val location: StateFlow<Fix?> = _location.asStateFlow()

    private val _status = MutableStateFlow(GpsStatus.QIDIRILMOQDA)
    val status: StateFlow<GpsStatus> = _status.asStateFlow()

    private var listening = false

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            handleLocation(location)
        }

        override fun onProviderEnabled(provider: String) {
            _status.value = GpsStatus.QIDIRILMOQDA
        }

        override fun onProviderDisabled(provider: String) {
            if (!isLocationEnabled()) _status.value = GpsStatus.OCHIRILGAN
        }

        @Deprecated("Android 10 dan boshqa ishlatilmaydi, lekin eski qurilmalar uchun kerak")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {
            // Eski Android versiyalari uchun bo'sh qoldiriladi
        }
    }

    /** Joylashuv ruxsati berilganmi? */
    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED

    /** Telefonda joylashuv xizmati yoqilganmi? */
    fun isLocationEnabled(): Boolean {
        val m = manager ?: return false
        return try {
            m.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                    m.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        } catch (e: Exception) {
            false
        }
    }

    /** Joylashuvni kuzatishni boshlaydi (ruxsat bo'lmasa — hech narsa qilmaydi). */
    @Synchronized
    fun start() {
        if (listening) return
        val m = manager ?: return
        if (!hasPermission()) {
            Log.w(TAG, "Joylashuv ruxsati yo'q")
            return
        }
        if (!isLocationEnabled()) {
            _status.value = GpsStatus.OCHIRILGAN
        }
        try {
            // Oxirgi ma'lum joylashuvni darhol ko'rsatamiz
            lastKnownLocation()?.let { handleLocation(it) }

            if (m.allProviders.contains(LocationManager.GPS_PROVIDER)) {
                m.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER, MIN_INTERVAL_MS, MIN_DISTANCE_M,
                    listener, Looper.getMainLooper()
                )
            }
            if (m.allProviders.contains(LocationManager.NETWORK_PROVIDER)) {
                m.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER, NETWORK_INTERVAL_MS, MIN_DISTANCE_M,
                    listener, Looper.getMainLooper()
                )
            }
            listening = true
        } catch (e: SecurityException) {
            Log.e(TAG, "Joylashuvni kuzatishda ruxsat xatosi", e)
        } catch (e: Exception) {
            Log.e(TAG, "Joylashuvni kuzatishni boshlab bo'lmadi", e)
        }
    }

    /** Kuzatishni to'xtatadi (batareyani tejash uchun). */
    @Synchronized
    fun stop() {
        if (!listening) return
        try {
            manager?.removeUpdates(listener)
        } catch (e: Exception) {
            Log.w(TAG, "Kuzatishni to'xtatishda xato", e)
        }
        listening = false
    }

    /** Tizimda saqlangan oxirgi joylashuv (ilova ochilishida darhol ko'rsatish uchun). */
    fun lastKnownLocation(): Location? {
        val m = manager ?: return null
        if (!hasPermission()) return null
        return try {
            val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            providers.mapNotNull { provider ->
                if (m.allProviders.contains(provider)) m.getLastKnownLocation(provider) else null
            }.maxByOrNull { it.time }
        } catch (e: SecurityException) {
            null
        }
    }

    private fun handleLocation(location: Location) {
        // Juda eski yoki aniqligi past o'lchovlarni tashlab yuboramiz
        val age = System.currentTimeMillis() - location.time
        if (age > MAX_AGE_MS && _location.value != null) return
        if (location.hasAccuracy() && location.accuracy > MAX_ACCURACY_M && _location.value != null) return

        val bearing = if (location.hasBearing() && location.hasSpeed() && location.speed > 1.0f) {
            location.bearing
        } else {
            null
        }

        _location.value = Fix(
            point = GeoPoint(location.latitude, location.longitude),
            speedMps = if (location.hasSpeed()) location.speed else 0f,
            bearing = bearing,
            accuracyM = if (location.hasAccuracy()) location.accuracy else 50f,
            timeMs = location.time,
            provider = location.provider ?: "noma'lum"
        )
        _status.value = if (location.hasAccuracy() && location.accuracy <= GOOD_ACCURACY_M) {
            GpsStatus.YAXSHI
        } else {
            GpsStatus.QIDIRILMOQDA
        }
    }

    /** Sun'iy yo'ldoshlar soni (ko'rsatish uchun; eski qurilmalarda 0). */
    fun deviceSupportsGnss(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N

    companion object {
        private const val TAG = "UzNav/GPS"
        private const val MIN_INTERVAL_MS = 1000L
        private const val NETWORK_INTERVAL_MS = 5000L
        private const val MIN_DISTANCE_M = 0f
        private const val MAX_AGE_MS = 60_000L
        private const val MAX_ACCURACY_M = 100f
        private const val GOOD_ACCURACY_M = 30f

        @Volatile
        private var instance: LocationEngine? = null

        fun get(context: Context): LocationEngine =
            instance ?: synchronized(this) {
                instance ?: LocationEngine(context.applicationContext).also { instance = it }
            }
    }
}
