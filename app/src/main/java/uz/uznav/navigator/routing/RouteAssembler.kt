package uz.uznav.navigator.routing

import uz.uznav.navigator.model.GeoPoint
import uz.uznav.navigator.model.ManeuverType
import uz.uznav.navigator.model.Place
import uz.uznav.navigator.model.Route
import uz.uznav.navigator.model.RouteProfile
import uz.uznav.navigator.model.RouteStep
import uz.uznav.navigator.util.GeoUtils
import kotlin.math.abs

/**
 * A* topgan yo'lni foydalanuvchi uchun tushunarli marshrutga aylantiradi:
 * geometriya, burilishlar ro'yxati, masofa, vaqt va tezlik cheklovlari.
 */
object RouteAssembler {

    /** Burilish deb hisoblanadigan eng kichik burchak (gradus). */
    private const val MIN_TURN_ANGLE = 23.0

    /** Yo'l nomi o'zgarganda burilish deb hisoblanadigan burchak. */
    private const val MIN_NAMED_TURN_ANGLE = 12.0

    /** Burilish yo'nalishini aniqlash uchun ko'rib chiqiladigan masofa (metr). */
    private const val BEARING_WINDOW_M = 25.0

    /** Juda yaqin burilishlar birlashtiriladi. */
    private const val MERGE_DISTANCE_M = 12.0

    fun assemble(
        graph: RoadGraph,
        path: AStarRouter.Path,
        profile: RouteProfile,
        destination: Place?,
        startOverride: GeoPoint? = null,
        endOverride: GeoPoint? = null
    ): Route {
        val nodes = path.nodes
        val edges = path.edges
        val points = ArrayList<GeoPoint>(nodes.size + 2)
        for (node in nodes) {
            points.add(GeoPoint(graph.latOf(node), graph.lonOf(node)))
        }
        // Foydalanuvchining aniq joylashuvi va manzilini marshrut uchlariga qo'shamiz
        if (startOverride != null && points.isNotEmpty() &&
            GeoUtils.distance(startOverride, points.first()) > 3.0
        ) {
            points.add(0, startOverride)
        }
        if (endOverride != null && points.isNotEmpty() &&
            GeoUtils.distance(endOverride, points.last()) > 3.0
        ) {
            points.add(endOverride)
        }

        val startShift = if (points.size > nodes.size &&
            startOverride != null && points.first() === startOverride
        ) 1 else 0

        // Har bir segment uchun tezlik cheklovi
        val speedLimits = IntArray(points.size)
        for (i in edges.indices) {
            val limit = graph.edgeInfo(edges[i]).speedLimitKmh
            val index = i + startShift
            if (index < speedLimits.size) speedLimits[index] = limit
        }
        if (startShift == 1 && speedLimits.size > 1) speedLimits[0] = speedLimits[1]

        val steps = buildSteps(graph, path, points, startShift)

        var distance = 0.0
        for (i in 1 until points.size) {
            distance += GeoUtils.distance(points[i - 1], points[i])
        }
        // Qo'shilgan uchlar uchun vaqtni ham moslashtiramiz
        val duration = if (path.lengthM > 1) path.durationS * (distance / path.lengthM)
        else distance / 11.0

        return Route(
            points = points,
            steps = steps,
            distanceM = distance,
            durationS = duration,
            profile = profile,
            speedLimits = speedLimits,
            destination = destination
        )
    }

    /**
     * Burilishlar ro'yxatini tuzadi.
     *
     * Har bir oraliq tugunda kiruvchi va chiquvchi yo'nalish burchagi
     * taqqoslanadi. Burchak katta bo'lsa yoki yo'l nomi o'zgarib, chorraha
     * bo'lsa — burilish ko'rsatmasi qo'shiladi.
     */
    private fun buildSteps(
        graph: RoadGraph,
        path: AStarRouter.Path,
        points: List<GeoPoint>,
        startShift: Int
    ): List<RouteStep> {
        val nodes = path.nodes
        val edges = path.edges
        val result = ArrayList<RouteStep>(32)
        if (points.size < 2) return result

        val firstRoad = if (edges.isNotEmpty()) graph.edgeInfo(edges[0]).displayName else null
        result.add(
            RouteStep(
                maneuver = ManeuverType.YOLGA_CHIQISH,
                pointIndex = 0,
                point = points.first(),
                roadName = firstRoad,
                distanceM = 0.0,
                durationS = 0.0
            )
        )

        var roundaboutExitCounter = 0
        var insideRoundabout = false
        var roundaboutEntryIndex = -1

        for (i in 1 until nodes.size - 1) {
            val incomingEdge = edges[i - 1]
            val outgoingEdge = edges[i]
            val incomingInfo = graph.edgeInfo(incomingEdge)
            val outgoingInfo = graph.edgeInfo(outgoingEdge)
            val pointIndex = i + startShift
            if (pointIndex >= points.size) break

            // --- Aylanma yo'l ---
            if (outgoingInfo.roundabout) {
                if (!insideRoundabout) {
                    insideRoundabout = true
                    roundaboutExitCounter = 0
                    roundaboutEntryIndex = pointIndex
                } else if (countBranches(graph, nodes[i]) > 2) {
                    // aylanma ichidagi har bir chiqish sanaladi
                    roundaboutExitCounter++
                }
                continue
            }
            if (insideRoundabout) {
                insideRoundabout = false
                roundaboutExitCounter++
                result.add(
                    RouteStep(
                        maneuver = ManeuverType.AYLANMA,
                        pointIndex = if (roundaboutEntryIndex >= 0) roundaboutEntryIndex else pointIndex,
                        point = points[if (roundaboutEntryIndex >= 0) roundaboutEntryIndex else pointIndex],
                        roadName = outgoingInfo.displayName,
                        distanceM = 0.0,
                        durationS = 0.0,
                        roundaboutExit = roundaboutExitCounter
                    )
                )
                continue
            }

            val incomingBearing = bearingBefore(points, pointIndex)
            val outgoingBearing = bearingAfter(points, pointIndex)
            if (incomingBearing.isNaN() || outgoingBearing.isNaN()) continue
            val angle = GeoUtils.normalizeAngle(outgoingBearing - incomingBearing)

            val nameChanged = incomingInfo.displayName != outgoingInfo.displayName
            val branches = countBranches(graph, nodes[i])
            val isJunction = branches > 2

            val needsInstruction = when {
                abs(angle) >= MIN_TURN_ANGLE -> true
                isJunction && nameChanged && abs(angle) >= MIN_NAMED_TURN_ANGLE -> true
                else -> false
            }
            if (!needsInstruction) continue

            // Juda yaqin joylashgan burilishlarni birlashtiramiz
            val previous = result.last()
            val distanceFromPrevious = distanceBetween(points, previous.pointIndex, pointIndex)
            if (distanceFromPrevious < MERGE_DISTANCE_M && previous.maneuver != ManeuverType.YOLGA_CHIQISH) {
                continue
            }

            result.add(
                RouteStep(
                    maneuver = maneuverFor(angle),
                    pointIndex = pointIndex,
                    point = points[pointIndex],
                    roadName = outgoingInfo.displayName,
                    distanceM = 0.0,
                    durationS = 0.0
                )
            )
        }

        result.add(
            RouteStep(
                maneuver = ManeuverType.MANZIL,
                pointIndex = points.size - 1,
                point = points.last(),
                roadName = null,
                distanceM = 0.0,
                durationS = 0.0
            )
        )

        return withDistances(result, points)
    }

    /** Bosqichlar orasidagi masofa va taxminiy vaqtni hisoblaydi. */
    private fun withDistances(steps: List<RouteStep>, points: List<GeoPoint>): List<RouteStep> {
        val cumulative = DoubleArray(points.size)
        for (i in 1 until points.size) {
            cumulative[i] = cumulative[i - 1] + GeoUtils.distance(points[i - 1], points[i])
        }
        val result = ArrayList<RouteStep>(steps.size)
        for (i in steps.indices) {
            val step = steps[i]
            val nextIndex = if (i + 1 < steps.size) steps[i + 1].pointIndex else points.size - 1
            val distance = (cumulative[nextIndex.coerceIn(0, points.size - 1)] -
                    cumulative[step.pointIndex.coerceIn(0, points.size - 1)]).coerceAtLeast(0.0)
            result.add(step.copy(distanceM = distance, durationS = distance / 11.0))
        }
        return result
    }

    private fun distanceBetween(points: List<GeoPoint>, fromIndex: Int, toIndex: Int): Double {
        var distance = 0.0
        var i = fromIndex.coerceAtLeast(0)
        while (i < toIndex && i + 1 < points.size) {
            distance += GeoUtils.distance(points[i], points[i + 1])
            i++
        }
        return distance
    }

    /** Tugunga ulangan turli yo'nalishlar soni (chorraha ekanini aniqlash uchun). */
    private fun countBranches(graph: RoadGraph, node: Int): Int {
        val neighbours = HashSet<Int>(6)
        for (edge in graph.edgeBegin(node) until graph.edgeEnd(node)) {
            neighbours.add(graph.edgeTarget(edge))
        }
        return neighbours.size + 1
    }

    /** Burilish nuqtasiga kelish yo'nalishi (oxirgi ~25 metr bo'yicha). */
    private fun bearingBefore(points: List<GeoPoint>, index: Int): Double {
        if (index <= 0) return Double.NaN
        var i = index - 1
        var distance = 0.0
        while (i > 0 && distance < BEARING_WINDOW_M) {
            distance += GeoUtils.distance(points[i], points[i + 1])
            if (distance >= BEARING_WINDOW_M) break
            i--
        }
        return GeoUtils.bearing(points[i], points[index])
    }

    /** Burilish nuqtasidan ketish yo'nalishi (keyingi ~25 metr bo'yicha). */
    private fun bearingAfter(points: List<GeoPoint>, index: Int): Double {
        if (index >= points.size - 1) return Double.NaN
        var i = index + 1
        var distance = 0.0
        while (i < points.size - 1 && distance < BEARING_WINDOW_M) {
            distance += GeoUtils.distance(points[i - 1], points[i])
            if (distance >= BEARING_WINDOW_M) break
            i++
        }
        return GeoUtils.bearing(points[index], points[i])
    }

    /** Burchakka qarab burilish turini aniqlaydi. */
    private fun maneuverFor(angle: Double): ManeuverType = when {
        angle >= 160 || angle <= -160 -> ManeuverType.ORQAGA_QAYTISH
        angle > 115 -> ManeuverType.KESKIN_ONGGA
        angle > 40 -> ManeuverType.ONGGA
        angle > MIN_NAMED_TURN_ANGLE -> ManeuverType.BIROZ_ONGGA
        angle < -115 -> ManeuverType.KESKIN_CHAPGA
        angle < -40 -> ManeuverType.CHAPGA
        angle < -MIN_NAMED_TURN_ANGLE -> ManeuverType.BIROZ_CHAPGA
        else -> ManeuverType.TOGRIGA
    }
}
