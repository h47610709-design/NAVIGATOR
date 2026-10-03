package uz.uznav.navigator.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import uz.uznav.navigator.UzNavApp
import uz.uznav.navigator.data.HistoryStore
import uz.uznav.navigator.databinding.ActivityListBinding
import uz.uznav.navigator.model.Place
import uz.uznav.navigator.util.GeoUtils

/**
 * Qidiruv tarixi ekrani: oldin qidirilgan manzillar.
 */
class HistoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityListBinding
    private lateinit var store: HistoryStore
    private lateinit var adapter: PlaceAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        store = HistoryStore.get(this)
        binding.sarlavha.text = "Qidiruv tarixi"
        binding.orqagaTugma.setOnClickListener { finish() }
        binding.qoshimchaTugma.visibility = View.VISIBLE
        binding.qoshimchaTugma.text = "Tozalash"
        binding.qoshimchaTugma.setOnClickListener { confirmClear() }

        adapter = PlaceAdapter(
            onClick = { place -> returnPlace(place) },
            onLongClick = { place ->
                store.delete(place)
                refresh()
            }
        )
        binding.royxat.layoutManager = LinearLayoutManager(this)
        binding.royxat.adapter = adapter

        refresh()
    }

    private fun refresh() {
        val current = UzNavApp.from(this).locationEngine.location.value?.point
        val items = store.all().map { entry ->
            entry.place.copy(
                distanceM = current?.let { GeoUtils.distance(it, entry.place.point) } ?: -1.0
            )
        }
        adapter.submit(items)
        binding.bolimBosh.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        binding.bolimBosh.text = "Qidiruv tarixi bo'sh."
    }

    private fun confirmClear() {
        AlertDialog.Builder(this)
            .setTitle("Tarix tozalansinmi?")
            .setMessage("Barcha qidiruv tarixi o'chiriladi.")
            .setPositiveButton("Tozalash") { _, _ ->
                store.clear()
                refresh()
            }
            .setNegativeButton("Bekor qilish", null)
            .show()
    }

    private fun returnPlace(place: Place) {
        setResult(Activity.RESULT_OK, PlaceIntent.put(Intent(), place))
        finish()
    }
}
