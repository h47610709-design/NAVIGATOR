package uz.uznav.navigator.map

import android.content.Context
import android.graphics.Matrix
import android.util.Log
import androidx.appcompat.content.res.AppCompatResources
import org.mapsforge.core.graphics.Cap
import org.mapsforge.core.graphics.Join
import org.mapsforge.core.graphics.Style
import org.mapsforge.core.model.BoundingBox
import org.mapsforge.core.model.LatLong
import org.mapsforge.core.model.Point
import org.mapsforge.core.model.Rotation
import org.mapsforge.core.util.LatLongUtils
import org.mapsforge.map.android.graphics.AndroidBitmap
import org.mapsforge.map.android.graphics.AndroidGraphicFactory
import org.mapsforge.map.android.util.AndroidUtil
import org.mapsforge.map.android.view.MapView
import org.mapsforge.map.datastore.MapDataStore
import org.mapsforge.map.layer.Layer
import org.mapsforge.map.layer.cache.TileCache
import org.mapsforge.map.layer.overlay.Marker
import org.mapsforge.map.layer.overlay.Polyline
import org.mapsforge.map.layer.renderer.TileRendererLayer
import org.mapsforge.map.rendertheme.internal.MapsforgeThemes
import uz.uznav.navigator.R
import uz.uznav.navigator.model.GeoPoint
import uz.uznav.navigator.model.Route
import kotlin.math.abs

/**
 * Xaritani boshqarish: offline xaritani ko'rsatish, marshrut chizig'i,
 * mashina belgisi, manzil belgisi, masshtab va burilish (kurs bo'yicha) rejimi.
 *
 * Bu sinf butunlay Mapsforge (OpenStreetMap) ustida ishlaydi — internet kerak emas.
 */
class MapController(
    private val context: Context,
    private val mapView: MapView
) {

    private var tileCache: TileCache? = null
    private var tileLayer: TileRendererLayer? = null
    private var routeLayer: Polyline? = null
    private var routeBorderLayer: Polyline? = null
    private var vehicleMarker: Marker? = null
    private var destinationMarker: Marker? = null
    private var tapLayer: TapLayer? = null

    private var nightMode = false
    private var lastBearing = Float.NaN
    private var mapLoaded = false

    /** Xaritada uzoq bosilganda chaqiriladi (manzil tanlash uchun). */
    var onLongPress: ((GeoPoint) -> Unit)? = null

    /** Xarita yuklanganmi? */
    val isMapLoaded: Boolean get() = mapLoaded

    // ------------------------------------------------------------------
    // Sozlash
    // ------------------------------------------------------------------

    fun setup() {
        mapView.isClickable = true
        mapView.setBuiltInZoomControls(false)
        mapView.mapScaleBar.isVisible = false
        mapView.setZoomLevelMin(ZOOM_MIN)
        mapView.setZoomLevelMax(ZOOM_MAX)
        mapView.model.frameBufferModel.overdrawFactor = 1.3

        tapLayer = TapLayer { point -> onLongPress?.invoke(point) }.also {
            mapView.layerManager.layers.add(it)
        }
    }

    /**
     * Xarita faylini yuklaydi.
     * @return xarita muvaffaqiyatli ochilgan bo'lsa true
     */
    fun loadMap(store: MapDataStore?, night: Boolean): Boolean {
        nightMode = night
        removeTileLayer()

        if (store == null) {
            mapLoaded = false
            return false
        }

        return try {
            mapView.model.displayModel.themeCallback = if (night) NightThemeCallback() else null

            val cache = tileCache ?: AndroidUtil.createTileCache(
                context,
                "uznav-tiles",
                mapView.model.displayModel.tileSize,
                1.0f,
                mapView.model.frameBufferModel.overdrawFactor
            ).also { tileCache = it }
            cache.purge()

            val layer = AndroidUtil.createTileRendererLayer(
                cache,
                mapView.model.mapViewPosition,
                store,
                MapsforgeThemes.MOTORIDER,
                false,
                true,
                false
            )
            // Marshrut va belgilar xaritaning ustida turishi uchun 0-o'ringa qo'yiladi
            mapView.layerManager.layers.add(0, layer)
            tileLayer = layer
            mapLoaded = true
            mapView.layerManager.redrawLayers()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Xaritani ochib bo'lmadi", e)
            mapLoaded = false
            false
        }
    }

    /** Tungi/yorug' rejimni almashtiradi. */
    fun setNightMode(night: Boolean, store: MapDataStore?) {
        if (night == nightMode && mapLoaded) return
        loadMap(store, night)
    }

    /**
     * Eski xarita qatlamini olib tashlaydi.
     * Diqqat: `onDestroy()` chaqirilmaydi, chunki u xarita faylini yopib
     * qo'yadi — fayl esa qidiruv va marshrut uchun ham kerak (uni [MapManager] boshqaradi).
     */
    private fun removeTileLayer() {
        tileLayer?.let { runCatching { mapView.layerManager.layers.remove(it) } }
        tileLayer = null
    }

    // ------------------------------------------------------------------
    // Pozitsiya va masshtab
    // ------------------------------------------------------------------

    fun center(): GeoPoint {
        val center = mapView.model.mapViewPosition.center
        return GeoPoint(center.latitude, center.longitude)
    }

    fun zoomLevel(): Int = mapView.model.mapViewPosition.zoomLevel.toInt()

    fun moveTo(point: GeoPoint, zoom: Int? = null, animate: Boolean = true) {
        val position = mapView.model.mapViewPosition
        if (zoom != null) position.setZoomLevel(zoom.coerceIn(ZOOM_MIN.toInt(), ZOOM_MAX.toInt()).toByte())
        if (animate) position.animateTo(point.toLatLong()) else position.setCenter(point.toLatLong())
    }

    fun zoomIn() = mapView.model.mapViewPosition.zoomIn()

    fun zoomOut() = mapView.model.mapViewPosition.zoomOut()

    /** Butun marshrut ekranga sig'adigan qilib ko'rsatadi. */
    fun fitRoute(route: Route) {
        if (route.points.isEmpty()) return
        val latLongs = route.points.map { it.toLatLong() }
        val boundingBox = BoundingBox(latLongs)
        val width = if (mapView.width > 0) mapView.width else 720
        val height = if (mapView.height > 0) mapView.height else 1280
        val tileSize = mapView.model.displayModel.tileSize
        val zoom = LatLongUtils.zoomForBounds(
            org.mapsforge.core.model.Dimension(width, height),
            boundingBox,
            tileSize
        )
        setRotation(0f)
        mapView.setMapViewCenterY(0.5f)
        mapView.model.mapViewPosition.setMapPosition(
            org.mapsforge.core.model.MapPosition(boundingBox.centerPoint, (zoom - 1).toByte().coerceAtLeast(ZOOM_MIN))
        )
    }

    /** Xaritani harakat yo'nalishi bo'yicha buradi (0 — shimol yuqorida). */
    fun setRotation(bearingDegrees: Float) {
        val pivotX = mapView.width * 0.5f
        val pivotY = mapView.height * 0.5f
        mapView.rotate(Rotation(-bearingDegrees, pivotX, pivotY))
        mapView.layerManager.redrawLayers()
    }

    /** Navigatsiya rejimida mashina ekranning pastki qismida turadi. */
    fun setNavigationView(enabled: Boolean) {
        mapView.setMapViewCenterY(if (enabled) 0.72f else 0.5f)
    }

    // ------------------------------------------------------------------
    // Marshrut chizig'i
    // ------------------------------------------------------------------

    /** Marshrutni xaritada chizadi (null bo'lsa o'chiradi). */
    fun showRoute(route: Route?) {
        clearRoute()
        if (route == null || route.points.size < 2) return

        val points = route.points.map { it.toLatLong() }

        val borderPaint = AndroidGraphicFactory.INSTANCE.createPaint().apply {
            setColor(ROUTE_BORDER_COLOR)
            setStrokeWidth(ROUTE_WIDTH + 8f)
            setStyle(Style.STROKE)
            setStrokeJoin(Join.ROUND)
            setStrokeCap(Cap.ROUND)
        }
        val linePaint = AndroidGraphicFactory.INSTANCE.createPaint().apply {
            setColor(if (nightMode) ROUTE_COLOR_NIGHT else ROUTE_COLOR_DAY)
            setStrokeWidth(ROUTE_WIDTH)
            setStyle(Style.STROKE)
            setStrokeJoin(Join.ROUND)
            setStrokeCap(Cap.ROUND)
        }

        val border = Polyline(borderPaint, AndroidGraphicFactory.INSTANCE)
        border.setPoints(points)
        val line = Polyline(linePaint, AndroidGraphicFactory.INSTANCE)
        line.setPoints(points)

        val layers = mapView.layerManager.layers
        layers.add(border)
        layers.add(line)
        routeBorderLayer = border
        routeLayer = line
        mapView.layerManager.redrawLayers()
    }

    fun clearRoute() {
        val layers = mapView.layerManager.layers
        routeLayer?.let { runCatching { layers.remove(it) } }
        routeBorderLayer?.let { runCatching { layers.remove(it) } }
        routeLayer = null
        routeBorderLayer = null
    }

    // ------------------------------------------------------------------
    // Mashina va manzil belgilari
    // ------------------------------------------------------------------

    /**
     * Mashina belgisini yangilaydi.
     *
     * @param rotateIcon xarita burilmayotgan bo'lsa, belgi o'zi buriladi
     */
    fun updateVehicle(point: GeoPoint, bearing: Float?, rotateIcon: Boolean) {
        val marker = vehicleMarker ?: createVehicleMarker(point).also {
            vehicleMarker = it
            mapView.layerManager.layers.add(it)
        }
        marker.latLong = point.toLatLong()

        val targetBearing = if (rotateIcon) (bearing ?: 0f) else 0f
        if (lastBearing.isNaN() || abs(targetBearing - lastBearing) > 4f) {
            lastBearing = targetBearing
            rotatedVehicleBitmap(targetBearing)?.let { marker.setBitmap(it) }
        }
        mapView.layerManager.redrawLayers()
    }

    private fun createVehicleMarker(point: GeoPoint): Marker {
        val bitmap = rotatedVehicleBitmap(0f)
        return Marker(point.toLatLong(), bitmap, 0, 0)
    }

    /** Mashina belgisini kerakli burchakka burib, yangi tasvir tayyorlaydi. */
    private fun rotatedVehicleBitmap(degrees: Float): org.mapsforge.core.graphics.Bitmap? {
        return try {
            val drawable = AppCompatResources.getDrawable(context, R.drawable.ic_mashina_belgisi)
                ?: return null
            val base = AndroidGraphicFactory.convertToAndroidBitmap(drawable)
            val matrix = Matrix().apply { postRotate(degrees) }
            val rotated = android.graphics.Bitmap.createBitmap(
                base, 0, 0, base.width, base.height, matrix, true
            )
            AndroidBitmap(rotated)
        } catch (e: Exception) {
            Log.w(TAG, "Mashina belgisini tayyorlab bo'lmadi", e)
            null
        }
    }

    /** Manzil belgisini qo'yadi (null bo'lsa o'chiradi). */
    fun setDestination(point: GeoPoint?) {
        destinationMarker?.let {
            runCatching { mapView.layerManager.layers.remove(it) }
            destinationMarker = null
        }
        if (point == null) return
        val drawable = AppCompatResources.getDrawable(context, R.drawable.ic_manzil_belgisi) ?: return
        val bitmap = AndroidGraphicFactory.convertToBitmap(drawable)
        val marker = Marker(point.toLatLong(), bitmap, 0, -bitmap.height / 2)
        mapView.layerManager.layers.add(marker)
        destinationMarker = marker
        mapView.layerManager.redrawLayers()
    }

    /**
     * Ekran yopilganda resurslarni bo'shatadi.
     * Xarita fayli yopilmaydi — u ilova darajasida ([MapManager]) saqlanadi
     * va navigatsiya xizmati uni ishlatishda davom etadi.
     */
    fun destroy() {
        runCatching {
            clearRoute()
            mapView.layerManager.layers.clear()
            tileCache?.destroy()
            mapView.destroy()
        }
        tileLayer = null
        tileCache = null
        vehicleMarker = null
        destinationMarker = null
        tapLayer = null
        mapLoaded = false
    }

    /**
     * Xaritada uzoq bosishni ushlaydigan ko'rinmas qatlam.
     * Foydalanuvchi xaritaning istalgan nuqtasini manzil qilib tanlay oladi.
     */
    private class TapLayer(private val callback: (GeoPoint) -> Unit) : Layer() {
        override fun draw(
            boundingBox: BoundingBox?,
            zoomLevel: Byte,
            canvas: org.mapsforge.core.graphics.Canvas?,
            topLeftPoint: Point?,
            rotation: Rotation?
        ) {
            // Bu qatlam hech narsa chizmaydi — faqat bosishlarni ushlaydi
        }

        override fun onLongPress(tapLatLong: LatLong?, layerXY: Point?, tapXY: Point?): Boolean {
            val position = tapLatLong ?: return false
            callback(GeoPoint(position.latitude, position.longitude))
            return true
        }
    }

    companion object {
        private const val TAG = "UzNav/Xarita"
        private const val ZOOM_MIN: Byte = 4
        private const val ZOOM_MAX: Byte = 19
        private const val ROUTE_WIDTH = 16f
        private const val ROUTE_COLOR_DAY = 0xFF1A73E8.toInt()
        private const val ROUTE_COLOR_NIGHT = 0xFF4FC3F7.toInt()
        private const val ROUTE_BORDER_COLOR = 0x66000000
    }
}
