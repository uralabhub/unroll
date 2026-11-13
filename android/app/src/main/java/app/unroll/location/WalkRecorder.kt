package app.unroll.location

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.IBinder
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.unroll.MainActivity
import app.unroll.R
import app.unroll.core.DemoRoll
import app.unroll.core.Fix
import app.unroll.core.Geo
import app.unroll.core.PhotoWalk
import app.unroll.core.TrailPoint
import app.unroll.data.WalkStore
import java.util.UUID

/**
 * Records a battery-friendly GPS trail during a "Photo Walk" so imports can be geotagged without
 * the camera's Bluetooth link (the #1 battery + reliability complaint about brand apps).
 * A point every ~25 m from the GPS provider; a foreground service keeps it alive in the pocket.
 */
class WalkRecorder(private val context: Context, private val store: WalkStore) {
    var currentId by mutableStateOf<String?>(store.walks.lastOrNull { it.end == null && !it.isDemo }?.id)
        private set
    var lastFix by mutableStateOf<Location?>(null)
        private set

    val walks: List<PhotoWalk> get() = store.walks
    val current: PhotoWalk? get() = currentId?.let { id -> store.walks.firstOrNull { it.id == id } }
    val isRecording: Boolean get() = currentId != null

    val hasPermission: Boolean
        get() = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private val listener = LocationListener { l -> onFix(l) }

    init {
        if (currentId != null && hasPermission) resume()
    }

    fun startWalk() {
        if (currentId != null) return
        val w = PhotoWalk(UUID.randomUUID().toString(), System.currentTimeMillis())
        store.update { it + w }
        currentId = w.id
        resume()
    }

    @SuppressLint("MissingPermission")
    private fun resume() {
        if (!hasPermission) return
        runCatching { context.startForegroundService(Intent(context, WalkService::class.java)) }
        val lm = context.getSystemService(LocationManager::class.java) ?: return
        runCatching { lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 5000L, 25f, listener, Looper.getMainLooper()) }
    }

    fun stopWalk() {
        context.getSystemService(LocationManager::class.java)?.removeUpdates(listener)
        context.stopService(Intent(context, WalkService::class.java))
        val id = currentId ?: return
        store.update { list -> list.map { if (it.id == id) it.copy(end = System.currentTimeMillis()) else it } }
        currentId = null
    }

    fun delete(walk: PhotoWalk) = store.update { list -> list.filterNot { it.id == walk.id } }

    /** Demo walk matching the demo roll, so geotagging and the recap map work out of the box. */
    fun installDemoWalk() = store.update { list -> list.filterNot { it.isDemo } + DemoRoll.walk() }

    private fun onFix(l: Location) {
        if (!l.hasAccuracy() || l.accuracy >= 100) return
        val id = currentId ?: return
        lastFix = l
        store.update { list ->
            list.map { if (it.id == id) it.copy(points = it.points + TrailPoint(l.latitude, l.longitude, l.time, l.accuracy.toDouble())) else it }
        }
    }

    /** Location at [t] (epoch ms), interpolated between trail points (max 15 min gap). */
    fun location(t: Long): Fix? = Geo.location(store.walks, t)

    fun walkCovering(times: List<Long>): PhotoWalk? = Geo.walkCovering(store.walks, times)
}

/** Keeps GPS logging alive with the screen off. */
class WalkService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("walk", "Photo walk", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(this, "walk")
            .setSmallIcon(R.drawable.ic_stat_unroll)
            .setContentTitle("Photo walk")
            .setContentText("Logging your route for geotags")
            .setOngoing(true)
            .setContentIntent(open)
            .build()
        runCatching { ServiceCompat.startForeground(this, 8, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION) }
            .onFailure { stopSelf() }
        return START_STICKY
    }
}
