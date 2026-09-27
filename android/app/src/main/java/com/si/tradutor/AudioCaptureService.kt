package com.si.tradutor

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.Base64
import android.util.Log
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.sqrt

class AudioCaptureService : Service() {

    companion object {
        private const val TAG = "SI_AUDIO"
        private const val BACKEND_URL = "https://si-u2ul.onrender.com"

        const val ACTION_START = "com.si.tradutor.ACTION_START"
        const val ACTION_STOP = "com.si.tradutor.ACTION_STOP"
        const val EXTRA_JOB_ID = "jobId"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"

        private const val CHANNEL_ID = "si_audio_capture"
        private const val NOTIFICATION_ID = 1001

        private const val CAPTURE_SAMPLE_RATE = 48000
        private const val SEND_SAMPLE_RATE = 16000
        private const val CAPTURE_CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val PCM_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val CAPTURE_CHUNK_BYTES = 9600
        private const val INPUT_QUEUE_SIZE = 80
        private const val OUTPUT_QUEUE_SIZE = 4
        private const val STATUS_INTERVAL_MS = 300L
    }

    @Volatile private var running = false
    @Volatile private var stopping = false
    @Volatile private var jobId = ""

    private var mediaProjection: MediaProjection? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var audioRecord: AudioRecord? = null
    private var mediaPlayer: MediaPlayer? = null
    private var playbackFile: File? = null

    private var captureThread: Thread? = null
    private var sendThread: Thread? = null
    private var statusThread: Thread? = null
    private var playbackThread: Thread? = null

    private val inputQueue = LinkedBlockingQueue<ByteArray>(INPUT_QUEUE_SIZE)

    private data class OutputAudio(
        val audioId: String,
        val wav: ByteArray
    )

    private val outputQueue = LinkedBlockingQueue<OutputAudio>(OUTPUT_QUEUE_SIZE)
    private val downloadedAudioIds = HashSet<String>()
    private val downloadedAudioLock = Any()

    @Volatile private var capturedChunks = 0L
    @Volatile private var sentChunks = 0L
    @Volatile private var receivedAudio = 0L
    @Volatile private var playedAudio = 0L
    @Volatile private var silentChunks = 0L
    @Volatile private var lastRms = 0L
    @Volatile private var lastError: String? = null

    override fun onCreate() {
        super.onCreate()
        criarCanal()
        publicarDiagnostico("🔵 Serviço criado")
        Log.d(TAG, "SI TRADUTOR LIVE - Deepgram + DeepL + Piper")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                pararServico(true)
                return START_NOT_STICKY
            }
            ACTION_START -> Unit
            else -> return START_NOT_STICKY
        }

        if (running) return START_STICKY

        jobId = intent.getStringExtra(EXTRA_JOB_ID).orEmpty()
        if (jobId.isEmpty()) {
            lastError = "jobId vazio"
            publicarDiagnostico("❌ jobId vazio")
            stopSelf()
            return START_NOT_STICKY
        }

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_RESULT_DATA)
        }

        if (resultCode != Activity.RESULT_OK || resultData == null) {
            lastError = "MediaProjection inválida"
            publicarDiagnostico("❌ MediaProjection inválida")
            stopSelf()
            return START_NOT_STICKY
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
        ) {
            lastError = "Permissão RECORD_AUDIO não concedida"
            publicarDiagnostico("❌ Permissão de áudio não concedida")
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            iniciarForeground()
            limparEstado()
            stopping = false
            running = true

            iniciarMediaProjection(resultCode, resultData)
            iniciarCaptura()
            iniciarEnvio()
            iniciarStatus()
            iniciarPlayback()

            publicarDiagnostico("🎤 Captura interna iniciada")
            Log.d(TAG, "SI INICIADO jobId=$jobId")
        } catch (e: Exception) {
            lastError = e.message ?: e.javaClass.simpleName
            Log.e(TAG, "Erro iniciando serviço", e)
            publicarDiagnostico("❌ Erro ao iniciar: $lastError")
            pararServico(false)
        }

        return START_STICKY
    }

    private fun criarCanal() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "SI Tradutor Live",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Captura e tradução de áudio em tempo real"
                }
            )
        }
    }

    private fun iniciarForeground() {
        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("SI Tradutor Live")
                .setContentText("Traduzindo áudio em tempo real")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("SI Tradutor Live")
                .setContentText("Traduzindo áudio em tempo real")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setOngoing(true)
                .build()
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun iniciarMediaProjection(resultCode: Int, resultData: Intent) {
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = manager.getMediaProjection(resultCode, resultData)
            ?: throw IllegalStateException("MediaProjection não criada")

        projectionCallback = object : MediaProjection.Callback() {
            override fun onStop() {
                Log.d(TAG, "MediaProjection encerrada")
                if (running) pararServico(true)
            }
        }
        mediaProjection?.registerCallback(projectionCallback!!, null)
    }

    private fun iniciarCaptura() {
        val projection = mediaProjection ?: throw IllegalStateException("MediaProjection não disponível")
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            throw IllegalStateException("Captura interna exige Android 10 ou superior")
        }

        val minBuffer = AudioRecord.getMinBufferSize(
            CAPTURE_SAMPLE_RATE,
            CAPTURE_CHANNEL,
            PCM_FORMAT
        )
        if (minBuffer <= 0) throw IllegalStateException("AudioRecord.getMinBufferSize falhou: $minBuffer")

        val captureConfig = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()

        val record = AudioRecord.Builder()
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(PCM_FORMAT)
                    .setSampleRate(CAPTURE_SAMPLE_RATE)
                    .setChannelMask(CAPTURE_CHANNEL)
                    .build()
            )
            .setBufferSizeInBytes(max(minBuffer * 4, CAPTURE_CHUNK_BYTES * 8))
            .setAudioPlaybackCaptureConfig(captureConfig)
            .build()

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw IllegalStateException("AudioRecord não inicializado")
        }

        audioRecord = record

        captureThread = Thread {
            val captureBuffer = ByteArray(CAPTURE_CHUNK_BYTES)
            try {
                record.startRecording()
                if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    throw IllegalStateException("AudioRecord não entrou em RECORDING")
                }

                Log.d(TAG, "CAPTURA INICIADA 48kHz")
                publicarDiagnostico("🎤 Capturando áudio interno...")

                while (running && !stopping) {
                    val read = record.read(
                        captureBuffer,
                        0,
                        captureBuffer.size,
                        AudioRecord.READ_BLOCKING
                    )

                    if (read > 0) {
                        capturedChunks++
                        val chunk = captureBuffer.copyOf(read)
                        lastRms = calcularRms(chunk)
                        if (lastRms == 0L) silentChunks++ else receivedAudio++

                        val pcm16k = downsample48kTo16k(chunk)
                        if (pcm16k.isNotEmpty()) {
                            if (!inputQueue.offer(pcm16k)) inputQueue.poll()
                            inputQueue.offer(pcm16k)
                        }

                        if (capturedChunks <= 5 || capturedChunks % 25L == 0L) {
                            Log.d(TAG, "CAPTURA=$capturedChunks read=$read rms=$lastRms fila=${inputQueue.size}")
                        }
                        publicarDiagnostico(null)
                        continue
                    }

                    lastError = "AudioRecord retornou $read"
                    Log.e(TAG, lastError!!)
                    publicarDiagnostico("⚠️ Captura retornou $read")
                    if (read == AudioRecord.ERROR_DEAD_OBJECT) break
                    Thread.sleep(100)
                }
            } catch (e: SecurityException) {
                lastError = "Permissão de áudio negada: ${e.message}"
                Log.e(TAG, lastError!!, e)
                publicarDiagnostico("❌ Permissão de áudio negada")
            } catch (e: Exception) {
                if (running) {
                    lastError = e.message ?: e.javaClass.simpleName
                    Log.e(TAG, "Erro na captura", e)
                    publicarDiagnostico("❌ Erro na captura: $lastError")
                }
            } finally {
                try { record.stop() } catch (_: Exception) { }
                try { record.release() } catch (_: Exception) { }
                if (audioRecord === record) audioRecord = null
            }
        }.apply {
            name = "SI-AudioCapture"
            isDaemon = true
        }
        captureThread?.start()
    }

    private fun downsample48kTo16k(data: ByteArray): ByteArray {
        val sampleCount = data.size / 2
        if (sampleCount < 3) return ByteArray(0)
        val outputSamples = sampleCount / 3
        val output = ByteArray(outputSamples * 2)
        var inputSample = 0
        var outByte = 0

        repeat(outputSamples) {
            val s1 = readPcm16(data, inputSample)
            val s2 = readPcm16(data, inputSample + 1)
            val s3 = readPcm16(data, inputSample + 2)
            val avg = ((s1 + s2 + s3) / 3).coerceIn(-32768, 32767)
            output[outByte] = (avg and 0xff).toByte()
            output[outByte + 1] = ((avg shr 8) and 0xff).toByte()
            inputSample += 3
            outByte += 2
        }
        return output
    }

    private fun readPcm16(data: ByteArray, sampleIndex: Int): Int {
        val p = sampleIndex * 2
        if (p + 1 >= data.size) return 0
        return ((data[p + 1].toInt() shl 8) or (data[p].toInt() and 0xff)).toShort().toInt()
    }

    private fun calcularRms(data: ByteArray): Long {
        if (data.size < 2) return 0L
        var sum = 0.0
        var count = 0
        var i = 0
        while (i + 1 < data.size) {
            val sample = ((data[i + 1].toInt() shl 8) or (data[i].toInt() and 0xff)).toShort().toInt()
            sum += sample.toDouble() * sample.toDouble()
            count++
            i += 2
        }
        return if (count == 0) 0L else sqrt(sum / count).toLong()
    }

    private fun iniciarEnvio() {
        sendThread = Thread {
            while (running && !stopping) {
                try {
                    val chunk = inputQueue.poll(500, TimeUnit.MILLISECONDS) ?: continue
                    enviarAudio(chunk)
                } catch (_: InterruptedException) {
                    break
                } catch (e: Exception) {
                    if (running) {
                        lastError = e.message ?: e.javaClass.simpleName
                        Log.e(TAG, "Erro envio", e)
                        publicarDiagnostico("⚠️ Erro envio: $lastError")
                    }
                }
            }
        }.apply {
            name = "SI-AudioSend"
            isDaemon = true
        }
        sendThread?.start()
    }

    private fun enviarAudio(audio: ByteArray) {
        var connection: HttpURLConnection? = null
        try {
            connection = URL("$BACKEND_URL/api/audio/chunk").openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            connection.doOutput = true
            connection.useCaches = false
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")

            val json = JSONObject()
            json.put("jobId", jobId)
            json.put("audio", Base64.encodeToString(audio, Base64.NO_WRAP))
            json.put("mimeType", "audio/pcm")
            json.put("sampleRate", SEND_SAMPLE_RATE)

            connection.outputStream.use { it.write(json.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            if (code in 200..299) {
                sentChunks++
                if (sentChunks <= 5 || sentChunks % 25L == 0L) Log.d(TAG, "ENVIO=$sentChunks")
            } else {
                lastError = "Render HTTP=$code"
                Log.e(TAG, lastError!!)
            }
            publicarDiagnostico(null)
        } catch (e: Exception) {
            if (running) {
                lastError = e.message ?: e.javaClass.simpleName
                Log.e(TAG, "Erro HTTP", e)
                publicarDiagnostico("⚠️ Erro HTTP: $lastError")
            }
        } finally {
            connection?.disconnect()
        }
    }

    private fun iniciarStatus() {
        statusThread = Thread {
            while (running && !stopping) {
                try {
                    consultarStatus()
                    Thread.sleep(STATUS_INTERVAL_MS)
                } catch (_: InterruptedException) {
                    break
                } catch (e: Exception) {
                    if (running) Log.e(TAG, "Erro status", e)
                    try { Thread.sleep(500) } catch (_: Exception) { }
                }
            }
        }.apply {
            name = "SI-Status"
            isDaemon = true
        }
        statusThread?.start()
    }

    private fun consultarStatus() {
        var connection: HttpURLConnection? = null
        try {
            connection = URL("$BACKEND_URL/api/audio/status/$jobId").openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 8000
            connection.readTimeout = 8000
            val code = connection.responseCode
            if (code !in 200..299) return

            val response = connection.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(response)
            if (!json.optBoolean("ok", false)) return

            val source = json.optString("sourceText", "")
            val translated = json.optString("translatedText", "")
            if (source.isNotEmpty() || translated.isNotEmpty()) {
                Log.d(TAG, "TEXTO source=$source translated=$translated")
            }

            var audioId = json.optString("audioId", "")
            var audioUrl = json.optString("audioUrl", "")
            if (audioId.isEmpty() || audioUrl.isEmpty()) return
            if (audioUrl.startsWith("/")) audioUrl = BACKEND_URL + audioUrl

            synchronized(downloadedAudioLock) {
                if (downloadedAudioIds.contains(audioId)) return
                downloadedAudioIds.add(audioId)
            }

            Log.d(TAG, "NOVO AUDIO PIPER=$audioId url=$audioUrl")
            publicarDiagnostico("🔊 Áudio Piper recebido")

            val wav = baixarWav(audioUrl)
            if (wav == null) {
                removerAudioBaixado(audioId)
                return
            }

            if (!isWavPcm16(wav)) {
                lastError = "WAV Piper inválido ou não PCM16"
                Log.e(TAG, lastError!!)
                removerAudioBaixado(audioId)
                publicarDiagnostico("❌ WAV Piper inválido")
                return
            }

            if (!outputQueue.offer(OutputAudio(audioId, wav), 2, TimeUnit.SECONDS)) {
                removerAudioBaixado(audioId)
                return
            }

            Log.d(TAG, "WAV PIPER ENFILEIRADO=${wav.size} bytes")
            publicarDiagnostico("🔊 Áudio Piper pronto para tocar")
        } catch (e: Exception) {
            if (running) Log.e(TAG, "Erro consultando status", e)
        } finally {
            connection?.disconnect()
        }
    }

    private fun baixarWav(url: String): ByteArray? {
        var connection: HttpURLConnection? = null
        return try {
            connection = URL(url).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 10000
            connection.readTimeout = 30000
            connection.setRequestProperty("Accept", "audio/wav,audio/x-wav,*/*")
            if (connection.responseCode !in 200..299) {
                lastError = "Download WAV HTTP=${connection.responseCode}"
                Log.e(TAG, lastError!!)
                null
            } else {
                connection.inputStream.use { input ->
                    val buffer = ByteArray(8192)
                    val out = ByteArrayOutputStream()
                    while (true) {
                        val n = input.read(buffer)
                        if (n <= 0) break
                        out.write(buffer, 0, n)
                        if (out.size() > 10 * 1024 * 1024) throw IllegalStateException("WAV maior que 10 MB")
                    }
                    out.toByteArray()
                }
            }
        } catch (e: Exception) {
            lastError = "Erro baixando WAV: ${e.message}"
            Log.e(TAG, lastError!!, e)
            null
        } finally {
            connection?.disconnect()
        }
    }

    private fun isWavPcm16(wav: ByteArray): Boolean {
        if (wav.size < 44) return false
        if (String(wav, 0, 4, Charsets.US_ASCII) != "RIFF") return false
        if (String(wav, 8, 4, Charsets.US_ASCII) != "WAVE") return false

        fun u16(p: Int): Int {
            if (p + 2 > wav.size) return -1
            return (wav[p].toInt() and 0xff) or ((wav[p + 1].toInt() and 0xff) shl 8)
        }
        fun i32(p: Int): Int {
            if (p + 4 > wav.size) return -1
            return (wav[p].toInt() and 0xff) or
                ((wav[p + 1].toInt() and 0xff) shl 8) or
                ((wav[p + 2].toInt() and 0xff) shl 16) or
                ((wav[p + 3].toInt() and 0xff) shl 24)
        }
        fun id(p: Int): String {
            if (p + 4 > wav.size) return ""
            return String(wav, p, 4, Charsets.US_ASCII)
        }

        var p = 12
        var format = 0
        var channels = 0
        var rate = 0
        var bits = 0
        var dataSize = 0

        while (p + 8 <= wav.size) {
            val name = id(p)
            val size = i32(p + 4)
            if (size < 0) return false
            val payload = p + 8
            if (payload > wav.size) return false
            val safeSize = minOf(size, wav.size - payload)

            if (name == "fmt " && safeSize >= 16) {
                format = u16(payload)
                channels = u16(payload + 2)
                rate = i32(payload + 4)
                bits = u16(payload + 14)
            }
            if (name == "data") {
                dataSize = safeSize
                break
            }
            p = payload + size
            if ((p and 1) != 0) p++
        }

        val ok = format == 1 && channels in 1..2 && rate > 0 && bits == 16 && dataSize > 0
        Log.d(TAG, "WAV format=$format channels=$channels rate=$rate bits=$bits data=$dataSize ok=$ok")
        return ok
    }

    private fun iniciarPlayback() {
        playbackThread = Thread {
            Log.d(TAG, "THREAD PLAYBACK PIPER INICIADA")
            while (running && !stopping) {
                try {
                    val item = outputQueue.poll(500, TimeUnit.MILLISECONDS) ?: continue
                    tocarWavComMediaPlayer(item)
                } catch (_: InterruptedException) {
                    break
                } catch (e: Exception) {
                    if (running) {
                        lastError = e.message ?: e.javaClass.simpleName
                        Log.e(TAG, "Erro playback", e)
                        publicarDiagnostico("❌ Erro playback: $lastError")
                    }
                }
            }
            liberarMediaPlayer()
        }.apply {
            name = "SI-PiperPlayback"
            isDaemon = true
        }
        playbackThread?.start()
    }

    private fun tocarWavComMediaPlayer(item: OutputAudio) {
        var file: File? = null
        var player: MediaPlayer? = null
        var completed = false

        try {
            file = File.createTempFile("si_piper_", ".wav", cacheDir)
            file.writeBytes(item.wav)
            playbackFile = file

            val finished = Object()
            var done = false
            var playbackError: String? = null

            player = MediaPlayer()
            mediaPlayer = player

            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            player.setDataSource(file.absolutePath)
            player.setVolume(1.0f, 1.0f)

            player.setOnPreparedListener {
                try {
                    Log.d(TAG, "PIPER WAV PREPARED size=${item.wav.size}")
                    publicarDiagnostico("🔊 Voz Piper tocando")
                    it.start()
                } catch (e: Exception) {
                    synchronized(finished) {
                        playbackError = e.message ?: e.javaClass.simpleName
                        done = true
                        finished.notifyAll()
                    }
                }
            }

            player.setOnCompletionListener {
                synchronized(finished) {
                    done = true
                    completed = true
                    finished.notifyAll()
                }
            }

            player.setOnErrorListener { _, what, extra ->
                synchronized(finished) {
                    playbackError = "MediaPlayer erro what=$what extra=$extra"
                    done = true
                    finished.notifyAll()
                }
                true
            }

            player.prepare()

            synchronized(finished) {
                while (!done && running && !stopping) {
                    try {
                        finished.wait(1000L)
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            }

            if (playbackError != null) throw IllegalStateException(playbackError)
            if (!completed) throw IllegalStateException("Piper não terminou a reprodução")

            playedAudio++
            publicarDiagnostico("🔊 Voz Piper reproduzida")
            Log.d(TAG, "PIPER AUDIO TOCADO #$playedAudio id=${item.audioId}")
            enviarAck(item.audioId)
        } finally {
            try { player?.stop() } catch (_: Exception) { }
            try { player?.reset() } catch (_: Exception) { }
            try { player?.release() } catch (_: Exception) { }
            if (mediaPlayer === player) mediaPlayer = null
            if (playbackFile === file) playbackFile = null
            try { file?.delete() } catch (_: Exception) { }
        }
    }

    private fun enviarAck(audioId: String) {
        Thread {
            var connection: HttpURLConnection? = null
            try {
                connection = URL("$BACKEND_URL/api/audio/ack/$jobId/$audioId").openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.connectTimeout = 5000
                connection.readTimeout = 5000
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write("{}".toByteArray()) }
                Log.d(TAG, "ACK=$audioId HTTP=${connection.responseCode}")
            } catch (e: Exception) {
                Log.e(TAG, "Erro ACK", e)
            } finally {
                connection?.disconnect()
                removerAudioBaixado(audioId)
            }
        }.apply {
            name = "SI-Ack"
            isDaemon = true
        }.start()
    }

    private fun removerAudioBaixado(audioId: String) {
        synchronized(downloadedAudioLock) {
            downloadedAudioIds.remove(audioId)
        }
    }

    private fun limparEstado() {
        inputQueue.clear()
        outputQueue.clear()
        synchronized(downloadedAudioLock) { downloadedAudioIds.clear() }
        capturedChunks = 0L
        sentChunks = 0L
        receivedAudio = 0L
        playedAudio = 0L
        silentChunks = 0L
        lastRms = 0L
        lastError = null
        getSharedPreferences("si_diagnostic", Context.MODE_PRIVATE).edit().clear().apply()
    }

    private fun publicarDiagnostico(status: String?) {
        try {
            val prefs = getSharedPreferences("si_diagnostic", Context.MODE_PRIVATE)
            val editor = prefs.edit()
            if (status != null) editor.putString("status", status)
            editor.putLong("captured", capturedChunks)
            editor.putLong("sent", sentChunks)
            editor.putLong("received", receivedAudio)
            editor.putLong("played", playedAudio)
            editor.putLong("rms", lastRms)
            editor.putInt("queue", inputQueue.size)
            editor.putString("error", lastError ?: "")
            editor.apply()
        } catch (_: Exception) { }
    }

    private fun liberarMediaPlayer() {
        synchronized(this) {
            val player = mediaPlayer
            mediaPlayer = null
            try { player?.stop() } catch (_: Exception) { }
            try { player?.reset() } catch (_: Exception) { }
            try { player?.release() } catch (_: Exception) { }
            val file = playbackFile
            playbackFile = null
            try { file?.delete() } catch (_: Exception) { }
        }
    }

    private fun pararServico(pararSessao: Boolean) {
        if (stopping) return
        stopping = true
        running = false

        Log.d(TAG, "PARANDO SERVIÇO")
        captureThread?.interrupt()
        sendThread?.interrupt()
        statusThread?.interrupt()
        playbackThread?.interrupt()

        try { audioRecord?.stop() } catch (_: Exception) { }
        try { audioRecord?.release() } catch (_: Exception) { }
        audioRecord = null
        liberarMediaPlayer()

        try { projectionCallback?.let { mediaProjection?.unregisterCallback(it) } } catch (_: Exception) { }
        projectionCallback = null
        try { mediaProjection?.stop() } catch (_: Exception) { }
        mediaProjection = null

        inputQueue.clear()
        outputQueue.clear()
        publicarDiagnostico("⏹ Monitoramento parado")

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        pararServico(false)
        super.onDestroy()
    }
}
