package com.techcom.live

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.IBinder
import android.net.wifi.WifiManager
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

/** Foreground service that owns the broadcast so it survives the app being backgrounded. */
class StreamService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wake: PowerManager.WakeLock? = null
    private var notifJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> handleStart(intent)
            ACTION_STOP -> { EngineHolder.get(this).stop(); shutdown() }
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun handleStart(intent: Intent) {
        val p = Runtime.pending
        Runtime.pending = null
        if (p == null) { stopSelf(); return }
        ensureChannel()
        val micOk = ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val camOk = ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        var type = 0
        if (Build.VERSION.SDK_INT >= 29) {
            when (p.cfg.mode) {
                StreamMode.CAMERA -> { if (camOk) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA; if (micOk) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE }
                StreamMode.SCREEN -> { type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION; if (micOk && p.cfg.screenAudio == ScreenAudio.MIC) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE }
                else -> { type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK; if (micOk && !p.cfg.audioOn) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE }
            }
        }
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, notification("Starting…"), type)
            else startForeground(NOTIF_ID, notification("Starting…"))
        } catch (e: Exception) {
            Runtime.error.value = "Android refused to start the background service: ${e.message}"
            Runtime.state.value = LiveState.ERROR
            stopSelf(); return
        }
        Runtime.keep.value = KeepAlive(fg = true)
        acquireWake()
        var mp: MediaProjection? = null
        if (p.cfg.mode == StreamMode.SCREEN) {
            val data: Intent? = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(EXTRA_DATA, Intent::class.java) else @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_DATA)
            val code = intent.getIntExtra(EXTRA_CODE, 0)
            if (data == null) {
                Runtime.error.value = "Screen capture permission data is missing."; Runtime.state.value = LiveState.ERROR; shutdown(); return
            }
            mp = try { (getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager).getMediaProjection(code, data) } catch (e: Exception) { null }
            if (mp == null) { Runtime.error.value = "Could not start screen capture."; Runtime.state.value = LiveState.ERROR; shutdown(); return }
        }
        val engine = EngineHolder.get(this)
        engine.onFinished = { shutdown() }
        watchNetwork()
        notifJob?.cancel()
        notifJob = scope.launch {
            Runtime.state.collectLatest { st ->
                if (p.cfg.notifications) {
                    val nm = getSystemService(NotificationManager::class.java)
                    nm.notify(NOTIF_ID, notification("${p.cfg.mode.label} • ${st.label}"))
                }
            }
        }
        engine.begin(p, mp)
    }

    private fun shutdown() {
        notifJob?.cancel()
        try { netCb?.let { (getSystemService(ConnectivityManager::class.java)).unregisterNetworkCallback(it) } } catch (_: Exception) { }
        netCb = null
        try { wake?.let { if (it.isHeld) it.release() } } catch (_: Exception) { }
        wake = null
        try { wifi?.let { if (it.isHeld) it.release() } } catch (_: Exception) { }
        wifi = null
        Runtime.keep.value = KeepAlive()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        if (EngineHolder.get(this).isStreaming) EngineHolder.get(this).stop("Stopped (service ended)")
        super.onDestroy()
    }

    private var netCb: ConnectivityManager.NetworkCallback? = null
    private fun watchNetwork() {
        val cm = getSystemService(ConnectivityManager::class.java)
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { Runtime.network.value = describe(cm, network); EngineHolder.get(this@StreamService).onNetworkBack() }
            override fun onCapabilitiesChanged(network: Network, c: NetworkCapabilities) { Runtime.network.value = describe(cm, network) }
            override fun onLost(network: Network) { Runtime.network.value = "No connection" }
        }
        try { cm.registerNetworkCallback(NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(), cb); netCb = cb } catch (_: Exception) { }
    }

    private var wifi: WifiManager.WifiLock? = null

    /** Keeps CPU and Wi-Fi awake with the screen off. Released only when the stream stops. */
    @Suppress("DEPRECATION")
    private fun acquireWake() {
        var wakeOk = false; var wifiOk = false
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TechCom:stream").apply { setReferenceCounted(false); acquire() }
            wakeOk = wake?.isHeld == true
        } catch (_: Exception) { }
        try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifi = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "TechCom:wifi").apply { setReferenceCounted(false); acquire() }
            wifiOk = wifi?.isHeld == true
        } catch (_: Exception) { }
        Runtime.keep.value = KeepAlive(fg = true, wake = wakeOk, wifi = wifiOk)
    }

    private fun ensureChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Live streaming", NotificationManager.IMPORTANCE_LOW).apply { description = "Shows while Tech.Com is broadcasting" })
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, StreamService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle("Tech.Com").setContentText(text)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setOngoing(true).setContentIntent(open)
            .addAction(0, "Stop live", stop).build()
    }

    companion object {
        const val ACTION_START = "com.techcom.live.START"
        const val ACTION_STOP = "com.techcom.live.STOP"
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        const val CHANNEL = "techcom_live"
        const val NOTIF_ID = 4101

        fun describe(cm: ConnectivityManager, n: Network): String {
            val c = cm.getNetworkCapabilities(n) ?: return "Connected"
            return when {
                c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobile data"
                c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
                else -> "Connected"
            }
        }
    }
}
