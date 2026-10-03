package uz.uznav.navigator.data

import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatDelegate
import uz.uznav.navigator.model.RouteProfile

/**
 * Ilova sozlamalari. Hammasi telefon ichida saqlanadi — hech qanday
 * akkaunt yoki serverga yuborish yo'q (maxfiylik talabi).
 */
class AppPrefs private constructor(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("uznav_sozlamalar", Context.MODE_PRIVATE)

    /** Mavzu: 0 — tizim bo'yicha, 1 — yorug', 2 — tungi. */
    var themeMode: Int
        get() = prefs.getInt(KEY_THEME, 0)
        set(value) = prefs.edit().putInt(KEY_THEME, value).apply()

    /** Ovozli yo'riqnoma yoqilganmi. */
    var voiceEnabled: Boolean
        get() = prefs.getBoolean(KEY_VOICE, true)
        set(value) = prefs.edit().putBoolean(KEY_VOICE, value).apply()

    /** Tezlik cheklovidan oshganda ogohlantirish. */
    var speedWarningEnabled: Boolean
        get() = prefs.getBoolean(KEY_SPEED_WARNING, true)
        set(value) = prefs.edit().putBoolean(KEY_SPEED_WARNING, value).apply()

    /** Navigatsiya paytida xarita harakat yo'nalishi bo'yicha burilsinmi. */
    var rotateMapWithBearing: Boolean
        get() = prefs.getBoolean(KEY_ROTATE_MAP, true)
        set(value) = prefs.edit().putBoolean(KEY_ROTATE_MAP, value).apply()

    /** Ekran navigatsiya paytida o'chmasin. */
    var keepScreenOn: Boolean
        get() = prefs.getBoolean(KEY_KEEP_SCREEN, true)
        set(value) = prefs.edit().putBoolean(KEY_KEEP_SCREEN, value).apply()

    /** Oxirgi tanlangan marshrut varianti. */
    var routeProfile: RouteProfile
        get() = runCatching {
            RouteProfile.valueOf(prefs.getString(KEY_PROFILE, RouteProfile.ENG_TEZ.name)!!)
        }.getOrDefault(RouteProfile.ENG_TEZ)
        set(value) = prefs.edit().putString(KEY_PROFILE, value.name).apply()

    /** Xaritani faqat Wi-Fi orqali yuklash. */
    var downloadOnWifiOnly: Boolean
        get() = prefs.getBoolean(KEY_WIFI_ONLY, true)
        set(value) = prefs.edit().putBoolean(KEY_WIFI_ONLY, value).apply()

    /** Oxirgi xarita markazi (ilova qayta ochilganda tiklanadi). */
    var lastLatitude: Double
        get() = java.lang.Double.longBitsToDouble(prefs.getLong(KEY_LAST_LAT, java.lang.Double.doubleToRawLongBits(41.311081)))
        set(value) = prefs.edit().putLong(KEY_LAST_LAT, java.lang.Double.doubleToRawLongBits(value)).apply()

    var lastLongitude: Double
        get() = java.lang.Double.longBitsToDouble(prefs.getLong(KEY_LAST_LON, java.lang.Double.doubleToRawLongBits(69.240562)))
        set(value) = prefs.edit().putLong(KEY_LAST_LON, java.lang.Double.doubleToRawLongBits(value)).apply()

    var lastZoom: Int
        get() = prefs.getInt(KEY_LAST_ZOOM, 14)
        set(value) = prefs.edit().putInt(KEY_LAST_ZOOM, value).apply()

    /** Tanlangan mavzuni (yorug'/tungi) butun ilovaga qo'llaydi. */
    fun applyTheme() {
        val mode = when (themeMode) {
            1 -> AppCompatDelegate.MODE_NIGHT_NO
            2 -> AppCompatDelegate.MODE_NIGHT_YES
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        AppCompatDelegate.setDefaultNightMode(mode)
    }

    companion object {
        private const val KEY_THEME = "mavzu"
        private const val KEY_VOICE = "ovoz"
        private const val KEY_SPEED_WARNING = "tezlik_ogohlantirish"
        private const val KEY_ROTATE_MAP = "xarita_burilishi"
        private const val KEY_KEEP_SCREEN = "ekran_yoniq"
        private const val KEY_PROFILE = "marshrut_turi"
        private const val KEY_WIFI_ONLY = "faqat_wifi"
        private const val KEY_LAST_LAT = "oxirgi_kenglik"
        private const val KEY_LAST_LON = "oxirgi_uzunlik"
        private const val KEY_LAST_ZOOM = "oxirgi_masshtab"

        @Volatile
        private var instance: AppPrefs? = null

        fun get(context: Context): AppPrefs =
            instance ?: synchronized(this) {
                instance ?: AppPrefs(context).also { instance = it }
            }
    }
}
