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
import android.media.AudioTrack
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.Base64
import android.util.Log
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.io.ByteArrayOutputStream
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

        // A captura interna do Android trabalha de forma mais confiável em 48 kHz.
        // O backend continua recebendo 16 kHz, então fazemos downsample 48k -> 16k.
        private const val CAPTURE_SAMPLE_RATE = 48000
        private const val SEND_SAMPLE_RATE = 16000
        private const val CAPTURE_CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val PCM_FORMAT = AudioFormat.ENCODING_PCM_16BIT

        // 100 ms em 48 kHz mono PCM16 = 9600 bytes.
        private const val CAPTURE_CHUNK_BYTES = 9600
        // 100 ms em 16 kHz mono PCM16 = 3200 bytes.
        private const val SEND_CHUNK_BYTES = 3200

        private const val INPUT_QUEUE_SIZE = 80
        private const val OUTPUT_QUEUE_SIZE = 8
        private const val STATUS_INTERVAL_MS = 300L
    }

    @Volatile private var running = false
    @Volatile private var stopping = false
    @Volatile private var jobId = ""

    private var mediaProjection: MediaProjection? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null

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
        val action = intent?.action
        Log.d(TAG, "onStartCommand=$action")

        if (action == ACTION_STOP) {
            pararServico(true)
            return START_NOT_STICKY
        }

        if (action != ACTION_START) {
            return START_NOT_STICKY
        }

        if (running) {
            Log.d(TAG, "Serviço já está rodando")
            return START_STICKY
        }

        jobId = intent?.getStringExtra(EXTRA_JOB_ID).orEmpty()
        if (jobId.isEmpty()) {
            lastError = "jobId vazio"
            publicarDiagnostico("❌ jobId vazio")
            stopSelf()
            return START_NOT_STICKY
        }

        val resultCode = intent.getIntExtra(
            EXTRA_RESULT_CODE,
            Activity.RESULT_CANCELED
        )

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
            publicarDiagnostico("❌ Permissão de microfone/captura não concedida")
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            iniciarForeground()
            limparEstado()
            running = true
            stopping = false

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
            publicarDiagnostico("❌ Erro ao iniciar: ${lastError}")
            pararServico(false)
        }

        return START_STICKY
    }

    private fun criarCanal() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_ID,
                "SI Tradutor Live",
                NotificationManager.IMPORTANCE_LOW
            )
            channel.description = "Captura e tradução de áudio em tempo real"
            manager.createNotificationChannel(channel)
        }
    }

    private fun iniciarForeground() {
        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("SI Tradutor Live")
                .setContentText("Capturando áudio em tempo real")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("SI Tradutor Live")
                .setContentText("Capturando áudio em tempo real")
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
        val projection = mediaProjection
            ?: throw IllegalStateException("MediaProjection não disponível")

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            throw IllegalStateException("Captura interna exige Android 10 ou superior")
        }

        val minBuffer = AudioRecord.getMinBufferSize(
            CAPTURE_SAMPLE_RATE,
            CAPTURE_CHANNEL,
            PCM_FORMAT
        )

        if (minBuffer <= 0) {
            throw IllegalStateException("AudioRecord.getMinBufferSize falhou: $minBuffer")
        }

        val bufferSize = max(
            minBuffer * 4,
            CAPTURE_CHUNK_BYTES * 8
        )

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
            .setBufferSizeInBytes(bufferSize)
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
                    throw IllegalStateException(
                        "AudioRecord não entrou em RECORDING; estado=${record.recordingState}"
                    )
                }

                Log.d(TAG, "CAPTURA INICIADA: ${CAPTURE_SAMPLE_RATE} Hz")
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
                        val rms = calcularRms(chunk)
                        lastRms = rms

                        if (rms == 0L) silentChunks++
                        else receivedAudio++

                        val pcm16k = downsample48kTo16k(chunk)
                        if (pcm16k.isNotEmpty()) {
                            if (!inputQueue.offer(pcm16k)) {
                                inputQueue.poll()
                                inputQueue.offer(pcm16k)
                            }
                        }

                        if (capturedChunks <= 5 || capturedChunks % 25L == 0L) {
                            Log.d(
                                TAG,
                                "CAPTURA=$capturedChunks read=$read rms=$rms " +
                                    "silencios=$silentChunks fila=${inputQueue.size}"
                            )
                        }

                        publicarDiagnostico(null)
                        continue
                    }

                    if (read == AudioRecord.ERROR_DEAD_OBJECT) {
                        lastError = "AudioRecord ERROR_DEAD_OBJECT"
                        Log.e(TAG, lastError!!)
                        break
                    }

                    if (read == AudioRecord.ERROR_INVALID_OPERATION) {
                        lastError = "AudioRecord ERROR_INVALID_OPERATION"
                        Log.e(TAG, lastError!!)
                    } else if (read == AudioRecord.ERROR_BAD_VALUE) {
                        lastError = "AudioRecord ERROR_BAD_VALUE"
                        Log.e(TAG, lastError!!)
                    } else if (read == AudioRecord.ERROR) {
                        lastError = "AudioRecord ERROR"
                        Log.e(TAG, lastError!!)
                    } else {
                        lastError = "AudioRecord read retornou $read"
                        Log.e(TAG, lastError!!)
                    }

                    publicarDiagnostico("⚠️ Captura retornou $read")
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
        if (data.size < 6) return ByteArray(0)

        val sampleCount = data.size / 2
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
            Log.d(TAG, "THREAD ENVIO INICIADA")

            while (running && !stopping) {
                try {
                    val chunk = inputQueue.poll(500, TimeUnit.MILLISECONDS) ?: continue
                    enviarAudio(chunk)
                } catch (_: InterruptedException) {
                    break
                } catch (e: Exception) {
                    if (running) {
                        lastError = e.message ?: e.javaClass.simpleName
                        Log.e(TAG, "Erro no envio", e)
                        publicarDiagnostico("⚠️ Erro no envio: $lastError")
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
            connection = URL("$BACKEND_URL/api/audio/chunk")
                .openConnection() as HttpURLConnection

            connection.requestMethod = "POST"
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            connection.doOutput = true
            connection.useCaches = false
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Connection", "close")

            val json = JSONObject()
            json.put("jobId", jobId)
            json.put("audio", Base64.encodeToString(audio, Base64.NO_WRAP))
            json.put("mimeType", "audio/pcm")
            json.put("sampleRate", SEND_SAMPLE_RATE)

            connection.outputStream.use { out ->
                out.write(json.toString().toByteArray(Charsets.UTF_8))
                out.flush()
            }

            val code = connection.responseCode
            if (code in 200..299) {
                sentChunks++
                if (sentChunks <= 5 || sentChunks % 25L == 0L) {
                    Log.d(TAG, "ENVIO=$sentChunks")
                }
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
            connection = URL("$BACKEND_URL/api/audio/status/$jobId")
                .openConnection() as HttpURLConnection

            connection.requestMethod = "GET"
            connection.connectTimeout = 8000
            connection.readTimeout = 8000
            connection.setRequestProperty("Accept", "application/json")

            val code = connection.responseCode
            if (code !in 200..299) return

            val response = connection.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(response)
            if (!json.optBoolean("ok", false)) return

            val sourceText = json.optString("sourceText", "")
            val translatedText = json.optString("translatedText", "")
            if (sourceText.isNotEmpty() || translatedText.isNotEmpty()) {
                Log.d(TAG, "TEXTO source=$sourceText translated=$translatedText")
            }

            var audioId = json.optString("audioId", "")
            var audioUrl = json.optString("audioUrl", "")

            if (audioId.isEmpty() || audioUrl.isEmpty()) {
                publicarDiagnostico(null)
                return
            }

            if (audioUrl.startsWith("/")) audioUrl = BACKEND_URL + audioUrl

            synchronized(downloadedAudioLock) {
                if (downloadedAudioIds.contains(audioId)) return
                downloadedAudioIds.add(audioId)
            }

            Log.d(TAG, "NOVO AUDIO PIPER=$audioId")
            publicarDiagnostico("🔊 Áudio Piper recebido")

            val wav = baixarWav(audioUrl)
            if (wav == null) {
                removerAudioBaixado(audioId)
                return
            }

            val validWav = validarWav(wav)
            if (validWav == null) {
                removerAudioBaixado(audioId)
                return
            }

            val item = OutputAudio(audioId, validWav)
            if (!outputQueue.offer(item, 2, TimeUnit.SECONDS)) {
                removerAudioBaixado(audioId)
                return
            }

            Log.d(TAG, "WAV PIPER ENFILEIRADO=${wav.size} bytes")
            publicarDiagnostico("🔊 Áudio Piper recebido")
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
                        if (out.size() > 10 * 1024 * 1024) {
                            throw IllegalStateException("WAV Piper maior que 10 MB")
                        }
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

    private data class WavInfo(
        val channels: Int,
        val sampleRate: Int,
        val bits: Int,
        val dataStart: Int,
        val dataSize: Int
    )

    private fun validarWav(wav: ByteArray): ByteArray? {
        val info = analisarWav(wav) ?: return null
        Log.d(
            TAG,
            "WAV OK rate=${info.sampleRate} channels=${info.channels} bits=${info.bits} data=${info.dataSize}"
        )
        return wav
    }

    private fun analisarWav(wav: ByteArray): WavInfo? {
        try {
            if (wav.size < 44) {
                lastError = "WAV pequeno demais: ${wav.size} bytes"
                return null
            }

            fun intLE(offset: Int): Int {
                if (offset < 0 || offset + 4 > wav.size) return -1
                return (wav[offset].toInt() and 0xff) or
                    ((wav[offset + 1].toInt() and 0xff) shl 8) or
                    ((wav[offset + 2].toInt() and 0xff) shl 16) or
                    ((wav[offset + 3].toInt() and 0xff) shl 24)
            }

            fun shortLE(offset: Int): Int {
                if (offset < 0 || offset + 2 > wav.size) return -1
                return (wav[offset].toInt() and 0xff) or
                    ((wav[offset + 1].toInt() and 0xff) shl 8)
            }

            fun chunkName(offset: Int): String {
                if (offset < 0 || offset + 4 > wav.size) return ""
                return String(wav, offset, 4, Charsets.US_ASCII)
            }

            if (chunkName(0) != "RIFF" || chunkName(8) != "WAVE") {
                lastError = "WAV inválido: RIFF/WAVE não encontrado"
                return null
            }

            var offset = 12
            var format = 0
            var channels = 0
            var sampleRate = 0
            var bits = 0
            var dataStart = -1
            var dataSize = 0

            while (offset + 8 <= wav.size) {
                val id = chunkName(offset)
                val size = intLE(offset + 4)
                if (size < 0) return null

                val payload = offset + 8
                if (payload > wav.size) return null

                val safeSize = minOf(size, wav.size - payload)

                if (id == "fmt " && safeSize >= 16) {
                    format = shortLE(payload)
                    channels = shortLE(payload + 2)
                    sampleRate = intLE(payload + 4)
                    bits = shortLE(payload + 14)
                }

                if (id == "data") {
                    dataStart = payload
                    dataSize = safeSize
                    break
                }

                offset = payload + size
                if ((offset and 1) != 0) offset++
            }

            if (format != 1 || channels !in 1..2 || sampleRate <= 0 || bits != 16 || dataStart < 0 || dataSize <= 0) {
                lastError = "WAV incompatível format=$format channels=$channels rate=$sampleRate bits=$bits data=$dataSize"
                return null
            }

            return WavInfo(channels, sampleRate, bits, dataStart, dataSize)
        } catch (e: Exception) {
            lastError = "Erro validando WAV: ${e.message}"
            return null
        }
    }

    private fun iniciarPlayback() {
        playbackThread = Thread {
            Log.d(TAG, "THREAD PLAYBACK INICIADA")

            while (running && !stopping) {
                try {
                    val item = outputQueue.poll(500, TimeUnit.MILLISECONDS) ?: continue
                    tocarWav(item)
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

            liberarAudioTrack()
        }.apply {
            name = "SI-PiperPlayback"
            isDaemon = true
        }

        playbackThread?.start()
    }

    private fun tocarWav(item: OutputAudio) {
        val info = analisarWav(item.wav)
        if (info == null) {
            enviarAck(item.audioId)
            return
        }

        var track: AudioTrack? = null

        try {
            val channelMask = if (info.channels == 1) {
                AudioFormat.CHANNEL_OUT_MONO
            } else {
                AudioFormat.CHANNEL_OUT_STEREO
            }

            val minBuffer = AudioTrack.getMinBufferSize(
                info.sampleRate,
                channelMask,
                AudioFormat.ENCODING_PCM_16BIT
            )

            if (minBuffer <= 0) {
                throw IllegalStateException("AudioTrack.getMinBufferSize falhou: $minBuffer")
            }

            val bufferSize = max(minBuffer * 2, 8192)

            track = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(info.sampleRate)
                            .setChannelMask(channelMask)
                            .build()
                    )
                    .setBufferSizeInBytes(bufferSize)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
            } else {
                @Suppress("DEPRECATION")
                AudioTrack(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(info.sampleRate)
                        .setChannelMask(channelMask)
                        .build(),
                    bufferSize,
                    AudioTrack.MODE_STREAM,
                    0
                )
            }

            if (track.state != AudioTrack.STATE_INITIALIZED) {
                throw IllegalStateException("AudioTrack não inicializado")
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                track.setVolume(1.0f)
            } else {
                @Suppress("DEPRECATION")
                track.setStereoVolume(1.0f, 1.0f)
            }

            synchronized(this) {
                audioTrack = track
            }

            track.play()

            if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                throw IllegalStateException("AudioTrack não entrou em PLAYING")
            }

            val end = info.dataStart + info.dataSize
            var offset = info.dataStart

            while (running && !stopping && offset < end) {
                val count = minOf(16384, end - offset)
                val written = track.write(
                    item.wav,
                    offset,
                    count,
                    AudioTrack.WRITE_BLOCKING
                )

                if (written < 0) {
                    throw IllegalStateException("AudioTrack.write retornou $written")
                }

                if (written == 0) {
                    Thread.sleep(10)
                } else {
                    offset += written
                }
            }

            try {
                track.stop()
            } catch (_: Exception) { }

            playedAudio++
            publicarDiagnostico("🔊 Áudio Piper reproduzido")
            Log.d(TAG, "AUDIO TOCADO #$playedAudio id=${item.audioId}")
            enviarAck(item.audioId)
        } finally {
            synchronized(this) {
                if (audioTrack === track) audioTrack = null
            }
            try { track?.flush() } catch (_: Exception) { }
            try { track?.release() } catch (_: Exception) { }
        }
    }

    private fun enviarAck(audioId: String) {
        Thread {
            var connection: HttpURLConnection? = null
            try {
                connection = URL("$BACKEND_URL/api/audio/ack/$jobId/$audioId")
                    .openConnection() as HttpURLConnection
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
        synchronized(downloadedAudioLock) {
            downloadedAudioIds.clear()
        }

        capturedChunks = 0L
        sentChunks = 0L
        receivedAudio = 0L
        playedAudio = 0L
        silentChunks = 0L
        lastRms = 0L
        lastError = null

        getSharedPreferences("si_diagnostic", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
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
        } catch (_: Exception) {
        }
    }

    private fun liberarAudioTrack() {
        synchronized(this) {
            val track = audioTrack
            audioTrack = null
            try { track?.pause() } catch (_: Exception) { }
            try { track?.flush() } catch (_: Exception) { }
            try { track?.release() } catch (_: Exception) { }
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

        liberarAudioTrack()

        try {
            projectionCallback?.let { mediaProjection?.unregisterCallback(it) }
        } catch (_: Exception) { }
        projectionCallback = null

        try { mediaProjection?.stop() } catch (_: Exception) { }
        mediaProjection = null

        inputQueue.clear()
        outputQueue.clear()

        publicarDiagnostico("⏹ Monitoramento parado")

        if (pararSessao) {
            // MainActivity já chama /api/audio/stop; não fazemos uma segunda chamada aqui.
        }

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        pararServico(false)
        super.onDestroy()
    }
}
