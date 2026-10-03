package uz.uznav.navigator.routing

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.mapsforge.map.datastore.MapDataStore
import uz.uznav.navigator.model.GeoPoint
import uz.uznav.navigator.model.Place
import uz.uznav.navigator.model.Route
import uz.uznav.navigator.model.RouteProfile
import uz.uznav.navigator.util.GeoUtils
import kotlin.coroutines.coroutineContext

/**
 * Offline marshrut xizmati.
 *
 * Ishlash tartibi:
 *  1. Xarita faylidan kerakli hudud yo'llari o'qiladi va graf quriladi.
 *  2. Boshlanish va manzil nuqtalari grafning eng yaqin tuguniga bog'lanadi.
 *  3. A* algoritmi bilan yo'l topiladi va burilishlar ro'yxati tuziladi.
 *
 * Graf keshda saqlanadi — marshrutdan chiqib ketilganda qayta hisoblash
 * (re-routing) bir necha yuz millisekundda, internetsiz bajariladi.
 */
class Router(private val storeProvider: () -> MapDataStore?) {

    /** Marshrut hisoblash natijasi. */
    sealed class Result {
        /** Muvaffaqiyatli: har bir tanlangan variant uchun marshrut. */
        data class Ok(val routes: List<Route>) : Result()

        /** Xatolik — foydalanuvchiga ko'rsatiladigan o'zbekcha matn. */
        data class Error(val message: String) : Result()
    }

    /** Hisoblash bosqichi haqida xabar (UI'da ko'rsatish uchun). */
    fun interface ProgressListener {
        fun onProgress(message: String, percent: Int)
    }

    private var cachedGraph: RoadGraph? = null
    private var cachedAtDestination: GeoPoint? = null

    /** Keshdagi grafni tozalaydi (masalan, boshqa xarita yuklanganda). */
    @Synchronized
    fun clearCache() {
        cachedGraph = null
        cachedAtDestination = null
    }

    /**
     * Marshrut(lar)ni hisoblaydi.
     *
     * @param profiles kerakli variantlar (eng tez, eng qisqa, pullik yo'lsiz)
     * @param allowCachedGraph marshrutdan chiqib ketilganda keshdagi grafni ishlatish
     */
    suspend fun calculate(
        start: GeoPoint,
        destination: Place,
        profiles: List<RouteProfile>,
        progress: ProgressListener? = null,
        allowCachedGraph: Boolean = true
    ): Result = withContext(Dispatchers.Default) {
        val store = storeProvider()
            ?: return@withContext Result.Error("Xarita fayli topilmadi. Avval xaritani yuklab oling.")

        if (!store.boundingBox().contains(destination.point.toLatLong())) {
            return@withContext Result.Error("Manzil yuklangan xarita hududidan tashqarida.")
        }

        // Coroutine bekor qilinganini sinxron kodda tekshirish uchun Job saqlanadi
        val job = coroutineContext[Job]
        val cancelled = { job != null && !job.isActive }

        try {
            val graph = obtainGraph(store, start, destination.point, progress, allowCachedGraph, cancelled)
            coroutineContext.ensureActive()

            if (graph.nodeCount == 0) {
                return@withContext Result.Error("Bu hududda yo'l ma'lumotlari topilmadi.")
            }

            val startNode = graph.nearestNode(start.lat, start.lon, SNAP_RADIUS_M)
            if (startNode < 0) {
                return@withContext Result.Error("Joriy joylashuvingiz yaqinida yo'l topilmadi.")
            }
            val endNode = graph.nearestNode(destination.point.lat, destination.point.lon, SNAP_RADIUS_M)
            if (endNode < 0) {
                return@withContext Result.Error("Manzil yaqinida avtomobil yo'li topilmadi.")
            }

            progress?.onProgress("Marshrut hisoblanmoqda…", 90)

            val routes = ArrayList<Route>(profiles.size)
            for (profile in profiles) {
                coroutineContext.ensureActive()
                val path = AStarRouter(graph, profile).findPath(startNode, endNode, cancelled)
                    ?: continue
                if (path.nodes.size < 2) continue
                routes.add(
                    RouteAssembler.assemble(
                        graph = graph,
                        path = path,
                        profile = profile,
                        destination = destination,
                        startOverride = start,
                        endOverride = destination.point
                    )
                )
            }

            if (routes.isEmpty()) {
                Result.Error("Marshrut topilmadi. Manzilni yoki xaritani tekshiring.")
            } else {
                progress?.onProgress("Marshrut tayyor", 100)
                Result.Ok(routes.distinctByDistance())
            }
        } catch (e: OutOfMemoryError) {
            clearCache()
            Log.e(TAG, "Xotira yetmadi", e)
            Result.Error("Telefon xotirasi yetmadi. Qisqaroq marshrutni sinab ko'ring.")
        } catch (e: Exception) {
            Log.e(TAG, "Marshrut hisoblashda xato", e)
            Result.Error("Marshrut hisoblashda xatolik yuz berdi.")
        }
    }

    /** Keshdagi grafni ishlatadi yoki yangisini quradi. */
    private suspend fun obtainGraph(
        store: MapDataStore,
        start: GeoPoint,
        destination: GeoPoint,
        progress: ProgressListener?,
        allowCachedGraph: Boolean,
        cancelled: () -> Boolean
    ): RoadGraph {
        val cached = cachedGraph
        if (allowCachedGraph && cached != null && cached.finished &&
            cached.covers(start.lat, start.lon, -GRAPH_EDGE_MARGIN_DEG) &&
            cached.covers(destination.lat, destination.lon, -GRAPH_EDGE_MARGIN_DEG) &&
            cachedAtDestination?.let { GeoUtils.distance(it, destination) < 50.0 } == true &&
            cached.nearestNode(start.lat, start.lon, SNAP_RADIUS_M) >= 0
        ) {
            Log.i(TAG, "Keshdagi grafdan foydalanilmoqda")
            return cached
        }

        progress?.onProgress("Xarita o'qilmoqda…", 1)
        val areas = GraphExtractor.corridorAreas(start, destination)
        val extractor = GraphExtractor(store)
        val graph = extractor.build(
            areas = areas,
            onProgress = { done, total ->
                if (total > 0 && done % 8 == 0) {
                    val percent = (done * 85 / total).coerceIn(1, 85)
                    progress?.onProgress("Xarita o'qilmoqda…", percent)
                }
            },
            isCancelled = cancelled
        )
        synchronized(this) {
            cachedGraph = graph
            cachedAtDestination = destination
        }
        return graph
    }

    /** Juda o'xshash marshrutlarni (masofasi deyarli bir xil) takrorlamaymiz. */
    private fun List<Route>.distinctByDistance(): List<Route> {
        val result = ArrayList<Route>(size)
        for (route in this) {
            val duplicate = result.any {
                kotlin.math.abs(it.distanceM - route.distanceM) < 30.0 &&
                        kotlin.math.abs(it.durationS - route.durationS) < 30.0
            }
            if (!duplicate) result.add(route)
        }
        return result
    }

    companion object {
        private const val TAG = "UzNav/Router"

        /** Nuqtani yo'lga bog'lash radiusi (metr). */
        private const val SNAP_RADIUS_M = 600.0

        /** Keshdagi grafni ishlatish uchun chekkadan zaxira masofa (~1 km). */
        private const val GRAPH_EDGE_MARGIN_DEG = 0.01
    }
}
