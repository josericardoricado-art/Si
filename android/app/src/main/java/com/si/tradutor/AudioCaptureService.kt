package com.si.tradutor

import android.Manifest
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
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.speech.tts.TextToSpeech
import android.util.Base64
import android.util.Log
import androidx.core.content.IntentCompat
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import kotlin.concurrent.thread

class AudioCaptureService : Service() {

    companion object {
        private const val TAG = "SI_AUDIO"
        private const val BACKEND_URL = "https://si-u2ul.onrender.com"
        private const val CHANNEL_ID = "si_audio_capture"
        private const val NOTIFICATION_ID = 1001

        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val CHUNK_SIZE = 3200

        const val ACTION_START = "com.si.tradutor.START_AUDIO"
        const val ACTION_STOP = "com.si.tradutor.STOP_AUDIO"

        const val EXTRA_JOB_ID = "jobId"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"
    }

    private var mediaProjection: MediaProjection? = null
    private var audioRecord: AudioRecord? = null
    private var textToSpeech: TextToSpeech? = null

    @Volatile private var running = false
    @Volatile private var captureStarted = false
    @Volatile private var jobId = ""
    @Volatile private var ttsReady = false
    @Volatile private var targetLanguage = "pt-BR"
    @Volatile private var diagnosticError: String? = null
    @Volatile private var readCount = 0
    @Volatile private var lastRead = 0
    @Volatile private var capturedBytes = 0L

    private val audioQueue = LinkedBlockingQueue<ByteArray>(40)

    private var captureThread: Thread? = null
    private var sendThread: Thread? = null
    private var outputThread: Thread? = null
    private var diagnosticThread: Thread? = null
    private var projectionCallback: MediaProjection.Callback? = null

    private var lastSpokenText = ""

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        initializeTextToSpeech()
        Log.d(TAG, "SI Tradutor Live - serviço criado. SDK=${Build.VERSION.SDK_INT}")
    }

    private fun initializeTextToSpeech() {
        textToSpeech = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                ttsReady = true
                configureTtsLanguage(targetLanguage)
                Log.d(TAG, "Android TTS inicializado")
            } else {
                ttsReady = false
                diagnosticError = "TextToSpeech não inicializou: status=$status"
                Log.e(TAG, diagnosticError!!)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand action=${intent?.action}")

        if (intent?.action == ACTION_STOP) {
            stopCapture(sendServerStop = true)
            return START_NOT_STICKY
        }

        jobId = intent?.getStringExtra(EXTRA_JOB_ID) ?: ""
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, -1) ?: -1

        val resultData: Intent? = if (intent != null) {
            try {
                IntentCompat.getParcelableExtra(intent, EXTRA_RESULT_DATA, Intent::class.java)
            } catch (e: Exception) {
                Log.e(TAG, "Erro lendo resultData", e)
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(EXTRA_RESULT_DATA)
            }
        } else null

        Log.d(TAG, "jobId=$jobId resultCode=$resultCode resultDataExiste=${resultData != null}")

        try {
            startForegroundCompat()
        } catch (e: Exception) {
            failCapture("Erro foreground: ${e.javaClass.simpleName}: ${e.message}")
            return START_NOT_STICKY
        }

        if (jobId.isBlank()) {
            failCapture("jobId vazio")
            return START_NOT_STICKY
        }

        if (resultCode != android.app.Activity.RESULT_OK) {
            failCapture("MediaProjection inválido: resultCode=$resultCode")
            return START_NOT_STICKY
        }

        if (resultData == null) {
            failCapture("MediaProjection inválido: resultData=null")
            return START_NOT_STICKY
        }

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            failCapture("RECORD_AUDIO não autorizado")
            return START_NOT_STICKY
        }

        if (running) {
            Log.d(TAG, "Captura já está rodando")
            return START_STICKY
        }

        startCapture(resultCode, resultData)
        return START_STICKY
    }

    private fun startCapture(resultCode: Int, resultData: Intent) {
        try {
            diagnosticError = null
            readCount = 0
            lastRead = 0
            capturedBytes = 0L
            captureStarted = false
            lastSpokenText = ""
            audioQueue.clear()
            running = true

            // Busca a língua escolhida pelo usuário diretamente na sessão do Render.
            loadTargetLanguage()

            val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val projection = projectionManager.getMediaProjection(resultCode, resultData)
                ?: run {
                    failCapture("MediaProjection retornou null")
                    return
                }

            mediaProjection = projection

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                projectionCallback = object : MediaProjection.Callback() {
                    override fun onStop() {
                        Log.w(TAG, "MediaProjection encerrada pelo Android")
                        diagnosticError = "MediaProjection encerrada pelo Android"
                        if (running) stopCapture(sendServerStop = false)
                    }
                }
                mediaProjection?.registerCallback(projectionCallback!!, null)
            }

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                failCapture("Android abaixo do 10 não suporta captura interna")
                return
            }

            val playbackConfig = AudioPlaybackCaptureConfiguration.Builder(mediaProjection!!)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()

            val audioFormat = AudioFormat.Builder()
                .setEncoding(AUDIO_FORMAT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(CHANNEL_CONFIG)
                .build()

            val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
            if (minBuffer <= 0) {
                failCapture("getMinBufferSize inválido: $minBuffer")
                return
            }

            val bufferSize = maxOf(minBuffer * 2, CHUNK_SIZE * 4)

            val record = AudioRecord.Builder()
                .setAudioFormat(audioFormat)
                .setBufferSizeInBytes(bufferSize)
                .setAudioPlaybackCaptureConfig(playbackConfig)
                .build()

            audioRecord = record

            if (record.state != AudioRecord.STATE_INITIALIZED) {
                failCapture("AudioRecord não foi inicializado")
                return
            }

            record.startRecording()

            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                failCapture("AudioRecord não entrou em RECORDING")
                return
            }

            captureStarted = true
            Log.d(TAG, "CAPTURA INTERNA INICIADA - PCM16 MONO 16000 Hz - alvo=$targetLanguage")

            sendDiagnostic()
            startCaptureThread()
            startSendThread()
            startOutputThread()
            startDiagnosticThread()

        } catch (e: SecurityException) {
            failCapture("SecurityException: ${e.message ?: "permissão negada"}")
        } catch (e: Exception) {
            failCapture("${e.javaClass.simpleName}: ${e.message ?: "erro desconhecido"}")
        }
    }

    private fun loadTargetLanguage() {
        val currentJob = jobId
        if (currentJob.isBlank()) return

        thread(name = "SI-LoadLanguage") {
            var connection: HttpURLConnection? = null
            try {
                val url = URL("$BACKEND_URL/api/audio/status/$currentJob")
                connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 5000
                connection.readTimeout = 8000
                connection.setRequestProperty("Connection", "close")

                if (connection.responseCode == 200) {
                    val response = connection.inputStream.bufferedReader().use { it.readText() }
                    val language = extractJsonString(response, "targetLang")
                    if (!language.isNullOrBlank()) {
                        targetLanguage = language
                        if (ttsReady) configureTtsLanguage(language)
                        Log.d(TAG, "Idioma alvo recebido do Render: $language")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Não foi possível ler targetLang do Render", e)
            } finally {
                connection?.disconnect()
            }
        }
    }

    private fun configureTtsLanguage(languageTag: String) {
        if (!ttsReady) return
        try {
            val locale = Locale.forLanguageTag(languageTag)
            var result = textToSpeech?.setLanguage(locale) ?: TextToSpeech.LANG_NOT_SUPPORTED
            if (result == TextToSpeech.LANG_NOT_SUPPORTED || result == TextToSpeech.LANG_MISSING_DATA) {
                result = textToSpeech?.setLanguage(Locale.forLanguageTag(locale.language)) ?: TextToSpeech.LANG_NOT_SUPPORTED
            }
            Log.d(TAG, "TTS idioma=$languageTag result=$result")
        } catch (e: Exception) {
            Log.e(TAG, "Erro configurando idioma TTS=$languageTag", e)
        }
    }

    private fun startCaptureThread() {
        captureThread = thread(name = "SI-AudioCapture") {
            val buffer = ByteArray(CHUNK_SIZE)
            while (running) {
                try {
                    val record = audioRecord ?: break
                    val read = record.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                    readCount++
                    lastRead = read

                    if (read > 0) {
                        val chunk = buffer.copyOf(read)
                        capturedBytes += read.toLong()
                        if (!audioQueue.offer(chunk)) {
                            audioQueue.poll()
                            audioQueue.offer(chunk)
                        }
                        if (readCount == 1 || readCount % 20 == 0) {
                            Log.d(TAG, "Áudio capturado: read=$read bytes=$capturedBytes queue=${audioQueue.size}")
                        }
                    } else if (read == AudioRecord.ERROR_INVALID_OPERATION) {
                        diagnosticError = "ERROR_INVALID_OPERATION"
                        break
                    } else if (read == AudioRecord.ERROR_BAD_VALUE) {
                        diagnosticError = "ERROR_BAD_VALUE"
                        break
                    } else if (read == AudioRecord.ERROR_DEAD_OBJECT) {
                        diagnosticError = "ERROR_DEAD_OBJECT"
                        break
                    }
                } catch (_: InterruptedException) {
                    break
                } catch (e: Exception) {
                    diagnosticError = "${e.javaClass.simpleName}: ${e.message ?: ""}"
                    Log.e(TAG, "Erro no AudioRecord", e)
                    break
                }
            }
            Log.d(TAG, "Thread de captura terminou")
        }
    }

    private fun startSendThread() {
        sendThread = thread(name = "SI-AudioSend") {
            while (running) {
                try {
                    val chunk = audioQueue.take()
                    sendAudioChunk(Base64.encodeToString(chunk, Base64.NO_WRAP))
                } catch (_: InterruptedException) {
                    break
                } catch (e: Exception) {
                    Log.e(TAG, "Erro enviando áudio", e)
                }
            }
        }
    }

    private fun sendAudioChunk(base64: String) {
        if (jobId.isBlank()) return
        var connection: HttpURLConnection? = null
        try {
            connection = URL("$BACKEND_URL/api/audio/chunk").openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = 8000
            connection.readTimeout = 10000
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Connection", "close")

            val json = "{\"jobId\":\"${escapeJson(jobId)}\",\"audio\":\"${escapeJson(base64)}\"}"
            connection.outputStream.use { it.write(json.toByteArray(Charsets.UTF_8)) }

            val code = connection.responseCode
            if (code !in 200..299) Log.e(TAG, "Render respondeu HTTP $code ao enviar chunk")
        } catch (e: Exception) {
            Log.e(TAG, "Falha ao enviar chunk", e)
        } finally {
            connection?.disconnect()
        }
    }

    private fun startOutputThread() {
        outputThread = thread(name = "SI-AudioTextOutput") {
            while (running) {
                try {
                    Thread.sleep(180)
                    val translated = getTranslatedText()
                    if (!translated.isNullOrBlank()) speakTranslatedText(translated)
                } catch (_: InterruptedException) {
                    break
                } catch (e: Exception) {
                    Log.e(TAG, "Erro buscando texto traduzido", e)
                }
            }
        }
    }

    private fun getTranslatedText(): String? {
        if (jobId.isBlank()) return null
        var connection: HttpURLConnection? = null
        return try {
            connection = URL("$BACKEND_URL/api/audio/text/$jobId").openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 5000
            connection.readTimeout = 8000
            connection.setRequestProperty("Connection", "close")

            if (connection.responseCode != 200) return null
            val response = BufferedReader(InputStreamReader(connection.inputStream)).use { it.readText() }
            val language = extractJsonString(response, "targetLang")
            if (!language.isNullOrBlank() && language != targetLanguage) {
                targetLanguage = language
                configureTtsLanguage(language)
            }
            extractJsonString(response, "text")
        } catch (e: Exception) {
            Log.e(TAG, "Erro recebendo texto traduzido", e)
            null
        } finally {
            connection?.disconnect()
        }
    }

    private fun speakTranslatedText(text: String) {
        val clean = text.trim()
        if (clean.isBlank() || !ttsReady) return
        if (clean == lastSpokenText) return

        try {
            configureTtsLanguage(targetLanguage)
            val params = android.os.Bundle()
            textToSpeech?.speak(clean, TextToSpeech.QUEUE_ADD, params, "si-${System.nanoTime()}")
            lastSpokenText = clean
            Log.d(TAG, "TTS reproduzindo: $clean")
        } catch (e: Exception) {
            Log.e(TAG, "Erro no TTS", e)
        }
    }

    private fun startDiagnosticThread() {
        diagnosticThread = thread(name = "SI-AudioDiagnostic") {
            while (running) {
                try {
                    Thread.sleep(3000)
                    sendDiagnostic()
                } catch (_: InterruptedException) {
                    break
                }
            }
        }
    }

    private fun sendDiagnostic() {
        if (jobId.isBlank()) return
        thread(name = "SI-DiagnosticRequest") {
            var connection: HttpURLConnection? = null
            try {
                connection = URL("$BACKEND_URL/api/audio/diagnostic").openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.connectTimeout = 5000
                connection.readTimeout = 8000
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setRequestProperty("Connection", "close")

                val recording = audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING
                val json = """
                    {"jobId":"${escapeJson(jobId)}","recording":$recording,"captureStarted":$captureStarted,"readCount":$readCount,"lastRead":$lastRead,"capturedBytes":$capturedBytes,"queueSize":${audioQueue.size},"targetLanguage":"${escapeJson(targetLanguage)}","ttsReady":$ttsReady,"error":${jsonStringOrNull(diagnosticError)}}
                """.trimIndent()

                connection.outputStream.use { it.write(json.toByteArray(Charsets.UTF_8)) }
                Log.d(TAG, "Diagnóstico HTTP ${connection.responseCode} reads=$readCount bytes=$capturedBytes tts=$ttsReady")
            } catch (e: Exception) {
                Log.e(TAG, "Falha no diagnóstico", e)
            } finally {
                connection?.disconnect()
            }
        }
    }

    private fun stopCapture(sendServerStop: Boolean) {
        if (!running && !captureStarted) {
            releaseAudioResources()
            return
        }

        running = false
        captureThread?.interrupt()
        sendThread?.interrupt()
        outputThread?.interrupt()
        diagnosticThread?.interrupt()
        captureThread = null
        sendThread = null
        outputThread = null
        diagnosticThread = null

        try { audioRecord?.stop() } catch (_: Exception) {}
        try { audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null

        if (sendServerStop && jobId.isNotBlank()) sendStopToServer()

        unregisterAndStopProjection()
        audioQueue.clear()
        captureStarted = false

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE)
            else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        } catch (_: Exception) {}

        Log.d(TAG, "Captura encerrada")
        stopSelf()
    }

    private fun releaseAudioResources() {
        try { audioRecord?.stop() } catch (_: Exception) {}
        try { audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null
        unregisterAndStopProjection()
        audioQueue.clear()
        captureStarted = false
    }

    private fun unregisterAndStopProjection() {
        try {
            projectionCallback?.let { mediaProjection?.unregisterCallback(it) }
        } catch (_: Exception) {}
        projectionCallback = null
        try { mediaProjection?.stop() } catch (_: Exception) {}
        mediaProjection = null
    }

    private fun sendStopToServer() {
        val currentJob = jobId
        if (currentJob.isBlank()) return
        thread(name = "SI-AudioStop") {
            var connection: HttpURLConnection? = null
            try {
                connection = URL("$BACKEND_URL/api/audio/stop").openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.connectTimeout = 5000
                connection.readTimeout = 8000
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setRequestProperty("Connection", "close")
                val json = "{\"jobId\":\"${escapeJson(currentJob)}\"}"
                connection.outputStream.use { it.write(json.toByteArray(Charsets.UTF_8)) }
                Log.d(TAG, "Stop enviado HTTP ${connection.responseCode}")
            } catch (e: Exception) {
                Log.e(TAG, "Erro enviando stop", e)
            } finally {
                connection?.disconnect()
            }
        }
    }

    private fun failCapture(message: String) {
        diagnosticError = message
        Log.e(TAG, "FALHA NA CAPTURA: $message")
        running = false
        captureStarted = false
        sendDiagnostic()
        releaseAudioResources()
        stopSelf()
    }

    private fun startForegroundCompat() {
        val notification = createNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "SI Tradutor Live", NotificationManager.IMPORTANCE_LOW)
            channel.description = "Captura de áudio para tradução em tempo real"
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("SI Tradutor Live")
                .setContentText("Traduzindo áudio da tela em tempo real...")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("SI Tradutor Live")
                .setContentText("Traduzindo áudio da tela em tempo real...")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setOngoing(true)
                .build()
        }
    }

    private fun escapeJson(value: String): String {
        return value.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
    }

    private fun extractJsonString(json: String, key: String): String? {
        return try {
            val pattern = "\"$key\"\\s*:\\s*\"((?:\\\\.|[^\"])*)\"".toRegex()
            pattern.find(json)?.groupValues?.getOrNull(1)?.replace("\\\"", "\"")?.replace("\\n", "\n")?.replace("\\r", "\r")
        } catch (_: Exception) {
            null
        }
    }

    private fun jsonStringOrNull(value: String?): String {
        return if (value.isNullOrBlank()) "null" else "\"${escapeJson(value)}\""
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.d(TAG, "AudioCaptureService destruído")
        if (running || captureStarted || audioRecord != null || mediaProjection != null) {
            stopCapture(sendServerStop = false)
        } else {
            releaseAudioResources()
        }

        try {
            textToSpeech?.stop()
            textToSpeech?.shutdown()
        } catch (_: Exception) {}
        textToSpeech = null
        ttsReady = false
        super.onDestroy()
    }
}
