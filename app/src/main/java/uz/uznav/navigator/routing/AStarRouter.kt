package uz.uznav.navigator.routing

import uz.uznav.navigator.model.RouteProfile
import uz.uznav.navigator.util.GeoUtils
import uz.uznav.navigator.util.MinHeap

/**
 * A* algoritmi asosidagi offline marshrut qidiruvi.
 *
 * Narx funksiyasi tanlangan marshrut variantiga bog'liq:
 *  - ENG_TEZ: yurish vaqti (soniya)
 *  - ENG_QISQA: masofa (metr)
 *  - PULLIK_YOQ: vaqt, lekin pullik yo'llarga katta jarima
 */
class AStarRouter(
    private val graph: RoadGraph,
    private val profile: RouteProfile
) {

    /** Topilgan yo'l: tugunlar va ular orasidagi qirralar ketma-ketligi. */
    class Path(
        val nodes: IntArray,
        val edges: IntArray,
        val lengthM: Double,
        val durationS: Double
    )

    /**
     * [startNode] dan [targetNode] gacha yo'l qidiradi.
     *
     * @param isCancelled qidiruvni to'xtatish uchun tekshiruv
     * @return topilgan yo'l yoki null
     */
    fun findPath(
        startNode: Int,
        targetNode: Int,
        isCancelled: () -> Boolean = { false }
    ): Path? {
        if (startNode < 0 || targetNode < 0) return null
        if (startNode == targetNode) return Path(intArrayOf(startNode), IntArray(0), 0.0, 0.0)
        if (graph.nodeCount == 0) return null

        val nodeCount = graph.nodeCount
        val gScore = DoubleArray(nodeCount) { Double.MAX_VALUE }
        val parentNode = IntArray(nodeCount) { -1 }
        val parentEdge = IntArray(nodeCount) { -1 }
        val closed = BooleanArray(nodeCount)

        val targetLat = graph.latOf(targetNode)
        val targetLon = graph.lonOf(targetNode)

        val heap = MinHeap(1 shl 14)
        gScore[startNode] = 0.0
        heap.push(heuristic(startNode, targetLat, targetLon), startNode)

        var visited = 0
        while (!heap.isEmpty) {
            val current = heap.pop()
            if (closed[current]) continue
            closed[current] = true
            visited++

            if (current == targetNode) {
                return buildPath(startNode, targetNode, parentNode, parentEdge)
            }
            // Har 4096 qadamda bekor qilinganini tekshiramiz
            if (visited and 0xFFF == 0 && isCancelled()) return null
            if (visited > MAX_VISITED) return null

            val begin = graph.edgeBegin(current)
            val end = graph.edgeEnd(current)
            for (edge in begin until end) {
                val next = graph.edgeTarget(edge)
                if (closed[next]) continue
                val cost = edgeCost(edge)
                val tentative = gScore[current] + cost
                if (tentative < gScore[next]) {
                    gScore[next] = tentative
                    parentNode[next] = current
                    parentEdge[next] = edge
                    heap.push(tentative + heuristic(next, targetLat, targetLon), next)
                }
            }
        }
        return null
    }

    /** Qirra narxi (soniya yoki metr). */
    private fun edgeCost(edge: Int): Double {
        val length = graph.edgeLength(edge).toDouble()
        val info = graph.edgeInfo(edge)
        var cost = if (profile.optimizeTime) {
            val speedMps = (info.speedKmh.coerceAtLeast(5)) / 3.6
            length / speedMps
        } else {
            length
        }
        // Pullik yo'llardan qochish
        if (profile.avoidToll && info.toll) cost *= TOLL_PENALTY
        // Xizmat yo'llari va tuproq yo'llar faqat zarurat bo'lganda tanlansin
        when (info.roadClass) {
            RoadTags.CLASS_SERVICE -> cost *= 1.6
            RoadTags.CLASS_TRACK -> cost *= 2.5
            RoadTags.CLASS_LIVING_STREET -> cost *= 1.4
        }
        return cost
    }

    /** Qolgan masofa bo'yicha optimistik baho. */
    private fun heuristic(node: Int, targetLat: Double, targetLon: Double): Double {
        val distance = GeoUtils.fastDistance(
            graph.latOf(node), graph.lonOf(node), targetLat, targetLon
        )
        return if (profile.optimizeTime) {
            (distance / MAX_SPEED_MPS) * HEURISTIC_WEIGHT
        } else {
            distance * HEURISTIC_WEIGHT
        }
    }

    private fun buildPath(
        startNode: Int,
        targetNode: Int,
        parentNode: IntArray,
        parentEdge: IntArray
    ): Path {
        // Oxiridan boshlab orqaga yuramiz
        val nodesReversed = ArrayList<Int>(256)
        val edgesReversed = ArrayList<Int>(256)
        var current = targetNode
        var lengthM = 0.0
        var durationS = 0.0
        while (current != startNode) {
            val edge = parentEdge[current]
            val previous = parentNode[current]
            if (edge < 0 || previous < 0) break
            nodesReversed.add(current)
            edgesReversed.add(edge)
            val length = graph.edgeLength(edge).toDouble()
            lengthM += length
            durationS += length / ((graph.edgeInfo(edge).speedKmh.coerceAtLeast(5)) / 3.6)
            current = previous
        }
        nodesReversed.add(startNode)

        val nodes = IntArray(nodesReversed.size)
        for (i in nodes.indices) nodes[i] = nodesReversed[nodes.size - 1 - i]
        val edges = IntArray(edgesReversed.size)
        for (i in edges.indices) edges[i] = edgesReversed[edges.size - 1 - i]

        return Path(nodes, edges, lengthM, durationS)
    }

    companion object {
        /** Eng yuqori hisobiy tezlik (m/s) — evristika uchun (110 km/soat). */
        private const val MAX_SPEED_MPS = 30.5

        /**
         * Evristika koeffitsiyenti. 1.0 — matematik jihatdan eng aniq natija,
         * 1.1 — biroz tezroq qidiruv (telefon uchun muhim), natija deyarli bir xil.
         */
        private const val HEURISTIC_WEIGHT = 1.08

        private const val TOLL_PENALTY = 12.0

        /** Qidiruv cheksiz cho'zilib ketmasligi uchun chegara. */
        private const val MAX_VISITED = 4_000_000
    }
}
