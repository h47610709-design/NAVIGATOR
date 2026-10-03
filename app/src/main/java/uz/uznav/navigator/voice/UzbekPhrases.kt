package uz.uznav.navigator.voice

import uz.uznav.navigator.model.ManeuverType
import uz.uznav.navigator.model.RouteStep
import uz.uznav.navigator.util.UzFormat

/**
 * Barcha navigatsiya matnlari — o'zbek tilida.
 *
 * Bu yerda ham ekranda ko'rsatiladigan, ham ovoz bilan aytiladigan
 * jumlalar tayyorlanadi. Ovoz uchun raqamlar so'z bilan yoziladi
 * (masalan "uch yuz metr"), chunki ko'pchilik ovoz sintezatorlari
 * raqamlarni o'zbekcha o'qiy olmaydi.
 */
object UzbekPhrases {

    /** Burilishning ekrandagi qisqa nomi. */
    fun maneuverTitle(step: RouteStep): String = when (step.maneuver) {
        ManeuverType.YOLGA_CHIQISH -> "Yo'lga chiqing"
        ManeuverType.TOGRIGA -> "To'g'riga davom eting"
        ManeuverType.CHAPGA -> "Chapga buriling"
        ManeuverType.ONGGA -> "O'ngga buriling"
        ManeuverType.BIROZ_CHAPGA -> "Biroz chapga oling"
        ManeuverType.BIROZ_ONGGA -> "Biroz o'ngga oling"
        ManeuverType.KESKIN_CHAPGA -> "Keskin chapga buriling"
        ManeuverType.KESKIN_ONGGA -> "Keskin o'ngga buriling"
        ManeuverType.ORQAGA_QAYTISH -> "Orqaga qayting"
        ManeuverType.AYLANMA -> "Aylanma yo'l: ${ordinal(step.roundaboutExit)} chiqish"
        ManeuverType.MANZIL -> "Manzilga yetib keldingiz"
    }

    /** Burilishdan keyin chiqiladigan yo'l nomi (bo'lsa). */
    fun roadLine(step: RouteStep): String =
        step.roadName?.takeIf { it.isNotBlank() } ?: ""

    /**
     * Ovoz uchun to'liq jumla.
     *
     * @param distanceM burilishgacha qolgan masofa; 0 yoki undan kichik bo'lsa
     *        "hoziroq" ko'rsatmasi aytiladi.
     */
    fun spokenInstruction(step: RouteStep, distanceM: Double, withRoadName: Boolean = true): String {
        val action = spokenAction(step)
        val road = if (withRoadName) step.roadName?.takeIf { it.isNotBlank() } else null

        val sentence = if (distanceM >= 60) {
            "${UzFormat.spokenDistance(distanceM)}dan keyin $action"
        } else {
            action
        }
        return if (road != null && step.maneuver != ManeuverType.MANZIL) {
            "$sentence. $road."
        } else {
            "$sentence."
        }
    }

    /** Harakatning ovozdagi ko'rinishi. */
    private fun spokenAction(step: RouteStep): String = when (step.maneuver) {
        ManeuverType.YOLGA_CHIQISH -> "yo'lga chiqing"
        ManeuverType.TOGRIGA -> "to'g'riga davom eting"
        ManeuverType.CHAPGA -> "chapga buriling"
        ManeuverType.ONGGA -> "o'ngga buriling"
        ManeuverType.BIROZ_CHAPGA -> "biroz chapga oling"
        ManeuverType.BIROZ_ONGGA -> "biroz o'ngga oling"
        ManeuverType.KESKIN_CHAPGA -> "keskin chapga buriling"
        ManeuverType.KESKIN_ONGGA -> "keskin o'ngga buriling"
        ManeuverType.ORQAGA_QAYTISH -> "orqaga qayting"
        ManeuverType.AYLANMA -> "aylanma yo'ldan ${ordinal(step.roundaboutExit)} chiqishga chiqing"
        ManeuverType.MANZIL -> "manzilingizga yetib keldingiz"
    }

    /** Tartib son: 1 -> "birinchi", 2 -> "ikkinchi" ... */
    fun ordinal(number: Int): String = when (number) {
        1 -> "birinchi"
        2 -> "ikkinchi"
        3 -> "uchinchi"
        4 -> "to'rtinchi"
        5 -> "beshinchi"
        6 -> "oltinchi"
        7 -> "yettinchi"
        else -> "${UzFormat.numberToUzbek(number.coerceAtLeast(1))}inchi"
    }

    // --- Doimiy jumlalar ---

    const val MARSHRUT_BOSHLANDI = "Marshrut boshlandi. Ehtiyot bo'lib haydang."
    const val MARSHRUTDAN_CHIQDINGIZ = "Marshrutdan chiqib ketdingiz. Yangi marshrut hisoblanmoqda."
    const val QAYTA_HISOBLANDI = "Yangi marshrut tayyor."
    const val MANZILGA_YETDINGIZ = "Manzilingizga yetib keldingiz."
    const val GPS_YOQOLDI = "GPS signali yo'qoldi."
    const val GPS_TOPILDI = "GPS signali tiklandi."
    const val MARSHRUT_TOXTATILDI = "Marshrut to'xtatildi."

    /** Tezlik cheklovidan oshganda ogohlantirish. */
    fun speedWarning(limitKmh: Int): String =
        "Diqqat. Tezlik cheklovi ${UzFormat.numberToUzbek(limitKmh)} kilometr."
}
