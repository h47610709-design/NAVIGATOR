package uz.uznav.navigator.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import uz.uznav.navigator.R
import uz.uznav.navigator.UzNavApp
import uz.uznav.navigator.databinding.ActivityMainBinding
import uz.uznav.navigator.location.LocationEngine
import uz.uznav.navigator.map.MapController
import uz.uznav.navigator.model.GeoPoint
import uz.uznav.navigator.model.ManeuverType
import uz.uznav.navigator.model.Place
import uz.uznav.navigator.model.PlaceKind
import uz.uznav.navigator.model.Route
import uz.uznav.navigator.nav.NavigationEngine
import uz.uznav.navigator.util.UzFormat
import uz.uznav.navigator.voice.UzbekPhrases

/**
 * Asosiy ekran: xarita, GPS joylashuv, qidiruv, marshrut tanlash va navigatsiya.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var app: UzNavApp
    private lateinit var mapController: MapController

    private val viewModel: MainViewModel by viewModels()

    /** Xarita foydalanuvchi joylashuvini kuzatib borsinmi? */
    private var followLocation = true

    private val searchLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            PlaceIntent.read(result.data)?.let { place -> onDestinationChosen(place) }
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        val locationGranted = granted[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                granted[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (locationGranted) {
            app.locationEngine.start()
        } else {
            showMessage("Joylashuv ruxsati berilmadi. Navigatsiya ishlashi uchun ruxsat kerak.")
        }
    }

    // ------------------------------------------------------------------
    // Hayotiy sikl
    // ------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        app = UzNavApp.from(this)

        mapController = MapController(this, binding.xarita)
        mapController.setup()
        mapController.onLongPress = { point -> askNavigateToPoint(point) }

        restoreLastPosition()
        loadMapFile()
        setupButtons()
        observeState()
        requestPermissionsIfNeeded()

        if (!app.mapManager.hasAnyMap()) {
            suggestMapDownload()
        }
    }

    override fun onStart() {
        super.onStart()
        app.locationEngine.start()
    }

    override fun onResume() {
        super.onResume()
        // Xarita yuklangan bo'lishi mumkin — qayta tekshiramiz
        if (!mapController.isMapLoaded && app.mapManager.hasAnyMap()) {
            loadMapFile()
        }
        applyKeepScreenOn()
    }

    override fun onStop() {
        super.onStop()
        saveLastPosition()
        // Navigatsiya ketayotgan bo'lsa GPS xizmat orqali davom etadi
        if (!app.navigation.engine.isActive) {
            app.locationEngine.stop()
        }
    }

    override fun onDestroy() {
        mapController.destroy()
        super.onDestroy()
    }

    // ------------------------------------------------------------------
    // Sozlash
    // ------------------------------------------------------------------

    private fun isNightMode(): Boolean =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES

    private fun loadMapFile() {
        val store = app.mapManager.store()
        val loaded = mapController.loadMap(store, isNightMode())
        binding.xaritaYoqMatni.isVisible = !loaded
        if (!loaded) {
            binding.xaritaYoqMatni.text =
                "Xarita yuklanmagan.\n\"Xaritalar\" bo'limidan O'zbekiston xaritasini yuklab oling."
        }
    }

    private fun restoreLastPosition() {
        val point = GeoPoint(app.prefs.lastLatitude, app.prefs.lastLongitude)
        mapController.moveTo(point, app.prefs.lastZoom, animate = false)
    }

    private fun saveLastPosition() {
        runCatching {
            val center = mapController.center()
            app.prefs.lastLatitude = center.lat
            app.prefs.lastLongitude = center.lon
            app.prefs.lastZoom = mapController.zoomLevel()
        }
    }

    private fun requestPermissionsIfNeeded() {
        val needed = ArrayList<String>(3)
        if (!app.locationEngine.hasPermission()) {
            needed.add(Manifest.permission.ACCESS_FINE_LOCATION)
            needed.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (needed.isNotEmpty()) {
            permissionLauncher.launch(needed.toTypedArray())
        } else {
            app.locationEngine.start()
        }
    }

    private fun applyKeepScreenOn() {
        if (app.prefs.keepScreenOn && app.navigation.engine.isActive) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun setupButtons() {
        binding.qidiruvPaneli.setOnClickListener { openSearch() }
        binding.qayergaTugma.setOnClickListener { openSearch() }

        binding.menyuTugma.setOnClickListener { view -> showMenu(view) }

        binding.kattalashtirishTugma.setOnClickListener { mapController.zoomIn() }
        binding.kichiklashtirishTugma.setOnClickListener { mapController.zoomOut() }

        binding.joylashuvTugma.setOnClickListener {
            val fix = app.locationEngine.location.value
            if (fix == null) {
                showMessage("GPS signali topilmadi")
                if (!app.locationEngine.isLocationEnabled()) {
                    showEnableLocationDialog()
                }
            } else {
                followLocation = true
                mapController.moveTo(fix.point, zoom = 17)
            }
        }

        binding.uyTugma.setOnClickListener {
            val home = app.let { uz.uznav.navigator.data.FavoritesStore.get(this).home() }
            if (home == null) showMessage("\"Uy\" manzili saqlanmagan")
            else onDestinationChosen(home.place.copy(name = home.title))
        }
        binding.ishTugma.setOnClickListener {
            val work = uz.uznav.navigator.data.FavoritesStore.get(this).work()
            if (work == null) showMessage("\"Ish\" manzili saqlanmagan")
            else onDestinationChosen(work.place.copy(name = work.title))
        }

        binding.marshrutBoshlashTugma.setOnClickListener { startNavigation() }
        binding.marshrutBekorTugma.setOnClickListener {
            viewModel.clearRoute()
            mapController.showRoute(null)
            mapController.setDestination(null)
        }
        binding.navigatsiyaToxtatishTugma.setOnClickListener { stopNavigation() }

        binding.variantTez.setOnClickListener { viewModel.selectRoute(indexOfProfileButton(0)) }
        binding.variantQisqa.setOnClickListener { viewModel.selectRoute(indexOfProfileButton(1)) }
        binding.variantPulliksiz.setOnClickListener { viewModel.selectRoute(indexOfProfileButton(2)) }

        binding.hisobBekorTugma.setOnClickListener { viewModel.cancelCalculation() }
    }

    /** Tugmalar tartibi bo'yicha marshrut variantini topadi. */
    private fun indexOfProfileButton(buttonIndex: Int): Int {
        val routes = viewModel.state.value.routes
        val profile = when (buttonIndex) {
            0 -> uz.uznav.navigator.model.RouteProfile.ENG_TEZ
            1 -> uz.uznav.navigator.model.RouteProfile.ENG_QISQA
            else -> uz.uznav.navigator.model.RouteProfile.PULLIK_YOQ
        }
        val index = routes.indexOfFirst { it.profile == profile }
        return if (index >= 0) index else viewModel.state.value.selectedRouteIndex
    }

    private fun showMenu(anchor: View) {
        val menu = PopupMenu(this, anchor)
        menu.menu.add(0, 1, 0, "Xaritalar")
        menu.menu.add(0, 2, 1, "Sevimli joylar")
        menu.menu.add(0, 3, 2, "Qidiruv tarixi")
        menu.menu.add(0, 4, 3, "Sozlamalar")
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> startActivity(Intent(this, MapsActivity::class.java))
                2 -> searchLauncher.launch(Intent(this, FavoritesActivity::class.java))
                3 -> searchLauncher.launch(Intent(this, HistoryActivity::class.java))
                4 -> startActivity(Intent(this, SettingsActivity::class.java))
            }
            true
        }
        menu.show()
    }

    private fun openSearch() {
        searchLauncher.launch(Intent(this, SearchActivity::class.java))
    }

    // ------------------------------------------------------------------
    // Holatni kuzatish
    // ------------------------------------------------------------------

    private fun observeState() {
        // GPS
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                app.locationEngine.location.collect { fix -> onLocationUpdate(fix) }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                app.locationEngine.status.collect { status -> showGpsStatus(status) }
            }
        }
        // Marshrut hisoblash holati
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { state -> renderRouteState(state) }
            }
        }
        // Navigatsiya holati
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.navigationState.collect { state -> renderNavigationState(state) }
            }
        }
    }

    private fun onLocationUpdate(fix: LocationEngine.Fix?) {
        if (fix == null) return
        val navigating = app.navigation.engine.isActive
        val navState = viewModel.navigationState.value
        val point = if (navigating && navState.snapped != null) navState.snapped else fix.point
        val bearing = navState.bearing ?: fix.bearing

        val rotateMap = navigating && app.prefs.rotateMapWithBearing && bearing != null
        mapController.updateVehicle(point, bearing, rotateIcon = !rotateMap)
        if (rotateMap && bearing != null) {
            mapController.setRotation(bearing)
        }

        if (followLocation || navigating) {
            mapController.moveTo(point, zoom = if (navigating) 17 else null)
        }

        binding.tezlikQiymati.text = UzFormat.speedKmh(fix.speedMps).toString()
    }

    private fun showGpsStatus(status: LocationEngine.GpsStatus) {
        val (text, color) = when (status) {
            LocationEngine.GpsStatus.YAXSHI -> "GPS: yaxshi" to R.color.gps_yaxshi
            LocationEngine.GpsStatus.QIDIRILMOQDA -> "GPS: qidirilmoqda…" to R.color.gps_qidirilmoqda
            LocationEngine.GpsStatus.OCHIRILGAN -> "GPS: o'chirilgan" to R.color.gps_yoq
        }
        binding.gpsHolati.text = text
        binding.gpsHolati.setTextColor(getColor(color))
    }

    private fun renderRouteState(state: MainViewModel.UiState) {
        // Hisoblash jarayoni
        binding.hisoblashPaneli.isVisible = state.calculating
        binding.hisoblashMatni.text = state.progressText
        binding.hisoblashDarajasi.progress = state.progressPercent

        // Xato va xabarlar
        state.error?.let {
            showMessage(it)
            viewModel.consumeMessages()
        }
        state.message?.let {
            showMessage(it)
            viewModel.consumeMessages()
        }

        val navigating = viewModel.navigationState.value.active
        binding.marshrutPaneli.isVisible = state.showPreview && !navigating
        binding.boshlangichPanel.isVisible = !state.showPreview && !navigating && !state.calculating

        if (state.showPreview) {
            renderRouteOptions(state)
            state.selectedRoute?.let { route ->
                mapController.showRoute(route)
                mapController.setDestination(route.destination?.point)
                if (!navigating) mapController.fitRoute(route)
            }
        }
    }

    private fun renderRouteOptions(state: MainViewModel.UiState) {
        val routes = state.routes
        binding.manzilNomi.text = state.destination?.name ?: "Tanlangan manzil"

        fun apply(button: com.google.android.material.button.MaterialButton, profile: uz.uznav.navigator.model.RouteProfile) {
            val route = routes.firstOrNull { it.profile == profile }
            if (route == null) {
                button.isEnabled = false
                button.text = "${profile.titleUz}\n—"
                return
            }
            button.isEnabled = true
            button.text = "${profile.titleUz}\n${UzFormat.duration(route.durationS)} • ${UzFormat.distance(route.distanceM)}"
            button.isChecked = state.selectedRoute?.profile == profile
        }

        apply(binding.variantTez, uz.uznav.navigator.model.RouteProfile.ENG_TEZ)
        apply(binding.variantQisqa, uz.uznav.navigator.model.RouteProfile.ENG_QISQA)
        apply(binding.variantPulliksiz, uz.uznav.navigator.model.RouteProfile.PULLIK_YOQ)

        state.selectedRoute?.let { route ->
            binding.marshrutXulosa.text =
                "${UzFormat.distance(route.distanceM)} • ${UzFormat.duration(route.durationS)} • " +
                        "Yetib borish: ${UzFormat.eta(route.durationS)}"
        }
    }

    private fun renderNavigationState(state: NavigationEngine.State) {
        binding.navigatsiyaPaneli.isVisible = state.active
        binding.burilishPaneli.isVisible = state.active && state.nextStep != null
        applyKeepScreenOn()

        if (!state.active) {
            mapController.setNavigationView(false)
            if (state.arrived) {
                mapController.showRoute(null)
                mapController.setDestination(null)
                viewModel.clearRoute()
                showMessage("Manzilingizga yetib keldingiz")
            }
            return
        }

        mapController.setNavigationView(true)
        binding.boshlangichPanel.isVisible = false
        binding.marshrutPaneli.isVisible = false

        state.route?.let { route -> ensureRouteDrawn(route) }

        val step = state.nextStep
        if (step != null) {
            binding.burilishMasofasi.text = UzFormat.distance(state.distanceToStepM)
            binding.burilishMatni.text = UzbekPhrases.maneuverTitle(step)
            val road = UzbekPhrases.roadLine(step)
            binding.burilishYoli.isVisible = road.isNotBlank()
            binding.burilishYoli.text = road
            binding.burilishBelgisi.setImageResource(maneuverIcon(step.maneuver))
        }

        binding.qolganMasofa.text = UzFormat.distance(state.remainingDistanceM)
        binding.qolganVaqt.text = UzFormat.duration(state.remainingDurationS)
        binding.yetibBorishVaqti.text = state.etaText
        binding.tezlikQiymati.text = state.speedKmh.toString()

        val limit = state.speedLimitKmh
        binding.tezlikCheklovi.isVisible = limit > 0
        binding.tezlikCheklovi.text = limit.toString()
        val tezlikOshgan = limit > 0 && state.speedKmh > limit + 10
        binding.tezlikQiymati.setTextColor(
            getColor(if (tezlikOshgan) R.color.tezlik_oshgan else R.color.matn_asosiy)
        )

        binding.qaytaHisoblashMatni.isVisible = state.rerouting
        if (state.rerouting) {
            binding.qaytaHisoblashMatni.text = "Qayta marshrut hisoblanmoqda…"
        }
    }

    private var drawnRouteHash: Int = 0

    /** Marshrut o'zgarganda (masalan, qayta hisoblangandan keyin) chizigini yangilaydi. */
    private fun ensureRouteDrawn(route: Route) {
        val hash = System.identityHashCode(route)
        if (hash == drawnRouteHash) return
        drawnRouteHash = hash
        mapController.showRoute(route)
        mapController.setDestination(route.destination?.point)
    }

    private fun maneuverIcon(type: ManeuverType): Int = when (type) {
        ManeuverType.CHAPGA, ManeuverType.KESKIN_CHAPGA -> R.drawable.ic_burilish_chap
        ManeuverType.BIROZ_CHAPGA -> R.drawable.ic_burilish_biroz_chap
        ManeuverType.ONGGA, ManeuverType.KESKIN_ONGGA -> R.drawable.ic_burilish_ong
        ManeuverType.BIROZ_ONGGA -> R.drawable.ic_burilish_biroz_ong
        ManeuverType.ORQAGA_QAYTISH -> R.drawable.ic_burilish_orqaga
        ManeuverType.AYLANMA -> R.drawable.ic_aylanma
        ManeuverType.MANZIL -> R.drawable.ic_manzil_belgisi
        else -> R.drawable.ic_togriga
    }

    // ------------------------------------------------------------------
    // Harakatlar
    // ------------------------------------------------------------------

    private fun onDestinationChosen(place: Place) {
        followLocation = false
        mapController.setDestination(place.point)
        val from = app.locationEngine.location.value?.point
        if (from == null) {
            showMessage("GPS signali topilmadi. Joylashuvingiz aniqlangach qayta urinib ko'ring.")
            mapController.moveTo(place.point, zoom = 16)
            return
        }
        viewModel.chooseDestination(place, from)
    }

    private fun askNavigateToPoint(point: GeoPoint) {
        AlertDialog.Builder(this)
            .setTitle("Shu yerga borasizmi?")
            .setMessage(
                "Tanlangan nuqta: ${String.format("%.5f", point.lat)}, ${String.format("%.5f", point.lon)}"
            )
            .setPositiveButton("Marshrut tuzish") { _, _ ->
                onDestinationChosen(
                    Place(
                        name = "Xaritadagi nuqta",
                        address = "Xaritada tanlangan joy",
                        point = point,
                        kind = PlaceKind.XARITA
                    )
                )
            }
            .setNegativeButton("Bekor qilish", null)
            .show()
    }

    private fun startNavigation() {
        val route = viewModel.state.value.selectedRoute
        if (route == null) {
            showMessage("Avval marshrutni tanlang")
            return
        }
        if (!app.locationEngine.hasPermission()) {
            requestPermissionsIfNeeded()
            return
        }
        followLocation = true
        viewModel.startNavigation()
        mapController.showRoute(route)
        drawnRouteHash = System.identityHashCode(route)
    }

    private fun stopNavigation() {
        viewModel.stopNavigation()
        mapController.showRoute(null)
        mapController.setDestination(null)
        mapController.setRotation(0f)
        mapController.setNavigationView(false)
        drawnRouteHash = 0
    }

    private fun suggestMapDownload() {
        AlertDialog.Builder(this)
            .setTitle("Xarita yuklanmagan")
            .setMessage(
                "Navigatsiya internetsiz ishlashi uchun O'zbekiston xaritasini " +
                        "bir marta yuklab olish kerak (taxminan 140 MB).\n\n" +
                        "Yuklab olgandan keyin internet umuman kerak bo'lmaydi."
            )
            .setPositiveButton("Xaritani yuklash") { _, _ ->
                startActivity(Intent(this, MapsActivity::class.java))
            }
            .setNegativeButton("Keyinroq", null)
            .show()
    }

    private fun showEnableLocationDialog() {
        AlertDialog.Builder(this)
            .setTitle("Joylashuv o'chirilgan")
            .setMessage("Navigatsiya uchun telefon sozlamalarida GPS (joylashuv) ni yoqing.")
            .setPositiveButton("Sozlamalar") { _, _ ->
                runCatching {
                    startActivity(Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                }
            }
            .setNegativeButton("Yopish", null)
            .show()
    }

    private fun showMessage(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
}
