package uz.uznav.navigator.nav

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import uz.uznav.navigator.location.LocationEngine
import uz.uznav.navigator.model.GeoPoint
import uz.uznav.navigator.model.ManeuverType
import uz.uznav.navigator.model.Route
import uz.uznav.navigator.model.RouteStep
import uz.uznav.navigator.util.GeoUtils
import uz.uznav.navigator.util.UzFormat
import uz.uznav.navigator.voice.UzbekPhrases
import uz.uznav.navigator.voice.UzbekVoice
import kotlin.math.max

/**
 * Navigatsiya "miyasi".
 *
 * Har bir GPS o'lchovida:
 *  - joriy joylashuv marshrut chizig'iga yopishtiriladi (map matching);
 *  - qolgan masofa, vaqt va keyingi burilish hisoblanadi;
 *  - kerakli paytda o'zbekcha ovozli ko'rsatma beriladi;
 *  - marshrutdan chiqib ketilsa, qayta hisoblash so'raladi (internetsiz).
 */
class NavigationEngine(private val voice: UzbekVoice) {

    /** Navigatsiyaning joriy holati (UI shu ma'lumotni ko'rsatadi). */
    data class State(
        val active: Boolean = false,
        val route: Route? = null,
        /** Marshrutga yopishtirilgan joylashuv (xaritada mashina shu yerda turadi). */
        val snapped: GeoPoint? = null,
        val bearing: Float? = null,
        val nextStep: RouteStep? = null,
        val distanceToStepM: Double = 0.0,
        val remainingDistanceM: Double = 0.0,
        val remainingDurationS: Double = 0.0,
        val speedKmh: Int = 0,
        val speedLimitKmh: Int = 0,
        val offRoute: Boolean = false,
        val rerouting: Boolean = false,
        val arrived: Boolean = false
    ) {
        /** Yetib borish vaqti, masalan "14:35". */
        val etaText: String get() = UzFormat.eta(remainingDurationS)
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** Marshrutdan chiqib ketilganda chaqiriladi (yangi marshrut kerak). */
    var onRerouteNeeded: ((from: GeoPoint) -> Unit)? = null

    /** Manzilga yetib kelinganda chaqiriladi. */
    var onArrived: (() -> Unit)? = null

    private var route: Route? = null
    private var currentPointIndex = 0
    private var offRouteCounter = 0
    private var lastRerouteTime = 0L
    private var lastSpeedWarningTime = 0L

    /** Qaysi bosqichda qaysi masofa ogohlantirishi aytilganini eslab qolamiz. */
    private val announced = HashSet<String>()

    // ------------------------------------------------------------------
    // Boshqaruv
    // ------------------------------------------------------------------

    /** Navigatsiyani boshlaydi. */
    @Synchronized
    fun start(newRoute: Route, speakStart: Boolean = true) {
        route = newRoute
        currentPointIndex = 0
        offRouteCounter = 0
        announced.clear()
        _state.value = State(
            active = true,
            route = newRoute,
            nextStep = newRoute.steps.firstOrNull { it.maneuver != ManeuverType.YOLGA_CHIQISH },
            remainingDistanceM = newRoute.distanceM,
            remainingDurationS = newRoute.durationS
        )
        if (speakStart) voice.speak(UzbekPhrases.MARSHRUT_BOSHLANDI, flush = true)
    }

    /** Qayta hisoblangan marshrutni o'rnatadi (ovozsiz davom etadi). */
    @Synchronized
    fun replaceRoute(newRoute: Route) {
        route = newRoute
        currentPointIndex = 0
        offRouteCounter = 0
        announced.clear()
        _state.value = _state.value.copy(
            active = true,
            route = newRoute,
            rerouting = false,
            offRoute = false,
            remainingDistanceM = newRoute.distanceM,
            remainingDurationS = newRoute.durationS,
            nextStep = newRoute.steps.firstOrNull { it.maneuver != ManeuverType.YOLGA_CHIQISH }
        )
        voice.speak(UzbekPhrases.QAYTA_HISOBLANDI, flush = true)
    }

    /** Qayta hisoblash boshlanganini belgilaydi. */
    @Synchronized
    fun markRerouting() {
        _state.value = _state.value.copy(rerouting = true)
    }

    /** Navigatsiyani to'xtatadi. */
    @Synchronized
    fun stop(speak: Boolean = true) {
        if (!_state.value.active) return
        route = null
        announced.clear()
        _state.value = State(active = false)
        if (speak) voice.speak(UzbekPhrases.MARSHRUT_TOXTATILDI, flush = true)
    }

    val isActive: Boolean get() = _state.value.active

    // ------------------------------------------------------------------
    // GPS o'lchovini qayta ishlash
    // ------------------------------------------------------------------

    @Synchronized
    fun onLocation(fix: LocationEngine.Fix) {
        val activeRoute = route ?: return
        if (!_state.value.active) return
        if (activeRoute.points.size < 2) return

        // 1) Marshrutdagi eng yaqin segmentni topamiz
        val match = matchToRoute(activeRoute, fix.point)
        val speedKmh = UzFormat.speedKmh(fix.speedMps)

        // 2) Marshrutdan chiqib ketganmi?
        val threshold = max(OFF_ROUTE_BASE_M, fix.accuracyM * 2.0)
        if (match.distanceM > threshold) {
            offRouteCounter++
            if (offRouteCounter >= OFF_ROUTE_FIX_COUNT && canReroute()) {
                lastRerouteTime = System.currentTimeMillis()
                offRouteCounter = 0
                _state.value = _state.value.copy(offRoute = true, rerouting = true, speedKmh = speedKmh)
                voice.speak(UzbekPhrases.MARSHRUTDAN_CHIQDINGIZ, flush = true)
                Log.i(TAG, "Marshrutdan chiqildi, qayta hisoblash so'raldi")
                onRerouteNeeded?.invoke(fix.point)
            } else {
                _state.value = _state.value.copy(speedKmh = speedKmh, offRoute = true)
            }
            return
        }
        offRouteCounter = 0
        currentPointIndex = match.segmentIndex

        // 3) Qolgan masofa va vaqt
        val remaining = activeRoute.remainingDistanceFrom(match.segmentIndex, match.distanceIntoSegmentM)
        val remainingDuration = activeRoute.remainingDurationFor(remaining)

        // 4) Keyingi burilish
        val distanceAlong = activeRoute.cumulativeDistance[match.segmentIndex] + match.distanceIntoSegmentM
        val nextStep = findNextStep(activeRoute, distanceAlong)
        val distanceToStep = nextStep?.let {
            (activeRoute.cumulativeDistance[it.pointIndex.coerceIn(0, activeRoute.points.size - 1)] -
                    distanceAlong).coerceAtLeast(0.0)
        } ?: remaining

        val speedLimit = activeRoute.speedLimitAt(match.segmentIndex)

        _state.value = _state.value.copy(
            active = true,
            route = activeRoute,
            snapped = GeoPoint(match.lat, match.lon),
            bearing = fix.bearing ?: routeBearing(activeRoute, match.segmentIndex),
            nextStep = nextStep,
            distanceToStepM = distanceToStep,
            remainingDistanceM = remaining,
            remainingDurationS = remainingDuration,
            speedKmh = speedKmh,
            speedLimitKmh = speedLimit,
            offRoute = false,
            rerouting = false,
            arrived = false
        )

        // 5) Ovozli ko'rsatmalar
        if (nextStep != null) announceIfNeeded(activeRoute, nextStep, distanceToStep, fix.speedMps)
        checkSpeedWarning(speedKmh, speedLimit)

        // 6) Manzilga yetib keldikmi?
        if (remaining <= ARRIVAL_DISTANCE_M) {
            voice.speak(UzbekPhrases.MANZILGA_YETDINGIZ, flush = true)
            _state.value = _state.value.copy(arrived = true, active = false, remainingDistanceM = 0.0)
            route = null
            onArrived?.invoke()
        }
    }

    private fun canReroute(): Boolean =
        System.currentTimeMillis() - lastRerouteTime > REROUTE_COOLDOWN_MS

    // ------------------------------------------------------------------
    // Yordamchi hisoblar
    // ------------------------------------------------------------------

    private class Match(
        val segmentIndex: Int,
        val lat: Double,
        val lon: Double,
        val distanceM: Double,
        val distanceIntoSegmentM: Double
    )

    /**
     * Joylashuvni marshrutga yopishtiradi.
     * Tezlik uchun avval joriy nuqta atrofidagi segmentlar tekshiriladi,
     * topilmasa butun marshrut bo'ylab qidiriladi.
     */
    private fun matchToRoute(route: Route, point: GeoPoint): Match {
        val points = route.points
        val windowStart = (currentPointIndex - 5).coerceAtLeast(0)
        val windowEnd = (currentPointIndex + 80).coerceAtMost(points.size - 2)

        var best = searchSegments(route, point, windowStart, windowEnd)
        if (best == null || best.distanceM > OFF_ROUTE_BASE_M) {
            val global = searchSegments(route, point, 0, points.size - 2)
            if (global != null && (best == null || global.distanceM < best.distanceM)) {
                best = global
            }
        }
        return best ?: Match(0, point.lat, point.lon, Double.MAX_VALUE, 0.0)
    }

    private fun searchSegments(route: Route, point: GeoPoint, from: Int, to: Int): Match? {
        val points = route.points
        if (points.size < 2 || from > to) return null
        var best: Match? = null
        for (i in from..to) {
            val a = points[i]
            val b = points[i + 1]
            val projection = GeoUtils.projectOnSegment(point.lat, point.lon, a.lat, a.lon, b.lat, b.lon)
            if (best == null || projection.distanceM < best.distanceM) {
                val segmentLength = GeoUtils.distance(a, b)
                best = Match(
                    segmentIndex = i,
                    lat = projection.lat,
                    lon = projection.lon,
                    distanceM = projection.distanceM,
                    distanceIntoSegmentM = segmentLength * projection.t
                )
            }
        }
        return best
    }

    /** Marshrut bo'ylab keyingi burilishni topadi. */
    private fun findNextStep(route: Route, distanceAlong: Double): RouteStep? {
        for (step in route.steps) {
            if (step.maneuver == ManeuverType.YOLGA_CHIQISH) continue
            val stepDistance = route.cumulativeDistance[
                step.pointIndex.coerceIn(0, route.points.size - 1)
            ]
            if (stepDistance - distanceAlong > -5.0) return step
        }
        return route.steps.lastOrNull()
    }

    /** Marshrut yo'nalishi (GPS yo'nalish bermaganda ishlatiladi). */
    private fun routeBearing(route: Route, segmentIndex: Int): Float? {
        val points = route.points
        if (segmentIndex + 1 >= points.size) return null
        return GeoUtils.bearing(points[segmentIndex], points[segmentIndex + 1]).toFloat()
    }

    /**
     * Ovozli ko'rsatmani kerakli masofalarda bir martadan aytadi.
     * Masofalar tezlikka moslashadi: tez yurilganda oldinroq aytiladi.
     */
    private fun announceIfNeeded(
        route: Route,
        step: RouteStep,
        distanceToStep: Double,
        speedMps: Float
    ) {
        val stepKey = "${route.hashCode()}-${step.pointIndex}-${step.maneuver}"
        val speedKmh = speedMps * 3.6

        // Ogohlantirish masofalari (metr)
        val thresholds = when {
            speedKmh > 80 -> listOf(1500.0, 700.0, 250.0, 80.0)
            speedKmh > 50 -> listOf(800.0, 400.0, 150.0, 60.0)
            else -> listOf(400.0, 200.0, 80.0, 30.0)
        }

        for ((index, threshold) in thresholds.withIndex()) {
            val key = "$stepKey@$index"
            if (distanceToStep <= threshold && announced.add(key)) {
                // Faqat eng mos (eng yaqin) ogohlantirish aytiladi
                val isLast = index == thresholds.lastIndex
                val text = if (isLast) {
                    UzbekPhrases.spokenInstruction(step, 0.0, withRoadName = false)
                } else {
                    UzbekPhrases.spokenInstruction(step, distanceToStep)
                }
                voice.speak(text, flush = isLast)
                // Qolgan (uzoqroq) masofalarni ham "aytilgan" deb belgilaymiz
                for (j in 0 until index) announced.add("$stepKey@$j")
                break
            }
        }
    }

    /** Tezlik cheklovidan oshganda ogohlantirish (daqiqada bir marta). */
    private fun checkSpeedWarning(speedKmh: Int, limitKmh: Int) {
        if (limitKmh <= 0 || speedKmh <= limitKmh + SPEED_TOLERANCE_KMH) return
        val now = System.currentTimeMillis()
        if (now - lastSpeedWarningTime < SPEED_WARNING_INTERVAL_MS) return
        lastSpeedWarningTime = now
        voice.speak(UzbekPhrases.speedWarning(limitKmh), flush = false)
    }

    companion object {
        private const val TAG = "UzNav/Navigatsiya"

        /** Marshrutdan chiqib ketish masofasi (metr). */
        private const val OFF_ROUTE_BASE_M = 45.0

        /** Necha marta ketma-ket chetda bo'lsa, qayta hisoblash boshlanadi. */
        private const val OFF_ROUTE_FIX_COUNT = 3

        /** Qayta hisoblashlar orasidagi eng kichik vaqt. */
        private const val REROUTE_COOLDOWN_MS = 12_000L

        /** Manzilga yetib kelgan deb hisoblanadigan masofa. */
        private const val ARRIVAL_DISTANCE_M = 28.0

        private const val SPEED_TOLERANCE_KMH = 10
        private const val SPEED_WARNING_INTERVAL_MS = 60_000L
    }
}
