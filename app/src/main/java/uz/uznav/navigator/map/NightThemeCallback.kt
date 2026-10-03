package uz.uznav.navigator.map

import org.mapsforge.core.graphics.Bitmap
import org.mapsforge.map.rendertheme.ThemeCallback
import org.mapsforge.map.rendertheme.renderinstruction.RenderInstruction
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Tungi rejim uchun rang o'zgartirgich.
 *
 * Xarita ranglarining "yorqinligi" teskarisiga aylantiriladi, lekin rang
 * tusi (hue) saqlanadi: oq fon qora bo'ladi, qora yozuvlar oqaradi,
 * yashil bog'lar esa yashilligicha qoladi — faqat to'qlashadi.
 * Natijada kechasi haydash paytida xarita ko'zni qamashtirmaydi.
 */
class NightThemeCallback : ThemeCallback {

    override fun getBitmap(bitmap: Bitmap): Bitmap = bitmap

    override fun getColor(origin: RenderInstruction?, color: Int): Int {
        val alpha = (color ushr 24) and 0xFF
        if (alpha == 0) return color

        val r = ((color shr 16) and 0xFF) / 255.0
        val g = ((color shr 8) and 0xFF) / 255.0
        val b = (color and 0xFF) / 255.0

        val maxValue = max(r, max(g, b))
        val minValue = min(r, min(g, b))
        val lightness = (maxValue + minValue) / 2.0

        // Yorqinlikni teskarisiga aylantiramiz va biroz qoraytiramiz
        var newLightness = 1.0 - lightness
        newLightness = (newLightness * 0.82).coerceIn(0.04, 0.92)

        val saturation = if (maxValue == minValue) 0.0 else {
            val delta = maxValue - minValue
            if (lightness > 0.5) delta / (2.0 - maxValue - minValue) else delta / (maxValue + minValue)
        }
        val hue = hueOf(r, g, b, maxValue, minValue)

        // Tungi rejimda ranglar biroz "yumshoq" bo'lsin
        val newSaturation = (saturation * 0.75).coerceIn(0.0, 1.0)
        val (nr, ng, nb) = hslToRgb(hue, newSaturation, newLightness)

        return (alpha shl 24) or (nr shl 16) or (ng shl 8) or nb
    }

    private fun hueOf(r: Double, g: Double, b: Double, maxValue: Double, minValue: Double): Double {
        if (abs(maxValue - minValue) < 1e-6) return 0.0
        val delta = maxValue - minValue
        val hue = when (maxValue) {
            r -> ((g - b) / delta) % 6.0
            g -> ((b - r) / delta) + 2.0
            else -> ((r - g) / delta) + 4.0
        }
        val degrees = hue * 60.0
        return if (degrees < 0) degrees + 360.0 else degrees
    }

    private fun hslToRgb(hue: Double, saturation: Double, lightness: Double): Triple<Int, Int, Int> {
        if (saturation <= 0.0) {
            val value = (lightness * 255).toInt().coerceIn(0, 255)
            return Triple(value, value, value)
        }
        val c = (1 - abs(2 * lightness - 1)) * saturation
        val x = c * (1 - abs((hue / 60.0) % 2 - 1))
        val m = lightness - c / 2
        val (r1, g1, b1) = when {
            hue < 60 -> Triple(c, x, 0.0)
            hue < 120 -> Triple(x, c, 0.0)
            hue < 180 -> Triple(0.0, c, x)
            hue < 240 -> Triple(0.0, x, c)
            hue < 300 -> Triple(x, 0.0, c)
            else -> Triple(c, 0.0, x)
        }
        return Triple(
            ((r1 + m) * 255).toInt().coerceIn(0, 255),
            ((g1 + m) * 255).toInt().coerceIn(0, 255),
            ((b1 + m) * 255).toInt().coerceIn(0, 255)
        )
    }
}
