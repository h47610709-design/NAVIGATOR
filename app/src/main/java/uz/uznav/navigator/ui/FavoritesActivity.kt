package uz.uznav.navigator.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.EditText
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import uz.uznav.navigator.UzNavApp
import uz.uznav.navigator.data.FavoritesStore
import uz.uznav.navigator.databinding.ActivityListBinding
import uz.uznav.navigator.model.FavoriteType
import uz.uznav.navigator.model.Place
import uz.uznav.navigator.util.GeoUtils

/**
 * Sevimli joylar ekrani: "Uy", "Ish" va foydalanuvchi qo'shgan joylar.
 */
class FavoritesActivity : AppCompatActivity() {

    private lateinit var binding: ActivityListBinding
    private lateinit var store: FavoritesStore
    private lateinit var adapter: PlaceAdapter

    /** Qidiruvdan qaytgan joyni sevimlilarga qo'shish uchun. */
    private val pickPlace = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            PlaceIntent.read(result.data)?.let { askTitleAndSave(it) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        store = FavoritesStore.get(this)
        binding.sarlavha.text = "Sevimli joylar"
        binding.orqagaTugma.setOnClickListener { finish() }
        binding.qoshimchaTugma.visibility = View.VISIBLE
        binding.qoshimchaTugma.text = "Qo'shish"
        binding.qoshimchaTugma.setOnClickListener {
            pickPlace.launch(Intent(this, SearchActivity::class.java))
        }

        adapter = PlaceAdapter(
            onClick = { place -> returnPlace(place) },
            onLongClick = { place -> confirmDelete(place) }
        )
        binding.royxat.layoutManager = LinearLayoutManager(this)
        binding.royxat.adapter = adapter

        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val current = UzNavApp.from(this).locationEngine.location.value?.point
        val items = store.all().map { favorite ->
            favorite.place.copy(
                name = favorite.title,
                address = when (favorite.type) {
                    FavoriteType.UY -> "Uy"
                    FavoriteType.ISH -> "Ish"
                    FavoriteType.BOSHQA -> favorite.place.address.ifBlank { "Sevimli joy" }
                },
                distanceM = current?.let { GeoUtils.distance(it, favorite.place.point) } ?: -1.0
            )
        }
        adapter.submit(items)
        binding.bolimBosh.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        binding.bolimBosh.text =
            "Sevimli joylar yo'q.\n\"Qo'shish\" tugmasi orqali uy yoki ish manzilingizni saqlang."
    }

    /** Joyni saqlashdan oldin nom va turini so'raydi. */
    private fun askTitleAndSave(place: Place) {
        val input = EditText(this).apply {
            setText(place.name)
            setPadding(48, 32, 48, 32)
        }
        AlertDialog.Builder(this)
            .setTitle("Sevimli joy nomi")
            .setView(input)
            .setPositiveButton("Saqlash") { _, _ ->
                val title = input.text.toString().ifBlank { place.name }
                chooseType(title, place)
            }
            .setNegativeButton("Bekor qilish", null)
            .show()
    }

    private fun chooseType(title: String, place: Place) {
        val types = arrayOf("Uy", "Ish", "Boshqa joy")
        AlertDialog.Builder(this)
            .setTitle("Joy turi")
            .setItems(types) { _, which ->
                val type = when (which) {
                    0 -> FavoriteType.UY
                    1 -> FavoriteType.ISH
                    else -> FavoriteType.BOSHQA
                }
                store.save(title, type, place)
                refresh()
            }
            .show()
    }

    private fun confirmDelete(place: Place) {
        val favorite = store.all().firstOrNull {
            it.title == place.name && it.place.point.lat == place.point.lat
        } ?: return
        AlertDialog.Builder(this)
            .setTitle("O'chirilsinmi?")
            .setMessage("\"${favorite.title}\" sevimlilardan o'chiriladi.")
            .setPositiveButton("O'chirish") { _, _ ->
                store.delete(favorite.id)
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
