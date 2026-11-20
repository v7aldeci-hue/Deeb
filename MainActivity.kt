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
import androidx.media3.common.audio.AudioProcessor
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
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.*
import kotlin.random.Random

// ---------------------------------------------------------------------------
// 1. Delay Audio Processor (BaseAudioProcessor)
// ---------------------------------------------------------------------------
@OptIn(UnstableApi::class)
class DelayAudioProcessor : BaseAudioProcessor() {
    private var ringBuffer: ShortArray = ShortArray(0)
    private val writeIndex = AtomicInteger(0)
    private var sampleRate = 44100
    private var channelCount = 2

    @Volatile var delaySeconds: Double = 0.0
        set(value) {
            field = value.coerceIn(0.0, 120.0)
        }

    @Volatile var isMuted: Boolean = false

    private val envelopeQueue = ConcurrentLinkedQueue<Pair<Long, Float>>()

    fun pollEnvelope(): List<Pair<Long, Float>> {
        val list = mutableListOf<Pair<Long, Float>>()
        while (true) {
            val item = envelopeQueue.poll() ?: break
            list.add(item)
        }
        return list
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.sampleRate != sampleRate || inputAudioFormat.channelCount != channelCount) {
            sampleRate = inputAudioFormat.sampleRate
            channelCount = inputAudioFormat.channelCount
            val maxBufferSamples = 125 * sampleRate * channelCount
            ringBuffer = ShortArray(maxBufferSamples)
            writeIndex.set(0)
        }
        return AudioProcessor.AudioFormat(sampleRate, channelCount, C.ENCODING_PCM_16BIT)
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining <= 0) return

        val isFloat = inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT
        val inputSamples: ShortArray
        if (isFloat) {
            val floatBuffer = inputBuffer.order(ByteOrder.nativeOrder()).asFloatBuffer()
            val floatCount = floatBuffer.remaining()
            inputSamples = ShortArray(floatCount)
            for (i in 0 until floatCount) {
                inputSamples[i] = (floatBuffer.get().coerceIn(-1f, 1f) * 32767f).toInt().toShort()
            }
        } else {
            val shortBuffer = inputBuffer.order(ByteOrder.nativeOrder()).asShortBuffer()
            inputSamples = ShortArray(shortBuffer.remaining())
            shortBuffer.get(inputSamples)
        }
        inputBuffer.position(inputBuffer.limit())

        // حساب مغلف الصوت (Envelope) للقياس
        if (inputSamples.isNotEmpty()) {
            var sum = 0.0
            for (s in inputSamples) sum += s.toDouble() * s
            envelopeQueue.add(System.nanoTime() to sqrt(sum / inputSamples.size).toFloat())
        }

        val maxBufferSamples = ringBuffer.size
        if (maxBufferSamples == 0) return

        val writePos = writeIndex.get()
        for (i in inputSamples.indices) {
            ringBuffer[(writePos + i) % maxBufferSamples] = inputSamples[i]
        }
        writeIndex.set((writePos + inputSamples.size) % maxBufferSamples)

        val samplesToDelay = (delaySeconds * sampleRate * channelCount).toInt()
        var readPos = (writePos - samplesToDelay) % maxBufferSamples
        if (readPos < 0) readPos += maxBufferSamples

        val outputBuffer = replaceOutputBuffer(inputSamples.size * 2)
        val outShort = outputBuffer.order(ByteOrder.nativeOrder()).asShortBuffer()

        if (isMuted) {
            for (i in 0 until inputSamples.size) outShort.put(0)
        } else {
            for (i in 0 until inputSamples.size) {
                outShort.put(ringBuffer[(readPos + i) % maxBufferSamples])
            }
        }
        outputBuffer.position(0)
        outputBuffer.limit(inputSamples.size * 2)
    }

    override fun onFlush() {
        envelopeQueue.clear()
        writeIndex.set(0)
    }

    override fun onReset() {
        onFlush()
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
// 3. Sync Engine (Strict validation & direction detection)
// ---------------------------------------------------------------------------
object SyncEngine {
    data class Result(
        val delaySeconds: Double, // >0 تعني أن الراديو متقدم. <0 تعني أن التلفاز متقدم.
        val isSuccess: Boolean,
        val message: String
    )

    fun computeDelay(radio: List<Pair<Long, Float>>, mic: List<Pair<Long, Float>>): Result {
        if (radio.size < 10 || mic.size < 10) return Result(0.0, false, "لم يتم جمع بيانات كافية.")

        // 1. فحص الصمت (هل سمعنا التلفاز؟)
        val micRms = mic.map { it.second }.average()
        if (micRms < 250.0) {
            return Result(0.0, false, "لم يتم سماع صوت التلفاز. يرجى التأكد من رفع صوت التلفاز والمحاولة مرة أخرى.")
        }
        val radioRms = radio.map { it.second }.average()
        if (radioRms < 150.0) {
            return Result(0.0, false, "صوت البث الصوتي خافت جداً أو لا يعمل. تحقق من الرابط.")
        }

        // 2. توحيد المقاسات لمقارنة دقيقة (شبكة 10 مللي ثانية)
        val startTime = min(radio.first().first, mic.first().first)
        val endTime = max(radio.last().first, mic.last().first)
        val size = ((endTime - startTime) / 10_000_000L).toInt() + 1
        val rArr = FloatArray(size)
        val mArr = FloatArray(size)

        for ((ts, rms) in radio) {
            val idx = ((ts - startTime) / 10_000_000L).toInt()
            if (idx in rArr.indices) rArr[idx] = rms
        }
        for ((ts, rms) in mic) {
            val idx = ((ts - startTime) / 10_000_000L).toInt()
            if (idx in mArr.indices) mArr[idx] = rms
        }

        val micStartIdx = ((mic.first().first - startTime) / 10_000_000L).toInt()
        val micEndIdx = ((mic.last().first - startTime) / 10_000_000L).toInt()

        var bestLagIdx = 0
        var bestCorr = -2.0

        // البحث من -30 ثانية (التلفاز متقدم) إلى +120 ثانية (الراديو متقدم)
        val minLag = -3000
        val maxLag = 12000

        for (lag in minLag..maxLag) {
            val start = max(micStartIdx, lag)
            val end = min(micEndIdx, rArr.size + lag)

            if (start < end) {
                val mSeg = mArr.copyOfRange(start, end)
                val rSeg = rArr.copyOfRange(start - lag, end - lag)
                val corr = correlate(mSeg, rSeg)
                if (corr > bestCorr) {
                    bestCorr = corr
                    bestLagIdx = lag
                }
            }
        }

        // 3. رفض التخمين (إذا كان التطابق ضعيفاً)
        if (bestCorr < 0.25) {
            return Result(0.0, false, "لم نتمكن من اكتشاف تطابق بين البث والتلفاز. تأكد من أن المعلق يتحدث عن نفس اللعبة، أو أعد المحاولة.")
        }

        val delaySeconds = bestLagIdx / 100.0
        val confidence = if (bestCorr > 0.5) "عالية" else "متوسطة"

        return if (delaySeconds > 0) {
            Result(delaySeconds, true, "الراديو متقدم بـ %.2f ثانية. (الثقة: %s)".format(delaySeconds, confidence))
        } else if (delaySeconds < 0) {
            Result(delaySeconds, true, "التلفاز متقدم بـ %.2f ثانية. (الثقة: %s)".format(abs(delaySeconds), confidence))
        } else {
            Result(0.0, true, "الراديو والتلفاز متزامنان تماماً. (الثقة: %s)".format(confidence))
        }
    }

    private fun correlate(m: FloatArray, r: FloatArray): Double {
        if (m.isEmpty() || r.isEmpty() || m.size != r.size) return -2.0
        var sumM = 0.0; var sumR = 0.0; var sumMR = 0.0
        var sumM2 = 0.0; var sumR2 = 0.0
        for (i in m.indices) {
            val mv = m[i].toDouble(); val rv = r[i].toDouble()
            sumM += mv; sumR += rv
            sumMR += mv * rv
            sumM2 += mv * mv; sumR2 += rv * rv
        }
        val n = m.size
        val num = n * sumMR - sumM * sumR
        val den = sqrt((n * sumM2 - sumM * sumM) * (n * sumR2 - sumR * sumR))
        return if (den == 0.0) -2.0 else num / den
    }
}

// ---------------------------------------------------------------------------
// 4. MainActivity
// ---------------------------------------------------------------------------
@OptIn(UnstableApi::class)
class MainActivity : AppCompatActivity() {

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

    private var player: ExoPlayer? = null
    private var audioProcessor: DelayAudioProcessor? = null
    private var relayServer: LocalRelayServer? = null

    private var isPlaying = false
    private var isMeasuring = false
    private var measuredDelay: Double? = null
    private var selectedDurationSec = 20

    private val mainHandler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("radio_sync", Context.MODE_PRIVATE) }
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

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

    private fun buildUI() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0B1120"))
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        val scroll = ScrollView(this)
        scroll.addView(root, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

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
            max = 12000
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

    private fun updateStatus(msg: String, isError: Boolean = false) {
        mainHandler.post { 
            statusText.text = msg 
            statusText.setTextColor(if (isError) Color.parseColor("#EF4444") else Color.WHITE)
        }
    }

    private fun updateDelayReadout(tvDelay: Double, radioDelay: Double) {
        mainHandler.post {
            delayReadout.text = "الصورة %.1f ث • الصوت %.2f ث".format(tvDelay, radioDelay)
        }
    }

    private fun restorePrefs() {
        val lastUrl = prefs.getString("last_url", "http://foxfm.xyz:8080/live1") ?: "http://foxfm.xyz:8080/live1"
        urlInput.setText(lastUrl)
    }

    private fun saveLastUrl(url: String) {
        prefs.edit().putString("last_url", url).apply()
    }

    private fun togglePlay() {
        if (isPlaying) stopPlayback() else startPlayback()
    }

    private fun startPlayback() {
        val url = urlInput.text.toString().trim()
        if (url.isEmpty()) {
            updateStatus("الرجاء إدخال رابط البث", true)
            return
        }
        saveLastUrl(url)

        val isHls = url.contains(".m3u8", ignoreCase = true)
        val playbackUrl: String
        if (isHls) {
            playbackUrl = url
        } else {
            relayServer = LocalRelayServer(url)
            val port = relayServer!!.start()
            playbackUrl = "http://127.0.0.1:$port/stream"
        }

        val proc = DelayAudioProcessor()
        audioProcessor = proc

        val renderersFactory = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): AudioSink {
                return DefaultAudioSink.Builder(context)
                    .setAudioProcessorChain(
                        DefaultAudioSink.DefaultAudioProcessorChain(proc)
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
                        updateStatus("خطأ في البث: ${error.errorCodeName} – ${error.message}", true)
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
        confidenceBadge.visibility = View.GONE
        updateDelayReadout(0.0, 0.0)
        updateStatus("تمت إعادة التأخير إلى 0.00 ث")
    }

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
            updateStatus("يجب تشغيل البث أولاً", true)
            return
        }

        isMeasuring = true
        measureButton.isEnabled = false
        countdownBar.visibility = View.VISIBLE
        countdownBar.progress = 0
        updateStatus("جاري القياس... يرجى إبقاء صوت التلفاز مرتفعاً")
        confidenceBadge.visibility = View.GONE

        val wasMuted = proc.isMuted
        proc.isMuted = true 

        val radioEnvelope = mutableListOf<Pair<Long, Float>>()
        val micEnvelope = mutableListOf<Pair<Long, Float>>()

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
            updateStatus("تعذر تهيئة الميكروفون", true)
            isMeasuring = false
            return
        }

        scope.launch {
            withContext(Dispatchers.IO) {
                recorder.startRecording()
                val buffer = ShortArray(1600)
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
            }

            countdownBar.visibility = View.GONE
            measureButton.isEnabled = true
            isMeasuring = false
            proc.isMuted = wasMuted

            radioEnvelope.addAll(proc.pollEnvelope())

            updateStatus("جاري حساب التأخير...")
            
            val result = withContext(Dispatchers.Default) {
                SyncEngine.computeDelay(radioEnvelope, micEnvelope)
            }

            if (!result.isSuccess) {
                // فشل الفحص أو لم يتم سماع صوت
                updateStatus(result.message, true)
                confidenceBadge.visibility = View.GONE
                measuredDelay = null
            } else {
                // نجاح الفحص
                measuredDelay = result.delaySeconds
                updateStatus(result.message, false)
                confidenceBadge.visibility = View.VISIBLE
                
                val confText = if (result.message.contains("عالية")) "عالية" else "متوسطة"
                confidenceBadge.text = "الثقة: $confText"

                if (result.delaySeconds > 0) {
                    updateDelayReadout(0.0, result.delaySeconds)
                } else {
                    updateDelayReadout(abs(result.delaySeconds), 0.0)
                }
            }
        }
    }

    private fun applyMeasuredDelay() {
        val d = measuredDelay ?: run {
            updateStatus("قم بإجراء فحص التأخير أولاً", true)
            return
        }

        if (d > 0.05) {
            audioProcessor?.delaySeconds = d
            delaySlider.progress = (d * 100).toInt()
            manualDelayReadout.text = String.format("%.2f ث", d)
            updateDelayReadout(0.0, d)
            updateStatus("تم تأخير الصوت بـ %.2f ثانية بنجاح.".format(d))
        } else if (d < -0.05) {
            updateStatus("التلفاز متقدم بـ %.2f ثانية. لا يمكن تقديم الراديو المباشر.".format(abs(d)), true)
            resetDelay()
        } else {
            updateStatus("الصوت والصورة متزامنان بالفعل.")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        player?.release()
        relayServer?.stop()
    }
}
