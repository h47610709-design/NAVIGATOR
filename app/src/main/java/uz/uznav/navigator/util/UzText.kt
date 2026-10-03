package uz.uznav.navigator.util

/**
 * O'zbek tilidagi matnlarni qidiruv uchun normallashtirish vositalari.
 *
 * O'zbekistondagi OpenStreetMap ma'lumotlarida nomlar lotin, kirill va
 * ba'zan rus tilida yozilgan bo'lishi mumkin. Shuningdek "oʻ", "o'", "o`"
 * kabi turli apostrof belgilari ishlatiladi. Qidiruv ishonchli bo'lishi uchun
 * barcha matnlar bitta "sodda" ko'rinishga keltiriladi.
 */
object UzText {

    /** Kirill harflarini lotinchaga o'girish jadvali (o'zbek va rus harflari). */
    private val CYRILLIC_TO_LATIN: Map<Char, String> = mapOf(
        'а' to "a", 'б' to "b", 'в' to "v", 'г' to "g", 'д' to "d", 'е' to "e",
        'ё' to "yo", 'ж' to "j", 'з' to "z", 'и' to "i", 'й' to "y", 'к' to "k",
        'л' to "l", 'м' to "m", 'н' to "n", 'о' to "o", 'п' to "p", 'р' to "r",
        'с' to "s", 'т' to "t", 'у' to "u", 'ф' to "f", 'х' to "x", 'ц' to "ts",
        'ч' to "ch", 'ш' to "sh", 'щ' to "sh", 'ъ' to "", 'ы' to "i", 'ь' to "",
        'э' to "e", 'ю' to "yu", 'я' to "ya",
        'ў' to "o", 'қ' to "q", 'ғ' to "g", 'ҳ' to "h"
    )

    /** Turli apostrof belgilari — hammasi bitta belgiga keltiriladi. */
    private const val APOSTROPHES = "'\u2018\u2019\u02BB\u02BC\u0060\u00B4\u2032"

    /**
     * Matnni qidiruv uchun normallashtiradi:
     * kichik harf, kirill -> lotin, apostroflarni olib tashlash,
     * ortiqcha tinish belgilari va bo'sh joylarni tozalash.
     */
    fun normalize(input: String?): String {
        if (input.isNullOrBlank()) return ""
        val lower = input.lowercase()
        val sb = StringBuilder(lower.length)
        for (ch in lower) {
            when {
                APOSTROPHES.indexOf(ch) >= 0 -> {
                    // apostrof butunlay tashlab yuboriladi: "o'zbekiston" -> "ozbekiston"
                }
                CYRILLIC_TO_LATIN.containsKey(ch) -> sb.append(CYRILLIC_TO_LATIN[ch])
                ch.isLetterOrDigit() -> sb.append(ch)
                else -> sb.append(' ')
            }
        }
        return sb.toString().trim().replace(Regex("\\s+"), " ")
    }

    /**
     * [query] so'rovi [target] nom ichida qanchalik mos kelishini baholaydi.
     * Natija 0.0 (mos emas) dan 1.0 (to'liq mos) gacha.
     */
    fun matchScore(query: String, target: String?): Double {
        val q = normalize(query)
        val t = normalize(target)
        if (q.isEmpty() || t.isEmpty()) return 0.0
        if (t == q) return 1.0
        if (t.startsWith(q)) return 0.9
        if (t.contains(q)) return 0.75

        // So'zma-so'z tekshirish: "amir temur" -> "amir temur shoh ko'chasi"
        val queryWords = q.split(' ').filter { it.length > 1 }
        if (queryWords.isEmpty()) return 0.0
        var matched = 0
        for (word in queryWords) {
            if (t.contains(word)) matched++
        }
        val ratio = matched.toDouble() / queryWords.size
        return if (ratio >= 0.5) 0.4 + 0.3 * ratio else 0.0
    }

    /** So'rovdan uy raqamini ajratib oladi, masalan "Amir Temur 108" -> "108". */
    fun extractHouseNumber(query: String): String? {
        val match = Regex("(?:^|\\s)(\\d{1,4}[a-zA-Zа-яА-Я]?)(?:\\s|$)").find(query.trim())
        return match?.groupValues?.get(1)
    }

    /** So'rovni vergul bo'yicha qismlarga ajratadi: "Ko'cha 5, Toshkent". */
    fun splitParts(query: String): List<String> =
        query.split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }
}
