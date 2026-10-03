package uz.uznav.navigator.routing

import android.util.Log
import org.mapsforge.core.model.BoundingBox
import org.mapsforge.core.model.Tile
import org.mapsforge.core.util.MercatorProjection
import org.mapsforge.map.datastore.MapDataStore
import uz.uznav.navigator.model.GeoPoint
import uz.uznav.navigator.util.GeoUtils
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Offline `.map` (OpenStreetMap) faylidan avtomobil yo'llari grafini quradi.
 *
 * Mapsforge xarita fayli ma'lumotlarni "tile"lar (kataklar) ko'rinishida saqlaydi.
 * Biz marshrut uchun kerakli hududdagi tile'larni o'qib, ulardagi yo'l
 * chiziqlarini [RoadGraph] ga joylaymiz. Butun jarayon internetsiz ishlaydi.
 */
class GraphExtractor(private val mapStore: MapDataStore) {

    /**
     * O'qiladigan hudud.
     *
     * @param bbox hudud chegarasi
     * @param zoom o'qish darajasi. 12 va undan yuqori qiymatlar bitta
     *        (eng batafsil) ichki fayldan o'qiladi, shuning uchun
     *        koordinatalar bir-biriga mos tushadi va graf uzilmaydi.
     * @param majorOnly faqat asosiy yo'llar (magistral, respublika yo'llari)
     */
    data class Area(val bbox: BoundingBox, val zoom: Byte, val majorOnly: Boolean)

    /** O'qish natijasi haqida qisqa statistika. */
    class Stats(var tilesRead: Int = 0, var waysAdded: Int = 0, var tilesFailed: Int = 0)

    /**
     * Berilgan hududlar bo'yicha grafni quradi.
     *
     * @param onProgress o'qilgan tile'lar soni / umumiy soni
     * @param isCancelled jarayonni bekor qilish uchun tekshiruv
     */
    fun build(
        areas: List<Area>,
        onProgress: ((done: Int, total: Int) -> Unit)? = null,
        isCancelled: () -> Boolean = { false }
    ): RoadGraph {
        val graph = RoadGraph()
        val stats = Stats()
        val visited = HashSet<Long>(4096)

        // Avval barcha tile'lar ro'yxatini tuzamiz (progressni ko'rsatish uchun)
        data class TileRef(val x: Int, val y: Int, val zoom: Byte, val majorOnly: Boolean)

        val tiles = ArrayList<TileRef>(512)
        for (area in areas) {
            val zoom = area.zoom
            val minX = MercatorProjection.longitudeToTileX(max(-180.0, area.bbox.minLongitude), zoom)
            val maxX = MercatorProjection.longitudeToTileX(min(180.0, area.bbox.maxLongitude), zoom)
            val minY = MercatorProjection.latitudeToTileY(min(85.0, area.bbox.maxLatitude), zoom)
            val maxY = MercatorProjection.latitudeToTileY(max(-85.0, area.bbox.minLatitude), zoom)
            for (x in minX..maxX) {
                for (y in minY..maxY) {
                    val key = (zoom.toLong() shl 58) or (x.toLong() shl 29) or y.toLong()
                    if (visited.add(key)) {
                        tiles.add(TileRef(x, y, zoom, area.majorOnly))
                    }
                }
            }
        }

        if (tiles.size > MAX_TILES) {
            Log.w(TAG, "Juda ko'p tile so'raldi: ${tiles.size}")
        }

        val total = tiles.size
        for ((index, ref) in tiles.withIndex()) {
            if (isCancelled()) break
            if (index >= MAX_TILES) break
            readTile(graph, Tile(ref.x, ref.y, ref.zoom, TILE_SIZE), ref.majorOnly, stats)
            if (graph.truncated) {
                Log.w(TAG, "Graf hajmi chegaraga yetdi, o'qish to'xtatildi")
                break
            }
            onProgress?.invoke(index + 1, total)
        }

        graph.finish()
        Log.i(TAG, "Graf tayyor: $graph (tile: ${stats.tilesRead}, xato: ${stats.tilesFailed})")
        return graph
    }

    private fun readTile(graph: RoadGraph, tile: Tile, majorOnly: Boolean, stats: Stats) {
        val result = try {
            mapStore.readMapData(tile)
        } catch (e: Exception) {
            stats.tilesFailed++
            Log.w(TAG, "Tile o'qishda xato: ${tile.tileX}/${tile.tileY}/${tile.zoomLevel}", e)
            return
        } ?: return

        stats.tilesRead++
        for (way in result.ways) {
            val info = RoadTags.parse(way.tags) ?: continue
            if (majorOnly && !RoadTags.isMajor(info.roadClass)) continue
            val blocks = way.latLongs ?: continue
            for (block in blocks) {
                if (block == null || block.size < 2) continue
                if (graph.addWay(block, info)) stats.waysAdded++
            }
        }
    }

    companion object {
        private const val TAG = "UzNav/Graf"
        private const val TILE_SIZE = 256

        /** Bitta marshrut uchun maksimal tile soni (telefonni ortiqcha yuklamaslik uchun). */
        const val MAX_TILES = 2400

        /**
         * Marshrut uchun o'qiladigan hududlarni hisoblaydi: to'g'ri chiziq
         * bo'ylab "koridor" + boshlanish va tugash nuqtalari atrofidagi
         * batafsil doiralar.
         */
        fun corridorAreas(start: GeoPoint, end: GeoPoint): List<Area> {
            val directDistance = GeoUtils.distance(start, end)
            val areas = ArrayList<Area>(16)

            // Qisqa masofalarda butun hududni batafsil o'qiymiz
            if (directDistance < 12_000) {
                areas.add(Area(boxAround(listOf(start, end), max(2_500.0, directDistance * 0.6)), 14, false))
                return areas
            }

            // Uzoq masofa: koridor + uchlarda batafsil hudud
            val corridorZoom: Byte = if (directDistance < 60_000) 13 else 12
            val majorOnly = directDistance > 35_000
            val corridorWidth = (directDistance * 0.22).coerceIn(6_000.0, 40_000.0)

            val steps = ceil(directDistance / corridorWidth).toInt().coerceIn(1, 60)
            var previous = start
            for (i in 1..steps) {
                val fraction = i.toDouble() / steps
                val point = GeoPoint(
                    start.lat + (end.lat - start.lat) * fraction,
                    start.lon + (end.lon - start.lon) * fraction
                )
                areas.add(Area(boxAround(listOf(previous, point), corridorWidth / 2), corridorZoom, majorOnly))
                previous = point
            }

            // Uchlardagi batafsil hudud — ko'chalar va kichik yo'llar uchun
            val detailRadius = if (directDistance > 100_000) 5_000.0 else 3_500.0
            areas.add(Area(boxAround(listOf(start), detailRadius), 14, false))
            areas.add(Area(boxAround(listOf(end), detailRadius), 14, false))
            return areas
        }

        /** Nuqtalar atrofida berilgan chekka (metr) bilan to'rtburchak hudud. */
        fun boxAround(points: List<GeoPoint>, marginM: Double): BoundingBox {
            var minLat = 90.0
            var maxLat = -90.0
            var minLon = 180.0
            var maxLon = -180.0
            for (p in points) {
                minLat = min(minLat, p.lat); maxLat = max(maxLat, p.lat)
                minLon = min(minLon, p.lon); maxLon = max(maxLon, p.lon)
            }
            val latMargin = GeoUtils.metersToLatDegrees(marginM)
            val lonMargin = GeoUtils.metersToLonDegrees(marginM, (minLat + maxLat) / 2)
            return BoundingBox(
                (minLat - latMargin).coerceAtLeast(-85.0),
                (minLon - lonMargin).coerceAtLeast(-180.0),
                (maxLat + latMargin).coerceAtMost(85.0),
                (maxLon + lonMargin).coerceAtMost(180.0)
            )
        }
    }
}
