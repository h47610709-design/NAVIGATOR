package uz.uznav.navigator

import android.app.Application
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.mapsforge.map.android.graphics.AndroidGraphicFactory
import uz.uznav.navigator.data.AppPrefs
import uz.uznav.navigator.data.MapManager
import uz.uznav.navigator.location.LocationEngine
import uz.uznav.navigator.nav.NavigationController
import uz.uznav.navigator.routing.Router
import uz.uznav.navigator.voice.UzbekVoice

/**
 * Ilovaning asosiy sinfi: barcha umumiy xizmatlar shu yerda bir marta yaratiladi.
 *
 * Shunday qilib xarita, GPS, ovoz va marshrut xizmatlari ekranlar
 * (Activity) o'rtasida bo'lishiladi va navigatsiya ekran yopilganda ham davom etadi.
 */
class UzNavApp : Application() {

    /** Ilova umri davomida yashaydigan coroutine doirasi. */
    val appScope: CoroutineScope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) }

    val prefs: AppPrefs by lazy { AppPrefs.get(this) }
    val mapManager: MapManager by lazy { MapManager.get(this) }
    val locationEngine: LocationEngine by lazy { LocationEngine.get(this) }
    val voice: UzbekVoice by lazy { UzbekVoice(this) }

    /** Offline marshrut xizmati — xarita fayllari ustida ishlaydi. */
    val router: Router by lazy { Router { mapManager.store() } }

    /** Navigatsiyani boshqaruvchi (GPS -> marshrut -> ovoz). */
    val navigation: NavigationController by lazy {
        NavigationController(
            context = this,
            scope = appScope,
            locationEngine = locationEngine,
            router = router,
            voice = voice,
            prefs = prefs
        )
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        // Mapsforge grafik tizimini ishga tushirish (xaritadan oldin majburiy)
        AndroidGraphicFactory.createInstance(this)

        // Foydalanuvchi tanlagan mavzu (yorug'/tungi)
        prefs.applyTheme()

        // Ovoz tizimini oldindan tayyorlab qo'yamiz
        voice.enabled = prefs.voiceEnabled
        voice.initialize()
    }

    companion object {
        @Volatile
        private var instance: UzNavApp? = null

        /** Istalgan joydan ilova xizmatlariga murojaat qilish. */
        fun from(context: Context): UzNavApp =
            instance ?: (context.applicationContext as UzNavApp).also { instance = it }
    }
}
