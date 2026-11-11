package com.example.dualaudio

import android.content.Context
import android.media.*
import android.net.Uri
import androidx.compose.runtime.*
import java.nio.ByteOrder

/**
 * Decodes a file once and feeds TWO AudioTracks, each pinned to its own output device
 * and run through its own DeviceChain (role / crossover / EQ / volume / delay).
 */
class DualPlayer(private val context: Context) {
    val chains = arrayOf(DeviceChain(), DeviceChain())
    val selected = mutableStateListOf<AudioDeviceInfo?>(null, null)
    var crossoverHz by mutableFloatStateOf(80f)
    var isPlaying by mutableStateOf(false)
    var positionMs by mutableLongStateOf(0L)
    var durationMs by mutableLongStateOf(0L)
    var status by mutableStateOf("Pick an audio file to start.")
    var lastUri: Uri? = null

    @Volatile private var paused = false
    @Volatile private var stopFlag = false
    @Volatile private var seekMs = -1L
    private var thread: Thread? = null

    fun start(uri: Uri) {
        if (selected.all { it == null }) { status = "No Bluetooth device selected."; return }
        stop()
        lastUri = uri; stopFlag = false; paused = false; isPlaying = true
        val devs = selected.toList()
        thread = Thread { run(uri, devs) }.also { it.start() }
    }

    fun togglePause() { paused = !paused; isPlaying = !paused }
    fun seekTo(ms: Long) { seekMs = ms }

    fun stop() {
        stopFlag = true
        thread?.join(1500); thread = null
        isPlaying = false
    }

    private fun run(uri: Uri, devs: List<AudioDeviceInfo?>) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        val tracks = arrayOfNulls<AudioTrack>(2)
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
            var channels = 2; var first = true
            var work = FloatArray(0); var src = FloatArray(0); var pcm = ShortArray(0)

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
                    val sr = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    for (d in 0..1) {
                        chains[d].prepare(sr)
                        val dev = devs[d] ?: continue
                        val min = AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
                        tracks[d] = AudioTrack.Builder()
                            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setSampleRate(sr).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
                            .setBufferSizeInBytes(min * 4)
                            .setTransferMode(AudioTrack.MODE_STREAM).build()
                            .also { it.setPreferredDevice(dev); it.play() }
                    }
                } else if (o >= 0) {
                    if (info.size > 0 && tracks.any { it != null }) {
                        val out = codec.getOutputBuffer(o)!!
                        out.position(info.offset); out.limit(info.offset + info.size)
                        val sb = out.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                        val arr = ShortArray(sb.remaining()); sb.get(arr)
                        val n = arr.size / channels
                        if (work.size < n * 2) { work = FloatArray(n * 2); src = FloatArray(n * 2); pcm = ShortArray(n * 2) }
                        for (k in 0 until n) {
                            val l = arr[k * channels] / 32768f
                            val r = if (channels > 1) arr[k * channels + 1] / 32768f else l
                            src[2 * k] = l; src[2 * k + 1] = r
                        }
                        for (d in 0..1) {
                            val t = tracks[d] ?: continue
                            System.arraycopy(src, 0, work, 0, n * 2)
                            chains[d].process(work, n, crossoverHz)
                            for (k in 0 until n * 2) pcm[k] = (work[k] * 32767f).toInt().toShort()
                            t.write(pcm, 0, n * 2)   // blocking
                        }
                        if (first) { first = false; status = routingReport(tracks, devs) }
                        positionMs = info.presentationTimeUs / 1000
                    }
                    codec.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
                }
            }
            tracks.forEach { it?.stop() }
        } catch (e: Exception) {
            status = "Error: ${e.message}"
        } finally {
            tracks.forEach { it?.release() }
            codec?.release(); extractor.release()
            isPlaying = false
        }
    }

    /** Tells the user whether Android actually honoured each device pin. */
    private fun routingReport(tracks: Array<AudioTrack?>, devs: List<AudioDeviceInfo?>): String {
        val sb = StringBuilder()
        for (d in 0..1) {
            val t = tracks[d] ?: continue
            val want = devs[d]!!; val got = t.routedDevice
            val ok = got != null && got.id == want.id
            sb.append("${"AB"[d]}: ${if (ok) "✓" else "✗ routed to ${got?.productName ?: "unknown"}"} ${want.productName}\n")
        }
        return sb.toString().trim()
    }
}
