package uz.uznav.navigator.util

import java.util.Calendar
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Masofa, vaqt va tezlikni o'zbek tilida chiroyli ko'rinishda formatlash.
 * Hamma matn — faqat o'zbekcha.
 */
object UzFormat {

    private val UZ_LOCALE = Locale("uz", "UZ")

    /** Masofani ekranda ko'rsatish uchun: "350 m", "1,2 km", "14 km". */
    fun distance(meters: Double): String {
        if (meters.isNaN() || meters < 0) return "— m"
        return when {
            meters < 1000 -> "${roundDistanceMeters(meters)} m"
            meters < 10000 -> String.format(UZ_LOCALE, "%.1f km", meters / 1000.0)
            else -> "${(meters / 1000.0).roundToInt()} km"
        }
    }

    /** Masofani 10 metrgacha yaxlitlaydi (navigatsiyada silliq ko'rinishi uchun). */
    private fun roundDistanceMeters(meters: Double): Int {
        val m = meters.roundToInt()
        return when {
            m < 20 -> m
            m < 100 -> (m / 10) * 10
            else -> (m / 50) * 50
        }
    }

    /** Davomiylik: "45 daqiqa", "1 soat 20 daqiqa", "2 kun 3 soat". */
    fun duration(seconds: Double): String {
        if (seconds.isNaN() || seconds < 0) return "—"
        val totalMinutes = (seconds / 60.0).roundToLong()
        if (totalMinutes < 1) return "1 daqiqadan kam"
        val days = totalMinutes / (60 * 24)
        val hours = (totalMinutes % (60 * 24)) / 60
        val minutes = totalMinutes % 60
        return buildString {
            if (days > 0) append("$days kun ")
            if (hours > 0) append("$hours soat ")
            if (minutes > 0 || (days == 0L && hours == 0L)) append("$minutes daqiqa")
        }.trim()
    }

    /** Yetib borish vaqti: hozirgi vaqtga [seconds] qo'shilib, "14:35" ko'rinishida qaytariladi. */
    fun eta(seconds: Double): String {
        if (seconds.isNaN() || seconds < 0) return "--:--"
        val cal = Calendar.getInstance()
        cal.add(Calendar.SECOND, seconds.roundToInt())
        return String.format(
            UZ_LOCALE, "%02d:%02d",
            cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE)
        )
    }

    /** Tezlik: km/soat butun son ko'rinishida. */
    fun speedKmh(metersPerSecond: Float): Int {
        if (metersPerSecond.isNaN() || metersPerSecond < 0) return 0
        return (metersPerSecond * 3.6f).roundToInt()
    }

    /** Fayl hajmi: "132 MB", "1,4 GB". */
    fun fileSize(bytes: Long): String {
        if (bytes <= 0) return "0 MB"
        val mb = bytes / (1024.0 * 1024.0)
        return if (mb >= 1024) String.format(UZ_LOCALE, "%.1f GB", mb / 1024.0)
        else String.format(UZ_LOCALE, "%.0f MB", mb)
    }

    /**
     * Ovozli yo'riqnoma uchun masofani o'zbekcha so'z bilan aytish.
     * Masalan: 300 -> "uch yuz metrdan keyin", 1500 -> "bir yarim kilometrdan keyin".
     * Raqamlar so'z bilan aytilgani uchun ovoz sintezatori ularni to'g'ri o'qiydi.
     */
    fun spokenDistance(meters: Double): String {
        val m = meters.roundToInt()
        return when {
            m >= 1500 -> "${numberToUzbek(((m / 1000.0).roundToInt()))} kilometr"
            m >= 1200 -> "bir yarim kilometr"
            m >= 900 -> "bir kilometr"
            m >= 100 -> "${numberToUzbek((m / 100) * 100)} metr"
            m >= 50 -> "ellik metr"
            else -> "${numberToUzbek((m / 10) * 10)} metr"
        }
    }

    private val ONES = arrayOf(
        "nol", "bir", "ikki", "uch", "to'rt", "besh",
        "olti", "yetti", "sakkiz", "to'qqiz"
    )
    private val TENS = arrayOf(
        "", "o'n", "yigirma", "o'ttiz", "qirq", "ellik",
        "oltmish", "yetmish", "sakson", "to'qson"
    )

    /** Sonni o'zbekcha so'zga aylantiradi (0..999 999 oralig'i navigatsiya uchun yetarli). */
    fun numberToUzbek(number: Int): String {
        if (number < 0) return "minus ${numberToUzbek(-number)}"
        if (number < 10) return ONES[number]
        if (number < 100) {
            val t = TENS[number / 10]
            val o = number % 10
            return if (o == 0) t else "$t ${ONES[o]}"
        }
        if (number < 1000) {
            val h = number / 100
            val rest = number % 100
            val head = if (h == 1) "yuz" else "${ONES[h]} yuz"
            return if (rest == 0) head else "$head ${numberToUzbek(rest)}"
        }
        if (number < 1_000_000) {
            val th = number / 1000
            val rest = number % 1000
            val head = if (th == 1) "ming" else "${numberToUzbek(th)} ming"
            return if (rest == 0) head else "$head ${numberToUzbek(rest)}"
        }
        return number.toString()
    }
}
