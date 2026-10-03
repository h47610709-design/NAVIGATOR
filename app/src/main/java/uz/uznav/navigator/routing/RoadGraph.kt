package uz.uznav.navigator.routing

import org.mapsforge.core.model.LatLong
import uz.uznav.navigator.util.GeoUtils
import uz.uznav.navigator.util.IntList
import uz.uznav.navigator.util.LongIntMap
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max

/**
 * Offline marshrut grafi.
 *
 * Graf to'g'ridan-to'g'ri `.map` (OpenStreetMap) faylidan o'qilgan yo'l
 * geometriyasidan quriladi. Har bir yo'l nuqtasi — graf tuguni, har bir
 * kesma — yo'naltirilgan qirra. Shunday qilib:
 *  - boshlang'ich/oxirgi nuqtani yo'lga aniq "yopishtirish" oson;
 *  - bir tomonlama yo'llar to'g'ri hisobga olinadi;
 *  - hech qanday internet yoki tashqi server kerak emas.
 *
 * Xotirani tejash uchun koordinatalar mikro-gradusda (Int) saqlanadi.
 */
class RoadGraph {

    // --- Tugunlar ---
    private var nodeLatE6 = IntArray(INITIAL_NODES)
    private var nodeLonE6 = IntArray(INITIAL_NODES)
    var nodeCount: Int = 0
        private set
    private val nodeIndex = LongIntMap(1 shl 17)

    // --- Yo'naltirilgan qirralar (vaqtinchalik ro'yxatlar) ---
    private val edgeFromList = IntList(INITIAL_EDGES)
    private val edgeToList = IntList(INITIAL_EDGES)
    private val edgeWayList = IntList(INITIAL_EDGES)

    // --- CSR ko'rinishidagi qo'shnilik (finish() dan keyin) ---
    private var csrStart: IntArray = IntArray(0)
    private var csrTarget: IntArray = IntArray(0)
    private var csrWay: IntArray = IntArray(0)
    private var csrLength: FloatArray = FloatArray(0)

    // --- Yo'llar haqidagi ma'lumot ---
    private val ways = ArrayList<RoadTags.RoadInfo>(4096)
    private val namePool = HashMap<String, String>(4096)
    private val waySignatures = HashSet<Long>(8192)

    // --- Fazoviy indeks (eng yaqin tugunni topish uchun) ---
    private val cellHead = LongIntMap(1 shl 16)
    private var cellNext: IntArray = IntArray(0)

    /** Graf qamrab olgan hudud. */
    var minLat = 90.0; private set
    var maxLat = -90.0; private set
    var minLon = 180.0; private set
    var maxLon = -180.0; private set

    /** Xotira cheklovi sababli ma'lumot to'liq o'qilmaganini bildiradi. */
    var truncated: Boolean = false
        private set

    var finished: Boolean = false
        private set

    val edgeCount: Int get() = csrTarget.size

    // ------------------------------------------------------------------
    // Qurish bosqichi
    // ------------------------------------------------------------------

    /**
     * Yo'lni (way) grafga qo'shadi.
     *
     * @param coordinates yo'l nuqtalari (mapsforge formatida)
     * @param info yo'lning teglaridan olingan ma'lumot
     * @return qo'shilgan bo'lsa true
     */
    fun addWay(coordinates: Array<LatLong>, info: RoadTags.RoadInfo): Boolean {
        if (finished || coordinates.size < 2) return false
        if (nodeCount > MAX_NODES) {
            truncated = true
            return false
        }

        // Bir xil yo'l bir nechta "tile"da takrorlanishi mumkin — takrorni tashlaymiz
        val signature = waySignature(coordinates, info)
        if (!waySignatures.add(signature)) return false

        val wayIndex = ways.size
        ways.add(internNames(info))

        var previous = -1
        for (coordinate in coordinates) {
            val node = addNode(coordinate.latitude, coordinate.longitude)
            if (previous >= 0 && previous != node) {
                if (info.onewayForward) addDirectedEdge(previous, node, wayIndex)
                if (info.onewayBackward) addDirectedEdge(node, previous, wayIndex)
            }
            previous = node
        }
        return true
    }

    /** Yo'l nomlarini takroran saqlamaslik uchun umumiy "pool"dan foydalanadi. */
    private fun internNames(info: RoadTags.RoadInfo): RoadTags.RoadInfo {
        val name = info.name?.let { namePool.getOrPut(it) { it } }
        val ref = info.ref?.let { namePool.getOrPut(it) { it } }
        return if (name === info.name && ref === info.ref) info
        else RoadTags.RoadInfo(
            name, ref, info.roadClass, info.speedKmh, info.speedLimitKmh,
            info.onewayForward, info.onewayBackward, info.toll, info.roundabout
        )
    }

    private fun waySignature(coordinates: Array<LatLong>, info: RoadTags.RoadInfo): Long {
        val first = coordinates.first()
        val last = coordinates.last()
        var h = 1125899906842597L
        h = 31 * h + Math.round(first.latitude * 1e6)
        h = 31 * h + Math.round(first.longitude * 1e6)
        h = 31 * h + Math.round(last.latitude * 1e6)
        h = 31 * h + Math.round(last.longitude * 1e6)
        h = 31 * h + coordinates.size
        h = 31 * h + (info.displayName?.hashCode() ?: 0)
        return h
    }

    private fun addNode(lat: Double, lon: Double): Int {
        val latE6 = Math.round(lat * 1e6).toInt()
        val lonE6 = Math.round(lon * 1e6).toInt()
        val key = (latE6.toLong() shl 32) or (lonE6.toLong() and 0xFFFFFFFFL)
        val existing = nodeIndex.get(key)
        if (existing >= 0) return existing

        if (nodeCount == nodeLatE6.size) {
            nodeLatE6 = nodeLatE6.copyOf(nodeLatE6.size * 2)
            nodeLonE6 = nodeLonE6.copyOf(nodeLonE6.size * 2)
        }
        val index = nodeCount++
        nodeLatE6[index] = latE6
        nodeLonE6[index] = lonE6
        nodeIndex.put(key, index)

        if (lat < minLat) minLat = lat
        if (lat > maxLat) maxLat = lat
        if (lon < minLon) minLon = lon
        if (lon > maxLon) maxLon = lon
        return index
    }

    private fun addDirectedEdge(from: Int, to: Int, wayIndex: Int) {
        edgeFromList.add(from)
        edgeToList.add(to)
        edgeWayList.add(wayIndex)
    }

    /**
     * Grafni yakunlaydi: uzilib qolgan uchlarni ulaydi, CSR tuzilmasini va
     * fazoviy indeksni quradi. Shundan keyin graf faqat o'qish uchun ishlatiladi.
     */
    fun finish() {
        if (finished) return
        buildSpatialIndex()
        stitchDanglingEnds()
        buildCsr()
        finished = true
    }

    /** Tugunlarni katakchalarga joylaydi (eng yaqin tugunni tez topish uchun). */
    private fun buildSpatialIndex() {
        cellHead.clear()
        cellNext = IntArray(nodeCount) { -1 }
        for (node in 0 until nodeCount) {
            val key = cellKey(latOf(node), lonOf(node))
            val head = cellHead.get(key)
            cellNext[node] = head
            cellHead.put(key, node)
        }
    }

    /**
     * Mapsforge xarita fayllarida yo'llar "tile" chegaralarida kesilgan bo'ladi.
     * Ba'zan kesilgan uchlar bir-biriga aniq tushmaydi — natijada graf uzilib
     * qoladi. Shu sababli faqat bitta qo'shnisi bor tugunlarni yaqin atrofdagi
     * boshqa tugun bilan ulab qo'yamiz (maksimum 10 metr).
     */
    private fun stitchDanglingEnds() {
        if (nodeCount == 0) return
        // Har bir tugun uchun chiquvchi/kiruvchi qirralar soni va qo'shnisi
        val outCount = IntArray(nodeCount)
        val inCount = IntArray(nodeCount)
        val outNeighbour = IntArray(nodeCount) { -1 }
        val inNeighbour = IntArray(nodeCount) { -1 }
        for (i in 0 until edgeFromList.size) {
            val from = edgeFromList[i]
            val to = edgeToList[i]
            outCount[from]++
            inCount[to]++
            if (outNeighbour[from] < 0) outNeighbour[from] = to
            if (inNeighbour[to] < 0) inNeighbour[to] = from
        }

        val connectorWay = ways.size
        ways.add(
            RoadTags.RoadInfo(
                name = null, ref = null, roadClass = RoadTags.CLASS_SERVICE,
                speedKmh = 20, speedLimitKmh = 0,
                onewayForward = true, onewayBackward = true,
                toll = false, roundabout = false
            )
        )

        var stitched = 0
        for (node in 0 until nodeCount) {
            // Yo'lning ochiq uchi: yoki faqat kiruvchi/chiquvchi qirrasi bor,
            // yoki ikki tomonlama yo'lning oxirgi nuqtasi (bitta qo'shni).
            val isDeadEnd = when {
                outCount[node] + inCount[node] > 2 -> false
                outCount[node] == 0 || inCount[node] == 0 -> true
                else -> outNeighbour[node] == inNeighbour[node]
            }
            if (!isDeadEnd) continue
            val lat = latOf(node)
            val lon = lonOf(node)
            val candidate = findNearestNodeExcept(lat, lon, node, STITCH_DISTANCE_M)
            if (candidate >= 0) {
                addDirectedEdge(node, candidate, connectorWay)
                addDirectedEdge(candidate, node, connectorWay)
                stitched++
                if (stitched > MAX_STITCHES) break
            }
        }
    }

    private fun buildCsr() {
        val edgeTotal = edgeFromList.size
        csrStart = IntArray(nodeCount + 1)
        csrTarget = IntArray(edgeTotal)
        csrWay = IntArray(edgeTotal)
        csrLength = FloatArray(edgeTotal)

        // 1) har bir tugundan chiquvchi qirralar sonini sanaymiz
        for (i in 0 until edgeTotal) csrStart[edgeFromList[i] + 1]++
        // 2) prefiks yig'indi
        for (n in 1..nodeCount) csrStart[n] += csrStart[n - 1]
        // 3) to'ldirish
        val cursor = csrStart.copyOf()
        for (i in 0 until edgeTotal) {
            val from = edgeFromList[i]
            val to = edgeToList[i]
            val position = cursor[from]++
            csrTarget[position] = to
            csrWay[position] = edgeWayList[i]
            csrLength[position] = GeoUtils.fastDistance(
                latOf(from), lonOf(from), latOf(to), lonOf(to)
            ).toFloat()
        }
        edgeFromList.clear()
        edgeToList.clear()
        edgeWayList.clear()
    }

    // ------------------------------------------------------------------
    // O'qish (marshrut qidirish paytida)
    // ------------------------------------------------------------------

    fun latOf(node: Int): Double = nodeLatE6[node] / 1e6
    fun lonOf(node: Int): Double = nodeLonE6[node] / 1e6

    /** Tugundan chiquvchi qirralar diapazoni: [edgeBegin, edgeEnd). */
    fun edgeBegin(node: Int): Int = csrStart[node]
    fun edgeEnd(node: Int): Int = csrStart[node + 1]

    fun edgeTarget(edge: Int): Int = csrTarget[edge]
    fun edgeLength(edge: Int): Float = csrLength[edge]
    fun edgeWay(edge: Int): Int = csrWay[edge]
    fun wayInfo(wayIndex: Int): RoadTags.RoadInfo = ways[wayIndex]
    fun edgeInfo(edge: Int): RoadTags.RoadInfo = ways[csrWay[edge]]

    /** Nuqta graf hududida joylashganmi (chekka bilan)? */
    fun covers(lat: Double, lon: Double, marginDeg: Double = 0.0): Boolean =
        lat >= minLat - marginDeg && lat <= maxLat + marginDeg &&
                lon >= minLon - marginDeg && lon <= maxLon + marginDeg

    /**
     * Berilgan koordinataga eng yaqin graf tugunini topadi.
     * @return tugun indeksi yoki -1 (radius ichida yo'l topilmasa)
     */
    fun nearestNode(lat: Double, lon: Double, maxDistanceM: Double = 500.0): Int =
        findNearestNodeExcept(lat, lon, -1, maxDistanceM)

    private fun findNearestNodeExcept(
        lat: Double,
        lon: Double,
        exclude: Int,
        maxDistanceM: Double
    ): Int {
        if (nodeCount == 0 || cellNext.isEmpty()) return -1
        val cellMeters = CELL_SIZE_DEG * 111000.0
        val maxRing = max(1, ceil(maxDistanceM / cellMeters).toInt())
        val baseX = cellX(lon)
        val baseY = cellY(lat)

        var best = -1
        var bestDistance = maxDistanceM
        var bestRing = -1
        for (ring in 0..maxRing) {
            // Natija topilgandan keyin yana bitta halqa tekshiriladi:
            // qo'shni katakchada yanada yaqinroq tugun bo'lishi mumkin.
            if (best >= 0 && ring > bestRing + 1) break
            for (dx in -ring..ring) {
                for (dy in -ring..ring) {
                    // faqat halqaning chekkasini tekshiramiz
                    if (ring > 0 && abs(dx) != ring && abs(dy) != ring) continue
                    val key = packCell(baseX + dx, baseY + dy)
                    var node = cellHead.get(key)
                    while (node >= 0) {
                        if (node != exclude) {
                            val distance = GeoUtils.fastDistance(lat, lon, latOf(node), lonOf(node))
                            if (distance < bestDistance) {
                                bestDistance = distance
                                best = node
                                bestRing = ring
                            }
                        }
                        node = cellNext[node]
                    }
                }
            }
        }
        return best
    }

    private fun cellX(lon: Double): Int = Math.floor(lon / CELL_SIZE_DEG).toInt()
    private fun cellY(lat: Double): Int = Math.floor(lat / CELL_SIZE_DEG).toInt()
    private fun packCell(x: Int, y: Int): Long = (x.toLong() shl 32) or (y.toLong() and 0xFFFFFFFFL)
    private fun cellKey(lat: Double, lon: Double): Long = packCell(cellX(lon), cellY(lat))

    /** Graf hajmi haqida qisqa ma'lumot (jurnal uchun). */
    override fun toString(): String =
        "RoadGraph(tugunlar=$nodeCount, qirralar=$edgeCount, yo'llar=${ways.size}, " +
                "hudud=[$minLat,$minLon .. $maxLat,$maxLon], to'liqmas=$truncated)"

    companion object {
        private const val INITIAL_NODES = 1 shl 16
        private const val INITIAL_EDGES = 1 shl 17

        /** Telefon xotirasini himoya qilish uchun maksimal tugunlar soni. */
        const val MAX_NODES = 2_500_000

        /** Uzilgan uchlarni ulash masofasi (metr). */
        private const val STITCH_DISTANCE_M = 10.0
        private const val MAX_STITCHES = 200_000

        /** Fazoviy indeks katakchasi (taxminan 110 metr). */
        private const val CELL_SIZE_DEG = 0.001
    }
}
