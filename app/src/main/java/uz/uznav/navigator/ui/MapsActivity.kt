package uz.uznav.navigator.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import uz.uznav.navigator.UzNavApp
import uz.uznav.navigator.data.MapManager
import uz.uznav.navigator.databinding.ActivityMapsBinding
import uz.uznav.navigator.databinding.ItemMapBinding
import uz.uznav.navigator.util.UzFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Offline xaritalarni boshqarish ekrani:
 *  - "Xaritani yuklab olish"
 *  - "Xarita yangilash"
 *  - "Yuklangan xaritalar"
 *
 * Internet faqat shu ekranda kerak bo'ladi. Xarita yuklangandan keyin
 * navigatsiya butunlay internetsiz ishlaydi.
 */
class MapsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMapsBinding
    private lateinit var mapManager: MapManager
    private lateinit var adapter: RegionAdapter

    private var downloadJob: Job? = null
    private var downloadingRegionId: String? = null
    private val progressById = HashMap<String, MapManager.DownloadState.Progress>()
    private val updateAvailable = HashSet<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMapsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        mapManager = UzNavApp.from(this).mapManager
        binding.orqagaTugma.setOnClickListener { finish() }

        adapter = RegionAdapter()
        binding.xaritalarRoyxati.layoutManager = LinearLayoutManager(this)
        binding.xaritalarRoyxati.adapter = adapter

        binding.yangilashniTekshirTugma.setOnClickListener { checkUpdates() }

        refreshHeader()
        adapter.notifyDataSetChanged()
    }

    override fun onDestroy() {
        downloadJob?.cancel()
        super.onDestroy()
    }

    private fun refreshHeader() {
        val maps = mapManager.downloadedMaps()
        binding.holatMatni.text = if (maps.isEmpty()) {
            "Hozircha xarita yuklanmagan.\nNavigatsiya ishlashi uchun O'zbekiston xaritasini yuklab oling."
        } else {
            val total = maps.sumOf { it.sizeBytes }
            "Yuklangan xaritalar: ${maps.size} ta • ${UzFormat.fileSize(total)}"
        }
        binding.papkaMatni.text = "Fayllar papkasi: ${mapManager.mapsDirectory().absolutePath}"
        binding.internetMatni.text = when {
            !mapManager.isOnline() -> "Internet aloqasi yo'q — faqat yuklangan xaritalardan foydalanish mumkin"
            mapManager.isWifi() -> "Wi-Fi orqali ulangan"
            else -> "Mobil internet orqali ulangan"
        }
    }

    /** Serverda yangi xarita versiyasi bor-yo'qligini tekshiradi. */
    private fun checkUpdates() {
        if (!mapManager.isOnline()) {
            toast("Internet aloqasi yo'q")
            return
        }
        binding.yangilashniTekshirTugma.isEnabled = false
        binding.yangilashniTekshirTugma.text = "Tekshirilmoqda…"
        lifecycleScope.launch {
            updateAvailable.clear()
            for (region in mapManager.regions) {
                if (!mapManager.isDownloaded(region)) continue
                if (mapManager.hasUpdate(region)) updateAvailable.add(region.id)
            }
            binding.yangilashniTekshirTugma.isEnabled = true
            binding.yangilashniTekshirTugma.text = "Yangilanishni tekshirish"
            adapter.notifyDataSetChanged()
            toast(
                if (updateAvailable.isEmpty()) "Barcha xaritalar eng yangi holatda"
                else "${updateAvailable.size} ta xarita uchun yangilanish mavjud"
            )
        }
    }

    private fun startDownload(region: MapManager.Region) {
        if (downloadJob?.isActive == true) {
            toast("Avval joriy yuklashni kuting yoki bekor qiling")
            return
        }
        if (!mapManager.isOnline()) {
            toast("Internet aloqasi yo'q")
            return
        }
        if (UzNavApp.from(this).prefs.downloadOnWifiOnly && !mapManager.isWifi()) {
            AlertDialog.Builder(this)
                .setTitle("Mobil internet")
                .setMessage(
                    "Sozlamalarda faqat Wi-Fi tanlangan. Xarita hajmi katta " +
                            "(${UzFormat.fileSize(region.approxBytes)}). Baribir yuklansinmi?"
                )
                .setPositiveButton("Ha, yuklansin") { _, _ -> launchDownload(region) }
                .setNegativeButton("Yo'q", null)
                .show()
            return
        }
        launchDownload(region)
    }

    private fun launchDownload(region: MapManager.Region) {
        downloadingRegionId = region.id
        adapter.notifyDataSetChanged()
        downloadJob = lifecycleScope.launch {
            mapManager.download(region) { state ->
                when (state) {
                    is MapManager.DownloadState.Progress -> {
                        progressById[region.id] = state
                        runOnUiThread { adapter.notifyDataSetChanged() }
                    }

                    is MapManager.DownloadState.Done -> {
                        progressById.remove(region.id)
                        downloadingRegionId = null
                        updateAvailable.remove(region.id)
                        runOnUiThread {
                            // Yangi xarita yuklanganda marshrut keshini tozalaymiz
                            UzNavApp.from(this@MapsActivity).router.clearCache()
                            refreshHeader()
                            adapter.notifyDataSetChanged()
                            toast("Xarita yuklandi: ${region.titleUz}")
                        }
                    }

                    is MapManager.DownloadState.Failed -> {
                        progressById.remove(region.id)
                        downloadingRegionId = null
                        runOnUiThread {
                            refreshHeader()
                            adapter.notifyDataSetChanged()
                            toast(state.message)
                        }
                    }
                }
            }
        }
    }

    private fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
        downloadingRegionId = null
        progressById.clear()
        adapter.notifyDataSetChanged()
        toast("Yuklash to'xtatildi. Keyinroq davom ettirish mumkin.")
    }

    private fun confirmDelete(region: MapManager.Region) {
        val local = mapManager.downloadedMaps().firstOrNull { it.file.name == region.fileName } ?: return
        AlertDialog.Builder(this)
            .setTitle("Xarita o'chirilsinmi?")
            .setMessage("${region.titleUz} (${UzFormat.fileSize(local.sizeBytes)}) telefondan o'chiriladi.")
            .setPositiveButton("O'chirish") { _, _ ->
                mapManager.delete(local)
                UzNavApp.from(this).router.clearCache()
                refreshHeader()
                adapter.notifyDataSetChanged()
            }
            .setNegativeButton("Bekor qilish", null)
            .show()
    }

    private fun toast(message: String) {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()
    }

    // ------------------------------------------------------------------
    // Ro'yxat adapteri
    // ------------------------------------------------------------------

    private inner class RegionAdapter : RecyclerView.Adapter<RegionAdapter.RegionViewHolder>() {

        private val dateFormat = SimpleDateFormat("dd.MM.yyyy", Locale("uz", "UZ"))

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RegionViewHolder {
            val itemBinding = ItemMapBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return RegionViewHolder(itemBinding)
        }

        override fun getItemCount(): Int = mapManager.regions.size

        override fun onBindViewHolder(holder: RegionViewHolder, position: Int) {
            holder.bind(mapManager.regions[position])
        }

        inner class RegionViewHolder(private val itemBinding: ItemMapBinding) :
            RecyclerView.ViewHolder(itemBinding.root) {

            fun bind(region: MapManager.Region) {
                itemBinding.xaritaNomi.text = region.titleUz
                val local = mapManager.downloadedMaps().firstOrNull { it.file.name == region.fileName }
                val downloading = downloadingRegionId == region.id
                val progress = progressById[region.id]

                itemBinding.xaritaHolati.text = when {
                    downloading && progress != null ->
                        "Yuklanmoqda: ${progress.percent}% " +
                                "(${UzFormat.fileSize(progress.downloadedBytes)} / ${UzFormat.fileSize(progress.totalBytes)})"

                    downloading -> "Yuklanmoqda…"
                    local != null && updateAvailable.contains(region.id) ->
                        "Yangilanish mavjud • ${UzFormat.fileSize(local.sizeBytes)}"

                    local != null ->
                        "Yuklangan • ${UzFormat.fileSize(local.sizeBytes)} • ${dateFormat.format(Date(local.updatedAt))}"

                    else -> "Yuklanmagan • taxminan ${UzFormat.fileSize(region.approxBytes)}"
                }

                itemBinding.yuklashDarajasi.visibility = if (downloading) View.VISIBLE else View.GONE
                itemBinding.yuklashDarajasi.progress = progress?.percent ?: 0

                itemBinding.asosiyTugma.text = when {
                    downloading -> "To'xtatish"
                    local != null && updateAvailable.contains(region.id) -> "Yangilash"
                    local != null -> "Qayta yuklash"
                    else -> "Yuklab olish"
                }
                itemBinding.asosiyTugma.setOnClickListener {
                    if (downloading) cancelDownload() else startDownload(region)
                }

                itemBinding.ochirishTugma.visibility = if (local != null && !downloading) View.VISIBLE else View.GONE
                itemBinding.ochirishTugma.setOnClickListener { confirmDelete(region) }
            }
        }
    }
}
