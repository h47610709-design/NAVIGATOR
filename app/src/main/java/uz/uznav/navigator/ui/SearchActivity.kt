package uz.uznav.navigator.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import uz.uznav.navigator.UzNavApp
import uz.uznav.navigator.data.FavoritesStore
import uz.uznav.navigator.data.HistoryStore
import uz.uznav.navigator.databinding.ActivitySearchBinding
import uz.uznav.navigator.model.GeoPoint
import uz.uznav.navigator.model.Place
import uz.uznav.navigator.search.OfflineSearch
import uz.uznav.navigator.util.GeoUtils

/**
 * Manzil qidirish ekrani.
 *
 * Qidiruv to'liq offline ishlaydi: ichki ma'lumotnoma + yuklangan xarita fayli.
 * Ko'cha nomi, uy raqami, shahar, tuman, mahalla yoki obyekt nomi bo'yicha qidirish mumkin.
 */
class SearchActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySearchBinding
    private lateinit var search: OfflineSearch
    private lateinit var adapter: PlaceAdapter

    private var searchJob: Job? = null
    private var currentLocation: GeoPoint? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySearchBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val app = UzNavApp.from(this)
        search = OfflineSearch(this, app.mapManager)
        currentLocation = app.locationEngine.location.value?.point
            ?: GeoPoint(app.prefs.lastLatitude, app.prefs.lastLongitude)

        adapter = PlaceAdapter(onClick = { place -> returnPlace(place) })
        binding.natijalarRoyxati.layoutManager = LinearLayoutManager(this)
        binding.natijalarRoyxati.adapter = adapter

        binding.orqagaTugma.setOnClickListener { finish() }
        binding.tozalashTugma.setOnClickListener {
            binding.qidiruvMaydoni.setText("")
            showStartSuggestions()
        }

        binding.qidiruvMaydoni.doAfterTextChanged { text ->
            val query = text?.toString().orEmpty()
            binding.tozalashTugma.visibility = if (query.isEmpty()) View.GONE else View.VISIBLE
            if (query.trim().length < 2) {
                showStartSuggestions()
            } else {
                scheduleSearch(query)
            }
        }

        binding.uyTugma.setOnClickListener {
            val home = FavoritesStore.get(this).home()
            if (home != null) returnPlace(home.place)
            else showEmpty("\"Uy\" manzili saqlanmagan. Avval sevimlilarga qo'shing.")
        }
        binding.ishTugma.setOnClickListener {
            val work = FavoritesStore.get(this).work()
            if (work != null) returnPlace(work.place)
            else showEmpty("\"Ish\" manzili saqlanmagan. Avval sevimlilarga qo'shing.")
        }
        binding.sevimlilarTugma.setOnClickListener {
            startActivity(Intent(this, FavoritesActivity::class.java))
        }
        binding.tarixTugma.setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java))
        }

        showStartSuggestions()
        binding.qidiruvMaydoni.requestFocus()
        showKeyboard()
    }

    /** Qidiruvni biroz kechiktirib ishga tushiradi (har harfda emas). */
    private fun scheduleSearch(query: String) {
        searchJob?.cancel()
        searchJob = lifecycleScope.launch {
            delay(300)
            binding.yuklanmoqda.visibility = View.VISIBLE
            binding.holatMatni.visibility = View.GONE
            val results = search.search(query, currentLocation)
            binding.yuklanmoqda.visibility = View.GONE
            if (results.isEmpty()) {
                adapter.submit(emptyList())
                val hasMap = UzNavApp.from(this@SearchActivity).mapManager.hasAnyMap()
                showEmpty(
                    if (hasMap) "Hech narsa topilmadi. Boshqacha yozib ko'ring."
                    else "Xarita yuklanmagan. \"Xaritalar\" bo'limidan O'zbekiston xaritasini yuklab oling."
                )
            } else {
                binding.holatMatni.visibility = View.GONE
                adapter.submit(results)
            }
        }
    }

    /** Qidiruv bo'sh bo'lganda tarix va sevimlilarni ko'rsatamiz. */
    private fun showStartSuggestions() {
        searchJob?.cancel()
        val favorites = FavoritesStore.get(this).all().map { favorite ->
            favorite.place.copy(
                name = favorite.title,
                distanceM = currentLocation?.let { GeoUtils.distance(it, favorite.place.point) } ?: -1.0
            )
        }
        val history = HistoryStore.get(this).all().take(12).map { entry ->
            entry.place.copy(
                distanceM = currentLocation?.let { GeoUtils.distance(it, entry.place.point) } ?: -1.0
            )
        }
        val suggestions = (favorites + history).distinctBy { it.name + it.point.lat }
        adapter.submit(suggestions)
        if (suggestions.isEmpty()) {
            showEmpty("Manzilni kiriting. Masalan: \"Amir Temur ko'chasi 108, Toshkent\"")
        } else {
            binding.holatMatni.visibility = View.GONE
        }
    }

    private fun showEmpty(message: String) {
        binding.holatMatni.text = message
        binding.holatMatni.visibility = View.VISIBLE
    }

    private fun returnPlace(place: Place) {
        hideKeyboard()
        setResult(Activity.RESULT_OK, PlaceIntent.put(Intent(), place))
        finish()
    }

    private fun showKeyboard() {
        val manager = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        manager?.showSoftInput(binding.qidiruvMaydoni, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun hideKeyboard() {
        val manager = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        manager?.hideSoftInputFromWindow(binding.qidiruvMaydoni.windowToken, 0)
    }
}
