package uz.uznav.navigator.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.mapsforge.map.datastore.MapDataStore
import org.mapsforge.map.datastore.MultiMapDataStore
import org.mapsforge.map.reader.MapFile
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

/**
 * Offline xaritalarni boshqarish: yuklab olish, yangilash, o'chirish va ochish.
 *
 * Xarita fayllari OpenStreetMap ma'lumotlaridan tayyorlangan `.map`
 * (Mapsforge) formatida bo'ladi va telefon xotirasida saqlanadi:
 *   Android/data/uz.uznav.navigator/files/maps/
 *
 * Internet FAQAT yuklab olish va yangilash uchun kerak. Yuklangandan keyin
 * butun navigatsiya internetsiz ishlaydi.
 */
class MapManager private constructor(private val context: Context) {

    /** Yuklab olish mumkin bo'lgan hudud. */
    data class Region(
        val id: String,
        val titleUz: String,
        val fileName: String,
        val url: String,
        val approxBytes: Long,
        val isUzbekistan: Boolean = false
    )

    /** Telefonga yuklangan xarita fayli. */
    data class LocalMap(
        val region: Region?,
        val file: File,
        val sizeBytes: Long,
        val updatedAt: Long
    ) {
        val title: String get() = region?.titleUz ?: file.name.removeSuffix(".map")
    }

    /** Yuklab olish holati. */
    sealed class DownloadState {
        data class Progress(val downloadedBytes: Long, val totalBytes: Long) : DownloadState() {
            val percent: Int
                get() = if (totalBytes > 0) ((downloadedBytes * 100) / totalBytes).toInt() else 0
        }

        data class Done(val map: LocalMap) : DownloadState()
        data class Failed(val message: String) : DownloadState()
    }

    private val mapsDir: File by lazy {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        File(base, "maps").apply { mkdirs() }
    }

    private val metaFile: File by lazy { File(mapsDir, "xaritalar.json") }

    @Volatile
    private var openStore: MultiMapDataStore? = null

    @Volatile
    private var openedFiles: List<String> = emptyList()

    // ------------------------------------------------------------------
    // Xaritalar ro'yxati
    // ------------------------------------------------------------------

    /** Yuklab olish mumkin bo'lgan hududlar (O'zbekiston birinchi o'rinda). */
    val regions: List<Region> = listOf(
        Region(
            "uzbekistan", "O'zbekiston (butun davlat)", "uzbekistan.map",
            "$BASE_URL/asia/uzbekistan.map", 138_000_000L, isUzbekistan = true
        ),
        Region(
            "kazakhstan", "Qozog'iston", "kazakhstan.map",
            "$BASE_URL/asia/kazakhstan.map", 416_000_000L
        ),
        Region(
            "kyrgyzstan", "Qirg'iziston", "kyrgyzstan.map",
            "$BASE_URL/asia/kyrgyzstan.map", 58_000_000L
        ),
        Region(
            "tajikistan", "Tojikiston", "tajikistan.map",
            "$BASE_URL/asia/tajikistan.map", 52_000_000L
        ),
        Region(
            "turkmenistan", "Turkmaniston", "turkmenistan.map",
            "$BASE_URL/asia/turkmenistan.map", 57_000_000L
        ),
        Region(
            "afghanistan", "Afg'oniston", "afghanistan.map",
            "$BASE_URL/asia/afghanistan.map", 143_000_000L
        )
    )

    fun regionById(id: String): Region? = regions.firstOrNull { it.id == id }

    /** Xarita fayllari saqlanadigan papka (sozlamalarda ko'rsatiladi). */
    fun mapsDirectory(): File = mapsDir

    /** Telefonga yuklangan barcha xaritalar. */
    fun downloadedMaps(): List<LocalMap> {
        val files = mapsDir.listFiles { f -> f.isFile && f.name.endsWith(".map") } ?: return emptyList()
        return files.sortedBy { it.name }.map { file ->
            LocalMap(
                region = regions.firstOrNull { it.fileName == file.name },
                file = file,
                sizeBytes = file.length(),
                updatedAt = serverTimestamp(file.name).takeIf { it > 0 } ?: file.lastModified()
            )
        }
    }

    fun isDownloaded(region: Region): Boolean = File(mapsDir, region.fileName).let {
        it.exists() && it.length() > MIN_VALID_SIZE
    }

    fun hasAnyMap(): Boolean = downloadedMaps().isNotEmpty()

    /** Xaritani o'chiradi. */
    fun delete(map: LocalMap): Boolean {
        closeStore()
        val removed = map.file.delete()
        File(mapsDir, map.file.name + ".part").delete()
        return removed
    }

    // ------------------------------------------------------------------
    // Xaritani ochish (navigatsiya va qidiruv uchun)
    // ------------------------------------------------------------------

    /**
     * Barcha yuklangan xaritalarni birlashtirib ochadi.
     * Natija keshlanadi; fayllar ro'yxati o'zgarsa qayta ochiladi.
     */
    @Synchronized
    fun store(): MapDataStore? {
        val maps = downloadedMaps()
        if (maps.isEmpty()) {
            closeStore()
            return null
        }
        val names = maps.map { it.file.absolutePath + ":" + it.sizeBytes }
        val current = openStore
        if (current != null && names == openedFiles) return current

        closeStore()
        val multi = MultiMapDataStore(MultiMapDataStore.DataPolicy.RETURN_ALL)
        var opened = 0
        for (map in maps) {
            try {
                multi.addMapDataStore(MapFile(map.file), false, false)
                opened++
            } catch (e: Exception) {
                Log.e(TAG, "Xarita faylini ochib bo'lmadi: ${map.file.name}", e)
            }
        }
        if (opened == 0) return null
        openStore = multi
        openedFiles = names
        return multi
    }

    @Synchronized
    fun closeStore() {
        runCatching { openStore?.close() }
        openStore = null
        openedFiles = emptyList()
    }

    // ------------------------------------------------------------------
    // Yuklab olish va yangilash
    // ------------------------------------------------------------------

    /** Internet mavjudmi? */
    fun isOnline(): Boolean {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /** Wi-Fi orqali ulanganmi? */
    fun isWifi(): Boolean {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    /**
     * Serverda yangiroq xarita bor-yo'qligini tekshiradi.
     * @return true — yangilash mavjud
     */
    suspend fun hasUpdate(region: Region): Boolean = withContext(Dispatchers.IO) {
        if (!isDownloaded(region)) return@withContext false
        val local = File(mapsDir, region.fileName)
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(region.url).openConnection() as HttpURLConnection).apply {
                requestMethod = "HEAD"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                setRequestProperty("User-Agent", USER_AGENT)
            }
            val code = connection.responseCode
            if (code !in 200..299) return@withContext false
            val remoteLength = connection.contentLengthLong
            val remoteModified = connection.lastModified
            val savedModified = serverTimestamp(region.fileName)
            when {
                remoteLength > 0 && remoteLength != local.length() -> true
                remoteModified > 0 && savedModified > 0 && remoteModified > savedModified -> true
                else -> false
            }
        } catch (e: Exception) {
            Log.w(TAG, "Yangilanishni tekshirib bo'lmadi", e)
            false
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * Xaritani yuklab oladi. Yuklash to'xtab qolsa, keyingi safar
     * qoldirilgan joydan davom etadi (HTTP Range).
     *
     * @param onState yuklash holati haqida xabar (UI uchun)
     */
    suspend fun download(
        region: Region,
        onState: (DownloadState) -> Unit
    ): DownloadState = withContext(Dispatchers.IO) {
        if (!isOnline()) {
            val state = DownloadState.Failed("Internet aloqasi yo'q")
            onState(state)
            return@withContext state
        }

        val target = File(mapsDir, region.fileName)
        val partial = File(mapsDir, region.fileName + ".part")
        var connection: HttpURLConnection? = null
        try {
            val existing = if (partial.exists()) partial.length() else 0L
            connection = (URL(region.url).openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("User-Agent", USER_AGENT)
                if (existing > 0) setRequestProperty("Range", "bytes=$existing-")
            }

            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                val state = DownloadState.Failed("Serverdan javob kelmadi (kod: $responseCode)")
                onState(state)
                return@withContext state
            }

            val resuming = responseCode == HttpURLConnection.HTTP_PARTIAL && existing > 0
            val contentLength = connection.contentLengthLong
            val totalBytes = if (resuming && contentLength > 0) existing + contentLength else contentLength
            val serverModified = connection.lastModified

            var downloaded = if (resuming) existing else 0L
            if (!resuming) partial.delete()

            connection.inputStream.use { input ->
                FileOutputStream(partial, resuming).use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var lastReport = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        if (downloaded - lastReport > REPORT_STEP) {
                            lastReport = downloaded
                            onState(DownloadState.Progress(downloaded, totalBytes))
                        }
                    }
                    output.flush()
                }
            }

            if (partial.length() < MIN_VALID_SIZE) {
                partial.delete()
                val state = DownloadState.Failed("Yuklangan fayl buzuq. Qaytadan urinib ko'ring.")
                onState(state)
                return@withContext state
            }

            closeStore()
            if (target.exists()) target.delete()
            if (!partial.renameTo(target)) {
                val state = DownloadState.Failed("Faylni saqlab bo'lmadi")
                onState(state)
                return@withContext state
            }
            saveServerTimestamp(region.fileName, serverModified)

            val local = LocalMap(region, target, target.length(), System.currentTimeMillis())
            val state = DownloadState.Done(local)
            onState(state)
            state
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Xaritani yuklashda xato", e)
            val state = DownloadState.Failed("Yuklashda xatolik: ${e.localizedMessage ?: "noma'lum xato"}")
            onState(state)
            state
        } finally {
            connection?.disconnect()
        }
    }

    // ------------------------------------------------------------------
    // Yordamchi: server vaqtini saqlash
    // ------------------------------------------------------------------

    private fun readMeta(): JSONObject = runCatching {
        if (metaFile.exists()) JSONObject(metaFile.readText()) else JSONObject()
    }.getOrDefault(JSONObject())

    private fun serverTimestamp(fileName: String): Long = readMeta().optLong(fileName, 0L)

    private fun saveServerTimestamp(fileName: String, timestamp: Long) {
        runCatching {
            val meta = readMeta()
            meta.put(fileName, if (timestamp > 0) timestamp else System.currentTimeMillis())
            metaFile.writeText(meta.toString())
        }.onFailure { Log.w(TAG, "Xarita ma'lumotini saqlashda xato", it) }
    }

    companion object {
        private const val TAG = "UzNav/Xarita"

        /** Mapsforge rasmiy xarita serveri (OpenStreetMap ma'lumotlari). */
        const val BASE_URL = "https://download.mapsforge.org/maps/v5"

        private const val USER_AGENT = "UzNav-Navigator/1.0 (Android; offline navigator)"
        private const val TIMEOUT_MS = 20_000
        private const val READ_TIMEOUT_MS = 60_000
        private const val BUFFER_SIZE = 64 * 1024
        private const val REPORT_STEP = 512 * 1024L
        private const val MIN_VALID_SIZE = 1024L * 1024L

        @Volatile
        private var instance: MapManager? = null

        fun get(context: Context): MapManager =
            instance ?: synchronized(this) {
                instance ?: MapManager(context.applicationContext).also { instance = it }
            }
    }
}
