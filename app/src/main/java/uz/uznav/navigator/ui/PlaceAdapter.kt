package uz.uznav.navigator.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import uz.uznav.navigator.R
import uz.uznav.navigator.databinding.ItemPlaceBinding
import uz.uznav.navigator.model.Place
import uz.uznav.navigator.model.PlaceKind
import uz.uznav.navigator.util.UzFormat

/**
 * Joylar ro'yxati uchun adapter (qidiruv natijalari, sevimlilar, tarix).
 */
class PlaceAdapter(
    private val onClick: (Place) -> Unit,
    private val onLongClick: ((Place) -> Unit)? = null
) : RecyclerView.Adapter<PlaceAdapter.PlaceViewHolder>() {

    private val items = ArrayList<Place>()

    fun submit(newItems: List<Place>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    fun itemAt(position: Int): Place? = items.getOrNull(position)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PlaceViewHolder {
        val binding = ItemPlaceBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return PlaceViewHolder(binding)
    }

    override fun onBindViewHolder(holder: PlaceViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class PlaceViewHolder(private val binding: ItemPlaceBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(place: Place) {
            binding.joyNomi.text = place.name
            val subtitle = buildString {
                if (place.address.isNotBlank()) append(place.address)
            }
            binding.joyManzili.text = subtitle
            binding.joyManzili.visibility = if (subtitle.isBlank()) View.GONE else View.VISIBLE

            binding.joyMasofasi.text = if (place.distanceM >= 0) {
                UzFormat.distance(place.distanceM)
            } else {
                ""
            }

            binding.joyBelgisi.setImageResource(iconFor(place.kind))

            binding.root.setOnClickListener { onClick(place) }
            binding.root.setOnLongClickListener {
                onLongClick?.invoke(place)
                onLongClick != null
            }
        }

        private fun iconFor(kind: PlaceKind): Int = when (kind) {
            PlaceKind.SHAHAR, PlaceKind.TUMAN -> R.drawable.ic_shahar
            PlaceKind.KOCHA, PlaceKind.MANZIL -> R.drawable.ic_kocha
            PlaceKind.SEVIMLI -> R.drawable.ic_sevimli
            PlaceKind.TARIX -> R.drawable.ic_tarix
            PlaceKind.XARITA -> R.drawable.ic_manzil_belgisi
            else -> R.drawable.ic_obyekt
        }
    }
}
