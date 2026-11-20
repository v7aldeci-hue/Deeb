// MainActivity.kt
// Package: com.example.radiosync
// Native Android (Kotlin) – no WebView, no HTML/JS, no Python.

package com.example.radiosync

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.*
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import kotlinx.coroutines.*
import java.io.*
import java.net.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.*
import kotlin.random.Random

// ---------------------------------------------------------------------------
// 1. Delay Audio Processor (BaseAudioProcessor)
// ---------------------------------------------------------------------------
@OptIn(UnstableApi::class)
class DelayAudioProcessor(
    private val sampleRate: Int,
    private val channelCount: Int
) : BaseAudioProcessor() {

    // 125 seconds of 16‑bit PCM
    private val maxBufferSamples = 125 * sampleRate * channelCount
    private val ringBuffer = ShortArray(maxBufferSamples)
    private val writeIndex = AtomicInteger(0)
    private val readIndex = AtomicInteger(0)

    @Volatile var delaySeconds: Double = 0.0
        set(value) {
            field = value.coerceIn(0.0, 120.0)
            val samples = (field * sampleRate * channelCount).toInt()
            readIndex.set((writeIndex.get() - samples + maxBufferSamples) % maxBufferSamples)
        }

    @Volatile var isMuted: Boolean = false

    // Envelope: one value every 10 ms (RMS of the block)
    private val envelopeIntervalMs = 10
    private val envelopeQueue = ConcurrentLinkedQueue<Pair<Long, Float>>() // (nanoTime, rms)

    fun pollEnvelope(): List<Pair<Long, Float>> {
        val list = mutableListOf<Pair<Long, Float>>()
        while (true) {
            val item = envelopeQueue.poll() ?: break
            list.add(item)
        }
        return list
    }

    override fun onConfigure(inputAudioFormat: androidx.media3.common.audio.AudioProcessor.AudioFormat): androidx.media3.common.audio.AudioProcessor.AudioFormat {
        return androidx.media3.common.audio.AudioProcessor.AudioFormat(
            sampleRate,
            channelCount,
            C.ENCODING_PCM_16BIT
        )
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining <= 0) return

        // Convert float input to 16‑bit if needed
        val isFloat = inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT
        val inputSamples: ShortArray
        if (isFloat) {
            val floatBuffer = inputBuffer.order(ByteOrder.nativeOrder()).asFloatBuffer()
            val floatCount = floatBuffer.remaining()
            inputSamples = ShortArray(floatCount)
            for (i in 0 until floatCount) {
                val f = floatBuffer.get()
                inputSamples[i] = (f.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
            }
        } else {
            val shortBuffer = inputBuffer.order(ByteOrder.nativeOrder()).asShortBuffer()
            inputSamples = ShortArray(shortBuffer.remaining())
            shortBuffer.get(inputSamples)
        }
        inputBuffer.position(inputBuffer.limit())

        // Write to ring buffer
        val writePos = writeIndex.get()
        for (i in inputSamples.indices) {
            ringBuffer[(writePos + i) % maxBufferSamples] = inputSamples[i]
        }
        writeIndex.set((writePos + inputSamples.size) % maxBufferSamples)

        // Envelope (RMS) – one value per block, tagged with nanoTime
        if (inputSamples.isNotEmpty()) {
            var sum = 0.0
            for (s in inputSamples) sum += s.toDouble() * s
            val rms = sqrt(sum / inputSamples.size).toFloat()
            envelopeQueue.add(System.nanoTime() to rms)
        }

        // Determine output size (same as input)
        val outputBuffer = replaceOutputBuffer(inputSamples.size * 2) // 16‑bit = 2 bytes
        val outShort = outputBuffer.order(ByteOrder.nativeOrder()).asShortBuffer()

        if (isMuted) {
            for (i in 0 until inputSamples.size) outShort.put(0)
        } else {
            val readPos = readIndex.get()
            for (i in 0 until inputSamples.size) {
                outShort.put(ringBuffer[(readPos + i) % maxBufferSamples])
            }
            readIndex.set((readPos + inputSamples.size) % maxBufferSamples)
        }
        outputBuffer.position(0)
        outputBuffer.limit(inputSamples.size * 2)
    }

    override fun onFlush() {
        envelopeQueue.clear()
    }

    override fun onReset() {
        writeIndex.set(0)
        readIndex.set(0)
        envelopeQueue.clear()
        delaySeconds = 0.0
        isMuted = false
    }
}

// ---------------------------------------------------------------------------
// 2. Local TCP Relay (HTTP/ICY → raw audio)
// ---------------------------------------------------------------------------
class LocalRelayServer(private val remoteUrl: String) {
    private var serverSocket: ServerSocket? = null
    private var relayThread: Thread? = null

    @Volatile var localPort: Int = -1
        private set

    fun start(): Int {
        serverSocket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        localPort = serverSocket!!.localPort
        relayThread = Thread {
            try {
                while (!Thread.currentThread().isInterrupted) {
                    val client = serverSocket!!.accept()
                    Thread { handleClient(client) }.start()
                }
            } catch (_: IOException) { }
        }.apply { start() }
        return localPort
    }

    fun stop() {
        relayThread?.interrupt()
        try { serverSocket?.close() } catch (_: IOException) { }
    }

    private fun handleClient(client: Socket) {
        try {
            val remote = URL(remoteUrl).openConnection() as HttpURLConnection
            remote.instanceFollowRedirects = true
            remote.setRequestProperty("User-Agent", "VLC/3.0.18")
            remote.setRequestProperty("Icy-MetaData", "0")
            remote.connectTimeout = 15_000
            remote.readTimeout = 15_000
            remote.connect()

            val input = remote.inputStream
            val output = client.getOutputStream()

            // Send a clean HTTP/1.1 200 OK to ExoPlayer (converts ICY if needed)
            val header = "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: audio/mpeg\r\n" +
                    "Connection: close\r\n\r\n"
            output.write(header.toByteArray())
            output.flush()

            val buffer = ByteArray(16 * 1024)
            while (!Thread.currentThread().isInterrupted) {
                val read = input.read(buffer)
                if (read < 0) break
                output.write(buffer, 0, read)
                output.flush()
            }
        } catch (_: Exception) {
        } finally {
            try { client.close() } catch (_: IOException) { }
        }
    }
}

// ---------------------------------------------------------------------------
// 3. Sync Engine (normalised cross‑correlation on envelopes)
// ---------------------------------------------------------------------------
object SyncEngine {
    data class Result(val delaySeconds: Double, val confidenceLabel: String)

    fun computeDelay(
        radioEnvelope: List<Pair<Long, Float>>,
        micEnvelope: List<Pair<Long, Float>>,
        maxDelaySec: Double = 120.0
    ): Result {
        if (radioEnvelope.size < 5 || micEnvelope.size < 5) {
            return Result(0.0, "منخفضة")
        }

        // Resample both to 100 Hz (10 ms grid) using nearest timestamp.
        val startNano = minOf(radioEnvelope.first().first, micEnvelope.first().first)
        val radioResampled = resample(radioEnvelope, startNano)
        val micResampled = resample(micEnvelope, startNano)

        val maxLag = (maxDelaySec * 100).toInt().coerceAtMost(radioResampled.size - 5)
        if (maxLag <= 0) return Result(0.0, "منخفضة")

        // Normalised cross‑correlation
        var bestLag = 0
        var bestCorr = -2.0
        for (lag in 0..maxLag) {
            val corr = normalizedCorrelation(radioResampled, micResampled, lag)
            if (corr > bestCorr) {
                bestCorr = corr
                bestLag = lag
            }
        }

        val delaySec = bestLag / 100.0
        val confidence = when {
            bestCorr > 0.6 -> "عالية"
            bestCorr > 0.35 -> "متوسطة"
            else -> "منخفضة"
        }
        return Result(delaySec, confidence)
    }

    private fun resample(
        envelope: List<Pair<Long, Float>>,
        startNano: Long,
        targetHz: Int = 100
    ): FloatArray {
        if (envelope.isEmpty()) return FloatArray(0)
        val durationSec = (envelope.last().first - startNano) / 1_000_000_000.0
        val size = (durationSec * targetHz).toInt().coerceAtLeast(1)
        val result = FloatArray(size)
        var idx = 0
        var nextTargetTime = startNano
        for ((ts, rms) in envelope) {
            while (idx < size && ts >= nextTargetTime) {
                result[idx] = rms
                idx++
                nextTargetTime += 1_000_000_000L / targetHz
            }
            if (idx >= size) break
        }
        return result
    }

    private fun normalizedCorrelation(a: FloatArray, b: FloatArray, lag: Int): Double {
        val n = minOf(a.size, b.size - lag)
        if (n < 5) return -2.0
        var sumA = 0.0; var sumB = 0.0; var sumAB = 0.0
        var sumA2 = 0.0; var sumB2 = 0.0
        for (i in 0 until n) {
            val va = a[i].toDouble()
            val vb = b[i + lag].toDouble()
            sumA += va; sumB += vb
            sumAB += va * vb
            sumA2 += va * va; sumB2 += vb * vb
        }
        val numerator = n * sumAB - sumA * sumB
        val denom = sqrt((n * sumA2 - sumA * sumA) * (n * sumB2 - sumB * sumB))
        return if (denom == 0.0) -2.0 else numerator / denom
    }
}

// ---------------------------------------------------------------------------
// 4. MainActivity
// ---------------------------------------------------------------------------
@OptIn(UnstableApi::class)
class MainActivity : AppCompatActivity() {

    // UI
    private lateinit var statusText: TextView
    private lateinit var delayReadout: TextView
    private lateinit var confidenceBadge: TextView
    private lateinit var urlInput: EditText
    private lateinit var playButton: Button
    private lateinit var measureButton: Button
    private lateinit var countdownBar: ProgressBar
    private lateinit var syncButton: Button
    private lateinit var muteButton: Button
    private lateinit var resetButton: Button
    private lateinit var manualDelayReadout: TextView
    private lateinit var delaySlider: SeekBar
    private lateinit var durationGroup: RadioGroup

    // Player
    private var player: ExoPlayer? = null
    private var audioProcessor: DelayAudioProcessor? = null
    private var relayServer: LocalRelayServer? = null

    // State
    private var isPlaying = false
    private var isMeasuring = false
    private var measuredDelay: Double? = null
    private var measuredConfidence: String? = null
    private var selectedDurationSec = 20

    private val mainHandler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("radio_sync", Context.MODE_PRIVATE) }

    private val recordPermissionLauncher =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startMeasurement()
            else updateStatus("يجب السماح بالوصول إلى الميكروفون لقياس التأخير")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = Color.parseColor("#0B1120")
        window.navigationBarColor = Color.parseColor("#0B1120")

        buildUI()
        restorePrefs()
        setRtl()
    }

    private fun setRtl() {
        window.decorView.layoutDirection = View.LAYOUT_DIRECTION_RTL
    }

    // -----------------------------------------------------------------------
    // UI construction (programmatic, no XML)
    // -----------------------------------------------------------------------
    private fun buildUI() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0B1120"))
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        val scroll = ScrollView(this)
        scroll.addView(root, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        // 1. Status card
        val statusCard = card()
        statusText = TextView(this).apply {
            text = "لم يتم الاتصال بعد"
            setTextColor(Color.WHITE)
            textSize = 16f
        }
        delayReadout = TextView(this).apply {
            text = "الصورة 0.0 ث • الصوت 0.00 ث"
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 14f
        }
        confidenceBadge = TextView(this).apply {
            visibility = View.GONE
            setTextColor(Color.parseColor("#10B981"))
            textSize = 12f
            setPadding(dp(8), dp(4), dp(8), dp(4))
            background = roundedBg(Color.parseColor("#10B981"), 20)
        }
        statusCard.addView(statusText)
        statusCard.addView(delayReadout)
        statusCard.addView(confidenceBadge)
        root.addView(statusCard)

        // 2. Stream bar
        val streamCard = card()
        urlInput = EditText(this).apply {
            hint = "أدخل رابط البث"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#64748B"))
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine()
            setBackgroundColor(Color.parseColor("#1E293B"))
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        playButton = emeraldButton("تشغيل") { togglePlay() }
        val streamRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        streamRow.addView(urlInput, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        streamRow.addView(playButton, LinearLayout.LayoutParams(dp(90), ViewGroup.LayoutParams.WRAP_CONTENT))
        streamCard.addView(streamRow)
        root.addView(streamCard)

        // 3. Measure card
        val measureCard = card()
        measureButton = emeraldButton("فحص التأخير") { requestMeasurement() }
        measureCard.addView(measureButton)

        durationGroup = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val durations = listOf(10, 15, 20, 30, 45, 60)
        for ((i, sec) in durations.withIndex()) {
            val rb = RadioButton(this).apply {
                text = "${sec} ث"
                setTextColor(Color.WHITE)
                id = View.generateViewId()
                tag = sec
                isChecked = sec == 20
            }
            durationGroup.addView(rb)
        }
        durationGroup.setOnCheckedChangeListener { _, checkedId ->
            val rb = findViewById<RadioButton>(checkedId)
            selectedDurationSec = rb?.tag as? Int ?: 20
        }
        measureCard.addView(durationGroup)

        countdownBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            visibility = View.GONE
            progressTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#10B981"))
        }
        measureCard.addView(countdownBar)
        root.addView(measureCard)

        // 4. Action row
        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(8))
        }
        syncButton = emeraldButton("زامن الآن") { applyMeasuredDelay() }
        muteButton = emeraldButton("صوت القناة") { toggleMute() }
        resetButton = emeraldButton("إيقاف المزامنة") { resetDelay() }
        actionRow.addView(syncButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        actionRow.addView(muteButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        actionRow.addView(resetButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(actionRow)

        // 5. Manual fine‑tuning card
        val manualCard = card()
        val manualTitle = TextView(this).apply {
            text = "ضبط يدوي"
            setTextColor(Color.WHITE)
            textSize = 18f
            setPadding(0, 0, 0, dp(8))
        }
        manualCard.addView(manualTitle)

        manualDelayReadout = TextView(this).apply {
            text = "0.00 ث"
            setTextColor(Color.parseColor("#10B981"))
            textSize = 32f
            gravity = Gravity.CENTER
        }
        manualCard.addView(manualDelayReadout)

        delaySlider = SeekBar(this).apply {
            max = 12000 // 0..120.00 in steps of 0.01
            progress = 0
            progressTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#10B981"))
            thumbTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#10B981"))
        }
        delaySlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val seconds = progress / 100.0
                    manualDelayReadout.text = String.format("%.2f ث", seconds)
                    audioProcessor?.delaySeconds = seconds
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        manualCard.addView(delaySlider)

        val nudgeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, 0)
        }
        val nudges = listOf(-10.0, -1.0, -0.25, 0.25, 1.0, 10.0)
        for (n in nudges) {
            val btn = Button(this).apply {
                text = if (n > 0) "+${n} ث" else "${n} ث"
                setTextColor(Color.WHITE)
                textSize = 12f
                background = roundedBg(Color.parseColor("#334155"), 20)
                setPadding(dp(8), dp(6), dp(8), dp(6))
                setOnClickListener {
                    val current = delaySlider.progress / 100.0
                    val newVal = (current + n).coerceIn(0.0, 120.0)
                    delaySlider.progress = (newVal * 100).toInt()
                }
            }
            nudgeRow.addView(btn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(4)
            })
        }
        manualCard.addView(nudgeRow)
        root.addView(manualCard)

        setContentView(scroll)
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun card(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = roundedBg(Color.parseColor("#1E293B"), 16)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) }
        }
    }

    private fun emeraldButton(text: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            this.text = text
            setTextColor(Color.WHITE)
            textSize = 15f
            background = roundedBg(Color.parseColor("#10B981"), 12)
            setOnClickListener { onClick() }
        }
    }

    private fun roundedBg(color: Int, radiusDp: Int): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
        }
    }

    private fun updateStatus(msg: String) {
        mainHandler.post { statusText.text = msg }
    }

    private fun updateDelayReadout(tvDelay: Double, radioDelay: Double) {
        mainHandler.post {
            delayReadout.text = "الصورة %.1f ث • الصوت %.2f ث".format(tvDelay, radioDelay)
        }
    }

    // -----------------------------------------------------------------------
    // Preferences
    // -----------------------------------------------------------------------
    private fun restorePrefs() {
        val lastUrl = prefs.getString("last_url", "http://foxfm.xyz:8080/live1") ?: "http://foxfm.xyz:8080/live1"
        urlInput.setText(lastUrl)
    }

    private fun saveLastUrl(url: String) {
        prefs.edit().putString("last_url", url).apply()
    }

    // -----------------------------------------------------------------------
    // Playback
    // -----------------------------------------------------------------------
    private fun togglePlay() {
        if (isPlaying) stopPlayback() else startPlayback()
    }

    private fun startPlayback() {
        val url = urlInput.text.toString().trim()
        if (url.isEmpty()) {
            updateStatus("الرجاء إدخال رابط البث")
            return
        }
        saveLastUrl(url)

        val isHls = url.contains(".m3u8", ignoreCase = true)
        val playbackUrl: String
        if (isHls) {
            playbackUrl = url
        } else {
            // Start local relay
            relayServer = LocalRelayServer(url)
            val port = relayServer!!.start()
            playbackUrl = "http://127.0.0.1:$port/stream"
        }

        audioProcessor = DelayAudioProcessor(44100, 2) // common for MP3/AAC; will be reconfigured
        val renderersFactory = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): AudioSink {
                return DefaultAudioSink.Builder(context)
                    .setAudioProcessorChain(
                        DefaultAudioSink.DefaultAudioProcessorChain(
                            arrayOf(audioProcessor)
                        )
                    )
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .build()
            }
        }

        player = ExoPlayer.Builder(this, renderersFactory)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(
                    DefaultHttpDataSource.Factory()
                        .setUserAgent("VLC/3.0.18")
                        .setAllowCrossProtocolRedirects(true)
                )
            )
            .build().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                        .build(),
                    true
                )
                addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        updateStatus("خطأ في البث: ${error.errorCodeName} – ${error.message}")
                    }

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_READY) {
                            updateStatus("البث يعمل الآن")
                        }
                    }
                })
                setMediaItem(MediaItem.fromUri(playbackUrl))
                prepare()
                playWhenReady = true
            }

        isPlaying = true
        playButton.text = "إيقاف"
        updateStatus("جارٍ الاتصال بالبث…")
    }

    private fun stopPlayback() {
        player?.release()
        player = null
        audioProcessor = null
        relayServer?.stop()
        relayServer = null
        isPlaying = false
        playButton.text = "تشغيل"
        updateStatus("تم إيقاف البث")
    }

    // -----------------------------------------------------------------------
    // Mute / Reset
    // -----------------------------------------------------------------------
    private fun toggleMute() {
        val proc = audioProcessor ?: return
        proc.isMuted = !proc.isMuted
        muteButton.text = if (proc.isMuted) "إلغاء الكتم" else "صوت القناة"
    }

    private fun resetDelay() {
        audioProcessor?.delaySeconds = 0.0
        delaySlider.progress = 0
        manualDelayReadout.text = "0.00 ث"
        measuredDelay = null
        measuredConfidence = null
        confidenceBadge.visibility = View.GONE
        updateStatus("تمت إعادة التأخير إلى 0.00 ث")
    }

    // -----------------------------------------------------------------------
    // Measurement
    // -----------------------------------------------------------------------
    private fun requestMeasurement() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            recordPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            startMeasurement()
        }
    }

    private fun startMeasurement() {
        if (isMeasuring) return
        val proc = audioProcessor ?: run {
            updateStatus("يجب تشغيل البث أولاً")
            return
        }

        isMeasuring = true
        measureButton.isEnabled = false
        countdownBar.visibility = View.VISIBLE
        countdownBar.progress = 0

        val wasMuted = proc.isMuted
        proc.isMuted = true // mute during measurement

        val radioEnvelope = mutableListOf<Pair<Long, Float>>()
        val micEnvelope = mutableListOf<Pair<Long, Float>>()

        // Start AudioRecord (16 kHz mono PCM)
        val sampleRate = 16000
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val minBuf = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            channelConfig,
            audioFormat,
            minBuf * 4
        )
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            updateStatus("تعذر تهيئة الميكروفون")
            isMeasuring = false
            return
        }

        val scope = CoroutineScope(Dispatchers.IO)
        scope.launch {
            recorder.startRecording()
            val buffer = ShortArray(1600) // 100 ms at 16 kHz
            val startNano = System.nanoTime()
            var elapsedMs = 0L
            val totalMs = selectedDurationSec * 1000L

            while (elapsedMs < totalMs && isActive) {
                val read = recorder.read(buffer, 0, buffer.size)
                if (read > 0) {
                    var sum = 0.0
                    for (i in 0 until read) sum += buffer[i].toDouble() * buffer[i]
                    val rms = sqrt(sum / read).toFloat()
                    micEnvelope.add(System.nanoTime() to rms)
                }
                elapsedMs = (System.nanoTime() - startNano) / 1_000_000
                withContext(Dispatchers.Main) {
                    countdownBar.progress = (elapsedMs * 100 / totalMs).toInt()
                }
            }

            recorder.stop()
            recorder.release()

            // Collect radio envelope accumulated during the same window
            radioEnvelope.addAll(proc.pollEnvelope())

            withContext(Dispatchers.Main) {
                countdownBar.visibility = View.GONE
                measureButton.isEnabled = true
                isMeasuring = false
                proc.isMuted = wasMuted

                if (radioEnvelope.size < 10 || micEnvelope.size < 10) {
                    updateStatus("لم يتم جمع بيانات كافية للقياس")
                    return@withContext
                }

                val result = SyncEngine.computeDelay(radioEnvelope, micEnvelope, 120.0)
                measuredDelay = result.delaySeconds
                measuredConfidence = result.confidenceLabel

                confidenceBadge.visibility = View.VISIBLE
                confidenceBadge.text = "الثقة: ${result.confidenceLabel}"

                if (result.delaySeconds > 0.05) {
                    updateDelayReadout(0.0, result.delaySeconds)
                    updateStatus("تم اكتشاف تأخير %.2f ث (الثقة: %s). اضغط «زامن الآن» للتطبيق.".format(
                        result.delaySeconds, result.confidenceLabel))
                } else {
                    updateDelayReadout(0.0, 0.0)
                    updateStatus("الراديو متأخر أو متزامن بالفعل مع الصورة. لا يمكن تقديم الصوت.")
                }
            }
        }
    }

    private fun applyMeasuredDelay() {
        val d = measuredDelay ?: run {
            updateStatus("قم بإجراء فحص التأخير أولاً")
            return
        }
        if (d <= 0.05) {
            updateStatus("لا يوجد تأخير موجب لتطبيقه (الراديو ليس متقدماً على الصورة)")
            return
        }
        audioProcessor?.delaySeconds = d
        delaySlider.progress = (d * 100).toInt()
        manualDelayReadout.text = String.format("%.2f ث", d)
        updateDelayReadout(0.0, d)
        updateStatus("تمت المزامنة عند تأخير %.2f ث".format(d))
    }

    // -----------------------------------------------------------------------
    override fun onDestroy() {
        super.onDestroy()
        player?.release()
        relayServer?.stop()
    }
}
