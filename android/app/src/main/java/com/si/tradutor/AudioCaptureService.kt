package com.si.tradutor

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.Base64
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class AudioCaptureService : Service() {

    companion object {

        private const val TAG = "SI_AUDIO"

        private const val BACKEND_URL =
            "https://si-u2ul.onrender.com"

        const val ACTION_START =
            "com.si.tradutor.START_AUDIO"

        const val ACTION_STOP =
            "com.si.tradutor.STOP_AUDIO"

        const val EXTRA_JOB_ID =
            "jobId"

        const val EXTRA_RESULT_CODE =
            "resultCode"

        const val EXTRA_RESULT_DATA =
            "resultData"

        private const val CHANNEL_ID =
            "si_audio_channel"

        private const val NOTIFICATION_ID =
            7001

        private const val SAMPLE_RATE =
            16000

        private const val OUTPUT_SAMPLE_RATE =
            24000

        private const val CHANNEL_IN =
            AudioFormat.CHANNEL_IN_MONO

        private const val CHANNEL_OUT =
            AudioFormat.CHANNEL_OUT_MONO

        private const val ENCODING =
            AudioFormat.ENCODING_PCM_16BIT

        private const val CHUNK_SIZE =
            3200

        private const val OUTPUT_POLL_MS =
            100L
    }

    @Volatile
    private var running = false

    @Volatile
    private var captureStarted = false

    @Volatile
    private var outputPlaying = false

    @Volatile
    private var errorMessage: String? = null

    private var jobId: String? = null

    private var targetLanguage =
        "pt-BR"

    private var mediaProjection:
            MediaProjection? = null

    private var audioRecord:
            AudioRecord? = null

    private var audioTrack:
            AudioTrack? = null

    private var audioManager:
            AudioManager? = null

    private var audioFocusRequest:
            AudioFocusRequest? = null

    private val audioQueue =
        LinkedBlockingQueue<ByteArray>(100)

    private val outputQueue =
        LinkedBlockingQueue<ByteArray>(100)

    private var captureThread:
            Thread? = null

    private var sendThread:
            Thread? = null

    private var outputThread:
            Thread? = null

    private var diagnosticThread:
            Thread? = null

    private var readCount = 0

    private var capturedBytes = 0L

    private var receivedOutputBytes = 0L

    private var playedOutputBytes = 0L

    private var outputChunksReceived = 0

    private var outputChunksPlayed = 0

    private var lastRead = 0

    private var queueSize = 0

    private var clientId =
        UUID.randomUUID().toString()

    private val projectionCallback =
        object : MediaProjection.Callback() {

            override fun onStop() {

                Log.d(
                    TAG,
                    "MediaProjection encerrado"
                )

                if (running) {
                    stopCapture()
                }
            }
        }

    override fun onCreate() {

        super.onCreate()

        Log.d(
            TAG,
            "AudioCaptureService criado"
        )

        audioManager =
            getSystemService(
                Context.AUDIO_SERVICE
            ) as AudioManager

        createNotificationChannel()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        if (intent == null) {
            return START_NOT_STICKY
        }

        val action =
            intent.action

        Log.d(
            TAG,
            "onStartCommand action=$action"
        )

        if (action == ACTION_STOP) {

            stopCapture()

            stopSelf()

            return START_NOT_STICKY
        }

        if (action != ACTION_START) {
            return START_NOT_STICKY
        }

        if (running) {

            Log.d(
                TAG,
                "Captura já está ativa"
            )

            return START_STICKY
        }

        jobId =
            intent.getStringExtra(
                EXTRA_JOB_ID
            )

        val resultCode =
            intent.getIntExtra(
                EXTRA_RESULT_CODE,
                -1
            )

        val resultData: Intent? =
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

        if (jobId.isNullOrBlank()) {

            errorMessage =
                "jobId não informado"

            Log.e(
                TAG,
                errorMessage!!
            )

            stopSelf()

            return START_NOT_STICKY
        }

        if (
            resultCode == -1 &&
            resultData == null
        ) {

            errorMessage =
                "Permissão de captura inválida"

            Log.e(
                TAG,
                errorMessage!!
            )

            stopSelf()

            return START_NOT_STICKY
        }

        if (
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {

            errorMessage =
                "Permissão RECORD_AUDIO não concedida"

            Log.e(
                TAG,
                errorMessage!!
            )

            stopSelf()

            return START_NOT_STICKY
        }

        startForegroundServiceNotification()

        startCapture(
            resultCode,
            resultData
        )

        return START_STICKY
    }

    private fun startCapture(
        resultCode: Int,
        resultData: Intent?
    ) {

        if (resultData == null) {

            errorMessage =
                "Intent de MediaProjection vazio"

            return
        }

        try {

            running = true

            captureStarted = false

            outputPlaying = false

            errorMessage = null

            audioQueue.clear()

            outputQueue.clear()

            readCount = 0

            capturedBytes = 0L

            receivedOutputBytes = 0L

            playedOutputBytes = 0L

            outputChunksReceived = 0

            outputChunksPlayed = 0

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
                    "Não foi possível obter MediaProjection"
                )
            }

            mediaProjection?.registerCallback(
                projectionCallback,
                null
            )

            loadSessionStatus()

            createAudioRecord()

            createAudioTrack()

            captureStarted = true

            Log.d(
                TAG,
                "Captura iniciada"
            )

            startCaptureThread()

            startSendThread()

            startOutputThread()

            startDiagnosticThread()

        } catch (e: Exception) {

            errorMessage =
                e.message ?: e.toString()

            Log.e(
                TAG,
                "Erro ao iniciar captura",
                e
            )

            stopCapture()
        }
    }

    private fun createAudioRecord() {

        val minBuffer =
            AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                CHANNEL_IN,
                ENCODING
            )

        if (minBuffer <= 0) {

            throw Exception(
                "AudioRecord.getMinBufferSize inválido"
            )
        }

        val bufferSize =
            maxOf(
                minBuffer * 2,
                CHUNK_SIZE * 4
            )

        val config =
            AudioFormat.Builder()
                .setEncoding(
                    ENCODING
                )
                .setSampleRate(
                    SAMPLE_RATE
                )
                .setChannelMask(
                    CHANNEL_IN
                )
                .build()

        /*
         * CORREÇÃO:
         *
         * mediaProjection é nullable.
         * O AudioPlaybackCaptureConfiguration
         * precisa receber MediaProjection não-null.
         */

        val projection =
            mediaProjection
                ?: throw Exception(
                    "MediaProjection não disponível"
                )

        val captureConfig =
            AudioPlaybackCaptureConfiguration
                .Builder(projection)
                .addMatchingUsage(
                    android.media.AudioAttributes
                        .USAGE_MEDIA
                )
                .addMatchingUsage(
                    android.media.AudioAttributes
                        .USAGE_GAME
                )
                .addMatchingUsage(
                    android.media.AudioAttributes
                        .USAGE_UNKNOWN
                )
                .build()

        audioRecord =
            AudioRecord.Builder()
                .setAudioFormat(
                    config
                )
                .setBufferSizeInBytes(
                    bufferSize
                )
                .setAudioPlaybackCaptureConfig(
                    captureConfig
                )
                .build()

        if (
            audioRecord?.state !=
            AudioRecord.STATE_INITIALIZED
        ) {

            throw Exception(
                "AudioRecord não foi inicializado"
            )
        }

        audioRecord?.startRecording()

        Log.d(
            TAG,
            "AudioRecord iniciado"
        )
    }

    private fun createAudioTrack() {

        val minBuffer =
            AudioTrack.getMinBufferSize(
                OUTPUT_SAMPLE_RATE,
                CHANNEL_OUT,
                ENCODING
            )

        if (minBuffer <= 0) {

            throw Exception(
                "AudioTrack.getMinBufferSize inválido"
            )
        }

        val bufferSize =
            maxOf(
                minBuffer * 4,
                24000
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
                    ENCODING
                )
                .setSampleRate(
                    OUTPUT_SAMPLE_RATE
                )
                .setChannelMask(
                    CHANNEL_OUT
                )
                .build()

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
                .setPerformanceMode(
                    AudioTrack.PERFORMANCE_MODE_LOW_LATENCY
                )
                .build()

        if (
            audioTrack?.state !=
            AudioTrack.STATE_INITIALIZED
        ) {

            throw Exception(
                "AudioTrack não foi inicializado"
            )
        }

        try {

            audioTrack?.setVolume(
                1.0f
            )

        } catch (_: Exception) {
        }

        routeAudioToSpeaker()

        requestAudioFocus()

        audioTrack?.play()

        outputPlaying = true

        Log.d(
            TAG,
            "AudioTrack iniciado"
        )
    }

    private fun routeAudioToSpeaker() {

        val track =
            audioTrack ?: return

        val manager =
            audioManager ?: return

        try {

            val devices =
                manager.getDevices(
                    AudioManager.GET_DEVICES_OUTPUTS
                )

            val speaker =
                devices.firstOrNull {

                    it.type ==
                        AudioDeviceInfo
                            .TYPE_BUILTIN_SPEAKER
                }

            if (speaker != null) {

                val result =
                    track.setPreferredDevice(
                        speaker
                    )

                Log.d(
                    TAG,
                    "Alto-falante selecionado: $result"
                )

            } else {

                Log.d(
                    TAG,
                    "Alto-falante interno não encontrado"
                )
            }

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Erro selecionando alto-falante",
                e
            )
        }
    }

    private fun requestAudioFocus() {

        val manager =
            audioManager ?: return

        try {

            if (Build.VERSION.SDK_INT >= 26) {

                val request =
                    AudioFocusRequest.Builder(
                        AudioManager
                            .AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
                    )
                        .setAudioAttributes(
                            AudioAttributes.Builder()
                                .setUsage(
                                    AudioAttributes
                                        .USAGE_MEDIA
                                )
                                .setContentType(
                                    AudioAttributes
                                        .CONTENT_TYPE_SPEECH
                                )
                                .build()
                        )
                        .build()

                audioFocusRequest =
                    request

                val result =
                    manager.requestAudioFocus(
                        request
                    )

                Log.d(
                    TAG,
                    "Audio focus=$result"
                )

            } else {

                @Suppress("DEPRECATION")

                manager.requestAudioFocus(
                    null,
                    AudioManager.STREAM_MUSIC,
                    AudioManager
                        .AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
                )
            }

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Erro no audio focus",
                e
            )
        }
    }

    private fun startCaptureThread() {

        captureThread =
            thread(
                name = "SI-Capture"
            ) {

                val buffer =
                    ByteArray(
                        CHUNK_SIZE
                    )

                while (running) {

                    try {

                        val count =
                            audioRecord?.read(
                                buffer,
                                0,
                                buffer.size
                            ) ?: -1

                        if (count > 0) {

                            readCount++

                            lastRead =
                                count

                            capturedBytes +=
                                count.toLong()

                            val chunk =
                                buffer.copyOf(
                                    count
                                )

                            if (
                                !audioQueue.offer(
                                    chunk,
                                    100,
                                    TimeUnit.MILLISECONDS
                                )
                            ) {

                                Log.w(
                                    TAG,
                                    "Fila de captura cheia"
                                )
                            }

                            queueSize =
                                audioQueue.size

                        } else {

                            Log.w(
                                TAG,
                                "AudioRecord.read=$count"
                            )

                            Thread.sleep(
                                20
                            )
                        }

                    } catch (e: Exception) {

                        if (running) {

                            errorMessage =
                                "Erro na captura: ${e.message}"

                            Log.e(
                                TAG,
                                "Erro na thread de captura",
                                e
                            )
                        }
                    }
                }

                Log.d(
                    TAG,
                    "Thread de captura encerrada"
                )
            }
    }

    private fun startSendThread() {

        sendThread =
            thread(
                name = "SI-Send"
            ) {

                while (running) {

                    try {

                        val chunk =
                            audioQueue.poll(
                                200,
                                TimeUnit.MILLISECONDS
                            )

                        if (chunk != null) {

                            sendAudioChunk(
                                chunk
                            )
                        }

                    } catch (e: Exception) {

                        if (running) {

                            Log.e(
                                TAG,
                                "Erro enviando áudio",
                                e
                            )
                        }
                    }
                }

                Log.d(
                    TAG,
                    "Thread de envio encerrada"
                )
            }
    }

    private fun sendAudioChunk(
        data: ByteArray
    ) {

        val id =
            jobId ?: return

        thread {

            try {

                val encoded =
                    Base64.encodeToString(
                        data,
                        Base64.NO_WRAP
                    )

                val body =
                    JSONObject()
                        .put(
                            "jobId",
                            id
                        )
                        .put(
                            "clientId",
                            clientId
                        )
                        .put(
                            "audio",
                            encoded
                        )
                        .toString()

                postJson(
                    "$BACKEND_URL/api/audio/chunk",
                    body
                )

            } catch (e: Exception) {

                if (running) {

                    Log.e(
                        TAG,
                        "Erro no envio do chunk",
                        e
                    )
                }
            }
        }
    }

    private fun startOutputThread() {

        outputThread =
            thread(
                name = "SI-Output"
            ) {

                while (running) {

                    try {

                        pollOutput()

                        Thread.sleep(
                            OUTPUT_POLL_MS
                        )

                    } catch (e: Exception) {

                        if (running) {

                            Log.e(
                                TAG,
                                "Erro na saída de áudio",
                                e
                            )
                        }
                    }
                }

                Log.d(
                    TAG,
                    "Thread de saída encerrada"
                )
            }
    }

    private fun pollOutput() {

        val id =
            jobId ?: return

        try {

            val response =
                getJson(
                    "$BACKEND_URL/api/audio/output/$id"
                ) ?: return

            if (response.isBlank()) {
                return
            }

            val json =
                JSONObject(
                    response
                )

            if (
                json.has("error") &&
                !json.isNull("error")
            ) {

                Log.e(
                    TAG,
                    "Erro backend: ${
                        json.optString(
                            "error"
                        )
                    }"
                )

                return
            }

            var encoded =
                json.optString(
                    "audio",
                    ""
                )

            if (encoded.isBlank()) {

                encoded =
                    json.optString(
                        "data",
                        ""
                    )
            }

            if (encoded.isBlank()) {

                encoded =
                    json.optString(
                        "pcm",
                        ""
                    )
            }

            if (encoded.isBlank()) {
                return
            }

            val pcm =
                Base64.decode(
                    encoded,
                    Base64.DEFAULT
                )

            if (pcm.isEmpty()) {
                return
            }

            receivedOutputBytes +=
                pcm.size.toLong()

            outputChunksReceived++

            outputQueue.offer(
                pcm,
                100,
                TimeUnit.MILLISECONDS
            )

            playOutputQueue()

        } catch (e: Exception) {

            if (running) {

                Log.e(
                    TAG,
                    "Erro lendo saída",
                    e
                )
            }
        }
    }

    private fun playOutputQueue() {

        val track =
            audioTrack ?: return

        if (
            track.state !=
            AudioTrack.STATE_INITIALIZED
        ) {
            return
        }

        try {

            while (true) {

                val pcm =
                    outputQueue.poll()
                        ?: break

                if (pcm.isEmpty()) {
                    continue
                }

                if (
                    track.playState !=
                    AudioTrack.PLAYSTATE_PLAYING
                ) {

                    routeAudioToSpeaker()

                    track.play()

                    outputPlaying = true
                }

                var offset = 0

                while (
                    offset < pcm.size &&
                    running
                ) {

                    val written =
                        if (
                            Build.VERSION.SDK_INT >= 23
                        ) {

                            track.write(
                                pcm,
                                offset,
                                pcm.size - offset,
                                AudioTrack.WRITE_BLOCKING
                            )

                        } else {

                            @Suppress("DEPRECATION")

                            track.write(
                                pcm,
                                offset,
                                pcm.size - offset
                            )
                        }

                    if (written > 0) {

                        offset +=
                            written

                        playedOutputBytes +=
                            written.toLong()

                    } else if (written < 0) {

                        Log.e(
                            TAG,
                            "AudioTrack.write=$written"
                        )

                        break

                    } else {

                        Thread.sleep(
                            5
                        )
                    }
                }

                outputChunksPlayed++

                Log.d(
                    TAG,
                    "Áudio reproduzido: ${pcm.size} bytes"
                )
            }

        } catch (e: Exception) {

            if (running) {

                errorMessage =
                    "Erro reproduzindo áudio: ${e.message}"

                Log.e(
                    TAG,
                    "Erro no AudioTrack",
                    e
                )
            }
        }
    }

    private fun loadSessionStatus() {

        val id =
            jobId ?: return

        try {

            val response =
                getJson(
                    "$BACKEND_URL/api/audio/status/$id"
                ) ?: return

            if (response.isBlank()) {
                return
            }

            val json =
                JSONObject(
                    response
                )

            val language =
                json.optString(
                    "targetLanguage",
                    ""
                )

            if (language.isNotBlank()) {

                targetLanguage =
                    language
            }

            Log.d(
                TAG,
                "Idioma destino=$targetLanguage"
            )

        } catch (e: Exception) {

            Log.w(
                TAG,
                "Não foi possível obter idioma: ${
                    e.message
                }"
            )
        }
    }

    private fun startDiagnosticThread() {

        diagnosticThread =
            thread(
                name = "SI-Diagnostic"
            ) {

                while (running) {

                    try {

                        sendDiagnostic()

                        Thread.sleep(
                            5000
                        )

                    } catch (e: Exception) {

                        if (running) {

                            Log.e(
                                TAG,
                                "Erro diagnóstico",
                                e
                            )
                        }
                    }
                }
            }
    }

    private fun sendDiagnostic() {

        val id =
            jobId ?: return

        try {

            val json =
                JSONObject()
                    .put(
                        "jobId",
                        id
                    )
                    .put(
                        "recording",
                        running
                    )
                    .put(
                        "captureStarted",
                        captureStarted
                    )
                    .put(
                        "readCount",
                        readCount
                    )
                    .put(
                        "lastRead",
                        lastRead
                    )
                    .put(
                        "capturedBytes",
                        capturedBytes
                    )
                    .put(
                        "queueSize",
                        audioQueue.size
                    )
                    .put(
                        "targetLanguage",
                        targetLanguage
                    )
                    .put(
                        "ttsReady",
                        outputPlaying
                    )
                    .put(
                        "audioTrackReady",
                        audioTrack?.state ==
                            AudioTrack.STATE_INITIALIZED
                    )
                    .put(
                        "outputPlaying",
                        outputPlaying
                    )
                    .put(
                        "receivedOutputBytes",
                        receivedOutputBytes
                    )
                    .put(
                        "playedOutputBytes",
                        playedOutputBytes
                    )
                    .put(
                        "outputChunksReceived",
                        outputChunksReceived
                    )
                    .put(
                        "outputChunksPlayed",
                        outputChunksPlayed
                    )
                    .put(
                        "error",
                        errorMessage
                    )

            postJson(
                "$BACKEND_URL/api/audio/diagnostic",
                json.toString()
            )

        } catch (e: Exception) {

            Log.w(
                TAG,
                "Falha no diagnóstico: ${e.message}"
            )
        }
    }

    private fun postJson(
        urlString: String,
        body: String
    ): String? {

        var connection:
                HttpURLConnection? = null

        return try {

            val url =
                URL(urlString)

            connection =
                url.openConnection()
                    as HttpURLConnection

            connection.requestMethod =
                "POST"

            connection.connectTimeout =
                15000

            connection.readTimeout =
                30000

            connection.doOutput =
                true

            connection.setRequestProperty(
                "Content-Type",
                "application/json"
            )

            connection.setRequestProperty(
                "Accept",
                "application/json"
            )

            connection.outputStream.use { output ->

                output.write(
                    body.toByteArray(
                        Charsets.UTF_8
                    )
                )
            }

            val code =
                connection.responseCode

            val stream =
                if (code in 200..299) {

                    connection.inputStream

                } else {

                    connection.errorStream
                }

            if (stream == null) {
                return null
            }

            BufferedReader(
                InputStreamReader(
                    stream
                )
            ).use {
                it.readText()
            }

        } catch (e: Exception) {

            Log.e(
                TAG,
                "HTTP POST erro: $urlString",
                e
            )

            null

        } finally {

            connection?.disconnect()
        }
    }

    private fun getJson(
        urlString: String
    ): String? {

        var connection:
                HttpURLConnection? = null

        return try {

            val url =
                URL(urlString)

            connection =
                url.openConnection()
                    as HttpURLConnection

            connection.requestMethod =
                "GET"

            connection.connectTimeout =
                10000

            connection.readTimeout =
                15000

            connection.setRequestProperty(
                "Accept",
                "application/json"
            )

            val code =
                connection.responseCode

            val stream =
                if (code in 200..299) {

                    connection.inputStream

                } else {

                    connection.errorStream
                }

            if (stream == null) {
                return null
            }

            BufferedReader(
                InputStreamReader(
                    stream
                )
            ).use {
                it.readText()
            }

        } catch (e: Exception) {

            Log.e(
                TAG,
                "HTTP GET erro: $urlString",
                e
            )

            null

        } finally {

            connection?.disconnect()
        }
    }

    private fun createNotificationChannel() {

        if (Build.VERSION.SDK_INT >= 26) {

            val channel =
                NotificationChannel(
                    CHANNEL_ID,
                    "SI Tradutor Live",
                    NotificationManager
                        .IMPORTANCE_LOW
                )

            channel.description =
                "Captura e tradução de áudio em tempo real"

            val manager =
                getSystemService(
                    NotificationManager::class.java
                )

            manager.createNotificationChannel(
                channel
            )
        }
    }

    private fun startForegroundServiceNotification() {

        val notification:
                Notification =
            NotificationCompat.Builder(
                this,
                CHANNEL_ID
            )
                .setContentTitle(
                    "SI Tradutor Live"
                )
                .setContentText(
                    "Traduzindo áudio em tempo real..."
                )
                .setSmallIcon(
                    R.drawable.si_logo
                )
                .setOngoing(true)
                .setCategory(
                    NotificationCompat.CATEGORY_SERVICE
                )
                .build()

        if (Build.VERSION.SDK_INT >= 29) {

            startForeground(
                NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo
                    .FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )

        } else {

            startForeground(
                NOTIFICATION_ID,
                notification
            )
        }
    }

    private fun stopCapture() {

        if (
            !running &&
            audioRecord == null &&
            audioTrack == null &&
            mediaProjection == null
        ) {
            return
        }

        Log.d(
            TAG,
            "Parando sessão $jobId"
        )

        running = false

        captureStarted = false

        outputPlaying = false

        try {
            captureThread?.interrupt()
        } catch (_: Exception) {
        }

        try {
            sendThread?.interrupt()
        } catch (_: Exception) {
        }

        try {
            outputThread?.interrupt()
        } catch (_: Exception) {
        }

        try {
            diagnosticThread?.interrupt()
        } catch (_: Exception) {
        }

        captureThread = null

        sendThread = null

        outputThread = null

        diagnosticThread = null

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

        abandonAudioFocus()

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

        audioQueue.clear()

        outputQueue.clear()

        Log.d(
            TAG,
            "Sessão parada: $jobId"
        )

        jobId = null
    }

    private fun abandonAudioFocus() {

        val manager =
            audioManager ?: return

        try {

            if (
                Build.VERSION.SDK_INT >= 26 &&
                audioFocusRequest != null
            ) {

                manager.abandonAudioFocusRequest(
                    audioFocusRequest!!
                )

                audioFocusRequest = null

            } else {

                @Suppress("DEPRECATION")

                manager.abandonAudioFocus(
                    null
                )
            }

        } catch (_: Exception) {
        }
    }

    override fun onDestroy() {

        Log.d(
            TAG,
            "AudioCaptureService destruído"
        )

        stopCapture()

        super.onDestroy()
    }

    override fun onBind(
        intent: Intent?
    ): IBinder? {

        return null
    }
}
