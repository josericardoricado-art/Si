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
import android.media.AudioTrack
import android.media.AudioManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.Base64
import android.util.Log
import androidx.core.content.IntentCompat
import org.json.JSONObject
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

        // Áudio capturado do aplicativo.
        private const val INPUT_SAMPLE_RATE = 16000

        // Áudio retornado pelo Gemini Live.
        private const val OUTPUT_SAMPLE_RATE = 24000

        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val OUTPUT_CHANNEL_CONFIG = AudioFormat.CHANNEL_OUT_MONO

        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

        private const val CHUNK_SIZE = 3200

        const val ACTION_START = "com.si.tradutor.START_AUDIO"
        const val ACTION_STOP = "com.si.tradutor.STOP_AUDIO"

        const val EXTRA_JOB_ID = "jobId"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"
    }

    // ============================================================
    // MEDIA PROJECTION / CAPTURA
    // ============================================================

    private var mediaProjection: MediaProjection? = null
    private var audioRecord: AudioRecord? = null

    // ============================================================
    // AUDIO OUTPUT
    // ============================================================

    private var audioTrack: AudioTrack? = null

    // ============================================================
    // ESTADO
    // ============================================================

    @Volatile
    private var running = false

    @Volatile
    private var captureStarted = false

    @Volatile
    private var jobId = ""

    @Volatile
    private var targetLanguage = "pt-BR"

    @Volatile
    private var diagnosticError: String? = null

    @Volatile
    private var readCount = 0

    @Volatile
    private var lastRead = 0

    @Volatile
    private var capturedBytes = 0L

    @Volatile
    private var receivedOutputBytes = 0L

    @Volatile
    private var playedOutputBytes = 0L

    @Volatile
    private var outputChunksReceived = 0

    @Volatile
    private var outputChunksPlayed = 0

    // ============================================================
    // FILA DE CAPTURA
    // ============================================================

    private val audioQueue = LinkedBlockingQueue<ByteArray>(40)

    // ============================================================
    // THREADS
    // ============================================================

    private var captureThread: Thread? = null
    private var sendThread: Thread? = null
    private var outputThread: Thread? = null
    private var diagnosticThread: Thread? = null

    private var projectionCallback: MediaProjection.Callback? = null

    // ============================================================
    // ANDROID SERVICE
    // ============================================================

    override fun onCreate() {
        super.onCreate()

        createNotificationChannel()

        Log.d(
            TAG,
            "SI Tradutor Live - serviço criado. SDK=${Build.VERSION.SDK_INT}"
        )
    }

    // ============================================================
    // START / STOP
    // ============================================================

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        Log.d(
            TAG,
            "onStartCommand action=${intent?.action}"
        )

        // --------------------------------------------------------
        // PARAR
        // --------------------------------------------------------

        if (intent?.action == ACTION_STOP) {
            stopCapture(sendServerStop = true)
            return START_NOT_STICKY
        }

        // --------------------------------------------------------
        // DADOS DO JOB
        // --------------------------------------------------------

        jobId = intent?.getStringExtra(EXTRA_JOB_ID) ?: ""

        val resultCode =
            intent?.getIntExtra(
                EXTRA_RESULT_CODE,
                -1
            ) ?: -1

        val resultData: Intent? =
            if (intent != null) {
                try {

                    IntentCompat.getParcelableExtra(
                        intent,
                        EXTRA_RESULT_DATA,
                        Intent::class.java
                    )

                } catch (e: Exception) {

                    Log.e(
                        TAG,
                        "Erro lendo resultData",
                        e
                    )

                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(
                        EXTRA_RESULT_DATA
                    )
                }
            } else {
                null
            }

        Log.d(
            TAG,
            "jobId=$jobId " +
                    "resultCode=$resultCode " +
                    "resultDataExiste=${resultData != null}"
        )

        // --------------------------------------------------------
        // FOREGROUND
        // --------------------------------------------------------

        try {

            startForegroundCompat()

        } catch (e: Exception) {

            failCapture(
                "Erro foreground: " +
                        "${e.javaClass.simpleName}: ${e.message}"
            )

            return START_NOT_STICKY
        }

        // --------------------------------------------------------
        // VALIDAÇÕES
        // --------------------------------------------------------

        if (jobId.isBlank()) {

            failCapture("jobId vazio")

            return START_NOT_STICKY
        }

        if (resultCode != android.app.Activity.RESULT_OK) {

            failCapture(
                "MediaProjection inválido: " +
                        "resultCode=$resultCode"
            )

            return START_NOT_STICKY
        }

        if (resultData == null) {

            failCapture(
                "MediaProjection inválido: resultData=null"
            )

            return START_NOT_STICKY
        }

        if (
            checkSelfPermission(
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {

            failCapture(
                "RECORD_AUDIO não autorizado"
            )

            return START_NOT_STICKY
        }

        if (running) {

            Log.d(
                TAG,
                "Captura já está rodando"
            )

            return START_STICKY
        }

        // --------------------------------------------------------
        // INICIAR
        // --------------------------------------------------------

        startCapture(
            resultCode,
            resultData
        )

        return START_STICKY
    }

    // ============================================================
    // INICIAR CAPTURA
    // ============================================================

    private fun startCapture(
        resultCode: Int,
        resultData: Intent
    ) {

        try {

            diagnosticError = null

            readCount = 0
            lastRead = 0
            capturedBytes = 0L

            receivedOutputBytes = 0L
            playedOutputBytes = 0L

            outputChunksReceived = 0
            outputChunksPlayed = 0

            captureStarted = false

            audioQueue.clear()

            running = true

            // ----------------------------------------------------
            // BUSCAR IDIOMA DA SESSÃO
            // ----------------------------------------------------

            loadTargetLanguage()

            // ----------------------------------------------------
            // MEDIA PROJECTION
            // ----------------------------------------------------

            val projectionManager =
                getSystemService(
                    Context.MEDIA_PROJECTION_SERVICE
                ) as MediaProjectionManager

            val projection =
                projectionManager.getMediaProjection(
                    resultCode,
                    resultData
                ) ?: run {

                    failCapture(
                        "MediaProjection retornou null"
                    )

                    return
                }

            mediaProjection = projection

            // ----------------------------------------------------
            // CALLBACK
            // ----------------------------------------------------

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {

                projectionCallback =
                    object : MediaProjection.Callback() {

                        override fun onStop() {

                            Log.w(
                                TAG,
                                "MediaProjection encerrada pelo Android"
                            )

                            diagnosticError =
                                "MediaProjection encerrada pelo Android"

                            if (running) {

                                stopCapture(
                                    sendServerStop = false
                                )
                            }
                        }
                    }

                mediaProjection?.registerCallback(
                    projectionCallback!!,
                    null
                )
            }

            // ----------------------------------------------------
            // ANDROID 10+
            // ----------------------------------------------------

            if (
                Build.VERSION.SDK_INT <
                Build.VERSION_CODES.Q
            ) {

                failCapture(
                    "Android abaixo do 10 " +
                            "não suporta captura interna"
                )

                return
            }

            // ----------------------------------------------------
            // CONFIGURAÇÃO DA CAPTURA INTERNA
            // ----------------------------------------------------

            val playbackConfig =
                AudioPlaybackCaptureConfiguration.Builder(
                    mediaProjection!!
                )
                    .addMatchingUsage(
                        AudioAttributes.USAGE_MEDIA
                    )
                    .addMatchingUsage(
                        AudioAttributes.USAGE_GAME
                    )
                    .addMatchingUsage(
                        AudioAttributes.USAGE_UNKNOWN
                    )
                    .build()

            // ----------------------------------------------------
            // FORMATO DE ENTRADA
            // ----------------------------------------------------

            val audioFormat =
                AudioFormat.Builder()
                    .setEncoding(
                        AUDIO_FORMAT
                    )
                    .setSampleRate(
                        INPUT_SAMPLE_RATE
                    )
                    .setChannelMask(
                        CHANNEL_CONFIG
                    )
                    .build()

            val minBuffer =
                AudioRecord.getMinBufferSize(
                    INPUT_SAMPLE_RATE,
                    CHANNEL_CONFIG,
                    AUDIO_FORMAT
                )

            if (minBuffer <= 0) {

                failCapture(
                    "getMinBufferSize inválido: $minBuffer"
                )

                return
            }

            val bufferSize =
                maxOf(
                    minBuffer * 2,
                    CHUNK_SIZE * 4
                )

            // ----------------------------------------------------
            // AUDIO RECORD
            // ----------------------------------------------------

            val record =
                AudioRecord.Builder()
                    .setAudioFormat(
                        audioFormat
                    )
                    .setBufferSizeInBytes(
                        bufferSize
                    )
                    .setAudioPlaybackCaptureConfig(
                        playbackConfig
                    )
                    .build()

            audioRecord = record

            if (
                record.state !=
                AudioRecord.STATE_INITIALIZED
            ) {

                failCapture(
                    "AudioRecord não foi inicializado"
                )

                return
            }

            // ----------------------------------------------------
            // INICIAR RECORDING
            // ----------------------------------------------------

            record.startRecording()

            if (
                record.recordingState !=
                AudioRecord.RECORDSTATE_RECORDING
            ) {

                failCapture(
                    "AudioRecord não entrou em RECORDING"
                )

                return
            }

            captureStarted = true

            Log.d(
                TAG,
                "CAPTURA INTERNA INICIADA - " +
                        "PCM16 MONO 16000 Hz - " +
                        "alvo=$targetLanguage"
            )

            // ----------------------------------------------------
            // INICIAR AUDIO OUTPUT
            // ----------------------------------------------------

            initializeAudioTrack()

            // ----------------------------------------------------
            // DIAGNÓSTICO
            // ----------------------------------------------------

            sendDiagnostic()

            // ----------------------------------------------------
            // THREADS
            // ----------------------------------------------------

            startCaptureThread()

            startSendThread()

            startOutputThread()

            startDiagnosticThread()

        } catch (e: SecurityException) {

            failCapture(
                "SecurityException: " +
                        (e.message ?: "permissão negada")
            )

        } catch (e: Exception) {

            failCapture(
                "${e.javaClass.simpleName}: " +
                        (e.message ?: "erro desconhecido")
            )
        }
    }

    // ============================================================
    // AUDIO TRACK
    // ============================================================

    private fun initializeAudioTrack() {

        try {

            val minBuffer =
                AudioTrack.getMinBufferSize(
                    OUTPUT_SAMPLE_RATE,
                    OUTPUT_CHANNEL_CONFIG,
                    AUDIO_FORMAT
                )

            if (minBuffer <= 0) {

                diagnosticError =
                    "AudioTrack buffer inválido: $minBuffer"

                Log.e(
                    TAG,
                    diagnosticError!!
                )

                return
            }

            val bufferSize =
                maxOf(
                    minBuffer * 4,
                    OUTPUT_SAMPLE_RATE * 2
                )

            val attributes =
                AudioAttributes.Builder()
                    .setUsage(
                        AudioAttributes.USAGE_MEDIA
                    )
                    .setContentType(
                        AudioAttributes.CONTENT_TYPE_SPEECH
                    )
                    .build()

            val format =
                AudioFormat.Builder()
                    .setEncoding(
                        AUDIO_FORMAT
                    )
                    .setSampleRate(
                        OUTPUT_SAMPLE_RATE
                    )
                    .setChannelMask(
                        OUTPUT_CHANNEL_CONFIG
                    )
                    .build()

            val track =
                AudioTrack.Builder()
                    .setAudioAttributes(
                        attributes
                    )
                    .setAudioFormat(
                        format
                    )
                    .setBufferSizeInBytes(
                        bufferSize
                    )
                    .setTransferMode(
                        AudioTrack.MODE_STREAM
                    )
                    .build()

            if (
                track.state !=
                AudioTrack.STATE_INITIALIZED
            ) {

                diagnosticError =
                    "AudioTrack não foi inicializado"

                Log.e(
                    TAG,
                    diagnosticError!!
                )

                try {
                    track.release()
                } catch (_: Exception) {
                }

                return
            }

            audioTrack = track

            track.play()

            Log.d(
                TAG,
                "AudioTrack iniciado: " +
                        "PCM16 MONO ${OUTPUT_SAMPLE_RATE} Hz"
            )

        } catch (e: Exception) {

            diagnosticError =
                "Erro AudioTrack: " +
                        "${e.javaClass.simpleName}: " +
                        "${e.message}"

            Log.e(
                TAG,
                diagnosticError!!,
                e
            )
        }
    }

    // ============================================================
    // IDIOMA
    // ============================================================

    private fun loadTargetLanguage() {

        val currentJob = jobId

        if (currentJob.isBlank()) {
            return
        }

        thread(
            name = "SI-LoadLanguage"
        ) {

            var connection:
                    HttpURLConnection? = null

            try {

                val url =
                    URL(
                        "$BACKEND_URL/api/audio/status/$currentJob"
                    )

                connection =
                    url.openConnection()
                            as HttpURLConnection

                connection.requestMethod = "GET"

                connection.connectTimeout = 5000

                connection.readTimeout = 8000

                connection.setRequestProperty(
                    "Connection",
                    "close"
                )

                if (
                    connection.responseCode == 200
                ) {

                    val response =
                        connection.inputStream
                            .bufferedReader()
                            .use {
                                it.readText()
                            }

                    val language =
                        extractJsonString(
                            response,
                            "targetLang"
                        )

                    if (
                        !language.isNullOrBlank()
                    ) {

                        targetLanguage = language

                        Log.d(
                            TAG,
                            "Idioma alvo recebido do Render: " +
                                    language
                        )
                    }
                }

            } catch (e: Exception) {

                Log.w(
                    TAG,
                    "Não foi possível ler targetLang do Render",
                    e
                )

            } finally {

                connection?.disconnect()
            }
        }
    }

    // ============================================================
    // THREAD DE CAPTURA
    // ============================================================

    private fun startCaptureThread() {

        captureThread =
            thread(
                name = "SI-AudioCapture"
            ) {

                val buffer =
                    ByteArray(CHUNK_SIZE)

                while (running) {

                    try {

                        val record =
                            audioRecord ?: break

                        val read =
                            record.read(
                                buffer,
                                0,
                                buffer.size,
                                AudioRecord.READ_BLOCKING
                            )

                        readCount++

                        lastRead = read

                        if (read > 0) {

                            val chunk =
                                buffer.copyOf(read)

                            capturedBytes +=
                                read.toLong()

                            if (
                                !audioQueue.offer(chunk)
                            ) {

                                audioQueue.poll()

                                audioQueue.offer(chunk)
                            }

                            if (
                                readCount == 1 ||
                                readCount % 20 == 0
                            ) {

                                Log.d(
                                    TAG,
                                    "Áudio capturado: " +
                                            "read=$read " +
                                            "bytes=$capturedBytes " +
                                            "queue=${audioQueue.size}"
                                )
                            }

                        } else if (
                            read ==
                            AudioRecord.ERROR_INVALID_OPERATION
                        ) {

                            diagnosticError =
                                "ERROR_INVALID_OPERATION"

                            break

                        } else if (
                            read ==
                            AudioRecord.ERROR_BAD_VALUE
                        ) {

                            diagnosticError =
                                "ERROR_BAD_VALUE"

                            break

                        } else if (
                            read ==
                            AudioRecord.ERROR_DEAD_OBJECT
                        ) {

                            diagnosticError =
                                "ERROR_DEAD_OBJECT"

                            break
                        }

                    } catch (
                        _: InterruptedException
                    ) {

                        break

                    } catch (e: Exception) {

                        diagnosticError =
                            "${e.javaClass.simpleName}: " +
                                    "${e.message ?: ""}"

                        Log.e(
                            TAG,
                            "Erro no AudioRecord",
                            e
                        )

                        break
                    }
                }

                Log.d(
                    TAG,
                    "Thread de captura terminou"
                )
            }
    }

    // ============================================================
    // THREAD DE ENVIO PARA RENDER
    // ============================================================

    private fun startSendThread() {

        sendThread =
            thread(
                name = "SI-AudioSend"
            ) {

                while (running) {

                    try {

                        val chunk =
                            audioQueue.take()

                        sendAudioChunk(
                            Base64.encodeToString(
                                chunk,
                                Base64.NO_WRAP
                            )
                        )

                    } catch (
                        _: InterruptedException
                    ) {

                        break

                    } catch (e: Exception) {

                        Log.e(
                            TAG,
                            "Erro enviando áudio",
                            e
                        )
                    }
                }
            }
    }

    // ============================================================
    // ENVIAR CHUNK
    // ============================================================

    private fun sendAudioChunk(
        base64: String
    ) {

        if (jobId.isBlank()) {
            return
        }

        var connection:
                HttpURLConnection? = null

        try {

            connection =
                URL(
                    "$BACKEND_URL/api/audio/chunk"
                )
                    .openConnection()
                        as HttpURLConnection

            connection.requestMethod = "POST"

            connection.doOutput = true

            connection.connectTimeout = 8000

            connection.readTimeout = 10000

            connection.setRequestProperty(
                "Content-Type",
                "application/json"
            )

            connection.setRequestProperty(
                "Connection",
                "close"
            )

            val json =
                JSONObject().apply {

                    put(
                        "jobId",
                        jobId
                    )

                    put(
                        "audio",
                        base64
                    )
                }.toString()

            connection.outputStream.use {

                it.write(
                    json.toByteArray(
                        Charsets.UTF_8
                    )
                )
            }

            val code =
                connection.responseCode

            if (code !in 200..299) {

                Log.e(
                    TAG,
                    "Render respondeu HTTP " +
                            "$code ao enviar chunk"
                )
            }

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Falha ao enviar chunk",
                e
            )

        } finally {

            connection?.disconnect()
        }
    }

    // ============================================================
    // THREAD DE SAÍDA GEMINI
    // ============================================================

    private fun startOutputThread() {

        outputThread =
            thread(
                name = "SI-AudioGeminiOutput"
            ) {

                while (running) {

                    try {

                        // Pequeno intervalo para não sobrecarregar
                        // o Render com requisições.
                        Thread.sleep(100)

                        fetchAndPlayGeminiAudio()

                    } catch (
                        _: InterruptedException
                    ) {

                        break

                    } catch (e: Exception) {

                        Log.e(
                            TAG,
                            "Erro buscando áudio do Gemini",
                            e
                        )
                    }
                }
            }
    }

    // ============================================================
    // BUSCAR ÁUDIO DO GEMINI NO RENDER
    // ============================================================

    private fun fetchAndPlayGeminiAudio() {

        if (jobId.isBlank()) {
            return
        }

        var connection:
                HttpURLConnection? = null

        try {

            val url =
                URL(
                    "$BACKEND_URL/api/audio/output/$jobId"
                )

            connection =
                url.openConnection()
                        as HttpURLConnection

            connection.requestMethod = "GET"

            connection.connectTimeout = 5000

            connection.readTimeout = 8000

            connection.setRequestProperty(
                "Connection",
                "close"
            )

            connection.setRequestProperty(
                "Accept",
                "application/json"
            )

            val code =
                connection.responseCode

            if (code != 200) {

                if (code != 204) {

                    Log.w(
                        TAG,
                        "Output Render HTTP $code"
                    )
                }

                return
            }

            val response =
                BufferedReader(
                    InputStreamReader(
                        connection.inputStream,
                        Charsets.UTF_8
                    )
                ).use {
                    it.readText()
                }

            if (response.isBlank()) {
                return
            }

            val audioBase64 =
                extractAudioBase64(
                    response
                )

            if (
                audioBase64.isNullOrBlank()
            ) {
                return
            }

            val pcm =
                try {

                    Base64.decode(
                        audioBase64,
                        Base64.DEFAULT
                    )

                } catch (e: Exception) {

                    Log.e(
                        TAG,
                        "Base64 do áudio inválido",
                        e
                    )

                    return
                }

            if (pcm.isEmpty()) {
                return
            }

            receivedOutputBytes +=
                pcm.size.toLong()

            outputChunksReceived++

            Log.d(
                TAG,
                "Áudio Gemini recebido: " +
                        "${pcm.size} bytes " +
                        "chunks=$outputChunksReceived"
            )

            playGeminiAudio(
                pcm
            )

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Erro recebendo áudio Gemini",
                e
            )

        } finally {

            connection?.disconnect()
        }
    }

    // ============================================================
    // EXTRAIR BASE64 DE DIFERENTES FORMATOS JSON
    // ============================================================

    private fun extractAudioBase64(
        response: String
    ): String? {

        return try {

            val json =
                JSONObject(response)

            // Formato principal:
            //
            // {
            //   "ok": true,
            //   "audio": "BASE64..."
            // }

            if (
                json.has("audio") &&
                !json.isNull("audio")
            ) {

                return json.optString(
                    "audio",
                    null
                )
            }

            // Possíveis nomes alternativos.

            if (
                json.has("data") &&
                !json.isNull("data")
            ) {

                val data =
                    json.optString(
                        "data",
                        ""
                    )

                if (data.isNotBlank()) {
                    return data
                }
            }

            if (
                json.has("pcm") &&
                !json.isNull("pcm")
            ) {

                return json.optString(
                    "pcm",
                    null
                )
            }

            null

        } catch (e: Exception) {

            Log.e(
                TAG,
                "JSON de output inválido: $response",
                e
            )

            null
        }
    }

    // ============================================================
    // REPRODUZIR ÁUDIO GEMINI
    // ============================================================

    private fun playGeminiAudio(
        pcm: ByteArray
    ) {

        try {

            var track = audioTrack

            if (
                track == null ||
                track.state !=
                AudioTrack.STATE_INITIALIZED
            ) {

                initializeAudioTrack()

                track = audioTrack
            }

            if (track == null) {

                diagnosticError =
                    "AudioTrack indisponível"

                Log.e(
                    TAG,
                    diagnosticError!!
                )

                return
            }

            if (
                track.playState !=
                AudioTrack.PLAYSTATE_PLAYING
            ) {

                track.play()

                Log.d(
                    TAG,
                    "AudioTrack voltou para PLAYING"
                )
            }

            var offset = 0

            while (
                offset < pcm.size &&
                running
            ) {

                val written =
                    track.write(
                        pcm,
                        offset,
                        pcm.size - offset,
                        AudioTrack.WRITE_BLOCKING
                    )

                if (written > 0) {

                    offset += written

                    playedOutputBytes +=
                        written.toLong()

                } else {

                    Log.e(
                        TAG,
                        "AudioTrack.write retornou $written"
                    )

                    break
                }
            }

            if (offset > 0) {

                outputChunksPlayed++

                Log.d(
                    TAG,
                    "Áudio Gemini reproduzido: " +
                            "$offset bytes " +
                            "chunks=$outputChunksPlayed"
                )
            }

        } catch (e: Exception) {

            diagnosticError =
                "Erro reproduzindo Gemini: " +
                        "${e.javaClass.simpleName}: " +
                        "${e.message}"

            Log.e(
                TAG,
                diagnosticError!!,
                e
            )
        }
    }

    // ============================================================
    // DIAGNÓSTICO
    // ============================================================

    private fun startDiagnosticThread() {

        diagnosticThread =
            thread(
                name = "SI-AudioDiagnostic"
            ) {

                while (running) {

                    try {

                        Thread.sleep(3000)

                        sendDiagnostic()

                    } catch (
                        _: InterruptedException
                    ) {

                        break
                    }
                }
            }
    }

    // ============================================================
    // ENVIAR DIAGNÓSTICO
    // ============================================================

    private fun sendDiagnostic() {

        if (jobId.isBlank()) {
            return
        }

        thread(
            name = "SI-DiagnosticRequest"
        ) {

            var connection:
                    HttpURLConnection? = null

            try {

                connection =
                    URL(
                        "$BACKEND_URL/api/audio/diagnostic"
                    )
                        .openConnection()
                            as HttpURLConnection

                connection.requestMethod = "POST"

                connection.doOutput = true

                connection.connectTimeout = 5000

                connection.readTimeout = 8000

                connection.setRequestProperty(
                    "Content-Type",
                    "application/json"
                )

                connection.setRequestProperty(
                    "Connection",
                    "close"
                )

                val recording =
                    audioRecord?.recordingState ==
                            AudioRecord.RECORDSTATE_RECORDING

                val outputPlaying =
                    audioTrack?.playState ==
                            AudioTrack.PLAYSTATE_PLAYING

                val json =
                    JSONObject().apply {

                        put(
                            "jobId",
                            jobId
                        )

                        put(
                            "recording",
                            recording
                        )

                        put(
                            "captureStarted",
                            captureStarted
                        )

                        put(
                            "readCount",
                            readCount
                        )

                        put(
                            "lastRead",
                            lastRead
                        )

                        put(
                            "capturedBytes",
                            capturedBytes
                        )

                        put(
                            "queueSize",
                            audioQueue.size
                        )

                        put(
                            "targetLanguage",
                            targetLanguage
                        )

                        put(
                            "ttsReady",
                            outputPlaying
                        )

                        put(
                            "audioTrackReady",
                            audioTrack?.state ==
                                    AudioTrack.STATE_INITIALIZED
                        )

                        put(
                            "outputPlaying",
                            outputPlaying
                        )

                        put(
                            "receivedOutputBytes",
                            receivedOutputBytes
                        )

                        put(
                            "playedOutputBytes",
                            playedOutputBytes
                        )

                        put(
                            "outputChunksReceived",
                            outputChunksReceived
                        )

                        put(
                            "outputChunksPlayed",
                            outputChunksPlayed
                        )

                        if (
                            diagnosticError.isNullOrBlank()
                        ) {

                            put(
                                "error",
                                JSONObject.NULL
                            )

                        } else {

                            put(
                                "error",
                                diagnosticError
                            )
                        }
                    }.toString()

                connection.outputStream.use {

                    it.write(
                        json.toByteArray(
                            Charsets.UTF_8
                        )
                    )
                }

                Log.d(
                    TAG,
                    "Diagnóstico HTTP " +
                            "${connection.responseCode} " +
                            "reads=$readCount " +
                            "captured=$capturedBytes " +
                            "received=$receivedOutputBytes " +
                            "played=$playedOutputBytes"
                )

            } catch (e: Exception) {

                Log.e(
                    TAG,
                    "Falha no diagnóstico",
                    e
                )

            } finally {

                connection?.disconnect()
            }
        }
    }

    // ============================================================
    // PARAR CAPTURA
    // ============================================================

    private fun stopCapture(
        sendServerStop: Boolean
    ) {

        if (
            !running &&
            !captureStarted
        ) {

            releaseAudioResources()

            return
        }

        running = false

        // --------------------------------------------------------
        // PARAR THREADS
        // --------------------------------------------------------

        captureThread?.interrupt()

        sendThread?.interrupt()

        outputThread?.interrupt()

        diagnosticThread?.interrupt()

        captureThread = null

        sendThread = null

        outputThread = null

        diagnosticThread = null

        // --------------------------------------------------------
        // PARAR AUDIO RECORD
        // --------------------------------------------------------

        try {
            audioRecord?.stop()
        } catch (_: Exception) {
        }

        try {
            audioRecord?.release()
        } catch (_: Exception) {
        }

        audioRecord = null

        // --------------------------------------------------------
        // PARAR AUDIO TRACK
        // --------------------------------------------------------

        try {
            audioTrack?.pause()
        } catch (_: Exception) {
        }

        try {
            audioTrack?.flush()
        } catch (_: Exception) {
        }

        try {
            audioTrack?.stop()
        } catch (_: Exception) {
        }

        try {
            audioTrack?.release()
        } catch (_: Exception) {
        }

        audioTrack = null

        // --------------------------------------------------------
        // INFORMAR RENDER
        // --------------------------------------------------------

        if (
            sendServerStop &&
            jobId.isNotBlank()
        ) {

            sendStopToServer()
        }

        // --------------------------------------------------------
        // MEDIA PROJECTION
        // --------------------------------------------------------

        unregisterAndStopProjection()

        audioQueue.clear()

        captureStarted = false

        // --------------------------------------------------------
        // FOREGROUND
        // --------------------------------------------------------

        try {

            if (
                Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.N
            ) {

                stopForeground(
                    STOP_FOREGROUND_REMOVE
                )

            } else {

                @Suppress("DEPRECATION")
                stopForeground(true)
            }

        } catch (_: Exception) {
        }

        Log.d(
            TAG,
            "Captura encerrada"
        )

        stopSelf()
    }

    // ============================================================
    // LIBERAR RECURSOS
    // ============================================================

    private fun releaseAudioResources() {

        try {
            audioRecord?.stop()
        } catch (_: Exception) {
        }

        try {
            audioRecord?.release()
        } catch (_: Exception) {
        }

        audioRecord = null

        try {
            audioTrack?.pause()
        } catch (_: Exception) {
        }

        try {
            audioTrack?.flush()
        } catch (_: Exception) {
        }

        try {
            audioTrack?.stop()
        } catch (_: Exception) {
        }

        try {
            audioTrack?.release()
        } catch (_: Exception) {
        }

        audioTrack = null

        unregisterAndStopProjection()

        audioQueue.clear()

        captureStarted = false
    }

    // ============================================================
    // MEDIA PROJECTION STOP
    // ============================================================

    private fun unregisterAndStopProjection() {

        try {

            projectionCallback?.let {

                mediaProjection?.unregisterCallback(
                    it
                )
            }

        } catch (_: Exception) {
        }

        projectionCallback = null

        try {

            mediaProjection?.stop()

        } catch (_: Exception) {
        }

        mediaProjection = null
    }

    // ============================================================
    // STOP NO RENDER
    // ============================================================

    private fun sendStopToServer() {

        val currentJob = jobId

        if (currentJob.isBlank()) {
            return
        }

        thread(
            name = "SI-AudioStop"
        ) {

            var connection:
                    HttpURLConnection? = null

            try {

                connection =
                    URL(
                        "$BACKEND_URL/api/audio/stop"
                    )
                        .openConnection()
                            as HttpURLConnection

                connection.requestMethod = "POST"

                connection.doOutput = true

                connection.connectTimeout = 5000

                connection.readTimeout = 8000

                connection.setRequestProperty(
                    "Content-Type",
                    "application/json"
                )

                connection.setRequestProperty(
                    "Connection",
                    "close"
                )

                val json =
                    JSONObject().apply {

                        put(
                            "jobId",
                            currentJob
                        )
                    }.toString()

                connection.outputStream.use {

                    it.write(
                        json.toByteArray(
                            Charsets.UTF_8
                        )
                    )
                }

                Log.d(
                    TAG,
                    "Stop enviado HTTP " +
                            connection.responseCode
                )

            } catch (e: Exception) {

                Log.e(
                    TAG,
                    "Erro enviando stop",
                    e
                )

            } finally {

                connection?.disconnect()
            }
        }
    }

    // ============================================================
    // FALHA
    // ============================================================

    private fun failCapture(
        message: String
    ) {

        diagnosticError = message

        Log.e(
            TAG,
            "FALHA NA CAPTURA: $message"
        )

        running = false

        captureStarted = false

        sendDiagnostic()

        releaseAudioResources()

        stopSelf()
    }

    // ============================================================
    // FOREGROUND
    // ============================================================

    private fun startForegroundCompat() {

        val notification =
            createNotification()

        if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.Q
        ) {

            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )

        } else {

            startForeground(
                NOTIFICATION_ID,
                notification
            )
        }
    }

    // ============================================================
    // NOTIFICATION CHANNEL
    // ============================================================

    private fun createNotificationChannel() {

        if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.O
        ) {

            val channel =
                NotificationChannel(
                    CHANNEL_ID,
                    "SI Tradutor Live",
                    NotificationManager.IMPORTANCE_LOW
                )

            channel.description =
                "Captura e tradução de áudio em tempo real"

            getSystemService(
                NotificationManager::class.java
            )?.createNotificationChannel(
                channel
            )
        }
    }

    // ============================================================
    // NOTIFICATION
    // ============================================================

    private fun createNotification(): Notification {

        return if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.O
        ) {

            Notification.Builder(
                this,
                CHANNEL_ID
            )
                .setContentTitle(
                    "SI Tradutor Live"
                )
                .setContentText(
                    "Traduzindo áudio da tela em tempo real..."
                )
                .setSmallIcon(
                    android.R.drawable.ic_media_play
                )
                .setOngoing(true)
                .build()

        } else {

            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle(
                    "SI Tradutor Live"
                )
                .setContentText(
                    "Traduzindo áudio da tela em tempo real..."
                )
                .setSmallIcon(
                    android.R.drawable.ic_media_play
                )
                .setOngoing(true)
                .build()
        }
    }

    // ============================================================
    // JSON STRING
    // ============================================================

    private fun extractJsonString(
        jsonText: String,
        key: String
    ): String? {

        return try {

            val json =
                JSONObject(jsonText)

            if (
                !json.has(key) ||
                json.isNull(key)
            ) {

                null

            } else {

                json.optString(
                    key,
                    null
                )
            }

        } catch (_: Exception) {

            try {

                val pattern =
                    "\"$key\"\\s*:\\s*\"((?:\\\\.|[^\"])*)\""
                        .toRegex()

                pattern
                    .find(jsonText)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.replace(
                        "\\\"",
                        "\""
                    )
                    ?.replace(
                        "\\n",
                        "\n"
                    )
                    ?.replace(
                        "\\r",
                        "\r"
                    )

            } catch (_: Exception) {

                null
            }
        }
    }

    // ============================================================
    // BIND
    // ============================================================

    override fun onBind(
        intent: Intent?
    ): IBinder? = null

    // ============================================================
    // DESTROY
    // ============================================================

    override fun onDestroy() {

        Log.d(
            TAG,
            "AudioCaptureService destruído"
        )

        if (
            running ||
            captureStarted ||
            audioRecord != null ||
            audioTrack != null ||
            mediaProjection != null
        ) {

            stopCapture(
                sendServerStop = false
            )

        } else {

            releaseAudioResources()
        }

        super.onDestroy()
    }
}
