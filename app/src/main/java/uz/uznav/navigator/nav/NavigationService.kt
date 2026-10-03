package uz.uznav.navigator.nav

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import uz.uznav.navigator.R
import uz.uznav.navigator.UzNavApp
import uz.uznav.navigator.ui.MainActivity
import uz.uznav.navigator.util.UzFormat
import uz.uznav.navigator.voice.UzbekPhrases

/**
 * Navigatsiya davom etayotganini ko'rsatuvchi oldingi plandagi (foreground) xizmat.
 *
 * Shu xizmat tufayli ekran o'chsa yoki boshqa ilova ochilsa ham
 * GPS kuzatuvi va ovozli yo'riqnoma to'xtamaydi.
 */
class NavigationService : LifecycleService() {

    private lateinit var app: UzNavApp

    override fun onCreate() {
        super.onCreate()
        app = UzNavApp.from(this)
        createChannel()
        startForegroundSafely(buildNotification("Navigatsiya tayyorlanmoqda…", ""))

        // Navigatsiya holati o'zgarganda bildirishnomani yangilab turamiz
        lifecycleScope.launch {
            app.navigation.engine.state.collectLatest { state ->
                if (!state.active) {
                    stopSelf()
                    return@collectLatest
                }
                val step = state.nextStep
                val title = if (step != null) {
                    val distance = UzFormat.distance(state.distanceToStepM)
                    "$distance — ${UzbekPhrases.maneuverTitle(step)}"
                } else {
                    "Navigatsiya davom etmoqda"
                }
                val text = "Qolgan masofa: ${UzFormat.distance(state.remainingDistanceM)} • " +
                        "Yetib borish: ${state.etaText}"
                notificationManager()?.notify(NOTIFICATION_ID, buildNotification(title, text))
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            app.navigation.stop()
            stopSelf()
            return START_NOT_STICKY
        }
        // GPS kuzatuvi xizmat davomida ham ishlashi kerak
        app.locationEngine.start()
        return START_STICKY
    }

    override fun onDestroy() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        super.onDestroy()
    }

    private fun startForegroundSafely(notification: Notification) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            // Masalan, joylashuv ruxsati berilmagan bo'lsa Android xizmatni to'xtatadi
            Log.e(TAG, "Oldingi plandagi xizmatni boshlab bo'lmadi", e)
            stopSelf()
        }
    }

    private fun buildNotification(title: String, text: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPending = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopPending = PendingIntent.getService(
            this, 1,
            Intent(this, NavigationService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_navigatsiya)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(contentPending)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(R.drawable.ic_toxtatish, "To'xtatish", stopPending)
            .build()
    }

    private fun notificationManager(): NotificationManager? =
        getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Navigatsiya",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Marshrut davom etayotganini ko'rsatadi"
            setShowBadge(false)
            enableVibration(false)
        }
        notificationManager()?.createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "UzNav/Xizmat"
        private const val CHANNEL_ID = "uznav_navigatsiya"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_STOP = "uz.uznav.navigator.TOXTATISH"

        /** Xizmatni ishga tushiradi. */
        fun start(context: Context) {
            val intent = Intent(context, NavigationService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Xizmatni boshlab bo'lmadi", e)
            }
        }

        /** Xizmatni to'xtatadi. */
        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, NavigationService::class.java)) }
        }
    }
}
