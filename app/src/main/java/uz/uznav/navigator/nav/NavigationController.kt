package uz.uznav.navigator.nav

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import uz.uznav.navigator.data.AppPrefs
import uz.uznav.navigator.location.LocationEngine
import uz.uznav.navigator.model.GeoPoint
import uz.uznav.navigator.model.Route
import uz.uznav.navigator.routing.Router
import uz.uznav.navigator.voice.UzbekVoice

/**
 * Navigatsiyani boshqaruvchi: GPS oqimini [NavigationEngine] ga uzatadi,
 * marshrutdan chiqib ketilganda offline qayta hisoblashni ishga tushiradi
 * va oldingi plandagi xizmatni (notification) yoqib-o'chiradi.
 */
class NavigationController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val locationEngine: LocationEngine,
    private val router: Router,
    private val voice: UzbekVoice,
    private val prefs: AppPrefs
) {

    /** Navigatsiya holati (UI shu oqimni kuzatadi). */
    val engine = NavigationEngine(voice)

    /** Foydalanuvchiga ko'rsatiladigan qisqa xabarlar (o'zbekcha). */
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private var rerouteJob: Job? = null

    init {
        // GPS oqimini navigatsiya "miyasi"ga ulaymiz
        scope.launch {
            locationEngine.location.collect { fix ->
                if (fix != null && engine.isActive) {
                    engine.onLocation(fix)
                }
            }
        }

        engine.onRerouteNeeded = { from -> reroute(from) }
        engine.onArrived = {
            NavigationService.stop(context)
            _messages.tryEmit("Manzilingizga yetib keldingiz")
        }
    }

    /** Navigatsiyani boshlaydi. */
    fun start(route: Route) {
        voice.enabled = prefs.voiceEnabled
        locationEngine.start()
        engine.start(route)
        NavigationService.start(context)
    }

    /** Navigatsiyani to'xtatadi. */
    fun stop() {
        rerouteJob?.cancel()
        rerouteJob = null
        engine.stop()
        NavigationService.stop(context)
    }

    /**
     * Marshrutdan chiqib ketilganda yangi marshrutni hisoblaydi.
     * Butun jarayon offline — keshdagi yo'l grafidan foydalaniladi.
     */
    private fun reroute(from: GeoPoint) {
        val destination = engine.state.value.route?.destination
        if (destination == null) {
            Log.w(TAG, "Qayta hisoblash uchun manzil yo'q")
            return
        }
        if (rerouteJob?.isActive == true) return

        engine.markRerouting()
        rerouteJob = scope.launch {
            val profile = engine.state.value.route?.profile ?: prefs.routeProfile
            when (val result = router.calculate(from, destination, listOf(profile))) {
                is Router.Result.Ok -> {
                    val route = result.routes.firstOrNull()
                    if (route != null) {
                        engine.replaceRoute(route)
                    } else {
                        _messages.tryEmit("Yangi marshrut topilmadi")
                    }
                }

                is Router.Result.Error -> {
                    Log.w(TAG, "Qayta hisoblash xatosi: ${result.message}")
                    _messages.tryEmit(result.message)
                }
            }
        }
    }

    companion object {
        private const val TAG = "UzNav/Boshqaruv"
    }
}
