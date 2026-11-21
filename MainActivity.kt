package com.techcom.live

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.content.ClipData
import android.content.ClipboardManager
import android.media.projection.MediaProjectionManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.webkit.WebViewAssetLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Hosts the Tech.Com HTML UI. All real streaming work is done natively through [Bridge]. */
class MainActivity : ComponentActivity() {
    private lateinit var web: WebView
    private lateinit var secure: SecureStore
    private val media = ConcurrentHashMap<String, Uri>()
    private var pending: PendingStart? = null
    private var startAfterPerms = false
    private var pickReplace = false

    private val pickLauncher = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { handlePicked(it) }
    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { onPermResult() }
    private val projLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val d = r.data
        if (r.resultCode == RESULT_OK && d != null) launchService(r.resultCode, d) else fail("Screen capture was not approved. The stream was not started.")
    }
    private val bgLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { pushPerms() }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        secure = SecureStore(this)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        window.statusBarColor = Color.parseColor("#0A1B45")
        window.navigationBarColor = Color.parseColor("#0E1630")
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = false

        val loader = WebViewAssetLoader.Builder().addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this)).build()
        web = WebView(this).apply {
            setBackgroundColor(Color.parseColor("#060A18"))
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            addJavascriptInterface(Bridge(), "TechComNative")
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    val u = request.url
                    if (u.host == HOST && u.path?.startsWith("/media/") == true) return serveMedia(request)
                    return loader.shouldInterceptRequest(u)
                }
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    if (request.url.host == HOST) return false
                    try { startActivity(Intent(Intent.ACTION_VIEW, request.url)) } catch (_: Exception) { }
                    return true   // never navigate the privileged page away
                }
            }
        }
        setContentView(web)
        web.loadUrl("https://$HOST/assets/index.html")

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                web.evaluateJavascript("window.onBack?window.onBack():false") { r ->
                    if (r != "true") { isEnabled = false; onBackPressedDispatcher.onBackPressed(); isEnabled = true }
                }
            }
        })

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                val flows: List<Flow<Any?>> = listOf(Runtime.state, Runtime.error, Runtime.bitrateKbps, Runtime.startedAtMs, Runtime.file, Runtime.result, Runtime.hint, Runtime.log, Runtime.keep)
                combine(flows) { it }.collect { pushState() }
            }
        }
    }

    // ---- state → JS -----------------------------------------------------------------------------------------

    private fun stateJson(): String {
        val o = JSONObject()
        o.put("state", Runtime.state.value.name)
        o.put("error", Runtime.error.value ?: JSONObject.NULL)
        o.put("bitrateKbps", Runtime.bitrateKbps.value ?: JSONObject.NULL)
        o.put("startedAt", Runtime.startedAtMs.value)
        o.put("result", Runtime.result.value ?: JSONObject.NULL)
        o.put("hint", Runtime.hint.value ?: JSONObject.NULL)
        o.put("log", JSONArray(Runtime.log.value))
        val k = Runtime.keep.value
        o.put("keep", JSONObject().put("fg", k.fg).put("wake", k.wake).put("wifi", k.wifi).put("battery", (getSystemService(POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName)))
        val f = Runtime.file.value
        o.put("file", if (f == null) JSONObject.NULL else JSONObject()
            .put("name", f.name).put("posMs", f.positionMs).put("durMs", f.durationMs)
            .put("pass", f.pass).put("index", f.index).put("count", f.count))
        return o.toString()
    }

    private fun pushState() { web.evaluateJavascript("window.onNativeState&&window.onNativeState(${stateJson()})", null) }

    private fun statusJson(): String {
        fun g(p: String) = if (ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED) "granted" else "denied"
        val notif = if (Build.VERSION.SDK_INT < 33) "granted" else g(Manifest.permission.POST_NOTIFICATIONS)
        val battery = (getSystemService(POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName)
        return JSONObject().put("camera", g(Manifest.permission.CAMERA)).put("microphone", g(Manifest.permission.RECORD_AUDIO))
            .put("notif", notif).put("battery", battery).toString()
    }

    private fun pushPerms() { web.evaluateJavascript("window.onNativePerms&&window.onNativePerms(${statusJson()})", null) }

    override fun onResume() { super.onResume(); pushPerms(); pushState() }

    // ---- video picking & preview ----------------------------------------------------------------------------

    private fun handlePicked(uris: List<Uri>, replace: Boolean = pickReplace, quiet: Boolean = false) {
        if (uris.isEmpty()) return
        lifecycleScope.launch {
            val arr = JSONArray(); var bad = 0
            for (u in uris) {
                try { contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) { }
                val info = withContext(Dispatchers.IO) { VideoInfo.read(this@MainActivity, u) }
                if (info == null) { bad++; continue }
                val id = UUID.randomUUID().toString().replace("-", "")
                media[id] = u
                arr.put(JSONObject().put("id", id).put("uri", u.toString()).put("url", "https://$HOST/media/$id")
                    .put("name", info.name).put("size", info.sizeBytes).put("dur", info.durationMs / 1000.0)
                    .put("w", info.width).put("h", info.height).put("replace", replace))
            }
            if (arr.length() > 0) web.evaluateJavascript("window.onNativeVideos&&window.onNativeVideos($arr)", null)
            if (bad > 0 && !quiet) web.evaluateJavascript("toast('$bad file(s) could not be read as video.')", null)
        }
    }

    /** Serves a picked video to the preview player with HTTP range support so seeking works. */
    private fun serveMedia(req: WebResourceRequest): WebResourceResponse {
        val notFound = WebResourceResponse("text/plain", "utf-8", 404, "Not found", emptyMap(), "".byteInputStream())
        val uri = media[req.url.lastPathSegment ?: return notFound] ?: return notFound
        return try {
            val afd = contentResolver.openAssetFileDescriptor(uri, "r") ?: return notFound
            val total = if (afd.length >= 0) afd.length else afd.parcelFileDescriptor.statSize
            val mime = contentResolver.getType(uri) ?: "video/mp4"
            val range = req.requestHeaders["Range"] ?: req.requestHeaders["range"]
            var start = 0L; var end = total - 1
            if (range != null && range.startsWith("bytes=")) {
                val p = range.removePrefix("bytes=").split("-")
                start = p.getOrNull(0)?.toLongOrNull() ?: 0L
                end = p.getOrNull(1)?.toLongOrNull() ?: (total - 1)
            }
            if (end >= total) end = total - 1
            val input = afd.createInputStream()
            var skipped = 0L
            while (skipped < start) { val n = input.skip(start - skipped); if (n <= 0) break; skipped += n }
            val len = end - start + 1
            val h = mutableMapOf("Accept-Ranges" to "bytes", "Content-Length" to len.toString(), "Access-Control-Allow-Origin" to "*")
            if (range != null) {
                h["Content-Range"] = "bytes $start-$end/$total"
                WebResourceResponse(mime, null, 206, "Partial Content", h, Bounded(input, len))
            } else WebResourceResponse(mime, null, 200, "OK", h, Bounded(input, len))
        } catch (e: Exception) { notFound }
    }

    private class Bounded(private val s: InputStream, private var left: Long) : InputStream() {
        override fun read(): Int { if (left <= 0) return -1; val r = s.read(); if (r >= 0) left--; return r }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (left <= 0) return -1
            val n = s.read(b, off, minOf(len.toLong(), left).toInt()); if (n > 0) left -= n; return n
        }
        override fun close() = s.close()
    }

    // ---- start / stop ---------------------------------------------------------------------------------------

    private fun fail(msg: String) {
        Runtime.error.value = msg
        Runtime.state.value = LiveState.ERROR
        pending = null; startAfterPerms = false
    }

    private fun begin(json: String) {
        val st = Runtime.state.value
        if (st != LiveState.OFFLINE && st != LiveState.ERROR) return
        Runtime.hint.value = null; Runtime.result.value = null; Runtime.error.value = null
        Runtime.state.value = LiveState.CONNECTING      // changes state so JS always sees a fresh transition
        try {
            val o = JSONObject(json)
            val mode = StreamMode.parse(o.optString("mode"))
            var sa = when (o.optString("sAudio")) { "System only", "Mic + System" -> ScreenAudio.SYSTEM; "Muted" -> ScreenAudio.MUTED; else -> ScreenAudio.MIC }
            if (o.optString("sAudio") == "Mic + System") Runtime.hint.value = "Mic + System together is not supported by the encoder; System audio is used."
            val cfg = StreamConfig(
                mode = mode, ratio = o.optString("ratio", "16:9"), res = o.optString("res", "720p"), fps = o.optInt("fps", 30),
                kbps = o.optInt("kbps", 4500), audioKbps = o.optInt("abr", 128), sampleRate = o.optInt("sr", 44100),
                audioOn = o.optBoolean("audio", true), screenAudio = sa,
                fit = if (o.optString("fit") == "cover") Fit.FILL else Fit.FIT,
                repeat = o.optBoolean("repeat", true), shuffle = o.optBoolean("shuffle", false),
                frontCamera = o.optString("facing", "user") == "user", notifications = o.optBoolean("notif", true),
                retries = o.optInt("retries", 0),
            )
            val url = o.optString("url").trim(); val key = o.optString("key").trim()
            val cm = getSystemService(ConnectivityManager::class.java)
            val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
            if (caps == null || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return fail("No internet connection. Connect to Wi-Fi or mobile data and try again.")
            if (!(url.startsWith("rtmp://") || url.startsWith("rtmps://"))) return fail("Server URL must start with rtmp:// or rtmps://")
            if (key.isEmpty()) return fail("Stream key is empty.")
            val items = ArrayList<PlaylistItem>()
            val arr = o.optJSONArray("items") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val it = arr.getJSONObject(i)
                items += PlaylistItem(it.getString("uri"), it.optString("name", "video"), (it.optDouble("dur", 0.0) * 1000).toLong(), 0, 0, 0)
            }
            if (mode.usesFile && items.isEmpty()) return fail("No video selected.")
            if (mode.usesFile && items.any { it.durationMs <= 0 }) return fail("A selected video has no readable duration.")
            if (mode == StreamMode.SCREEN && cfg.screenAudio == ScreenAudio.SYSTEM && Build.VERSION.SDK_INT < 29) return fail("System audio capture needs Android 10 or newer.")
            pending = PendingStart(cfg, url, key, items)
            val need = ArrayList<String>()
            when (mode) {
                StreamMode.CAMERA -> { need += Manifest.permission.CAMERA; need += Manifest.permission.RECORD_AUDIO }
                StreamMode.SCREEN -> if (cfg.screenAudio == ScreenAudio.MIC) need += Manifest.permission.RECORD_AUDIO
                else -> if (!cfg.audioOn) need += Manifest.permission.RECORD_AUDIO
            }
            val missing = need.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                startAfterPerms = true; permLauncher.launch((missing + Manifest.permission.POST_NOTIFICATIONS).toTypedArray()); return
            }
            if (missing.isNotEmpty()) { startAfterPerms = true; permLauncher.launch(missing.toTypedArray()) } else proceed()
        } catch (e: Exception) { fail("Could not read the start request: ${e.message}") }
    }

    private fun onPermResult() {
        pushPerms()
        if (!startAfterPerms) return
        startAfterPerms = false
        val p = pending ?: return
        val required = when (p.cfg.mode) {
            StreamMode.CAMERA -> listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
            StreamMode.SCREEN -> if (p.cfg.screenAudio == ScreenAudio.MIC) listOf(Manifest.permission.RECORD_AUDIO) else emptyList()
            else -> if (!p.cfg.audioOn) listOf(Manifest.permission.RECORD_AUDIO) else emptyList()
        }
        val denied = required.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (denied.isNotEmpty()) fail("Permission denied: " + denied.joinToString { it.substringAfterLast('.').lowercase() } + ". The stream was not started.")
        else proceed()
    }

    private fun proceed() {
        val p = pending ?: return
        if (p.cfg.mode == StreamMode.SCREEN) {
            val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projLauncher.launch(mpm.createScreenCaptureIntent())
        } else launchService(0, null)
    }

    private fun launchService(code: Int, data: Intent?) {
        val p = pending ?: return
        Runtime.pending = p; pending = null
        val i = Intent(this, StreamService::class.java).setAction(StreamService.ACTION_START).putExtra(StreamService.EXTRA_CODE, code)
        if (data != null) i.putExtra(StreamService.EXTRA_DATA, data)
        try { ContextCompat.startForegroundService(this, i) } catch (e: Exception) { fail("Could not start the background service: ${e.message}") }
    }

    private fun endStream() { startService(Intent(this, StreamService::class.java).setAction(StreamService.ACTION_STOP)) }

    // ---- JavaScript bridge (only our own page is ever loaded in this WebView) ---------------------------------

    inner class Bridge {
        @JavascriptInterface fun start(json: String) { runOnUiThread { begin(json) } }
        @JavascriptInterface fun stop() { runOnUiThread { endStream() } }
        @JavascriptInterface fun pickVideos(replace: Boolean) { runOnUiThread { pickReplace = replace; pickLauncher.launch(arrayOf("video/*")) } }
        @JavascriptInterface fun keepScreenOn(on: Boolean) {
            runOnUiThread { if (on) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) else window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        }
        @JavascriptInterface fun getKey(): String = secure.key()
        @JavascriptInterface fun copy(text: String) {
            runOnUiThread { (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Tech.Com log", text)) }
        }
        @JavascriptInterface fun restoreVideos(json: String) {
            runOnUiThread {
                val a = try { JSONArray(json) } catch (_: Exception) { JSONArray() }
                val list = (0 until a.length()).mapNotNull { runCatching { Uri.parse(a.getString(it)) }.getOrNull() }
                if (list.isNotEmpty()) handlePicked(list, replace = false, quiet = true)
            }
        }
        /** Opens a plain TCP connection to the RTMP host to tell "network blocks the port" from "wrong key". */
        @JavascriptInterface fun testRtmp(url: String) {
            Thread {
                val res = JSONObject()
                try {
                    val u = Uri.parse(url.trim()); val host = u.host ?: throw IllegalArgumentException("Invalid URL")
                    val port = if (u.port > 0) u.port else if (u.scheme == "rtmps") 443 else 1935
                    res.put("host", host).put("port", port)
                    val t0 = System.currentTimeMillis()
                    java.net.Socket().use { it.connect(java.net.InetSocketAddress(host, port), 6000) }
                    res.put("ok", true).put("ms", System.currentTimeMillis() - t0)
                } catch (e: Exception) { res.put("ok", false).put("error", e.message ?: e.javaClass.simpleName) }
                runOnUiThread { web.evaluateJavascript("window.onNativeTest&&window.onNativeTest($res)", null) }
            }.start()
        }
        @JavascriptInterface fun saveKey(k: String): Boolean = secure.saveKey(k)
        @JavascriptInterface fun status(): String = statusJson()
        @JavascriptInterface fun snapshot(): String = stateJson()
        @JavascriptInterface fun requestPerms(json: String) {
            runOnUiThread {
                val names = try { JSONArray(json) } catch (_: Exception) { JSONArray() }
                val list = ArrayList<String>()
                for (i in 0 until names.length()) when (names.optString(i)) {
                    "camera" -> list += Manifest.permission.CAMERA
                    "microphone" -> list += Manifest.permission.RECORD_AUDIO
                    "notif" -> if (Build.VERSION.SDK_INT >= 33) list += Manifest.permission.POST_NOTIFICATIONS
                }
                val missing = list.filter { ContextCompat.checkSelfPermission(this@MainActivity, it) != PackageManager.PERMISSION_GRANTED }
                if (missing.isEmpty()) pushPerms() else permLauncher.launch(missing.toTypedArray())
            }
        }
        @JavascriptInterface fun requestBattery() {
            runOnUiThread {
                try { bgLauncher.launch(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))) }
                catch (_: Exception) { try { bgLauncher.launch(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } catch (_: Exception) { } }
            }
        }
    }

    companion object { const val HOST = "appassets.androidplatform.net" }
}
