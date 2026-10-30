package com.shantanu.nmaps.record

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.shantanu.nmaps.MainActivity
import com.shantanu.nmaps.R
import com.shantanu.nmaps.data.Fix
import com.shantanu.nmaps.data.LocationTracker
import com.shantanu.nmaps.nav.distanceMeters
import com.shantanu.nmaps.nav.formatClock
import com.shantanu.nmaps.nav.formatDistance
import com.shantanu.nmaps.sensors.Sensors
import com.shantanu.nmaps.share.LiveLocation
import com.shantanu.nmaps.share.LiveShare
import com.shantanu.nmaps.share.Relay
import com.shantanu.nmaps.share.ShareCrypto
import com.shantanu.nmaps.timeline.HistoryDb
import com.shantanu.nmaps.timeline.HistoryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/** Navigation details the service shows in its notification and sends with a live share. */
data class NavInfo(
    val instruction: String,
    val destination: String,
    val eta: Long,
    /** Distance to the next turn, e.g. "250 m", shown as the status-bar chip on Android 16. */
    val distance: String = "",
    val glyph: com.shantanu.nmaps.ui.DotGlyph? = null,
    /** Share of the trip done, 0..1. */
    val progress: Float = 0f,
)

/**
 * One foreground service for everything that must keep running with the screen off:
 * recording an activity, sharing live location, and turn-by-turn navigation.
 * Call [Tracking.sync] whenever one of those starts or stops.
 */
object Tracking {
    val nav = MutableStateFlow<NavInfo?>(null)
    /** "End" tapped in the navigation notification; the app's view model ends the trip. */
    val endNavRequests = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** Mirrors the timeline setting so the service can add its fixes to history. */
    @Volatile var timelineEnabled = false

    fun sync(context: Context) {
        val needed = Recorder.isActive || LiveShare.isActive || nav.value != null
        val intent = Intent(context, TrackingService::class.java)
        if (needed) ContextCompat.startForegroundService(context, intent)
        else if (TrackingService.running) context.startService(intent)
    }
}

class TrackingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var fixJob: Job? = null
    private var recordJobs: List<Job> = emptyList()
    private var tickJob: Job? = null
    private var navJob: Job? = null
    private var channelReady = false

    private var lastShared: Fix? = null
    private var lastShareTime = 0L
    private var lastSessionTopic: String? = null
    private var arrowCache: Pair<com.shantanu.nmaps.ui.DotGlyph, android.graphics.Bitmap>? = null

    override fun onBind(intent: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        running = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_END_NAV) {
            Tracking.endNavRequests.tryEmit(Unit)
            // Works even when the app's screen is gone and nobody collects the request.
            Tracking.nav.value = null
        }
        // Must go foreground on every start, even when about to stop, or Android kills the app.
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        reconcile()
        return START_NOT_STICKY
    }

    private fun reconcile() {
        val recording = Recorder.isActive
        val sharing = LiveShare.isActive
        val navigating = Tracking.nav.value != null
        if (!recording && !sharing && !navigating) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        // Navigation reads location in the app itself; the service only keeps it alive.
        if ((recording || sharing) && fixJob == null) fixJob = scope.launch { LocationTracker(this@TrackingService).fixes().collect(::onFix) }
        if (!recording && !sharing) { fixJob?.cancel(); fixJob = null }

        if (recording && recordJobs.isEmpty()) recordJobs = startRecordingSensors()
        if (!recording) { recordJobs.forEach { it.cancel() }; recordJobs = emptyList() }

        // Turn distances change every second or two; refresh the notification as they do.
        if (navigating && navJob == null) navJob = scope.launch {
            Tracking.nav.collect { if (it != null) getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification()) }
        }
        if (!navigating) { navJob?.cancel(); navJob = null }

        if (tickJob == null) tickJob = scope.launch {
            var n = 0
            while (isActive) {
                Recorder.tick(System.currentTimeMillis())
                LiveShare.session.value?.let { if (it.until <= System.currentTimeMillis()) stopSharing() }
                if (n++ % 5 == 0) getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification())
                delay(1000)
            }
        }
    }

    private fun startRecordingSensors(): List<Job> {
        val sensors = Sensors(this)
        val tracker = LocationTracker(this)
        val canCountSteps = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED
        return listOfNotNull(
            scope.launch { tracker.satellites().collect(Recorder::onSatellites) },
            scope.launch { sensors.stillness().collect(Recorder::onStill) },
            if (sensors.hasBarometer) scope.launch { sensors.pressureAltitude().collect(Recorder::onBarometer) } else null,
            if (sensors.hasStepCounter && canCountSteps) scope.launch { sensors.steps().collect { Recorder.onSteps(it) } } else null,
        )
    }

    private fun onFix(fix: Fix) {
        Recorder.onFix(fix)
        if (Tracking.timelineEnabled) {
            val db = HistoryDb.get(this)
            scope.launch(Dispatchers.IO) { db.add(HistoryPoint(fix.time, fix.lat, fix.lon, fix.accuracy, fix.speed), thin = true) }
        }
        val session = LiveShare.session.value ?: return
        // A new share starts fresh: send the first position straight away.
        if (session.link.topic != lastSessionTopic) {
            lastSessionTopic = session.link.topic
            lastShared = null
            lastShareTime = 0L
        }
        val now = System.currentTimeMillis()
        val prev = lastShared
        // Send when moved 50 m (at most every 30 s), or a heartbeat every 5 minutes; keeps within the relay's limits.
        val moved = prev == null || distanceMeters(prev.point, fix.point) > 50
        if ((moved && now - lastShareTime > 30_000) || now - lastShareTime > 5 * 60_000) {
            lastShared = fix
            lastShareTime = now
            val nav = Tracking.nav.value
            val payload = LiveLocation(
                session.name, fix.lat, fix.lon, fix.accuracy, fix.speed, battery(), now, session.until,
                destination = nav?.destination, eta = nav?.eta,
            )
            publish(session, payload)
        }
    }

    private fun publish(session: LiveShare.Session, payload: LiveLocation) {
        scope.launch {
            runCatching { Relay.publish(session.link.topic, ShareCrypto.encrypt(session.link.key, payload.toJson())) }
                .onSuccess { LiveShare.error.value = null }
                .onFailure { LiveShare.error.value = "Couldn't reach the sharing relay. Trying again when you move, or within 5 minutes." }
        }
    }

    private fun stopSharing() {
        val session = LiveShare.session.value ?: return
        val last = lastShared
        LiveShare.stop()
        if (last != null) publish(session, LiveLocation(session.name, last.lat, last.lon, last.accuracy, 0f, battery(), System.currentTimeMillis(), session.until, stopped = true))
        lastShared = null
        reconcile()
    }

    private fun battery(): Int? =
        getSystemService(BatteryManager::class.java)?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it in 0..100 }

    private fun notification(): Notification {
        if (!channelReady) {
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(NotificationChannel(CHANNEL, "Navigation, recording and sharing", NotificationManager.IMPORTANCE_LOW))
            channelReady = true
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val rec = Recorder.state.value
        val nav = Tracking.nav.value
        val share = LiveShare.session.value
        val lines = buildList {
            if (nav != null) add(nav.instruction)
            if (rec.status != RecStatus.Idle) {
                val d = formatDistance(rec.distance)
                add("${rec.sport.label}: ${d.value} ${d.unit}, ${formatClock(rec.movingMs)}")
            }
            if (share != null) add("Sharing location until ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(share.until))}")
        }
        val title = when {
            nav != null -> "Navigating to ${nav.destination}"
            rec.status == RecStatus.Paused -> "${rec.sport.label} paused"
            rec.status == RecStatus.AutoPaused -> "${rec.sport.label} auto-paused"
            rec.status != RecStatus.Idle -> "Recording ${rec.sport.label.lowercase()}"
            share != null -> "Sharing your location"
            else -> "Maps"
        }
        if (nav != null) return navNotification(nav, open, lines.drop(1))
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(lines.firstOrNull { it != title }.orEmpty())
            .setStyle(NotificationCompat.BigTextStyle().bigText(lines.joinToString("\n")))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(if (nav != null) NotificationCompat.CATEGORY_NAVIGATION else NotificationCompat.CATEGORY_WORKOUT)
            .setContentIntent(open)
            .build()
    }

    /**
     * Turn-by-turn as an Android 16 Live Update: the next turn drawn as a dot-matrix arrow, the
     * distance as the status-bar chip, and trip progress as a bar. Older Android shows a normal
     * ongoing notification with the same content.
     */
    private fun navNotification(nav: NavInfo, open: PendingIntent, extra: List<String>): Notification {
        val end = PendingIntent.getService(
            this, 1, Intent(this, TrackingService::class.java).setAction(ACTION_END_NAV), PendingIntent.FLAG_IMMUTABLE,
        )
        // The arrow only changes at a new turn; reuse its bitmap between updates.
        val arrow = nav.glyph?.let { g -> arrowCache?.takeIf { it.first === g }?.second ?: glyphBitmap(g).also { arrowCache = g to it } }
        val eta = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(nav.eta))
        val b = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(listOf(nav.distance, nav.instruction).filter { it.isNotBlank() }.joinToString(" · "))
            .setContentText((listOf("Arrive $eta at ${nav.destination}") + extra).joinToString("\n"))
            .setLargeIcon(arrow)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(open)
            .addAction(0, "End", end)
            .setShortCriticalText(nav.distance)
            .setRequestPromotedOngoing(true)
            .setStyle(
                NotificationCompat.ProgressStyle()
                    .setProgress((nav.progress * 100).toInt())
                    .setStyledByProgress(true)
                    .setProgressSegments(listOf(NotificationCompat.ProgressStyle.Segment(100).setColor(0xFFD71921.toInt())))
            )
        return b.build()
    }

    /** The banner's dot-matrix arrow as a bitmap for the notification. */
    private fun glyphBitmap(glyph: com.shantanu.nmaps.ui.DotGlyph): android.graphics.Bitmap {
        val px = (64 * resources.displayMetrics.density).toInt()
        val bmp = android.graphics.Bitmap.createBitmap(px, px, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        c.drawColor(android.graphics.Color.BLACK)
        val cell = px / glyph.width.coerceAtLeast(glyph.height).toFloat()
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        for (y in 0 until glyph.height) for (x in 0 until glyph.width) {
            paint.color = when (glyph.cell(x, y)) {
                '#' -> android.graphics.Color.WHITE
                'r' -> 0xFFD71921.toInt()
                else -> 0xFF1E1E1E.toInt()
            }
            c.drawCircle((x + 0.5f) * cell, (y + 0.5f) * cell, cell * 0.38f, paint)
        }
        return bmp
    }

    override fun onDestroy() {
        running = false
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        @Volatile var running = false
            private set
        private const val CHANNEL = "tracking"
        private const val NOTIFICATION_ID = 7
        private const val ACTION_END_NAV = "end-nav"
    }
}
