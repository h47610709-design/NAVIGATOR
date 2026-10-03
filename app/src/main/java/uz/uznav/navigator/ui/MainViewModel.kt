package uz.uznav.navigator.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import uz.uznav.navigator.UzNavApp
import uz.uznav.navigator.data.HistoryStore
import uz.uznav.navigator.model.GeoPoint
import uz.uznav.navigator.model.Place
import uz.uznav.navigator.model.Route
import uz.uznav.navigator.model.RouteProfile
import uz.uznav.navigator.routing.Router

/**
 * Asosiy ekran mantig'i: manzil tanlash, marshrut variantlarini hisoblash,
 * navigatsiyani boshlash va to'xtatish.
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    /** Asosiy ekran holati. */
    data class UiState(
        val destination: Place? = null,
        val routes: List<Route> = emptyList(),
        val selectedRouteIndex: Int = 0,
        val calculating: Boolean = false,
        val progressText: String = "",
        val progressPercent: Int = 0,
        val error: String? = null,
        val message: String? = null
    ) {
        val selectedRoute: Route?
            get() = routes.getOrNull(selectedRouteIndex)

        /** Marshrut tanlash paneli ko'rinsinmi? */
        val showPreview: Boolean get() = routes.isNotEmpty()
    }

    private val app: UzNavApp get() = UzNavApp.from(getApplication())

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** Navigatsiya holati (burilishlar, masofa, ETA). */
    val navigationState = app.navigation.engine.state

    private var routeJob: Job? = null

    /** Barcha variantlar hisoblansinmi (marshrutni tanlash uchun). */
    private val profiles = listOf(RouteProfile.ENG_TEZ, RouteProfile.ENG_QISQA, RouteProfile.PULLIK_YOQ)

    init {
        // Navigatsiya xizmatidan kelgan xabarlarni ekranga chiqaramiz
        viewModelScope.launch {
            app.navigation.messages.collect { message ->
                _state.value = _state.value.copy(message = message)
            }
        }
    }

    /** Manzilni tanlab, marshrut variantlarini hisoblaydi. */
    fun chooseDestination(place: Place, from: GeoPoint?) {
        HistoryStore.get(getApplication()).add(place)
        _state.value = _state.value.copy(
            destination = place,
            routes = emptyList(),
            selectedRouteIndex = 0,
            error = null
        )
        calculateRoutes(place, from)
    }

    /** Marshrutni (qayta) hisoblaydi. */
    fun calculateRoutes(place: Place, from: GeoPoint?) {
        val start = from
        if (start == null) {
            _state.value = _state.value.copy(
                error = "GPS signali topilmadi. Joylashuvingiz aniqlanmaguncha marshrut hisoblab bo'lmaydi."
            )
            return
        }

        routeJob?.cancel()
        _state.value = _state.value.copy(
            calculating = true,
            progressText = "Marshrut tayyorlanmoqda…",
            progressPercent = 0,
            error = null
        )

        routeJob = viewModelScope.launch {
            val result = app.router.calculate(
                start = start,
                destination = place,
                profiles = profiles,
                progress = { message, percent ->
                    _state.value = _state.value.copy(progressText = message, progressPercent = percent)
                }
            )
            when (result) {
                is Router.Result.Ok -> {
                    val preferred = app.prefs.routeProfile
                    val index = result.routes.indexOfFirst { it.profile == preferred }.coerceAtLeast(0)
                    _state.value = _state.value.copy(
                        calculating = false,
                        routes = result.routes,
                        selectedRouteIndex = index,
                        destination = place,
                        error = null
                    )
                }

                is Router.Result.Error -> {
                    _state.value = _state.value.copy(
                        calculating = false,
                        routes = emptyList(),
                        error = result.message
                    )
                }
            }
        }
    }

    /** Marshrut variantini tanlaydi. */
    fun selectRoute(index: Int) {
        val routes = _state.value.routes
        if (index !in routes.indices) return
        app.prefs.routeProfile = routes[index].profile
        _state.value = _state.value.copy(selectedRouteIndex = index)
    }

    /** Navigatsiyani boshlaydi. */
    fun startNavigation() {
        val route = _state.value.selectedRoute ?: return
        app.navigation.start(route)
    }

    /** Navigatsiyani to'xtatadi va marshrutni tozalaydi. */
    fun stopNavigation() {
        app.navigation.stop()
        clearRoute()
    }

    /** Marshrutni tozalaydi. */
    fun clearRoute() {
        routeJob?.cancel()
        routeJob = null
        _state.value = UiState()
    }

    /** Hisoblashni bekor qiladi. */
    fun cancelCalculation() {
        routeJob?.cancel()
        routeJob = null
        _state.value = _state.value.copy(calculating = false, progressText = "")
    }

    /** Ko'rsatilgan xabar/xatoni o'chiradi. */
    fun consumeMessages() {
        if (_state.value.error != null || _state.value.message != null) {
            _state.value = _state.value.copy(error = null, message = null)
        }
    }

    override fun onCleared() {
        routeJob?.cancel()
        super.onCleared()
    }
}
