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
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.Base64
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

class AudioCaptureService : Service() {

    companion object {

        private const val TAG = "SI_AUDIO_SERVICE"

        private const val BACKEND_URL =
            "https://si-u2ul.onrender.com"

        const val ACTION_START =
            "com.si.tradutor.ACTION_START"

        const val ACTION_STOP =
            "com.si.tradutor.ACTION_STOP"

        const val EXTRA_JOB_ID =
            "jobId"

        const val EXTRA_RESULT_CODE =
            "resultCode"

        const val EXTRA_RESULT_DATA =
            "resultData"

        private const val CHANNEL_ID =
            "si_audio_channel"

        private const val NOTIFICATION_ID =
            9001

        private const val SAMPLE_RATE_INPUT =
            16000

        private const val SAMPLE_RATE_OUTPUT =
            24000

        private const val CHUNK_SIZE =
            3200

        private const val OUTPUT_CHUNK_QUEUE_LIMIT =
            100

        private const val DIAGNOSTIC_INTERVAL =
            5000L
    }

    // ---------------------------------------------------------
    // CONTROLE
    // ---------------------------------------------------------

    private val running =
        AtomicBoolean(false)

    private var jobId: String? = null

    private var mediaProjection: MediaProjection? = null

    private var audioRecord: AudioRecord? = null

    private var audioTrack: AudioTrack? = null

    private var audioManager: AudioManager? = null

    private var audioFocusRequest: AudioFocusRequest? = null

    // ---------------------------------------------------------
    // CONTADORES
    // ---------------------------------------------------------

    private val readCount =
        AtomicLong(0)

    private val capturedBytes =
        AtomicLong(0)

    private val sentChunks =
        AtomicLong(0)

    private val sentBytes =
        AtomicLong(0)

    private val receivedOutputChunks =
        AtomicLong(0)

    private val playedOutputChunks =
        AtomicLong(0)

    private val receivedOutputBytes =
        AtomicLong(0)

    private val playedOutputBytes =
        AtomicLong(0)

    // ---------------------------------------------------------
    // ESTADOS
    // ---------------------------------------------------------

    @Volatile
    private var captureStarted = false

    @Volatile
    private var audioRecordReady = false

    @Volatile
    private var audioTrackReady = false

    @Volatile
    private var outputPlaying = false

    @Volatile
    private var lastError = ""

    @Volatile
    private var lastStage = "created"

    @Volatile
    private var lastRecordRead = 0

    @Volatile
    private var lastHttpStatus = 0

    // ---------------------------------------------------------
    // FILAS
    // ---------------------------------------------------------

    private val inputQueue =
        LinkedBlockingQueue<ByteArray>(50)

    private val outputQueue =
        LinkedBlockingQueue<ByteArray>(OUTPUT_CHUNK_QUEUE_LIMIT)

    // ---------------------------------------------------------
    // CURSOR DO ÁUDIO
    // ---------------------------------------------------------

    @Volatile
    private var lastOutputSeq = 0L

    // ---------------------------------------------------------
    // SERVICE
    // ---------------------------------------------------------

    override fun onCreate() {
        super.onCreate()

        Log.d(TAG, "================================")
        Log.d(TAG, "SI AUDIO SERVICE ONCREATE")
        Log.d(TAG, "================================")

        lastStage = "onCreate"

        criarCanalNotificacao()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        Log.d(TAG, "================================")
        Log.d(TAG, "SI AUDIO SERVICE ONSTARTCOMMAND")
        Log.d(TAG, "action=${intent?.action}")
        Log.d(TAG, "================================")

        if (intent == null) {
            lastError = "Intent nulo"
            lastStage = "intent_null"
            return START_NOT_STICKY
        }

        when (intent.action) {

            ACTION_START -> {

                jobId =
                    intent.getStringExtra(EXTRA_JOB_ID)

                val resultCode =
                    intent.getIntExtra(
                        EXTRA_RESULT_CODE,
                        -1
                    )

                val resultData =
                    if (Build.VERSION.SDK_INT >= 33) {
                        intent.getParcelableExtra(
                            EXTRA_RESULT_DATA,
                            Intent::class.java
                        )
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(
                            EXTRA_RESULT_DATA
                        )
                    }

                Log.d(TAG, "JOB ID = $jobId")
                Log.d(TAG, "RESULT CODE = $resultCode")
                Log.d(TAG, "RESULT DATA = ${resultData != null}")

                if (jobId.isNullOrBlank()) {

                    lastError =
                        "jobId não recebido"

                    lastStage =
                        "jobid_missing"

                    mostrarToast(
                        "SI: erro - jobId não recebido"
                    )

                    enviarDiagnostico(
                        "jobid_missing"
                    )

                    return START_NOT_STICKY
                }

                // -------------------------------------------------
                // DIAGNÓSTICO IMEDIATO
                // -------------------------------------------------

                enviarDiagnostico(
                    "onStartCommand"
                )

                // -------------------------------------------------
                // FOREGROUND
                // -------------------------------------------------

                try {

                    iniciarForeground()

                    Log.d(
                        TAG,
                        "Foreground iniciado"
                    )

                    lastStage =
                        "foreground_started"

                    enviarDiagnostico(
                        "foreground_started"
                    )

                } catch (e: Exception) {

                    lastError =
                        "Foreground: ${e.message}"

                    lastStage =
                        "foreground_error"

                    Log.e(
                        TAG,
                        "Erro no foreground",
                        e
                    )

                    enviarDiagnostico(
                        "foreground_error"
                    )

                    mostrarToast(
                        "SI: erro ao iniciar serviço"
                    )

                    pararTudo()

                    return START_NOT_STICKY
                }

                // -------------------------------------------------
                // INICIAR CAPTURA
                // -------------------------------------------------

                if (!running.get()) {

                    iniciarCaptura(
                        resultCode,
                        resultData
                    )
                }
            }

            ACTION_STOP -> {

                Log.d(
                    TAG,
                    "ACTION_STOP"
                )

                lastStage =
                    "stop_requested"

                enviarDiagnostico(
                    "stop_requested"
                )

                pararTudo()
            }
        }

        return START_NOT_STICKY
    }

    // ---------------------------------------------------------
    // FOREGROUND
    // ---------------------------------------------------------

    private fun iniciarForeground() {

        val notification =
            criarNotificacao()

        if (Build.VERSION.SDK_INT >= 29) {

            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )

        } else {

            @Suppress("DEPRECATION")
            startForeground(
                NOTIFICATION_ID,
                notification
            )
        }
    }

    private fun criarCanalNotificacao() {

        if (Build.VERSION.SDK_INT >= 26) {

            val manager =
                getSystemService(
                    Context.NOTIFICATION_SERVICE
                ) as NotificationManager

            val channel =
                NotificationChannel(
                    CHANNEL_ID,
                    "SI Tradutor Live",
                    NotificationManager.IMPORTANCE_LOW
                )

            channel.description =
                "Captura de áudio do SI Tradutor Live"

            manager.createNotificationChannel(
                channel
            )
        }
    }

    private fun criarNotificacao(): Notification {

        return if (Build.VERSION.SDK_INT >= 26) {

            Notification.Builder(
                this,
                CHANNEL_ID
            )
                .setContentTitle(
                    "SI Tradutor Live"
                )
                .setContentText(
                    "🎙️ Traduzindo áudio em tempo real"
                )
                .setSmallIcon(
                    android.R.drawable.ic_btn_speak_now
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
                    "🎙️ Traduzindo áudio em tempo real"
                )
                .setSmallIcon(
                    android.R.drawable.ic_btn_speak_now
                )
                .setOngoing(true)
                .build()
        }
    }

    // ---------------------------------------------------------
    // INICIAR CAPTURA
    // ---------------------------------------------------------

    private fun iniciarCaptura(
        resultCode: Int,
        resultData: Intent?
    ) {

        if (running.get()) {
            return
        }

        if (Build.VERSION.SDK_INT < 29) {

            lastError =
                "Android 10 ou superior necessário"

            lastStage =
                "android_version_error"

            enviarDiagnostico(
                "android_version_error"
            )

            mostrarToast(
                "SI precisa do Android 10 ou superior"
            )

            pararTudo()

            return
        }

        if (resultCode != -1) {

            lastError =
                "Permissão MediaProjection inválida"

            lastStage =
                "projection_permission_error"

            enviarDiagnostico(
                "projection_permission_error"
            )

            mostrarToast(
                "SI: permissão de captura não autorizada"
            )

            pararTudo()

            return
        }

        if (resultData == null) {

            lastError =
                "resultData nulo"

            lastStage =
                "projection_data_error"

            enviarDiagnostico(
                "projection_data_error"
            )

            mostrarToast(
                "SI: dados da captura inválidos"
            )

            pararTudo()

            return
        }

        running.set(true)

        try {

            // -------------------------------------------------
            // MEDIA PROJECTION
            // -------------------------------------------------

            lastStage =
                "creating_media_projection"

            enviarDiagnostico(
                "creating_media_projection"
            )

            val manager =
                getSystemService(
                    Context.MEDIA_PROJECTION_SERVICE
                ) as MediaProjectionManager

            mediaProjection =
                manager.getMediaProjection(
                    resultCode,
                    resultData
                )

            if (mediaProjection == null) {

                throw Exception(
                    "MediaProjection retornou null"
                )
            }

            mediaProjection?.registerCallback(
                object : MediaProjection.Callback() {

                    override fun onStop() {

                        Log.d(
                            TAG,
                            "MediaProjection foi parada"
                        )

                        lastStage =
                            "projection_stopped"

                        enviarDiagnostico(
                            "projection_stopped"
                        )

                        pararTudo()
                    }
                },
                null
            )

            Log.d(
                TAG,
                "MediaProjection OK"
            )

            lastStage =
                "media_projection_ok"

            enviarDiagnostico(
                "media_projection_ok"
            )

            // -------------------------------------------------
            // AUDIO RECORD
            // -------------------------------------------------

            configurarAudioCapture()

            // -------------------------------------------------
            // AUDIO OUTPUT
            // -------------------------------------------------

            configurarAudioOutput()

            // -------------------------------------------------
            // THREAD DE CAPTURA
            // -------------------------------------------------

            thread(
                name = "SI-Audio-Capture"
            ) {
                loopCaptura()
            }

            // -------------------------------------------------
            // THREAD DE ENVIO
            // -------------------------------------------------

            thread(
                name = "SI-Audio-Send"
            ) {
                loopEnvio()
            }

            // -------------------------------------------------
            // THREAD DE SAÍDA
            // -------------------------------------------------

            thread(
                name = "SI-Audio-Output-Poll"
            ) {
                loopPollingSaida()
            }

            // -------------------------------------------------
            // THREAD DE PLAYBACK
            // -------------------------------------------------

            thread(
                name = "SI-Audio-Output-Play"
            ) {
                loopPlayback()
            }

            // -------------------------------------------------
            // THREAD DE DIAGNÓSTICO
            // -------------------------------------------------

            thread(
                name = "SI-Audio-Diagnostic"
            ) {
                loopDiagnostico()
            }

            mostrarToast(
                "🎙️ SI: serviço de áudio iniciado"
            )

            lastStage =
                "capture_threads_started"

            enviarDiagnostico(
                "capture_threads_started"
            )

        } catch (e: Exception) {

            Log.e(
                TAG,
                "ERRO AO INICIAR CAPTURA",
                e
            )

            lastError =
                e.message ?: e.javaClass.simpleName

            lastStage =
                "capture_start_error"

            enviarDiagnostico(
                "capture_start_error"
            )

            mostrarToast(
                "SI: erro na captura de áudio"
            )

            pararTudo()
        }
    }

    // ---------------------------------------------------------
    // CONFIGURAR AUDIO CAPTURE
    // ---------------------------------------------------------

    private fun configurarAudioCapture() {

        lastStage =
            "configuring_audio_record"

        enviarDiagnostico(
            "configuring_audio_record"
        )

        if (
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {

            throw SecurityException(
                "RECORD_AUDIO não autorizado"
            )
        }

        val format =
            AudioFormat.Builder()
                .setEncoding(
                    AudioFormat.ENCODING_PCM_16BIT
                )
                .setSampleRate(
                    SAMPLE_RATE_INPUT
                )
                .setChannelMask(
                    AudioFormat.CHANNEL_IN_MONO
                )
                .build()

        val playbackConfig =
            AudioPlaybackCaptureConfiguration
                .Builder(mediaProjection!!)
                .addMatchingUsage(
                    android.media.AudioAttributes.USAGE_MEDIA
                )
                .addMatchingUsage(
                    android.media.AudioAttributes.USAGE_GAME
                )
                .addMatchingUsage(
                    android.media.AudioAttributes.USAGE_UNKNOWN
                )
                .build()

        val minBuffer =
            AudioRecord.getMinBufferSize(
                SAMPLE_RATE_INPUT,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )

        if (minBuffer <= 0) {

            throw Exception(
                "AudioRecord.getMinBufferSize retornou $minBuffer"
            )
        }

        val bufferSize =
            maxOf(
                minBuffer * 2,
                CHUNK_SIZE * 4
            )

        Log.d(
            TAG,
            "AudioRecord minBuffer=$minBuffer bufferSize=$bufferSize"
        )

        val attributes =
            android.media.AudioAttributes.Builder()
                .setUsage(
                    android.media.AudioAttributes.USAGE_MEDIA
                )
                .build()

        audioRecord =
            AudioRecord.Builder()
                .setAudioFormat(format)
                .setBufferSizeInBytes(
                    bufferSize
                )
                .setAudioPlaybackCaptureConfig(
                    playbackConfig
                )
                .build()

        if (
            audioRecord?.state !=
            AudioRecord.STATE_INITIALIZED
        ) {

            throw Exception(
                "AudioRecord não inicializou. state=${audioRecord?.state}"
            )
        }

        audioRecordReady = true

        Log.d(
            TAG,
            "AudioRecord inicializado"
        )

        lastStage =
            "audio_record_ready"

        enviarDiagnostico(
            "audio_record_ready"
        )

        try {

            audioRecord?.startRecording()

        } catch (e: Exception) {

            lastError =
                "startRecording: ${e.message}"

            lastStage =
                "audio_record_start_error"

            enviarDiagnostico(
                "audio_record_start_error"
            )

            throw e
        }

        if (
            audioRecord?.recordingState !=
            AudioRecord.RECORDSTATE_RECORDING
        ) {

            throw Exception(
                "AudioRecord não entrou em RECORDSTATE_RECORDING"
            )
        }

        captureStarted = true

        Log.d(
            TAG,
            "AudioRecord começou a gravar"
        )

        lastStage =
            "audio_record_recording"

        enviarDiagnostico(
            "audio_record_recording"
        )
    }

    // ---------------------------------------------------------
    // LOOP DE CAPTURA
    // ---------------------------------------------------------

    private fun loopCaptura() {

        Log.d(
            TAG,
            "LOOP DE CAPTURA INICIADO"
        )

        val buffer =
            ByteArray(CHUNK_SIZE)

        while (running.get()) {

            try {

                val count =
                    audioRecord?.read(
                        buffer,
                        0,
                        buffer.size,
                        AudioRecord.READ_BLOCKING
                    ) ?: -1

                lastRecordRead =
                    count

                if (count > 0) {

                    readCount.incrementAndGet()

                    capturedBytes.addAndGet(
                        count.toLong()
                    )

                    val chunk =
                        buffer.copyOf(count)

                    if (
                        !inputQueue.offer(chunk)
                    ) {

                        Log.w(
                            TAG,
                            "Fila de entrada cheia"
                        )
                    }

                    if (
                        readCount.get() <= 5L ||
                        readCount.get() % 100L == 0L
                    ) {

                        Log.d(
                            TAG,
                            "ÁUDIO CAPTURADO bytes=$count total=${capturedBytes.get()}"
                        )
                    }

                } else if (count < 0) {

                    lastError =
                        "AudioRecord.read retornou $count"

                    lastStage =
                        "audio_read_error"

                    Log.e(
                        TAG,
                        lastError
                    )

                    enviarDiagnostico(
                        "audio_read_error"
                    )

                    break

                } else {

                    Log.d(
                        TAG,
                        "AudioRecord.read retornou 0"
                    )
                }

            } catch (e: Exception) {

                lastError =
                    "loopCaptura: ${e.message}"

                lastStage =
                    "capture_loop_error"

                Log.e(
                    TAG,
                    lastError,
                    e
                )

                enviarDiagnostico(
                    "capture_loop_error"
                )

                break
            }
        }

        Log.d(
            TAG,
            "LOOP DE CAPTURA TERMINOU"
        )
    }

    // ---------------------------------------------------------
    // LOOP DE ENVIO
    // ---------------------------------------------------------

    private fun loopEnvio() {

        Log.d(
            TAG,
            "LOOP DE ENVIO INICIADO"
        )

        while (running.get()) {

            try {

                val chunk =
                    inputQueue.take()

                enviarChunk(
                    chunk
                )

            } catch (e: InterruptedException) {

                break

            } catch (e: Exception) {

                lastError =
                    "loopEnvio: ${e.message}"

                lastStage =
                    "send_loop_error"

                Log.e(
                    TAG,
                    lastError,
                    e
                )

                enviarDiagnostico(
                    "send_loop_error"
                )
            }
        }

        Log.d(
            TAG,
            "LOOP DE ENVIO TERMINOU"
        )
    }

    // ---------------------------------------------------------
    // ENVIO DO CHUNK
    // ---------------------------------------------------------

    private fun enviarChunk(
        audio: ByteArray
    ) {

        val currentJob =
            jobId ?: return

        try {

            val audioBase64 =
                Base64.encodeToString(
                    audio,
                    Base64.NO_WRAP
                )

            val json =
                """
                {
                  "jobId":"$currentJob",
                  "audio":"$audioBase64"
                }
                """.trimIndent()

            val connection =
                URL(
                    "$BACKEND_URL/api/audio/chunk"
                ).openConnection()
                    as HttpURLConnection

            connection.requestMethod =
                "POST"

            connection.connectTimeout =
                15000

            connection.readTimeout =
                15000

            connection.doOutput =
                true

            connection.setRequestProperty(
                "Content-Type",
                "application/json"
            )

            connection.outputStream.use { output ->

                output.write(
                    json.toByteArray(
                        Charsets.UTF_8
                    )
                )
            }

            val responseCode =
                connection.responseCode

            lastHttpStatus =
                responseCode

            if (
                responseCode in 200..299
            ) {

                sentChunks.incrementAndGet()

                sentBytes.addAndGet(
                    audio.size.toLong()
                )

                if (
                    sentChunks.get() <= 5L ||
                    sentChunks.get() % 50L == 0L
                ) {

                    Log.d(
                        TAG,
                        "CHUNK ENVIADO #${sentChunks.get()} bytes=${audio.size}"
                    )
                }

            } else {

                val errorText =
                    try {

                        BufferedReader(
                            InputStreamReader(
                                connection.errorStream
                                    ?: connection.inputStream
                            )
                        ).use {
                            it.readText()
                        }

                    } catch (_: Exception) {
                        ""
                    }

                lastError =
                    "HTTP chunk $responseCode $errorText"

                Log.e(
                    TAG,
                    lastError
                )

                enviarDiagnostico(
                    "chunk_http_error"
                )
            }

            connection.disconnect()

        } catch (e: Exception) {

            lastError =
                "enviarChunk: ${e.message}"

            lastStage =
                "chunk_network_error"

            Log.e(
                TAG,
                lastError,
                e
            )

            enviarDiagnostico(
                "chunk_network_error"
            )
        }
    }

    // ---------------------------------------------------------
    // CONFIGURAR AUDIO OUTPUT
    // ---------------------------------------------------------

    private fun configurarAudioOutput() {

        lastStage =
            "configuring_audio_output"

        enviarDiagnostico(
            "configuring_audio_output"
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
                    AudioFormat.ENCODING_PCM_16BIT
                )
                .setSampleRate(
                    SAMPLE_RATE_OUTPUT
                )
                .setChannelMask(
                    AudioFormat.CHANNEL_OUT_MONO
                )
                .build()

        val minBuffer =
            AudioTrack.getMinBufferSize(
                SAMPLE_RATE_OUTPUT,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )

        if (minBuffer <= 0) {

            throw Exception(
                "AudioTrack.getMinBufferSize inválido: $minBuffer"
            )
        }

        val bufferSize =
            maxOf(
                minBuffer * 2,
                4096
            )

        audioTrack =
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
            audioTrack?.state !=
            AudioTrack.STATE_INITIALIZED
        ) {

            throw Exception(
                "AudioTrack não inicializou"
            )
        }

        audioManager =
            getSystemService(
                Context.AUDIO_SERVICE
            ) as AudioManager

        if (Build.VERSION.SDK_INT >= 26) {

            val focusRequest =
                AudioFocusRequest.Builder(
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
                )
                    .setAudioAttributes(
                        attributes
                    )
                    .setAcceptsDelayedFocusGain(
                        false
                    )
                    .build()

            audioFocusRequest =
                focusRequest

            audioManager?.requestAudioFocus(
                focusRequest
            )

        } else {

            @Suppress("DEPRECATION")
            audioManager?.requestAudioFocus(
                null,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
            )
        }

        audioTrack?.play()

        audioTrackReady = true
        outputPlaying = true

        Log.d(
            TAG,
            "AudioTrack pronto"
        )

        lastStage =
            "audio_track_ready"

        enviarDiagnostico(
            "audio_track_ready"
        )
    }

    // ---------------------------------------------------------
    // POLLING DA SAÍDA
    // ---------------------------------------------------------

    private fun loopPollingSaida() {

        Log.d(
            TAG,
            "LOOP DE POLLING INICIADO"
        )

        while (running.get()) {

            try {

                buscarAudioTraduzido()

                Thread.sleep(250)

            } catch (e: InterruptedException) {

                break

            } catch (e: Exception) {

                lastError =
                    "polling: ${e.message}"

                Log.e(
                    TAG,
                    lastError
                )

                Thread.sleep(1000)
            }
        }

        Log.d(
            TAG,
            "LOOP DE POLLING TERMINOU"
        )
    }

    // ---------------------------------------------------------
    // BUSCAR SAÍDA
    // ---------------------------------------------------------

    private fun buscarAudioTraduzido() {

        val currentJob =
            jobId ?: return

        try {

            val url =
                "$BACKEND_URL/api/audio/output/$currentJob" +
                        "?after=$lastOutputSeq&limit=20"

            val connection =
                URL(url).openConnection()
                    as HttpURLConnection

            connection.requestMethod =
                "GET"

            connection.connectTimeout =
                10000

            connection.readTimeout =
                10000

            val responseCode =
                connection.responseCode

            lastHttpStatus =
                responseCode

            if (
                responseCode !in 200..299
            ) {

                connection.disconnect()
                return
            }

            val response =
                BufferedReader(
                    InputStreamReader(
                        connection.inputStream
                    )
                ).use {
                    it.readText()
                }

            connection.disconnect()

            processarRespostaOutput(
                response
            )

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Erro buscando output: ${e.message}"
            )
        }
    }

    // ---------------------------------------------------------
    // PROCESSAR OUTPUT JSON
    // ---------------------------------------------------------

    private fun processarRespostaOutput(
        json: String
    ) {

        try {

            if (
                !json.contains("\"chunks\"")
            ) {
                return
            }

            val chunksSection =
                json.substringAfter(
                    "\"chunks\":[",
                    ""
                ).substringBeforeLast(
                    "]"
                )

            if (
                chunksSection.isBlank()
            ) {
                return
            }

            val regex =
                Regex(
                    """\{"seq":(\d+),"audio":"([^"]*)"\}"""
                )

            val matches =
                regex.findAll(
                    chunksSection
                )

            for (match in matches) {

                val seq =
                    match.groupValues[1]
                        .toLongOrNull()
                        ?: continue

                val base64 =
                    match.groupValues[2]

                if (
                    seq <= lastOutputSeq
                ) {
                    continue
                }

                val audio =
                    try {

                        Base64.decode(
                            base64,
                            Base64.DEFAULT
                        )

                    } catch (e: Exception) {

                        Log.e(
                            TAG,
                            "Base64 inválido",
                            e
                        )

                        continue
                    }

                if (
                    audio.isEmpty()
                ) {
                    continue
                }

                if (
                    outputQueue.offer(audio)
                ) {

                    lastOutputSeq =
                        maxOf(
                            lastOutputSeq,
                            seq
                        )

                    receivedOutputChunks
                        .incrementAndGet()

                    receivedOutputBytes
                        .addAndGet(
                            audio.size.toLong()
                        )

                    Log.d(
                        TAG,
                        "OUTPUT RECEBIDO seq=$seq bytes=${audio.size}"
                    )
                }
            }

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Erro processando output",
                e
            )
        }
    }

    // ---------------------------------------------------------
    // LOOP DE PLAYBACK
    // ---------------------------------------------------------

    private fun loopPlayback() {

        Log.d(
            TAG,
            "LOOP DE PLAYBACK INICIADO"
        )

        while (running.get()) {

            try {

                val audio =
                    outputQueue.take()

                if (
                    audioTrack?.state !=
                    AudioTrack.STATE_INITIALIZED
                ) {
                    continue
                }

                if (
                    audioTrack?.playState !=
                    AudioTrack.PLAYSTATE_PLAYING
                ) {

                    audioTrack?.play()

                    outputPlaying = true
                }

                val written =
                    audioTrack?.write(
                        audio,
                        0,
                        audio.size,
                        AudioTrack.WRITE_BLOCKING
                    ) ?: -1

                if (written > 0) {

                    playedOutputChunks
                        .incrementAndGet()

                    playedOutputBytes
                        .addAndGet(
                            written.toLong()
                        )

                    Log.d(
                        TAG,
                        "OUTPUT TOCADO bytes=$written"
                    )
                }

            } catch (e: InterruptedException) {

                break

            } catch (e: Exception) {

                lastError =
                    "playback: ${e.message}"

                Log.e(
                    TAG,
                    lastError,
                    e
                )

                Thread.sleep(500)
            }
        }

        Log.d(
            TAG,
            "LOOP DE PLAYBACK TERMINOU"
        )
    }

    // ---------------------------------------------------------
    // DIAGNÓSTICO CONTÍNUO
    // ---------------------------------------------------------

    private fun loopDiagnostico() {

        while (running.get()) {

            try {

                enviarDiagnostico(
                    "heartbeat"
                )

                Thread.sleep(
                    DIAGNOSTIC_INTERVAL
                )

            } catch (
                e: InterruptedException
            ) {

                break

            } catch (e: Exception) {

                Log.e(
                    TAG,
                    "Erro diagnóstico",
                    e
                )
            }
        }
    }

    // ---------------------------------------------------------
    // ENVIAR DIAGNÓSTICO
    // ---------------------------------------------------------

    private fun enviarDiagnostico(
        stage: String
    ) {

        val currentJob =
            jobId ?: return

        thread(
            name = "SI-Diagnostic-HTTP"
        ) {

            try {

                val json =
                    """
                    {
                      "jobId":"$currentJob",
                      "stage":"${escaparJson(stage)}",
                      "recording":$running,
                      "captureStarted":$captureStarted,
                      "audioRecordReady":$audioRecordReady,
                      "audioTrackReady":$audioTrackReady,
                      "outputPlaying":$outputPlaying,
                      "readCount":${readCount.get()},
                      "capturedBytes":${capturedBytes.get()},
                      "sentChunks":${sentChunks.get()},
                      "sentBytes":${sentBytes.get()},
                      "receivedOutputChunks":${receivedOutputChunks.get()},
                      "receivedOutputBytes":${receivedOutputBytes.get()},
                      "playedOutputChunks":${playedOutputChunks.get()},
                      "playedOutputBytes":${playedOutputBytes.get()},
                      "lastRecordRead":$lastRecordRead,
                      "lastOutputSeq":$lastOutputSeq,
                      "inputQueueSize":${inputQueue.size},
                      "outputQueueSize":${outputQueue.size},
                      "lastHttpStatus":$lastHttpStatus,
                      "lastStage":"${escaparJson(lastStage)}",
                      "lastError":"${escaparJson(lastError)}"
                    }
                    """.trimIndent()

                val connection =
                    URL(
                        "$BACKEND_URL/api/audio/diagnostic"
                    ).openConnection()
                        as HttpURLConnection

                connection.requestMethod =
                    "POST"

                connection.connectTimeout =
                    10000

                connection.readTimeout =
                    10000

                connection.doOutput =
                    true

                connection.setRequestProperty(
                    "Content-Type",
                    "application/json"
                )

                connection.outputStream.use {
                    it.write(
                        json.toByteArray(
                            Charsets.UTF_8
                        )
                    )
                }

                val responseCode =
                    connection.responseCode

                Log.d(
                    TAG,
                    "DIAGNOSTIC POST stage=$stage HTTP=$responseCode"
                )

                connection.disconnect()

            } catch (e: Exception) {

                Log.e(
                    TAG,
                    "Falha enviando diagnóstico: ${e.message}"
                )
            }
        }
    }

    // ---------------------------------------------------------
    // JSON
    // ---------------------------------------------------------

    private fun escaparJson(
        value: String
    ): String {

        return value
            .replace(
                "\\",
                "\\\\"
            )
            .replace(
                "\"",
                "\\\""
            )
            .replace(
                "\n",
                "\\n"
            )
            .replace(
                "\r",
                "\\r"
            )
    }

    // ---------------------------------------------------------
    // TOAST
    // ---------------------------------------------------------

    private fun mostrarToast(
        mensagem: String
    ) {

        try {

            android.os.Handler(
                mainLooper
            ).post {

                Toast.makeText(
                    applicationContext,
                    mensagem,
                    Toast.LENGTH_LONG
                ).show()
            }

        } catch (_: Exception) {
        }
    }

    // ---------------------------------------------------------
    // PARAR TUDO
    // ---------------------------------------------------------

    private fun pararTudo() {

        if (
            !running.getAndSet(false)
        ) {

            // Mesmo se já estiver parado,
            // libera os recursos.
        }

        Log.d(
            TAG,
            "PARANDO SI AUDIO SERVICE"
        )

        lastStage =
            "stopping"

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

        try {

            if (Build.VERSION.SDK_INT >= 26) {

                audioFocusRequest?.let {
                    audioManager?.abandonAudioFocusRequest(
                        it
                    )
                }

            } else {

                @Suppress("DEPRECATION")
                audioManager?.abandonAudioFocus(
                    null
                )
            }

        } catch (_: Exception) {
        }

        audioFocusRequest = null
        audioManager = null

        try {

            mediaProjection?.unregisterCallback(
                projectionCallback
            )

        } catch (_: Exception) {
        }

        try {
            mediaProjection?.stop()
        } catch (_: Exception) {
        }

        mediaProjection = null

        inputQueue.clear()
        outputQueue.clear()

        captureStarted = false
        audioRecordReady = false
        audioTrackReady = false
        outputPlaying = false

        Log.d(
            TAG,
            "SI AUDIO SERVICE PARADO"
        )

        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (_: Exception) {
        }

        stopSelf()
    }

    private val projectionCallback =
        object : MediaProjection.Callback() {

            override fun onStop() {

                Log.d(
                    TAG,
                    "Projection callback onStop"
                )
            }
        }

    override fun onDestroy() {

        Log.d(
            TAG,
            "SI AUDIO SERVICE ONDESTROY"
        )

        pararTudo()

        super.onDestroy()
    }

    override fun onBind(
        intent: Intent?
    ): IBinder? {
        return null
    }
}
