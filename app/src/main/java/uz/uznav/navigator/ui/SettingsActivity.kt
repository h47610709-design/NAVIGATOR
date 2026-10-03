package uz.uznav.navigator.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import uz.uznav.navigator.UzNavApp
import uz.uznav.navigator.databinding.ActivitySettingsBinding

/**
 * Sozlamalar ekrani — barcha matnlar o'zbek tilida.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val app = UzNavApp.from(this)
        val prefs = app.prefs

        binding.orqagaTugma.setOnClickListener { finish() }

        // --- Mavzu ---
        when (prefs.themeMode) {
            1 -> binding.mavzuYorug.isChecked = true
            2 -> binding.mavzuTungi.isChecked = true
            else -> binding.mavzuTizim.isChecked = true
        }
        binding.mavzuGuruhi.setOnCheckedChangeListener { _, checkedId ->
            prefs.themeMode = when (checkedId) {
                binding.mavzuYorug.id -> 1
                binding.mavzuTungi.id -> 2
                else -> 0
            }
            prefs.applyTheme()
        }

        // --- Ovoz ---
        binding.ovozKalit.isChecked = prefs.voiceEnabled
        binding.ovozKalit.setOnCheckedChangeListener { _, checked ->
            prefs.voiceEnabled = checked
            app.voice.enabled = checked
            if (checked) {
                app.voice.speak("Ovozli yo'riqnoma yoqildi", flush = true)
            }
        }
        binding.ovozHolati.text = app.voice.stateMessage()
        binding.ovozSinovTugma.setOnClickListener {
            app.voice.enabled = true
            app.voice.speak("Uch yuz metrdan keyin o'ngga buriling.", flush = true)
            app.voice.enabled = prefs.voiceEnabled
            binding.ovozHolati.text = app.voice.stateMessage()
        }
        binding.ovozSozlamalariTugma.setOnClickListener {
            // Tizimning "Matndan nutqqa" sozlamalarini ochamiz (o'zbekcha ovoz paketi uchun)
            runCatching {
                startActivity(Intent("com.android.settings.TTS_SETTINGS"))
            }.onFailure {
                android.widget.Toast.makeText(
                    this,
                    "Tizim sozlamalarini ochib bo'lmadi",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        }

        // --- Navigatsiya ---
        binding.tezlikOgohlantirishKalit.isChecked = prefs.speedWarningEnabled
        binding.tezlikOgohlantirishKalit.setOnCheckedChangeListener { _, checked ->
            prefs.speedWarningEnabled = checked
        }

        binding.xaritaBurilishiKalit.isChecked = prefs.rotateMapWithBearing
        binding.xaritaBurilishiKalit.setOnCheckedChangeListener { _, checked ->
            prefs.rotateMapWithBearing = checked
        }

        binding.ekranYoniqKalit.isChecked = prefs.keepScreenOn
        binding.ekranYoniqKalit.setOnCheckedChangeListener { _, checked ->
            prefs.keepScreenOn = checked
        }

        binding.faqatWifiKalit.isChecked = prefs.downloadOnWifiOnly
        binding.faqatWifiKalit.setOnCheckedChangeListener { _, checked ->
            prefs.downloadOnWifiOnly = checked
        }

        // --- Xaritalar ---
        binding.xaritalarTugma.setOnClickListener {
            startActivity(Intent(this, MapsActivity::class.java))
        }

        val maps = app.mapManager.downloadedMaps()
        binding.xaritaHolati.text = if (maps.isEmpty()) {
            "Xarita yuklanmagan"
        } else {
            "Yuklangan xaritalar: " + maps.joinToString(", ") { it.title }
        }

        // --- Ilova haqida ---
        binding.ilovaHaqida.text = buildString {
            append("UzNav — O'zbek Navigator\n")
            append("Versiya 1.0.0\n\n")
            append("Xarita ma'lumotlari: OpenStreetMap (ODbL litsenziyasi).\n")
            append("Marshrut va qidiruv telefon ichida, internetsiz hisoblanadi.\n")
            append("Joylashuvingiz hech qayerga yuborilmaydi — hamma narsa telefonda qoladi.")
        }
    }
}
