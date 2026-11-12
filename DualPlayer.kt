package com.example.dualaudio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.*
import android.media.audiofx.AudioEffect
import android.media.audiofx.Equalizer
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin

/** Same physical device can show up as A2DP, call-mode (SCO) or LE entries with different ids; the address is stable. */
fun AudioDeviceInfo.key(): String = if (address.isNullOrBlank()) "id:$id" else address

/** One shared player for the Activity and the capture service. */
object Engine {
    @Volatile private var inst: DualPlayer? = null
    fun get(ctx: Context): DualPlayer =
        inst ?: synchronized(this) { inst ?: DualPlayer(ctx.applicationContext).also { inst = it } }
}

/**
 * Feeds TWO AudioTracks (each pinned to its own output device, each with its own
 * DeviceChain) from either a decoded file or captured phone audio.
 */
class DualPlayer(private val context: Context) {
    val chains = arrayOf(DeviceChain(), DeviceChain())
    val selected = mutableStateListOf<AudioDeviceInfo?>(null, null)
    var crossoverHz by mutableFloatStateOf(80f)
    var isPlaying by mutableStateOf(false)
    var capturing by mutableStateOf(false)
    var positionMs by mutableLongStateOf(0L)
    var durationMs by mutableLongStateOf(0L)
    var status by mutableStateOf("Start phone audio, or pick a file.")
    var lastUri: Uri? = null

    @Volatile private var paused = false
    @Volatile private var stopFlag = false
    @Volatile private var seekMs = -1L
    private var thread: Thread? = null
    private var projection: MediaProjection? = null
    private val tracks = arrayOfNulls<AudioTrack>(2)
    private var activeDevs: List<AudioDeviceInfo?> = listOf(null, null)
    private var work = FloatArray(0)
    private var pcm = ShortArray(0)
    private var writes = 0
    private var note = ""
    private val am = context.getSystemService(AudioManager::class.java)

    // ---------- shared output side ----------
    private fun buildTrack(sr: Int, dev: AudioDeviceInfo, mult: Int): AudioTrack {
        val min = AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sr).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
            .setBufferSizeInBytes(min * mult)
            .setTransferMode(AudioTrack.MODE_STREAM).build()
        t.setPreferredDevice(dev)
        return t
    }

    private fun openTracks(sr: Int, devs: List<AudioDeviceInfo?>, mult: Int) {
        closeTracks()
        activeDevs = devs; writes = 0
        for (d in 0..1) {
            chains[d].prepare(sr)
            val dev = devs[d] ?: continue
            tracks[d] = buildTrack(sr, dev, mult).also { it.play() }
        }
    }

    private fun closeTracks() {
        for (d in 0..1) {
            tracks[d]?.let { try { it.stop() } catch (_: Exception) {}; it.release() }
            tracks[d] = null
        }
    }

    /** src = interleaved stereo float. Runs each device's chain and writes (blocking). */
    private fun pushFrames(src: FloatArray, n: Int) {
        if (work.size < n * 2) { work = FloatArray(n * 2); pcm = ShortArray(n * 2) }
        for (d in 0..1) {
            val t = tracks[d] ?: continue
            System.arraycopy(src, 0, work, 0, n * 2)
            chains[d].process(work, n, crossoverHz)
            for (k in 0 until n * 2) pcm[k] = (work[k] * 32767f).toInt().toShort()
            t.write(pcm, 0, n * 2)
        }
        if (++writes == 8) status = routingReport()
    }

    private fun routingReport(): String {
        val sb = StringBuilder()
        for (d in 0..1) {
            val t = tracks[d] ?: continue
            val want = activeDevs[d]!!; val got = t.routedDevice
            val ok = got != null && got.id == want.id
            sb.append("${"AB"[d]}: ${if (ok) "✓ playing on" else "✗ Android sent it to ${got?.productName ?: "another output"} instead of"} ${want.productName}\n")
        }
        if (note.isNotEmpty()) sb.append(note)
        return sb.toString().trim()
    }

    fun systemRoute(): List<AudioDeviceInfo> = try {
        if (Build.VERSION.SDK_INT >= 33)
            am.getAudioDevicesForAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
        else emptyList()
    } catch (_: Exception) { emptyList() }

    fun stop() {
        stopFlag = true
        thread?.join(1500); thread = null
        val p = projection; projection = null
        try { p?.stop() } catch (_: Exception) {}
        isPlaying = false; capturing = false
    }

    // ---------- source 1: phone audio ----------
    /** Returns false if nothing could be started. */
    fun startCapture(proj: MediaProjection): Boolean {
        if (Build.VERSION.SDK_INT < 29) { status = "Phone-audio capture needs Android 10 or newer."; return false }
        stop()
        val route = systemRoute().map { it.key() }.toSet()
        val devs = selected.map { d -> if (d != null && d.key() in route) null else d }
        note = selected.indices.filter { selected[it] != null && devs[it] == null }.joinToString("\n") {
            "${"AB"[it]}: is the phone's current output, so it plays the original sound unprocessed."
        }
        if (devs.all { it == null }) {
            status = "Nothing to process: pick a second device that isn't the phone's current output.\n$note"
            try { proj.stop() } catch (_: Exception) {}
            return false
        }
        projection = proj
        stopFlag = false; capturing = true; isPlaying = true
        status = "Capturing phone audio…"
        thread = Thread { runCapture(proj, devs) }.also { it.start() }
        return true
    }

    @Suppress("MissingPermission")
    private fun runCapture(proj: MediaProjection, devs: List<AudioDeviceInfo?>) {
        var rec: AudioRecord? = null
        try {
            val sr = 48000
            val cfg = AudioPlaybackCaptureConfiguration.Builder(proj)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .excludeUid(android.os.Process.myUid())
                .build()
            val minIn = AudioRecord.getMinBufferSize(sr, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
            rec = AudioRecord.Builder()
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sr).setChannelMask(AudioFormat.CHANNEL_IN_STEREO).build())
                .setBufferSizeInBytes(maxOf(minIn, 4096) * 2)
                .setAudioPlaybackCaptureConfig(cfg).build()
            openTracks(sr, devs, 2)
            rec.startRecording()
            val buf = ShortArray(1920); val src = FloatArray(1920)
            while (!stopFlag) {
                val got = rec.read(buf, 0, buf.size)
                if (got < 0) { status = "Capture error $got"; break }
                val n = got / 2
                if (n == 0) continue
                for (k in 0 until n * 2) src[k] = buf[k] / 32768f
                pushFrames(src, n)
            }
        } catch (e: Exception) {
            status = "Error: ${e.message}"
        } finally {
            try { rec?.stop() } catch (_: Exception) {}
            rec?.release(); closeTracks()
            isPlaying = false; capturing = false
        }
    }

    // ---------- source 2: file ----------
    fun start(uri: Uri) {
        if (selected.all { it == null }) { status = "No device selected."; return }
        stop()
        note = ""; lastUri = uri; stopFlag = false; paused = false; isPlaying = true
        val devs = selected.toList()
        thread = Thread { runFile(uri, devs) }.also { it.start() }
    }

    fun togglePause() { paused = !paused; isPlaying = !paused }
    fun seekTo(ms: Long) { seekMs = ms }

    private fun runFile(uri: Uri, devs: List<AudioDeviceInfo?>) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            val idx = (0 until extractor.trackCount).first {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            }
            extractor.selectTrack(idx)
            val fmt = extractor.getTrackFormat(idx)
            durationMs = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) / 1000 else 0
            codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(fmt, null, null, 0); codec.start()

            val info = MediaCodec.BufferInfo()
            var inDone = false; var outDone = false
            var channels = 2
            var src = FloatArray(0)

            while (!outDone && !stopFlag) {
                if (seekMs >= 0) {
                    extractor.seekTo(seekMs * 1000, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                    codec.flush(); inDone = false
                    tracks.forEach { it?.pause(); it?.flush(); it?.play() }
                    seekMs = -1
                }
                if (paused) {
                    tracks.forEach { it?.pause() }
                    while (paused && !stopFlag) Thread.sleep(40)
                    tracks.forEach { it?.play() }
                    continue
                }
                if (!inDone) {
                    val i = codec.dequeueInputBuffer(5000)
                    if (i >= 0) {
                        val n = extractor.readSampleData(codec.getInputBuffer(i)!!, 0)
                        if (n < 0) { codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true }
                        else { codec.queueInputBuffer(i, 0, n, extractor.sampleTime, 0); extractor.advance() }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 5000)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val f = codec.outputFormat
                    channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    openTracks(f.getInteger(MediaFormat.KEY_SAMPLE_RATE), devs, 4)
                } else if (o >= 0) {
                    if (info.size > 0 && tracks.any { it != null }) {
                        val out = codec.getOutputBuffer(o)!!
                        out.position(info.offset); out.limit(info.offset + info.size)
                        val sb = out.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                        val arr = ShortArray(sb.remaining()); sb.get(arr)
                        val n = arr.size / channels
                        if (src.size < n * 2) src = FloatArray(n * 2)
                        for (k in 0 until n) {
                            val l = arr[k * channels] / 32768f
                            val r = if (channels > 1) arr[k * channels + 1] / 32768f else l
                            src[2 * k] = l; src[2 * k + 1] = r
                        }
                        pushFrames(src, n)
                        positionMs = info.presentationTimeUs / 1000
                    }
                    codec.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
                }
            }
        } catch (e: Exception) {
            status = "Error: ${e.message}"
        } finally {
            closeTracks()
            codec?.release(); extractor.release()
            isPlaying = false
        }
    }

    // ---------- diagnostics ----------
    /** 6 s sweep through ONE device's chain, so you can hear routing + role + EQ on that device alone. */
    fun testTone(idx: Int) {
        val dev = selected[idx]
        if (dev == null) { status = "Pick a device first."; return }
        if (thread?.isAlive == true) { status = "Stop playback first."; return }
        stopFlag = false
        thread = Thread {
            var t: AudioTrack? = null
            try {
                val sr = 48000
                val ch = chains[idx]; ch.prepare(sr)
                val tr = buildTrack(sr, dev, 4); t = tr; tr.play()
                val n = 2400; val total = sr * 6
                val w = FloatArray(n * 2); val s = ShortArray(n * 2)
                var pos = 0; var phase = 0.0; var c = 0
                while (pos < total && !stopFlag) {
                    for (k in 0 until n) {
                        val f = 40.0 * (12000.0 / 40.0).pow((pos + k).toDouble() / total)
                        phase += 2 * PI * f / sr
                        val v = (0.3 * sin(phase)).toFloat(); w[2 * k] = v; w[2 * k + 1] = v
                    }
                    ch.process(w, n, crossoverHz)
                    for (k in 0 until n * 2) s[k] = (w[k] * 32767f).toInt().toShort()
                    tr.write(s, 0, n * 2); pos += n
                    if (++c == 8) {
                        val got = tr.routedDevice
                        status = "Test ${"AB"[idx]}: " + if (got != null && got.id == dev.id) "✓ playing on ${dev.productName}"
                        else "✗ Android sent it to ${got?.productName ?: "another output"}"
                    }
                }
                tr.stop()
            } catch (e: Exception) { status = "Error: ${e.message}" } finally { t?.release() }
        }.also { it.start() }
    }
}

/** Foreground service that owns the screen-capture permission while we capture phone audio. */
class CaptureService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val player = Engine.get(this)
        if (intent?.action == "STOP") {
            player.stop(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
            return START_NOT_STICKY
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("capture", "Audio capture", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, "capture")
            .setContentTitle("Dual Audio EQ")
            .setContentText("Processing phone audio")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(1, n)

        val code = intent?.getIntExtra("code", 0) ?: 0
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) intent?.getParcelableExtra("data", Intent::class.java)
        else @Suppress("DEPRECATION") intent?.getParcelableExtra("data")
        if (data == null) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return START_NOT_STICKY }

        val proj = getSystemService(MediaProjectionManager::class.java).getMediaProjection(code, data)
        proj.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { player.stop() }
        }, Handler(Looper.getMainLooper()))
        if (!player.startCapture(proj)) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
        return START_NOT_STICKY
    }

    override fun onDestroy() { Engine.get(this).stop(); super.onDestroy() }
}


object FxState {
    var running by mutableStateOf(false)
    var log by mutableStateOf("Not running.")
}

/**
 * "EQ only" mode: no screen share. Players announce their audio session with a broadcast;
 * we attach the system Equalizer effect to that session and drive it from Device A's sliders.
 */
class EffectsService : Service() {
    private val eqs = HashMap<Int, Equalizer>()
    private val seen = ArrayList<String>()
    private val handler = Handler(Looper.getMainLooper())
    private var last: List<Float> = emptyList()
    private var registered = false
    private var activeIdx = 0
    private var lastRouteCheck = 0L
    private var lastAdded = ""
    private var routeInfo = ""
    private val audioManager by lazy { getSystemService(AudioManager::class.java) }

    private val devCb = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>?) {
            val sel = Engine.get(this@EffectsService).selected
            added?.lastOrNull { d -> sel.any { it?.key() == d.key() } }?.let { lastAdded = it.key() }
        }
    }

    /** Decides which device's EQ profile to use, from which selected device is currently playing. */
    private fun pickActive() {
        val p = Engine.get(this)
        val sel = p.selected
        val route = p.systemRoute().map { it.key() }.toSet()
        val present = if (route.isNotEmpty()) route
            else audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { it.key() }.toSet()
        val a = sel[0]?.let { it.key() in present } == true
        val b = sel[1]?.let { it.key() in present } == true
        routeInfo = when {
            a && b && route.isNotEmpty() -> { activeIdx = 0
                "Both devices are playing. Android applies one EQ to both, so Device A's EQ is used for both." }
            a && b -> { activeIdx = if (lastAdded == sel[1]?.key()) 1 else 0
                "Both connected. Using the EQ of the one connected last (Device ${"AB"[activeIdx]})." }
            b -> { activeIdx = 1; "Playing on ${sel[1]?.productName} → using Device B's EQ." }
            a -> { activeIdx = 0; "Playing on ${sel[0]?.productName} → using Device A's EQ." }
            else -> { activeIdx = 0; "Neither selected device is active. Using Device A's EQ. Pick your devices in the cards below." }
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val sid = i.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, AudioEffect.ERROR_BAD_VALUE)
            val pkg = i.getStringExtra(AudioEffect.EXTRA_PACKAGE_NAME) ?: "unknown app"
            if (pkg == packageName) return
            if (i.action == AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION) {
                seen.add("→ $pkg opened session $sid"); attach(sid)
            } else {
                seen.add("← $pkg closed session $sid"); detach(sid)
            }
            publish()
        }
    }

    private val poll = object : Runnable {
        override fun run() {
            val now = SystemClock.elapsedRealtime()
            if (now - lastRouteCheck > 1000) { lastRouteCheck = now; pickActive(); publish() }
            val g = gains()
            if (g != last) { last = g; eqs.values.forEach { applyTo(it, g) } }
            handler.postDelayed(this, 250)
        }
    }

    private fun gains(): List<Float> = Engine.get(this).chains[activeIdx].gains.toList()

    private fun interp(f: Double, g: List<Float>): Double {
        val fs = BAND_FREQS
        if (f <= fs[0]) return g[0].toDouble()
        if (f >= fs[4]) return g[4].toDouble()
        for (i in 0 until 4) if (f <= fs[i + 1]) {
            val t = (ln(f) - ln(fs[i].toDouble())) / (ln(fs[i + 1].toDouble()) - ln(fs[i].toDouble()))
            return g[i] + (g[i + 1] - g[i]) * t
        }
        return 0.0
    }

    private fun applyTo(e: Equalizer, g: List<Float>) {
        try {
            val range = e.getBandLevelRange()
            for (b in 0 until e.getNumberOfBands().toInt()) {
                val hz = e.getCenterFreq(b.toShort()) / 1000.0
                val lvl = (interp(hz, g) * 100).toInt().coerceIn(range[0].toInt(), range[1].toInt())
                e.setBandLevel(b.toShort(), lvl.toShort())
            }
        } catch (_: Exception) {}
    }

    private fun attach(sid: Int) {
        if (sid == AudioEffect.ERROR_BAD_VALUE || eqs.containsKey(sid)) return
        try {
            val e = Equalizer(0, sid)
            e.setEnabled(true)
            eqs[sid] = e
            applyTo(e, gains())
        } catch (ex: Exception) { seen.add("  ✗ couldn't attach to session $sid: ${ex.message}") }
    }

    private fun detach(sid: Int) {
        eqs.remove(sid)?.let { try { it.release() } catch (_: Exception) {} }
    }

    private fun publish() {
        val head = if (seen.none { it.startsWith("→") })
            "Waiting for a player to announce its audio session…\nNow skip or restart a track in Echo."
        else "Equalizer attached to ${eqs.size} session(s)."
        FxState.log = head + "\n" + routeInfo + "\n" + seen.takeLast(6).joinToString("\n")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return START_NOT_STICKY }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("capture", "Audio capture", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, "capture")
            .setContentTitle("Dual Audio EQ")
            .setContentText("Equalizer active")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(2, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        else startForeground(2, n)

        if (!registered) {
            val f = IntentFilter().apply {
                addAction(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION)
                addAction(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION)
            }
            ContextCompat.registerReceiver(this, receiver, f, ContextCompat.RECEIVER_EXPORTED)
            registered = true
            audioManager.registerAudioDeviceCallback(devCb, handler)
            pickActive()
            try {
                val g = Equalizer(0, 0); g.setEnabled(true); eqs[0] = g; applyTo(g, gains())
                seen.add("Global (session 0) EQ attached. Only works on some phones.")
            } catch (_: Exception) { seen.add("Global (session 0) EQ not available on this phone.") }
            handler.post(poll)
        }
        FxState.running = true
        publish()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(poll)
        if (registered) {
            try { unregisterReceiver(receiver) } catch (_: Exception) {}
            try { audioManager.unregisterAudioDeviceCallback(devCb) } catch (_: Exception) {}
        }
        eqs.values.forEach { try { it.release() } catch (_: Exception) {} }
        eqs.clear()
        FxState.running = false; FxState.log = "Stopped."
        super.onDestroy()
    }
}
