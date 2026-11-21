// MainActivity.kt
// Package: com.example.radiosync

package com.example.radiosync

import android.Manifest
import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.*
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.*
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
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

// ---------------------------------------------------------------------------
// 1. Delay Audio Processor (مع خاصية Crossfade لتفادي القرقعة)
// ---------------------------------------------------------------------------
@OptIn(UnstableApi::class)
class DelayAudioProcessor : BaseAudioProcessor() {
    private var ringBuffer: ShortArray = ShortArray(0)
    private val writeIndex = AtomicInteger(0)
    private var sampleRate = 44100
    private var channelCount = 2

    @Volatile var delaySeconds: Double = 0.0
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
            ringBuffer = ShortArray(125 * sampleRate * channelCount)
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
            inputSamples = ShortArray(floatBuffer.remaining())
            for (i in inputSamples.indices) {
                inputSamples[i] = (floatBuffer.get().coerceIn(-1f, 1f) * 32767f).toInt().toShort()
            }
        } else {
            val shortBuffer = inputBuffer.order(ByteOrder.nativeOrder()).asShortBuffer()
            inputSamples = ShortArray(shortBuffer.remaining())
            shortBuffer.get(inputSamples)
        }
        inputBuffer.position(inputBuffer.limit())

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
            // Crossfade بسيط لتفادي "الفرقعة" عند تغيير التأخير
            val targetSamplesToDelay = (delaySeconds * sampleRate * channelCount).toInt()
            var targetReadPos = (writePos - targetSamplesToDelay) % maxBufferSamples
            if (targetReadPos < 0) targetReadPos += maxBufferSamples
            
            val diff = abs(readPos - targetReadPos)
            val smoothingFactor = if (diff > 1000) 0.1f else 0.5f // تعديل ناعم

            var currentRead = readPos.toFloat()
            for (i in 0 until inputSamples.size) {
                // اقتراب ناعم نحو نقطة القراءة المطلوبة
                currentRead += (targetReadPos - currentRead) * smoothingFactor
                val idx = (currentRead.toInt() + i) % maxBufferSamples
                outShort.put(ringBuffer[if (idx < 0) idx + maxBufferSamples else idx])
            }
        }
        outputBuffer.position(0)
        outputBuffer.limit(inputSamples.size * 2)
    }

    override fun onFlush() { envelopeQueue.clear(); writeIndex.set(0) }
    override fun onReset() { onFlush(); delaySeconds = 0.0; isMuted = false }
}

// ---------------------------------------------------------------------------
// 2. المشغل العام (Background Player Singleton)
// ---------------------------------------------------------------------------
@OptIn(UnstableApi::class)
object PlayerManager {
    var player: ExoPlayer? = null
    var audioProcessor: DelayAudioProcessor? = null
    var relayServer: LocalRelayServer? = null
    var isPlaying = false
}

// ---------------------------------------------------------------------------
// 3. Local TCP Relay (مع إعادة الاتصال التلقائي)
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
        var retries = 0
        try {
            while (retries < 5 && !Thread.currentThread().isInterrupted) {
                try {
                    val remote = URL(remoteUrl).openConnection() as HttpURLConnection
                    remote.instanceFollowRedirects = true
                    remote.setRequestProperty("User-Agent", "VLC/3.0.18")
                    remote.setRequestProperty("Icy-MetaData", "0")
                    remote.connectTimeout = 10000
                    remote.readTimeout = 10000
                    remote.connect()

                    val input = remote.inputStream
                    val output = client.getOutputStream()

                    val header = "HTTP/1.1 200 OK\r\nContent-Type: audio/mpeg\r\nConnection: close\r\n\r\n"
                    output.write(header.toByteArray())
                    output.flush()

                    val buffer = ByteArray(16 * 1024)
                    while (!Thread.currentThread().isInterrupted) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        output.flush()
                    }
                    break // نجح البث
                } catch (e: Exception) {
                    retries++
                    Thread.sleep(2000) // انتظار قبل المحاولة مجدداً
                }
            }
        } finally {
            try { client.close() } catch (_: IOException) { }
        }
    }
}

// ---------------------------------------------------------------------------
// 4. Sync Engine (مع خوارزمية Onset للتركيز على أحداث المباراة)
// ---------------------------------------------------------------------------
object SyncEngine {
    data class Result(val delaySeconds: Double, val isSuccess: Boolean, val message: String)

    private fun computeOnsets(envelope: List<Pair<Long, Float>>): List<Pair<Long, Float>> {
        if (envelope.size < 2) return envelope
        val onsets = mutableListOf<Pair<Long, Float>>()
        var prev = envelope[0].second
        for (i in 1 until envelope.size) {
            val current = envelope[i].second
            val diff = current - prev
            onsets.add(envelope[i].first to (if (diff > 100f) diff else 0f))
            prev = current
        }
        return onsets
    }

    fun computeDelay(radioRaw: List<Pair<Long, Float>>, micRaw: List<Pair<Long, Float>>): Result {
        if (radioRaw.size < 10 || micRaw.size < 10) return Result(0.0, false, "لم يتم جمع بيانات كافية.")

        val micRms = micRaw.map { it.second }.average()
        if (micRms < 200.0) return Result(0.0, false, "لم يتم سماع صوت التلفاز. يرجى رفع الصوت والمحاولة مجدداً.")

        val radio = computeOnsets(radioRaw)
        val mic = computeOnsets(micRaw)

        if (radio.sumOf { it.second.toDouble() } < 1000.0 || mic.sumOf { it.second.toDouble() } < 1000.0) {
            return Result(0.0, false, "لم نرصد أحداثاً قوية (أهداف أو صافرات). أعد المحاولة أثناء هجمة.")
        }

        val startTime = min(radio.first().first, mic.first().first)
        val endTime = max(radio.last().first, mic.last().first)
        val size = ((endTime - startTime) / 10_000_000L).toInt() + 1
        val rArr = FloatArray(size)
        val mArr = FloatArray(size)

        for ((ts, v) in radio) { val idx = ((ts - startTime) / 10_000_000L).toInt(); if (idx in rArr.indices) rArr[idx] = v }
        for ((ts, v) in mic) { val idx = ((ts - startTime) / 10_000_000L).toInt(); if (idx in mArr.indices) mArr[idx] = v }

        val micStartIdx = ((micRaw.first().first - startTime) / 10_000_000L).toInt()
        val micEndIdx = ((micRaw.last().first - startTime) / 10_000_000L).toInt()

        var bestLagIdx = 0
        var bestCorr = -2.0

        for (lag in -3000..12000) { // -30 ثانية (تلفاز متقدم) إلى +120 ثانية (راديو متقدم)
            val start = max(micStartIdx, lag)
            val end = min(micEndIdx, rArr.size + lag)

            if (start < end) {
                val mSeg = mArr.copyOfRange(start, end)
                val rSeg = rArr.copyOfRange(start - lag, end - lag)
                val corr = correlate(mSeg, rSeg)
                if (corr > bestCorr) { bestCorr = corr; bestLagIdx = lag }
            }
        }

        if (bestCorr < 0.15) return Result(0.0, false, "لم نتمكن من اكتشاف تطابق. تأكد من أن المعلقين ينقلون نفس المباراة.")

        val delaySeconds = bestLagIdx / 100.0
        val confidence = if (bestCorr > 0.4) "عالية" else "متوسطة"

        return when {
            delaySeconds > 0 -> Result(delaySeconds, true, "الراديو متقدم بـ %.2f ثانية. (الثقة: %s)".format(delaySeconds, confidence))
            delaySeconds < 0 -> Result(delaySeconds, true, "التلفاز متقدم بـ %.2f ثانية. (الثقة: %s)".format(abs(delaySeconds), confidence))
            else -> Result(0.0, true, "الراديو والتلفاز متزامنان. (الثقة: %s)".format(confidence))
        }
    }

    private fun correlate(m: FloatArray, r: FloatArray): Double {
        if (m.isEmpty() || r.isEmpty() || m.size != r.size) return -2.0
        var sumMR = 0.0; var sumM2 = 0.0; var sumR2 = 0.0
        for (i in m.indices) {
            val mv = m[i].toDouble(); val rv = r[i].toDouble()
            sumMR += mv * rv; sumM2 += mv * mv; sumR2 += rv * rv
        }
        val den = sqrt(sumM2 * sumR2)
        return if (den == 0.0) -2.0 else sumMR / den
    }
}

// ---------------------------------------------------------------------------
// 5. Foreground Service (للتشغيل في الخلفية)
// ---------------------------------------------------------------------------
class RadioForegroundService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel("radio_channel", "Radio Sync", NotificationManager.IMPORTANCE_LOW)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = NotificationCompat.Builder(this, "radio_channel")
            .setContentTitle("Radio Sync يعمل")
            .setContentText("جاري مزامنة البث الصوتي...")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .build()
        startForeground(1, notification)
        return START_NOT_STICKY
    }
}

// ---------------------------------------------------------------------------
// 6. MainActivity
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

    private var measuredDelay: Double? = null
    private var selectedDurationSec = 20
    private var isMeasuring = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("radio_sync", Context.MODE_PRIVATE) }
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val recordPermissionLauncher = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startMeasurement() else updateStatus("يجب السماح بالوصول إلى الميكروفون لقياس التأخير")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = Color.parseColor("#0B1120")
        window.navigationBarColor = Color.parseColor("#0B1120")

        requestNotificationPermission()
        buildUI()
        setRtl()
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }
    }

    private fun setRtl() { window.decorView.layoutDirection = View.LAYOUT_DIRECTION_RTL }

    private fun buildUI() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.parseColor("#0B1120"))
            layoutDirection = View.LAYOUT_DIRECTION_RTL; setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        val scroll = ScrollView(this)
        scroll.addView(root, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        val statusCard = card()
        statusText = TextView(this).apply { text = "لم يتم الاتصال بعد"; setTextColor(Color.WHITE); textSize = 16f }
        delayReadout = TextView(this).apply { text = "الصورة 0.0 ث • الصوت 0.00 ث"; setTextColor(Color.parseColor("#94A3B8")); textSize = 14f }
        confidenceBadge = TextView(this).apply { visibility = View.GONE; setTextColor(Color.parseColor("#10B981")); textSize = 12f; setPadding(dp(8), dp(4), dp(8), dp(4)); background = roundedBg(Color.parseColor("#10B981"), 20) }
        statusCard.addView(statusText); statusCard.addView(delayReadout); statusCard.addView(confidenceBadge)
        root.addView(statusCard)

        val streamCard = card()
        urlInput = EditText(this).apply {
            hint = "أدخل رابط البث"; setTextColor(Color.WHITE); setHintTextColor(Color.parseColor("#64748B"))
            inputType = InputType.TYPE_TEXT_VARIATION_URI; setSingleLine(); setBackgroundColor(Color.parseColor("#1E293B")); setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        val lastUrl = prefs.getString("last_url", "http://foxfm.xyz:8080/live1") ?: "http://foxfm.xyz:8080/live1"
        urlInput.setText(lastUrl)
        
        playButton = emeraldButton("تشغيل") { togglePlay() }
        val streamRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        streamRow.addView(urlInput, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        streamRow.addView(playButton, LinearLayout.LayoutParams(dp(90), ViewGroup.LayoutParams.WRAP_CONTENT))
        streamCard.addView(streamRow)
        
        // Quick URLs (أزرار حفظ الروابط السريعة)
        val quickUrlsLayout = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), 0, 0) }
        val btnSaveUrl = Button(this).apply {
            text = "+ حفظ الرابط"; setTextColor(Color.WHITE); textSize = 12f; background = roundedBg(Color.parseColor("#334155"), 12)
            setOnClickListener {
                val url = urlInput.text.toString().trim()
                if (url.isNotEmpty()) {
                    val saved = prefs.getStringSet("saved_urls", mutableSetOf()) as MutableSet<String>
                    saved.add(url); prefs.edit().putStringSet("saved_urls", saved).apply(); rebuildQuickUrls(quickUrlsLayout)
                }
            }
        }
        quickUrlsLayout.addView(btnSaveUrl)
        streamCard.addView(quickUrlsLayout)
        rebuildQuickUrls(quickUrlsLayout)
        
        root.addView(streamCard)

        val measureCard = card()
        measureButton = emeraldButton("فحص التأخير") { requestMeasurement() }
        measureCard.addView(measureButton)

        val durationGroup = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL; gravity = Gravity.CENTER }
        for (sec in listOf(10, 15, 20, 30, 45, 60)) {
            val rb = RadioButton(this).apply { text = "${sec} ث"; setTextColor(Color.WHITE); id = View.generateViewId(); tag = sec; isChecked = sec == 20 }
            durationGroup.addView(rb)
        }
        durationGroup.setOnCheckedChangeListener { _, checkedId -> selectedDurationSec = (findViewById<RadioButton>(checkedId)?.tag as? Int) ?: 20 }
        measureCard.addView(durationGroup)

        countdownBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; progress = 0; visibility = View.GONE; progressTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#10B981")) }
        measureCard.addView(countdownBar); root.addView(measureCard)

        val actionRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(0, dp(8), 0, dp(8)) }
        syncButton = emeraldButton("زامن الآن") { applyMeasuredDelay() }
        muteButton = emeraldButton("صوت القناة") { toggleMute() }
        resetButton = emeraldButton("إيقاف المزامنة") { resetDelay() }
        actionRow.addView(syncButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        actionRow.addView(muteButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        actionRow.addView(resetButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(actionRow)

        val manualCard = card()
        manualCard.addView(TextView(this).apply { text = "ضبط يدوي"; setTextColor(Color.WHITE); textSize = 18f; setPadding(0, 0, 0, dp(8)) })
        manualDelayReadout = TextView(this).apply { text = "0.00 ث"; setTextColor(Color.parseColor("#10B981")); textSize = 32f; gravity = Gravity.CENTER }
        manualCard.addView(manualDelayReadout)

        delaySlider = SeekBar(this).apply {
            max = 12000; progress = 0
            progressTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#10B981"))
            thumbTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#10B981"))
        }
        delaySlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val seconds = progress / 100.0
                    manualDelayReadout.text = String.format("%.2f ث", seconds)
                    PlayerManager.audioProcessor?.delaySeconds = seconds
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        manualCard.addView(delaySlider)

        val nudgeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(0, dp(8), 0, 0) }
        for (n in listOf(-10.0, -1.0, -0.25, 0.25, 1.0, 10.0)) {
            val btn = Button(this).apply {
                text = if (n > 0) "+${n} ث" else "${n} ث"; setTextColor(Color.WHITE); textSize = 12f
                background = roundedBg(Color.parseColor("#334155"), 20); setPadding(dp(8), dp(6), dp(8), dp(6))
                setOnClickListener {
                    val newVal = ((delaySlider.progress / 100.0) + n).coerceIn(0.0, 120.0)
                    delaySlider.progress = (newVal * 100).toInt()
                }
            }
            nudgeRow.addView(btn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(4) })
        }
        manualCard.addView(nudgeRow); root.addView(manualCard)
        setContentView(scroll)
    }

    private fun rebuildQuickUrls(layout: LinearLayout) {
        layout.removeViews(1, layout.childCount - 1)
        val saved = prefs.getStringSet("saved_urls", mutableSetOf()) ?: mutableSetOf()
        for (url in saved) {
            val host = try { URL(url).host } catch (_: Exception) { url }
            val btn = Button(this).apply {
                text = host; setTextColor(Color.WHITE); textSize = 12f; background = roundedBg(Color.parseColor("#1E293B"), 12)
                setPadding(dp(8), dp(6), dp(8), dp(6)); setOnClickListener { urlInput.setText(url) }
            }
            layout.addView(btn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(4) })
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(14), dp(16), dp(14)); background = roundedBg(Color.parseColor("#1E293B"), 16)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) }
    }
    private fun emeraldButton(text: String, onClick: () -> Unit): Button = Button(this).apply {
        this.text = text; setTextColor(Color.WHITE); textSize = 15f; background = roundedBg(Color.parseColor("#10B981"), 12); setOnClickListener { onClick() }
    }
    private fun roundedBg(color: Int, radiusDp: Int): GradientDrawable = GradientDrawable().apply { setColor(color); cornerRadius = dp(radiusDp).toFloat() }
    private fun updateStatus(msg: String, isError: Boolean = false) { mainHandler.post { statusText.text = msg; statusText.setTextColor(if (isError) Color.parseColor("#EF4444") else Color.WHITE) } }
    private fun updateDelayReadout(tvDelay: Double, radioDelay: Double) { mainHandler.post { delayReadout.text = "الصورة %.1f ث • الصوت %.2f ث".format(tvDelay, radioDelay) } }

    private fun togglePlay() {
        if (PlayerManager.isPlaying) {
            PlayerManager.player?.release(); PlayerManager.player = null; PlayerManager.audioProcessor = null
            PlayerManager.relayServer?.stop(); PlayerManager.relayServer = null; PlayerManager.isPlaying = false
            playButton.text = "تشغيل"; updateStatus("تم إيقاف البث")
            stopService(Intent(this, RadioForegroundService::class.java))
        } else {
            val url = urlInput.text.toString().trim()
            if (url.isEmpty()) { updateStatus("الرجاء إدخال رابط البث", true); return }
            prefs.edit().putString("last_url", url).apply()

            val playbackUrl = if (url.contains(".m3u8", ignoreCase = true)) url else {
                PlayerManager.relayServer = LocalRelayServer(url)
                "http://127.0.0.1:${PlayerManager.relayServer!!.start()}/stream"
            }

            PlayerManager.audioProcessor = DelayAudioProcessor()
            val renderersFactory = object : DefaultRenderersFactory(this) {
                override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink {
                    return DefaultAudioSink.Builder(context)
                        .setAudioProcessorChain(DefaultAudioSink.DefaultAudioProcessorChain(PlayerManager.audioProcessor!!))
                        .setEnableFloatOutput(enableFloatOutput).setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams).build()
                }
            }

            PlayerManager.player = ExoPlayer.Builder(this, renderersFactory)
                .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultHttpDataSource.Factory().setUserAgent("VLC/3.0.18").setAllowCrossProtocolRedirects(true)))
                .build().apply {
                    setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
                    addListener(object : Player.Listener {
                        override fun onPlayerError(error: PlaybackException) { updateStatus("خطأ في البث: ${error.message}", true) }
                        override fun onPlaybackStateChanged(playbackState: Int) { if (playbackState == Player.STATE_READY) updateStatus("البث يعمل الآن") }
                    })
                    setMediaItem(MediaItem.fromUri(playbackUrl)); prepare(); playWhenReady = true
                }

            PlayerManager.isPlaying = true; playButton.text = "إيقاف"; updateStatus("جارٍ الاتصال بالبث…")
            ContextCompat.startForegroundService(this, Intent(this, RadioForegroundService::class.java))
        }
    }

    private fun toggleMute() {
        val proc = PlayerManager.audioProcessor ?: return
        proc.isMuted = !proc.isMuted; muteButton.text = if (proc.isMuted) "إلغاء الكتم" else "صوت القناة"
    }

    private fun resetDelay() {
        PlayerManager.audioProcessor?.delaySeconds = 0.0; delaySlider.progress = 0; manualDelayReadout.text = "0.00 ث"
        measuredDelay = null; confidenceBadge.visibility = View.GONE; updateDelayReadout(0.0, 0.0)
        updateStatus("تمت إعادة التأخير إلى 0.00 ث")
    }

    private fun requestMeasurement() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            recordPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        } else startMeasurement()
    }

    private fun startMeasurement() {
        if (isMeasuring) return
        val proc = PlayerManager.audioProcessor ?: run { updateStatus("يجب تشغيل البث أولاً", true); return }

        isMeasuring = true; measureButton.isEnabled = false; countdownBar.visibility = View.VISIBLE; countdownBar.progress = 0
        updateStatus("جاري القياس... يرجى إبقاء صوت التلفاز مرتفعاً"); confidenceBadge.visibility = View.GONE

        val wasMuted = proc.isMuted; proc.isMuted = true
        val radioEnvelope = mutableListOf<Pair<Long, Float>>(); val micEnvelope = mutableListOf<Pair<Long, Float>>()

        val sampleRate = 16000; val channelConfig = AudioFormat.CHANNEL_IN_MONO; val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val minBuf = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        
        val recorder = AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, channelConfig, audioFormat, minBuf * 4)
        if (recorder.state != AudioRecord.STATE_INITIALIZED) { updateStatus("تعذر تهيئة الميكروفون", true); isMeasuring = false; return }

        scope.launch {
            withContext(Dispatchers.IO) {
                recorder.startRecording(); val buffer = ShortArray(1600); val startNano = System.nanoTime()
                var elapsedMs = 0L; val totalMs = selectedDurationSec * 1000L

                while (elapsedMs < totalMs && isActive) {
                    val read = recorder.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        var sum = 0.0
                        for (i in 0 until read) sum += buffer[i].toDouble() * buffer[i]
                        micEnvelope.add(System.nanoTime() to sqrt(sum / read).toFloat())
                    }
                    elapsedMs = (System.nanoTime() - startNano) / 1_000_000
                    withContext(Dispatchers.Main) { countdownBar.progress = (elapsedMs * 100 / totalMs).toInt() }
                }
                recorder.stop(); recorder.release()
            }

            countdownBar.visibility = View.GONE; measureButton.isEnabled = true; isMeasuring = false; proc.isMuted = wasMuted
            radioEnvelope.addAll(proc.pollEnvelope()); updateStatus("جاري حساب التأخير...")
            
            val result = withContext(Dispatchers.Default) { SyncEngine.computeDelay(radioEnvelope, micEnvelope) }

            if (!result.isSuccess) {
                updateStatus(result.message, true); confidenceBadge.visibility = View.GONE; measuredDelay = null
            } else {
                measuredDelay = result.delaySeconds; updateStatus(result.message, false)
                confidenceBadge.visibility = View.VISIBLE
                confidenceBadge.text = "الثقة: ${if (result.message.contains("عالية")) "عالية" else "متوسطة"}"
                if (result.delaySeconds > 0) updateDelayReadout(0.0, result.delaySeconds) else updateDelayReadout(abs(result.delaySeconds), 0.0)
            }
        }
    }

    private fun applyMeasuredDelay() {
        val d = measuredDelay ?: run { updateStatus("قم بإجراء فحص التأخير أولاً", true); return }
        if (d > 0.05) {
            PlayerManager.audioProcessor?.delaySeconds = d; delaySlider.progress = (d * 100).toInt()
            manualDelayReadout.text = String.format("%.2f ث", d); updateDelayReadout(0.0, d)
            updateStatus("تم تأخير الصوت بـ %.2f ثانية بنجاح.".format(d))
        } else if (d < -0.05) {
            updateStatus("التلفاز متقدم بـ %.2f ثانية. لا يمكن تقديم الراديو المباشر.".format(abs(d)), true); resetDelay()
        } else updateStatus("الصوت والصورة متزامنان بالفعل.")
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        // لا نقوم بإغلاق المشغل هنا لكي يستمر في الخلفية (Foreground Service)
        if (!isFinishing) {
            // إذا تم إغلاق الواجهة فقط، نتركه يعمل
        }
    }
}
