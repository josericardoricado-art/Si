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
import android.media.MediaRecorder
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
        const val EXTRA_CAPTURE_MODE = "captureMode"
        const val MODE_MICROPHONE = "microphone"
        const val MODE_INTERNAL = "internal"

        private const val CHANNEL_ID = "si_audio_capture"
        private const val NOTIFICATION_ID = 1001

        private const val CAPTURE_SAMPLE_RATE = 48000
        private const val SEND_SAMPLE_RATE = 16000
        private const val CAPTURE_CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val PCM_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val CAPTURE_CHUNK_BYTES = 9600
        private const val INPUT_QUEUE_SIZE = 80
        private const val BATCH_CHUNKS = 10 // 10 x 100 ms = 1 segundo por POST
        private const val OUTPUT_QUEUE_SIZE = 4
        private const val STATUS_INTERVAL_MS = 300L
    }

    @Volatile private var running = false
    @Volatile private var stopping = false
    @Volatile private var jobId = ""
    @Volatile private var captureMode = MODE_INTERNAL

    private var mediaProjection: MediaProjection? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var audioRecord: AudioRecord? = null

    private var mediaPlayer: MediaPlayer? = null
    private var playbackFile: File? = null
    private val mediaPlayerLock = Any()

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
    @Volatile private var receivedAudio = 0L // WAVs Piper validados e colocados na fila
    @Volatile private var playedAudio = 0L
    @Volatile private var playbackAttempts = 0L
    @Volatile private var lastPlaybackStage = "Aguardando WAV Piper"
    @Volatile private var outputQueueCount = 0
    @Volatile private var silentChunks = 0L
    @Volatile private var lastRms = 0L
    @Volatile private var peakLevel = 0
    @Volatile private var nonZeroSamples = 0
    @Volatile private var silentStreak = 0L
    @Volatile private var captureState = "Aguardando captura"
    @Volatile private var androidCaptureSilenced = false
    @Volatile private var lastError: String? = null
    @Volatile private var serverStatus = "Aguardando servidor"
    @Volatile private var deepgramConnected = false
    @Volatile private var sourceText = ""
    @Volatile private var translatedText = ""
    @Volatile private var serverError = ""
    @Volatile private var serverAudioQueue = 0

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

        captureMode = if (intent.getStringExtra(EXTRA_CAPTURE_MODE) == MODE_MICROPHONE) MODE_MICROPHONE else MODE_INTERNAL
        jobId = intent.getStringExtra(EXTRA_JOB_ID).orEmpty()
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

        if (captureMode == MODE_INTERNAL && (resultCode != Activity.RESULT_OK || resultData == null)) {
            lastError = "MediaProjection inválida"
            publicarDiagnostico("❌ MediaProjection inválida")
            stopSelf()
            return START_NOT_STICKY
        }

        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            lastError = "Permissão RECORD_AUDIO não concedida"
            publicarDiagnostico("❌ Permissão de áudio não concedida")
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            limparEstado()
            stopping = false
            running = true

            iniciarForeground()
            if (captureMode == MODE_INTERNAL) {
                iniciarMediaProjection(resultCode, resultData!!)
            }
            iniciarCaptura()
            iniciarEnvio()
            iniciarStatus()
            iniciarPlayback()

            publicarDiagnostico(if (captureMode == MODE_MICROPHONE) "🎤 Capturando pelo microfone..." else "🎤 Capturando áudio interno...")
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
                if (captureMode == MODE_MICROPHONE) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                else ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun iniciarMediaProjection(resultCode: Int, resultData: Intent) {
        val manager = getSystemService(
            Context.MEDIA_PROJECTION_SERVICE
        ) as MediaProjectionManager

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
        if (captureMode == MODE_INTERNAL && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            throw IllegalStateException("Captura interna exige Android 10 ou superior")
        }

        val minBuffer = AudioRecord.getMinBufferSize(
            CAPTURE_SAMPLE_RATE,
            CAPTURE_CHANNEL,
            PCM_FORMAT
        )

        if (minBuffer <= 0) {
            throw IllegalStateException(
                "AudioRecord.getMinBufferSize falhou: $minBuffer"
            )
        }

        val builder = AudioRecord.Builder()
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(PCM_FORMAT)
                    .setSampleRate(CAPTURE_SAMPLE_RATE)
                    .setChannelMask(CAPTURE_CHANNEL)
                    .build()
            )
            .setBufferSizeInBytes(max(minBuffer * 4, CAPTURE_CHUNK_BYTES * 8))

        if (captureMode == MODE_MICROPHONE) {
            builder.setAudioSource(MediaRecorder.AudioSource.MIC)
        } else {
            val projection = mediaProjection ?: throw IllegalStateException("MediaProjection não disponível")
            val captureConfig = AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            builder.setAudioPlaybackCaptureConfig(captureConfig)
        }
        val record = builder.build()

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw IllegalStateException("AudioRecord não inicializado")
        }

        audioRecord = record
        captureState = "AudioRecord inicializado: 48 kHz mono, PCM16 ($captureMode)"
        publicarDiagnostico(null)

        captureThread = Thread {
            val captureBuffer = ByteArray(CAPTURE_CHUNK_BYTES)

            try {
                record.startRecording()

                if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    throw IllegalStateException(
                        "AudioRecord não entrou em RECORDING"
                    )
                }

                captureState = "AudioRecord gravando: 48 kHz ($captureMode)"
                Log.d(TAG, "CAPTURA INICIADA 48kHz")
                publicarDiagnostico(if (captureMode == MODE_MICROPHONE) "🎤 Capturando pelo microfone..." else "🎤 Capturando áudio interno...")

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
                        // Android 10+ pode silenciar a captura por política do sistema.
                        // Este sinal não depende do volume do alto-falante.
                        if (capturedChunks == 1L || capturedChunks % 25L == 0L) {
                            androidCaptureSilenced = try {
                                record.activeRecordingConfiguration?.isClientSilenced == true
                            } catch (_: Exception) {
                                false
                            }
                        }
                        val stats = analisarPcm(chunk)
                        lastRms = stats.first
                        peakLevel = stats.second
                        nonZeroSamples = stats.third

                        if (nonZeroSamples == 0) {
                            silentChunks++
                            silentStreak++
                            if (silentStreak >= 50L) {
                                captureState = if (captureMode == MODE_MICROPHONE) {
                                    "Microfone sem sinal: aproxime o som e confira a permissão"
                                } else if (androidCaptureSilenced) {
                                    "Android sinaliza captura silenciada; verifique restrições de captura"
                                } else {
                                    "PCM zerado: app de origem pode bloquear captura interna"
                                }
                            }
                        } else {
                            silentStreak = 0L
                            captureState = if (captureMode == MODE_MICROPHONE) "Áudio detectado no microfone" else "Áudio detectado na captura interna"
                        }

                        // Não sobrecarregar o Render com blocos totalmente zerados.
                        // Mantém blocos com amostras não nulas, mesmo em volume baixo.
                        if (captureMode == MODE_MICROPHONE || nonZeroSamples > 0) {
                            val pcm16k = downsample48kTo16k(chunk)
                            if (pcm16k.isNotEmpty()) {
                                if (!inputQueue.offer(pcm16k)) {
                                    inputQueue.poll()
                                    inputQueue.offer(pcm16k)
                                }
                            }
                        }

                        if (
                            capturedChunks <= 5L ||
                            capturedChunks % 25L == 0L
                        ) {
                            Log.d(
                                TAG,
                                "CAPTURA=$capturedChunks read=$read " +
                                    "rms=$lastRms fila=${inputQueue.size}"
                            )
                        }

                        publicarDiagnostico(null)
                        continue
                    }

                    lastError = "AudioRecord retornou $read"
                    Log.e(TAG, lastError!!)
                    publicarDiagnostico("⚠️ Captura retornou $read")

                    if (read == AudioRecord.ERROR_DEAD_OBJECT) break
                    Thread.sleep(100L)
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
            val avg = ((s1 + s2 + s3) / 3)
                .coerceIn(-32768, 32767)

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

        return (
            (data[p + 1].toInt() shl 8) or
                (data[p].toInt() and 0xff)
            ).toShort().toInt()
    }

    // Retorna RMS, pico absoluto e quantidade de amostras não nulas.
    // O RMS inteiro pode ser 0 com sinal muito baixo; o pico distingue isso
    // de um buffer inteiramente preenchido com zeros.
    private fun analisarPcm(data: ByteArray): Triple<Long, Int, Int> {
        if (data.size < 2) return Triple(0L, 0, 0)

        var sum = 0.0
        var count = 0
        var peak = 0
        var nonZero = 0
        var i = 0

        while (i + 1 < data.size) {
            val sample = (
                (data[i + 1].toInt() shl 8) or
                    (data[i].toInt() and 0xff)
                ).toShort().toInt()

            sum += sample.toDouble() * sample.toDouble()
            peak = max(peak, kotlin.math.abs(sample))
            if (sample != 0) nonZero++
            count++
            i += 2
        }

        return if (count == 0) Triple(0L, 0, 0)
        else Triple(sqrt(sum / count).toLong(), peak, nonZero)
    }

    private fun iniciarEnvio() {
        sendThread = Thread {
            Log.d(TAG, "THREAD ENVIO INICIADA")

            while (running && !stopping) {
                try {
                    val chunk = inputQueue.poll(
                        500,
                        TimeUnit.MILLISECONDS
                    ) ?: continue

                    // Um POST por 100 ms satura a rede móvel e deixa a fila cheia.
                    // Agrupa até 1 segundo de PCM contínuo, preservando a ordem.
                    val batch = java.io.ByteArrayOutputStream()
                    batch.write(chunk)
                    var count = 1
                    while (count < BATCH_CHUNKS) {
                        val next = inputQueue.poll(120, TimeUnit.MILLISECONDS) ?: break
                        batch.write(next)
                        count++
                    }
                    enviarAudio(batch.toByteArray())
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
            connection = URL(
                "$BACKEND_URL/api/audio/chunk"
            ).openConnection() as HttpURLConnection

            connection.requestMethod = "POST"
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            connection.doOutput = true
            connection.useCaches = false
            connection.setRequestProperty(
                "Content-Type",
                "application/json"
            )
            connection.setRequestProperty(
                "Accept",
                "application/json"
            )

            val json = JSONObject()
            json.put("jobId", jobId)
            json.put(
                "audio",
                Base64.encodeToString(audio, Base64.NO_WRAP)
            )
            json.put("mimeType", "audio/pcm")
            json.put("sampleRate", SEND_SAMPLE_RATE)

            connection.outputStream.use {
                it.write(json.toString().toByteArray(Charsets.UTF_8))
                it.flush()
            }

            val code = connection.responseCode

            if (code in 200..299) {
                sentChunks++

                if (
                    sentChunks <= 5L ||
                    sentChunks % 25L == 0L
                ) {
                    Log.d(TAG, "ENVIO=$sentChunks")
                }
            } else {
                val details = try { connection.errorStream?.bufferedReader()?.use { it.readText() } ?: "" } catch (_: Exception) { "" }
                lastError = "Render HTTP=$code ${details.take(160)}"
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
                    try { Thread.sleep(500L) } catch (_: Exception) { }
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
            connection = URL(
                "$BACKEND_URL/api/audio/status/$jobId"
            ).openConnection() as HttpURLConnection

            connection.requestMethod = "GET"
            connection.connectTimeout = 8000
            connection.readTimeout = 8000

            val code = connection.responseCode
            if (code !in 200..299) return

            val response = connection.inputStream
                .bufferedReader()
                .use { it.readText() }

            val json = JSONObject(response)
            if (!json.optBoolean("ok", false)) return

            val source = if (json.isNull("sourceText")) "" else json.optString("sourceText", "")
            val translated = if (json.isNull("translatedText")) "" else json.optString("translatedText", "")
            sourceText = source.takeLast(240)
            translatedText = translated.takeLast(240)
            serverStatus = json.optString("status", "desconhecido")
            deepgramConnected = json.optBoolean("deepgramConnected", false)
            serverError = if (json.isNull("error")) "" else json.optString("error", "")
            serverAudioQueue = json.optInt("audioQueueSize", 0)
            publicarDiagnostico(null)

            if (source.isNotEmpty() || translated.isNotEmpty()) {
                Log.d(
                    TAG,
                    "TEXTO source=$source translated=$translated"
                )
            }

            // JSONObject.optString converte JSON null na string literal "null".
            // Nunca tentar abrir URL("null") nem marcar esse áudio como baixado.
            val audioId = if (json.isNull("audioId")) "" else json.optString("audioId", "").trim()
            var audioUrl = if (json.isNull("audioUrl")) "" else json.optString("audioUrl", "").trim()

            if (audioId.isBlank() || audioId.equals("null", ignoreCase = true) ||
                audioUrl.isBlank() || audioUrl.equals("null", ignoreCase = true)
            ) {
                return // O servidor ainda não tem um WAV disponível.
            }

            if (audioUrl.startsWith("/")) {
                audioUrl = BACKEND_URL + audioUrl
            }

            if (!audioUrl.startsWith("https://") && !audioUrl.startsWith("http://")) {
                lastError = "URL WAV inválida: $audioUrl"
                lastPlaybackStage = "URL WAV inválida"
                publicarDiagnostico("❌ Endereço do WAV inválido")
                return
            }

            synchronized(downloadedAudioLock) {
                if (downloadedAudioIds.contains(audioId)) return
                downloadedAudioIds.add(audioId)
            }

            Log.d(
                TAG,
                "NOVO AUDIO PIPER=$audioId url=$audioUrl"
            )
            lastPlaybackStage = "URL Piper recebida; baixando WAV"
            publicarDiagnostico("🔊 URL do Piper recebida; baixando...")

            val wav = baixarWav(audioUrl)

            if (wav == null) {
                lastPlaybackStage = "Falha no download do WAV"
                publicarDiagnostico("❌ Não foi possível baixar o WAV Piper")
                removerAudioBaixado(audioId)
                return
            }

            if (!isWavPcm16(wav)) {
                lastPlaybackStage = "WAV inválido"
                lastError = "WAV Piper inválido ou não PCM16"
                Log.e(TAG, lastError!!)
                removerAudioBaixado(audioId)
                publicarDiagnostico("❌ WAV Piper inválido")
                return
            }

            val item = OutputAudio(audioId, wav)

            if (!outputQueue.offer(item, 2, TimeUnit.SECONDS)) {
                removerAudioBaixado(audioId)
                lastPlaybackStage = "Fila de saída cheia"
                lastError = "Fila de reprodução cheia"
                publicarDiagnostico("⚠️ Fila de voz cheia")
                return
            }

            receivedAudio++
            outputQueueCount = outputQueue.size
            lastPlaybackStage = "WAV validado e enfileirado"
            Log.d(
                TAG,
                "WAV PIPER ENFILEIRADO=${wav.size} bytes; recebidos=$receivedAudio"
            )
            publicarDiagnostico("🔊 WAV Piper pronto para tocar")
        } catch (e: Exception) {
            if (running) {
                lastError = e.message ?: e.javaClass.simpleName
                Log.e(TAG, "Erro consultando status", e)
                publicarDiagnostico("⚠️ Erro status: $lastError")
            }
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
            connection.setRequestProperty(
                "Accept",
                "audio/wav,audio/x-wav,*/*"
            )

            if (connection.responseCode !in 200..299) {
                lastError = "Download WAV HTTP=${connection.responseCode}"
                Log.e(TAG, lastError!!)
                null
            } else {
                connection.inputStream.use { input ->
                    val buffer = ByteArray(8192)
                    val output = ByteArrayOutputStream()

                    while (true) {
                        val n = input.read(buffer)
                        if (n <= 0) break

                        output.write(buffer, 0, n)

                        if (output.size() > 10 * 1024 * 1024) {
                            throw IllegalStateException(
                                "WAV maior que 10 MB"
                            )
                        }
                    }

                    output.toByteArray()
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
        val bitsPerSample: Int,
        val dataStart: Int,
        val dataSize: Int
    )

    private fun lerWavInfo(wav: ByteArray): WavInfo? {
        if (wav.size < 44) return null
        if (String(wav, 0, 4, Charsets.US_ASCII) != "RIFF") return null
        if (String(wav, 8, 4, Charsets.US_ASCII) != "WAVE") return null

        fun u16(p: Int): Int {
            if (p < 0 || p + 2 > wav.size) return -1
            return (wav[p].toInt() and 0xff) or
                ((wav[p + 1].toInt() and 0xff) shl 8)
        }

        fun i32(p: Int): Int {
            if (p < 0 || p + 4 > wav.size) return -1
            return (wav[p].toInt() and 0xff) or
                ((wav[p + 1].toInt() and 0xff) shl 8) or
                ((wav[p + 2].toInt() and 0xff) shl 16) or
                ((wav[p + 3].toInt() and 0xff) shl 24)
        }

        fun chunkId(p: Int): String {
            if (p < 0 || p + 4 > wav.size) return ""
            return String(wav, p, 4, Charsets.US_ASCII)
        }

        var p = 12
        var format = 0
        var channels = 0
        var rate = 0
        var bits = 0
        var dataStart = -1
        var dataSize = 0

        while (p + 8 <= wav.size) {
            val id = chunkId(p)
            val size = i32(p + 4)
            if (size < 0) return null

            val payload = p + 8
            if (payload > wav.size) return null

            val safeSize = minOf(size, wav.size - payload)

            if (id == "fmt " && safeSize >= 16) {
                format = u16(payload)
                channels = u16(payload + 2)
                rate = i32(payload + 4)
                bits = u16(payload + 14)
            }

            if (id == "data") {
                dataStart = payload
                dataSize = safeSize
                break
            }

            p = payload + size
            if ((p and 1) != 0) p++
        }

        if (
            format != 1 ||
            channels !in 1..2 ||
            rate <= 0 ||
            bits != 16 ||
            dataStart < 0 ||
            dataSize <= 0
        ) {
            Log.e(
                TAG,
                "WAV incompatível format=$format channels=$channels " +
                    "rate=$rate bits=$bits data=$dataSize"
            )
            return null
        }

        Log.d(
            TAG,
            "WAV OK rate=$rate channels=$channels bits=$bits " +
                "data=$dataSize bytes=${wav.size}"
        )

        return WavInfo(
            channels,
            rate,
            bits,
            dataStart,
            dataSize
        )
    }

    private fun isWavPcm16(wav: ByteArray): Boolean {
        return lerWavInfo(wav) != null
    }

    private fun iniciarPlayback() {
        playbackThread = Thread {
            Log.d(TAG, "THREAD PLAYBACK PIPER INICIADA")
            publicarDiagnostico("🔊 Aguardando voz Piper...")

            while (running && !stopping) {
                try {
                    val item = outputQueue.poll(
                        500,
                        TimeUnit.MILLISECONDS
                    ) ?: continue

                    outputQueueCount = outputQueue.size
                    tocarWavComMediaPlayer(item)
                } catch (_: InterruptedException) {
                    break
                } catch (e: Exception) {
                    if (running) {
                        lastError = e.message ?: e.javaClass.simpleName
                        Log.e(TAG, "Erro playback", e)
                        publicarDiagnostico(
                            "❌ Erro playback: $lastError"
                        )
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

    /*
     * CORREÇÃO PRINCIPAL DA VOZ PIPER:
     * O código anterior usava prepare() + OnPreparedListener.
     * Nesta thread não existe Looper para entregar o callback,
     * então a reprodução podia ficar parada para sempre.
     * Aqui usamos prepare() síncrono e, logo depois, start().
     */
    private fun tocarWavComMediaPlayer(item: OutputAudio) {
        var file: File? = null
        var player: MediaPlayer? = null

        try {
            playbackAttempts++
            lastPlaybackStage = "Iniciando reprodução #$playbackAttempts"
            publicarDiagnostico("🔊 Preparando reprodução Piper...")
            val info = lerWavInfo(item.wav)
                ?: throw IllegalStateException("WAV Piper inválido")

            file = File.createTempFile(
                "si_piper_${item.audioId}_",
                ".wav",
                cacheDir
            )
            file.writeBytes(item.wav)

            synchronized(mediaPlayerLock) {
                liberarMediaPlayerInterno()

                player = MediaPlayer()
                mediaPlayer = player
                playbackFile = file

                player!!.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(
                            AudioAttributes.CONTENT_TYPE_SPEECH
                        )
                        .build()
                )

                player!!.setDataSource(file.absolutePath)
                player!!.setVolume(1.0f, 1.0f)

                // prepare() é síncrono; não dependemos de OnPreparedListener.
                lastPlaybackStage = "MediaPlayer.prepare()"
                publicarDiagnostico("🔊 Preparando WAV no Android...")
                player!!.prepare()

                Log.d(
                    TAG,
                    "PIPER WAV PREPARED audioId=${item.audioId} " +
                        "rate=${info.sampleRate} channels=${info.channels} " +
                        "bytes=${item.wav.size}"
                )

                lastPlaybackStage = "MediaPlayer.start()"
                publicarDiagnostico("🔊 Iniciando voz Piper...")
                player!!.start()
                lastPlaybackStage = "MediaPlayer.start() chamado"

                Log.d(
                    TAG,
                    "PIPER START audioId=${item.audioId}"
                )
            }

            while (running && !stopping) {
                val playing = synchronized(mediaPlayerLock) {
                    try {
                        player?.isPlaying == true
                    } catch (_: Exception) {
                        false
                    }
                }

                if (!playing) break
                Thread.sleep(20L)
            }

            playedAudio++
            lastPlaybackStage = "Reprodução finalizada"
            publicarDiagnostico("🔊 Voz Piper reproduzida")
            Log.d(
                TAG,
                "PIPER AUDIO TOCADO #$playedAudio id=${item.audioId}"
            )

            enviarAck(item.audioId)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            Log.d(TAG, "Playback interrompido")
        } catch (e: Exception) {
            lastError = "${e.javaClass.simpleName}: ${e.message ?: "sem detalhes"}"
            lastPlaybackStage = "Falha: $lastPlaybackStage"
            Log.e(TAG, "Erro reproduzindo WAV Piper", e)
            publicarDiagnostico("❌ Piper não reproduziu: $lastError")
        } finally {
            synchronized(mediaPlayerLock) {
                if (mediaPlayer === player) {
                    liberarMediaPlayerInterno()
                } else {
                    try { player?.stop() } catch (_: Exception) { }
                    try { player?.reset() } catch (_: Exception) { }
                    try { player?.release() } catch (_: Exception) { }
                }
            }

            try { file?.delete() } catch (_: Exception) { }
        }
    }

    private fun enviarAck(audioId: String) {
        Thread {
            var connection: HttpURLConnection? = null

            try {
                connection = URL(
                    "$BACKEND_URL/api/audio/ack/$jobId/$audioId"
                ).openConnection() as HttpURLConnection

                connection.requestMethod = "POST"
                connection.connectTimeout = 5000
                connection.readTimeout = 5000
                connection.doOutput = true
                connection.setRequestProperty(
                    "Content-Type",
                    "application/json"
                )

                connection.outputStream.use {
                    it.write("{}".toByteArray(Charsets.UTF_8))
                }

                Log.d(
                    TAG,
                    "ACK=$audioId HTTP=${connection.responseCode}"
                )
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

        synchronized(downloadedAudioLock) {
            downloadedAudioIds.clear()
        }

        capturedChunks = 0L
        sentChunks = 0L
        receivedAudio = 0L
        playedAudio = 0L
        playbackAttempts = 0L
        lastPlaybackStage = "Aguardando WAV Piper"
        outputQueueCount = 0
        silentChunks = 0L
        lastRms = 0L
        peakLevel = 0
        nonZeroSamples = 0
        silentStreak = 0L
        captureState = "Aguardando captura"
        androidCaptureSilenced = false
        serverStatus = "Aguardando servidor"
        deepgramConnected = false
        sourceText = ""
        translatedText = ""
        serverError = ""
        serverAudioQueue = 0
        lastError = null

        getSharedPreferences(
            "si_diagnostic",
            Context.MODE_PRIVATE
        ).edit().clear().apply()
    }

    private fun publicarDiagnostico(status: String?) {
        try {
            val prefs = getSharedPreferences(
                "si_diagnostic",
                Context.MODE_PRIVATE
            )

            val editor = prefs.edit()

            if (status != null) {
                editor.putString("status", status)
            }

            editor.putLong("captured", capturedChunks)
            editor.putLong("sent", sentChunks)
            editor.putLong("received", receivedAudio)
            editor.putLong("played", playedAudio)
            editor.putLong("playbackAttempts", playbackAttempts)
            editor.putString("playbackStage", lastPlaybackStage)
            editor.putInt("outputQueue", outputQueue.size)
            editor.putLong("rms", lastRms)
            editor.putInt("peak", peakLevel)
            editor.putInt("nonZero", nonZeroSamples)
            editor.putString("captureState", captureState)
            editor.putString("captureMode", captureMode)
            editor.putString("serverStatus", serverStatus)
            editor.putBoolean("deepgramConnected", deepgramConnected)
            editor.putString("sourceText", sourceText)
            editor.putString("translatedText", translatedText)
            editor.putString("serverError", serverError)
            editor.putInt("serverAudioQueue", serverAudioQueue)
            editor.putBoolean("androidCaptureSilenced", androidCaptureSilenced)
            editor.putLong("silentStreak", silentStreak)
            editor.putInt("queue", inputQueue.size)
            editor.putString("error", lastError ?: "")
            editor.apply()
        } catch (_: Exception) {
        }
    }

    private fun liberarMediaPlayer() {
        synchronized(mediaPlayerLock) {
            liberarMediaPlayerInterno()
        }
    }

    private fun liberarMediaPlayerInterno() {
        val player = mediaPlayer
        mediaPlayer = null

        try { player?.stop() } catch (_: Exception) { }
        try { player?.reset() } catch (_: Exception) { }
        try { player?.release() } catch (_: Exception) { }

        val file = playbackFile
        playbackFile = null
        try { file?.delete() } catch (_: Exception) { }
    }

    private fun pararServico(enviarStop: Boolean) {
        if (stopping) return

        stopping = true
        running = false

        Log.d(TAG, "================================")
        Log.d(TAG, "PARANDO SI")
        Log.d(TAG, "capturados=$capturedChunks")
        Log.d(TAG, "enviados=$sentChunks")
        Log.d(TAG, "silenciosos=$silentChunks")
        Log.d(TAG, "RMS=$lastRms")
        Log.d(TAG, "tocados=$playedAudio")
        Log.d(TAG, "erro=$lastError")
        Log.d(TAG, "================================")

        captureThread?.interrupt()
        sendThread?.interrupt()
        statusThread?.interrupt()
        playbackThread?.interrupt()

        try { audioRecord?.stop() } catch (_: Exception) { }
        try { audioRecord?.release() } catch (_: Exception) { }
        audioRecord = null

        liberarMediaPlayer()

        try {
            projectionCallback?.let {
                mediaProjection?.unregisterCallback(it)
            }
        } catch (_: Exception) {
        }

        projectionCallback = null

        try { mediaProjection?.stop() } catch (_: Exception) { }
        mediaProjection = null

        inputQueue.clear()
        outputQueue.clear()

        if (enviarStop && jobId.isNotEmpty()) {
            val stopId = jobId
            Thread {
                enviarStopAoServidor(stopId)
            }.start()
        }

        publicarDiagnostico("⏹ Monitoramento parado")

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        } catch (_: Exception) {
        }

        stopSelf()
    }

    private fun enviarStopAoServidor(stopJobId: String) {
        var connection: HttpURLConnection? = null

        try {
            connection = URL(
                "$BACKEND_URL/api/audio/stop/$stopJobId"
            ).openConnection() as HttpURLConnection

            connection.requestMethod = "POST"
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.doOutput = true
            connection.setRequestProperty(
                "Content-Type",
                "application/json"
            )

            connection.outputStream.use {
                it.write("{}".toByteArray(Charsets.UTF_8))
            }

            Log.d(
                TAG,
                "STOP Render HTTP=${connection.responseCode}"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Erro STOP", e)
        } finally {
            connection?.disconnect()
        }
    }

    override fun onDestroy() {
        if (!stopping) {
            pararServico(false)
        }
        super.onDestroy()
    }
}
