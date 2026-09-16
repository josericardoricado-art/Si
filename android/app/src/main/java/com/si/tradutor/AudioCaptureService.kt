package com.si.tradutor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaProjection
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.util.Base64
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

class AudioCaptureService : Service() {

    companion object {

        const val ACTION_START =
            "com.si.tradutor.START_AUDIO"

        const val ACTION_STOP =
            "com.si.tradutor.STOP_AUDIO"

        const val EXTRA_RESULT_CODE =
            "resultCode"

        const val EXTRA_RESULT_DATA =
            "resultData"

        const val EXTRA_JOB_ID =
            "jobId"

        private const val TAG =
            "SI_AUDIO"

        private const val BACKEND_URL =
            "https://si-u2ul.onrender.com"

        private const val CHANNEL_ID =
            "si_audio_channel"

        private const val NOTIFICATION_ID =
            1001

        private const val SAMPLE_RATE =
            16000

        private const val OUTPUT_SAMPLE_RATE =
            24000

        private const val CHANNEL_COUNT =
            1

        private const val CHUNK_SIZE =
            3200

        private const val OUTPUT_POLL_MS =
            100L

        private const val DIAGNOSTIC_MS =
            5000L
    }

    // ========================================================
    // CONTROLE
    // ========================================================

    private val running =
        AtomicBoolean(false)

    private val captureRunning =
        AtomicBoolean(false)

    private val outputRunning =
        AtomicBoolean(false)

    // ========================================================
    // ANDROID
    // ========================================================

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

    // ========================================================
    // THREADS
    // ========================================================

    private var captureThread:
            Thread? = null

    private var sendThread:
            Thread? = null

    private var outputPollThread:
            Thread? = null

    private var outputPlayThread:
            Thread? = null

    private var diagnosticThread:
            Thread? = null

    // ========================================================
    // FILAS
    // ========================================================

    private val audioQueue =
        LinkedBlockingQueue<ByteArray>(80)

    private val outputQueue =
        LinkedBlockingQueue<ByteArray>(100)

    // ========================================================
    // SESSÃO
    // ========================================================

    private var jobId:
            String? = null

    private var lastOutputSeq =
        0L

    // ========================================================
    // DIAGNÓSTICO
    // ========================================================

    @Volatile
    private var readCount =
        0L

    @Volatile
    private var capturedBytes =
        0L

    @Volatile
    private var sentChunks =
        0L

    @Volatile
    private var sentBytes =
        0L

    @Volatile
    private var outputChunksReceived =
        0L

    @Volatile
    private var outputBytesReceived =
        0L

    @Volatile
    private var playedOutputBytes =
        0L

    @Volatile
    private var outputChunksPlayed =
        0L

    @Volatile
    private var lastReadBytes =
        0

    @Volatile
    private var lastOutputBytes =
        0

    @Volatile
    private var lastHttpError =
        ""

    // ========================================================
    // MEDIA PROJECTION CALLBACK
    // ========================================================

    private val projectionCallback =
        object : MediaProjection.Callback() {

            override fun onStop() {

                Log.d(
                    TAG,
                    "MediaProjection foi encerrada pelo Android"
                )

                stopEverything(
                    notifyBackend = true
                )
            }
        }

    // ========================================================
    // ON CREATE
    // ========================================================

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

    // ========================================================
    // START COMMAND
    // ========================================================

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        if (intent == null) {

            return START_NOT_STICKY
        }

        when (
            intent.action
        ) {

            ACTION_START -> {

                val resultCode =
                    intent.getIntExtra(
                        EXTRA_RESULT_CODE,
                        -1
                    )

                val resultData =
                    intent.getParcelableExtra<Intent>(
                        EXTRA_RESULT_DATA
                    )

                val receivedJobId =
                    intent.getStringExtra(
                        EXTRA_JOB_ID
                    )

                if (
                    resultCode == -1 ||
                    resultData == null ||
                    receivedJobId.isNullOrBlank()
                ) {

                    Log.e(
                        TAG,
                        "Dados inválidos para iniciar captura"
                    )

                    stopSelf()

                    return START_NOT_STICKY
                }

                jobId =
                    receivedJobId

                startForegroundServiceNotification()

                if (
                    !running.get()
                ) {

                    startCapture(
                        resultCode,
                        resultData,
                        receivedJobId
                    )
                }
            }

            ACTION_STOP -> {

                Log.d(
                    TAG,
                    "Recebido comando STOP"
                )

                stopEverything(
                    notifyBackend = true
                )
            }
        }

        return START_NOT_STICKY
    }

    // ========================================================
    // NOTIFICAÇÃO
    // ========================================================

    private fun createNotificationChannel() {

        if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.O
        ) {

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

    private fun startForegroundServiceNotification() {

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

    private fun createNotification():
            Notification {

        return NotificationCompat
            .Builder(
                this,
                CHANNEL_ID
            )
            .setContentTitle(
                "SI Tradutor Live"
            )
            .setContentText(
                "Traduzindo o áudio da tela..."
            )
            .setSmallIcon(
                R.drawable.si_logo
            )
            .setOngoing(true)
            .setCategory(
                NotificationCompat.CATEGORY_SERVICE
            )
            .build()
    }

    // ========================================================
    // INICIAR CAPTURA
    // ========================================================

    private fun startCapture(
        resultCode: Int,
        resultData: Intent,
        currentJobId: String
    ) {

        if (
            Build.VERSION.SDK_INT <
            Build.VERSION_CODES.Q
        ) {

            Log.e(
                TAG,
                "AudioPlaybackCapture requer Android 10 ou superior"
            )

            stopSelf()

            return
        }

        try {

            running.set(true)

            lastOutputSeq = 0L

            Log.d(
                TAG,
                "Iniciando captura. jobId=$currentJobId"
            )

            val projectionManager =
                getSystemService(
                    Context.MEDIA_PROJECTION_SERVICE
                ) as android.media.projection.MediaProjectionManager

            val projection =
                projectionManager.getMediaProjection(
                    resultCode,
                    resultData
                )
                    ?: throw Exception(
                        "MediaProjection não disponível"
                    )

            mediaProjection =
                projection

            projection.registerCallback(
                projectionCallback,
                null
            )

            setupAudioCapture(
                projection
            )

            setupAudioOutput()

            startCaptureThread()

            startSendThread()

            startOutputPollThread()

            startOutputPlayThread()

            startDiagnosticThread()

            Log.d(
                TAG,
                "CAPTURA INICIADA COM SUCESSO"
            )

        } catch (error: Exception) {

            Log.e(
                TAG,
                "Erro iniciando captura",
                error
            )

            lastHttpError =
                error.message ?: "erro"

            stopEverything(
                notifyBackend = true
            )
        }
    }

    // ========================================================
    // CONFIGURAR CAPTURA
    // ========================================================

    private fun setupAudioCapture(
        projection: MediaProjection
    ) {

        val captureConfig =
            AudioPlaybackCaptureConfiguration
                .Builder(
                    projection
                )
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

        val audioFormat =
            AudioFormat.Builder()
                .setEncoding(
                    AudioFormat.ENCODING_PCM_16BIT
                )
                .setSampleRate(
                    SAMPLE_RATE
                )
                .setChannelMask(
                    AudioFormat.CHANNEL_IN_MONO
                )
                .build()

        val minBuffer =
            AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )

        val bufferSize =
            maxOf(
                minBuffer * 2,
                CHUNK_SIZE * 4
            )

        Log.d(
            TAG,
            "AudioRecord buffer=$bufferSize"
        )

        val record =
            AudioRecord.Builder()
                .setAudioFormat(
                    audioFormat
                )
                .setBufferSizeInBytes(
                    bufferSize
                )
                .setAudioPlaybackCaptureConfig(
                    captureConfig
                )
                .build()

        if (
            record.state !=
            AudioRecord.STATE_INITIALIZED
        ) {

            record.release()

            throw Exception(
                "AudioRecord não foi inicializado"
            )
        }

        audioRecord =
            record
    }

    // ========================================================
    // CONFIGURAR SAÍDA
    // ========================================================

    private fun setupAudioOutput() {

        val outputFormat =
            AudioFormat.Builder()
                .setEncoding(
                    AudioFormat.ENCODING_PCM_16BIT
                )
                .setSampleRate(
                    OUTPUT_SAMPLE_RATE
                )
                .setChannelMask(
                    AudioFormat.CHANNEL_OUT_MONO
                )
                .build()

        val minBuffer =
            AudioTrack.getMinBufferSize(
                OUTPUT_SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )

        val bufferSize =
            maxOf(
                minBuffer * 2,
                24000
            )

        /*
         * IMPORTANTE:
         *
         * Não usamos USAGE_MEDIA aqui.
         *
         * O SI está capturando USAGE_MEDIA dos outros
         * aplicativos. Se o próprio SI também usasse
         * USAGE_MEDIA, o áudio traduzido poderia voltar
         * para o AudioRecord.
         *
         * USAGE_ASSISTANCE_ACCESSIBILITY evita esse
         * ciclo de captura.
         */

        val attributes =
            AudioAttributes.Builder()
                .setUsage(
                    AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY
                )
                .setContentType(
                    AudioAttributes.CONTENT_TYPE_SPEECH
                )
                .build()

        val track =
            AudioTrack.Builder()
                .setAudioAttributes(
                    attributes
                )
                .setAudioFormat(
                    outputFormat
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

            track.release()

            throw Exception(
                "AudioTrack não foi inicializado"
            )
        }

        track.setVolume(
            1.0f
        )

        audioTrack =
            track

        val manager =
            audioManager

        try {

            val devices =
                manager?.getDevices(
                    AudioManager.GET_DEVICES_OUTPUTS
                )

            val speaker =
                devices?.firstOrNull {
                    it.type ==
                        android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                }

            if (
                speaker != null
            ) {

                val result =
                    track.setPreferredDevice(
                        speaker
                    )

                Log.d(
                    TAG,
                    "Rota para alto-falante: $result"
                )
            }

        } catch (error: Exception) {

            Log.w(
                TAG,
                "Não foi possível definir alto-falante",
                error
            )
        }

        requestAudioFocus()

        track.play()

        outputRunning.set(true)

        Log.d(
            TAG,
            "AudioTrack iniciado"
        )
    }

    // ========================================================
    // AUDIO FOCUS
    // ========================================================

    private fun requestAudioFocus() {

        val manager =
            audioManager
                ?: return

        try {

            if (
                Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.O
            ) {

                val attributes =
                    AudioAttributes.Builder()
                        .setUsage(
                            AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY
                        )
                        .setContentType(
                            AudioAttributes.CONTENT_TYPE_SPEECH
                        )
                        .build()

                val request =
                    AudioFocusRequest.Builder(
                        AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
                    )
                        .setAudioAttributes(
                            attributes
                        )
                        .setAcceptsDelayedFocusGain(
                            false
                        )
                        .setWillPauseWhenDucked(
                            false
                        )
                        .build()

                audioFocusRequest =
                    request

                manager.requestAudioFocus(
                    request
                )

            } else {

                @Suppress("DEPRECATION")
                manager.requestAudioFocus(
                    null,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
                )
            }

        } catch (error: Exception) {

            Log.w(
                TAG,
                "Falha no áudio focus",
                error
            )
        }
    }

    // ========================================================
    // THREAD DE CAPTURA
    // ========================================================

    private fun startCaptureThread() {

        captureRunning.set(true)

        captureThread =
            Thread {

                val record =
                    audioRecord
                        ?: return@Thread

                val buffer =
                    ByteArray(
                        CHUNK_SIZE
                    )

                try {

                    record.startRecording()

                    Log.d(
                        TAG,
                        "AudioRecord começou a gravar"
                    )

                    while (
                        running.get() &&
                        captureRunning.get()
                    ) {

                        val bytes =
                            record.read(
                                buffer,
                                0,
                                buffer.size,
                                AudioRecord.READ_BLOCKING
                            )

                        if (
                            bytes > 0
                        ) {

                            val chunk =
                                buffer.copyOf(
                                    bytes
                                )

                            readCount++

                            capturedBytes +=
                                bytes

                            lastReadBytes =
                                bytes

                            /*
                             * Se a fila estiver cheia,
                             * remove o bloco mais antigo
                             * para manter o áudio ao vivo.
                             */

                            if (
                                !audioQueue.offer(
                                    chunk
                                )
                            ) {

                                audioQueue.poll()

                                audioQueue.offer(
                                    chunk
                                )
                            }
                        }

                        else if (
                            bytes <
                            0
                        ) {

                            Log.e(
                                TAG,
                                "AudioRecord erro: $bytes"
                            )

                            break
                        }
                    }

                } catch (error: Exception) {

                    Log.e(
                        TAG,
                        "Erro na captura",
                        error
                    )

                    lastHttpError =
                        error.message ?: "capture error"

                } finally {

                    try {

                        record.stop()

                    } catch (_: Exception) {
                    }
                }

            }.apply {
                name =
                    "SI-Capture"
                start()
            }
    }

    // ========================================================
    // THREAD DE ENVIO
    // ========================================================

    private fun startSendThread() {

        sendThread =
            Thread {

                while (
                    running.get()
                ) {

                    try {

                        val chunk =
                            audioQueue.take()

                        val currentJobId =
                            jobId
                                ?: continue

                        sendAudioChunk(
                            currentJobId,
                            chunk
                        )

                    } catch (
                        interrupted: InterruptedException
                    ) {

                        break

                    } catch (error: Exception) {

                        Log.e(
                            TAG,
                            "Erro enviando áudio",
                            error
                        )

                        lastHttpError =
                            error.message
                                ?: "send error"
                    }
                }

            }.apply {
                name =
                    "SI-Send"
                start()
            }
    }

    // ========================================================
    // ENVIAR CHUNK
    // ========================================================

    private fun sendAudioChunk(
        currentJobId: String,
        pcm: ByteArray
    ) {

        val base64 =
            Base64.encodeToString(
                pcm,
                Base64.NO_WRAP
            )

        val body =
            JSONObject().apply {

                put(
                    "jobId",
                    currentJobId
                )

                put(
                    "audio",
                    base64
                )
            }

        val response =
            postJson(
                "/api/audio/chunk",
                body.toString()
            )

        if (
            response.first
        ) {

            sentChunks++

            sentBytes +=
                pcm.size

        } else {

            lastHttpError =
                response.second
        }
    }

    // ========================================================
    // POLLING DA SAÍDA
    // ========================================================

    private fun startOutputPollThread() {

        outputPollThread =
            Thread {

                while (
                    running.get()
                ) {

                    try {

                        val currentJobId =
                            jobId

                        if (
                            currentJobId != null
                        ) {

                            pollOutput(
                                currentJobId
                            )
                        }

                        Thread.sleep(
                            OUTPUT_POLL_MS
                        )

                    } catch (
                        interrupted: InterruptedException
                    ) {

                        break

                    } catch (error: Exception) {

                        Log.e(
                            TAG,
                            "Erro no polling de saída",
                            error
                        )

                        Thread.sleep(
                            300L
                        )
                    }
                }

            }.apply {
                name =
                    "SI-Output-Poll"
                start()
            }
    }

    // ========================================================
    // BUSCAR SAÍDA
    // ========================================================

    private fun pollOutput(
        currentJobId: String
    ) {

        val url =
            "$BACKEND_URL/api/audio/output/$currentJobId" +
                "?after=$lastOutputSeq&limit=20"

        val result =
            getJson(
                url
            )

        if (
            !result.first
        ) {

            lastHttpError =
                result.second

            return
        }

        val json =
            try {
                JSONObject(
                    result.second
                )
            } catch (
                error: Exception
            ) {
                return
            }

        if (
            !json.optBoolean(
                "ok",
                false
            )
        ) {
            return
        }

        val chunks =
            json.optJSONArray(
                "chunks"
            )
                ?: return

        var highestSeq =
            lastOutputSeq

        for (
            index in 0 until chunks.length()
        ) {

            val item =
                chunks.optJSONObject(
                    index
                )
                    ?: continue

            val seq =
                item.optLong(
                    "seq",
                    0L
                )

            val base64 =
                item.optString(
                    "audio",
                    ""
                )

            if (
                seq <=
                lastOutputSeq
            ) {
                continue
            }

            if (
                base64.isBlank()
            ) {
                continue
            }

            try {

                val pcm =
                    Base64.decode(
                        base64,
                        Base64.NO_WRAP
                    )

                if (
                    pcm.isNotEmpty()
                ) {

                    if (
                        outputQueue.offer(
                            pcm
                        )
                    ) {

                        outputChunksReceived++

                        outputBytesReceived +=
                            pcm.size

                        lastOutputBytes =
                            pcm.size

                        if (
                            seq >
                            highestSeq
                        ) {

                            highestSeq =
                                seq
                        }
                    }
                }

            } catch (error: Exception) {

                Log.e(
                    TAG,
                    "Erro decodificando áudio",
                    error
                )
            }
        }

        /*
         * Só avançamos o cursor depois de receber
         * corretamente os blocos.
         */

        if (
            highestSeq >
            lastOutputSeq
        ) {

            lastOutputSeq =
                highestSeq
        }
    }

    // ========================================================
    // THREAD DE REPRODUÇÃO
    // ========================================================

    private fun startOutputPlayThread() {

        outputPlayThread =
            Thread {

                while (
                    running.get()
                ) {

                    try {

                        val pcm =
                            outputQueue.take()

                        playPcm(
                            pcm
                        )

                    } catch (
                        interrupted: InterruptedException
                    ) {

                        break

                    } catch (error: Exception) {

                        Log.e(
                            TAG,
                            "Erro reproduzindo áudio",
                            error
                        )

                        lastHttpError =
                            error.message
                                ?: "play error"
                    }
                }

            }.apply {
                name =
                    "SI-Output-Play"
                start()
            }
    }

    // ========================================================
    // REPRODUZIR PCM
    // ========================================================

    private fun playPcm(
        pcm: ByteArray
    ) {

        val track =
            audioTrack
                ?: return

        if (
            track.playState !=
            AudioTrack.PLAYSTATE_PLAYING
        ) {

            track.play()
        }

        var offset =
            0

        while (
            offset <
            pcm.size &&
            running.get()
        ) {

            val written =
                track.write(
                    pcm,
                    offset,
                    pcm.size - offset,
                    AudioTrack.WRITE_BLOCKING
                )

            if (
                written > 0
            ) {

                offset +=
                    written

                playedOutputBytes +=
                    written

            } else {

                Log.e(
                    TAG,
                    "AudioTrack.write retornou $written"
                )

                break
            }
        }

        outputChunksPlayed++
    }

    // ========================================================
    // DIAGNÓSTICO
    // ========================================================

    private fun startDiagnosticThread() {

        diagnosticThread =
            Thread {

                while (
                    running.get()
                ) {

                    try {

                        sendDiagnostic()

                        Thread.sleep(
                            DIAGNOSTIC_MS
                        )

                    } catch (
                        interrupted: InterruptedException
                    ) {

                        break

                    } catch (error: Exception) {

                        Log.w(
                            TAG,
                            "Erro diagnóstico",
                            error
                        )
                    }
                }

            }.apply {
                name =
                    "SI-Diagnostic"
                start()
            }
    }

    private fun sendDiagnostic() {

        val currentJobId =
            jobId
                ?: return

        val manager =
            audioManager

        var volume =
            0

        var maxVolume =
            0

        try {

            volume =
                manager?.getStreamVolume(
                    AudioManager.STREAM_MUSIC
                ) ?: 0

            maxVolume =
                manager?.getStreamMaxVolume(
                    AudioManager.STREAM_MUSIC
                ) ?: 0

        } catch (_: Exception) {
        }

        val track =
            audioTrack

        val diagnostic =
            JSONObject().apply {

                put(
                    "jobId",
                    currentJobId
                )

                put(
                    "recording",
                    running.get()
                )

                put(
                    "captureStarted",
                    captureRunning.get()
                )

                put(
                    "readCount",
                    readCount
                )

                put(
                    "lastRead",
                    lastReadBytes
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
                    "sentChunks",
                    sentChunks
                )

                put(
                    "sentBytes",
                    sentBytes
                )

                put(
                    "outputQueueSize",
                    outputQueue.size
                )

                put(
                    "receivedOutputBytes",
                    outputBytesReceived
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

                put(
                    "lastOutputBytes",
                    lastOutputBytes
                )

                put(
                    "lastOutputSeq",
                    lastOutputSeq
                )

                put(
                    "audioTrackReady",
                    track?.state ==
                        AudioTrack.STATE_INITIALIZED
                )

                put(
                    "audioTrackPlaying",
                    track?.playState ==
                        AudioTrack.PLAYSTATE_PLAYING
                )

                put(
                    "outputPlaying",
                    outputRunning.get()
                )

                put(
                    "musicVolume",
                    volume
                )

                put(
                    "musicMaxVolume",
                    maxVolume
                )

                put(
                    "lastHttpError",
                    lastHttpError
                )
            }

        postJson(
            "/api/audio/diagnostic",
            diagnostic.toString()
        )
    }

    // ========================================================
    // POST JSON
    // ========================================================

    private fun postJson(
        endpoint: String,
        body: String
    ): Pair<Boolean, String> {

        var connection:
                HttpURLConnection? =
            null

        return try {

            val url =
                URL(
                    "$BACKEND_URL$endpoint"
                )

            connection =
                url.openConnection()
                    as HttpURLConnection

            connection.requestMethod =
                "POST"

            connection.connectTimeout =
                10000

            connection.readTimeout =
                15000

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

            OutputStreamWriter(
                connection.outputStream,
                Charsets.UTF_8
            ).use { writer ->

                writer.write(
                    body
                )

                writer.flush()
            }

            val code =
                connection.responseCode

            val stream =
                if (
                    code in 200..299
                ) {
                    connection.inputStream
                } else {
                    connection.errorStream
                }

            val response =
                if (
                    stream != null
                ) {

                    BufferedReader(
                        InputStreamReader(
                            stream,
                            Charsets.UTF_8
                        )
                    ).use {
                        it.readText()
                    }

                } else {
                    ""
                }

            Pair(
                code in 200..299,
                response
            )

        } catch (error: Exception) {

            Pair(
                false,
                error.message
                    ?: "network error"
            )

        } finally {

            connection?.disconnect()
        }
    }

    // ========================================================
    // GET JSON
    // ========================================================

    private fun getJson(
        fullUrl: String
    ): Pair<Boolean, String> {

        var connection:
                HttpURLConnection? =
            null

        return try {

            val url =
                URL(
                    fullUrl
                )

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
                if (
                    code in 200..299
                ) {
                    connection.inputStream
                } else {
                    connection.errorStream
                }

            val response =
                if (
                    stream != null
                ) {

                    BufferedReader(
                        InputStreamReader(
                            stream,
                            Charsets.UTF_8
                        )
                    ).use {
                        it.readText()
                    }

                } else {
                    ""
                }

            Pair(
                code in 200..299,
                response
            )

        } catch (error: Exception) {

            Pair(
                false,
                error.message
                    ?: "network error"
            )

        } finally {

            connection?.disconnect()
        }
    }

    // ========================================================
    // PARAR TUDO
    // ========================================================

    private fun stopEverything(
        notifyBackend: Boolean
    ) {

        if (
            !running.getAndSet(false)
        ) {

            stopSelf()

            return
        }

        Log.d(
            TAG,
            "Parando SI Audio Service"
        )

        captureRunning.set(false)
        outputRunning.set(false)

        if (
            notifyBackend
        ) {

            val currentJobId =
                jobId

            if (
                currentJobId != null
            ) {

                Thread {

                    try {

                        val body =
                            JSONObject().apply {
                                put(
                                    "jobId",
                                    currentJobId
                                )
                            }

                        postJson(
                            "/api/audio/stop",
                            body.toString()
                        )

                    } catch (_: Exception) {
                    }

                }.start()
            }
        }

        try {
            audioRecord?.stop()
        } catch (_: Exception) {
        }

        try {
            audioRecord?.release()
        } catch (_: Exception) {
        }

        audioRecord =
            null

        try {
            audioTrack?.stop()
        } catch (_: Exception) {
        }

        try {
            audioTrack?.flush()
        } catch (_: Exception) {
        }

        try {
            audioTrack?.release()
        } catch (_: Exception) {
        }

        audioTrack =
            null

        try {

            val projection =
                mediaProjection

            if (
                projection != null
            ) {

                projection.unregisterCallback(
                    projectionCallback
                )

                projection.stop()
            }

        } catch (_: Exception) {
        }

        mediaProjection =
            null

        abandonAudioFocus()

        audioQueue.clear()
        outputQueue.clear()

        captureThread?.interrupt()
        sendThread?.interrupt()
        outputPollThread?.interrupt()
        outputPlayThread?.interrupt()
        diagnosticThread?.interrupt()

        captureThread =
            null

        sendThread =
            null

        outputPollThread =
            null

        outputPlayThread =
            null

        diagnosticThread =
            null

        Log.d(
            TAG,
            "SI Audio Service parado"
        )

        stopForeground(
            STOP_FOREGROUND_REMOVE
        )

        stopSelf()
    }

    // ========================================================
    // ABANDONAR AUDIO FOCUS
    // ========================================================

    private fun abandonAudioFocus() {

        val manager =
            audioManager
                ?: return

        try {

            if (
                Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.O
            ) {

                val request =
                    audioFocusRequest

                if (
                    request != null
                ) {

                    manager.abandonAudioFocusRequest(
                        request
                    )
                }

            } else {

                @Suppress("DEPRECATION")
                manager.abandonAudioFocus(
                    null
                )
            }

        } catch (_: Exception) {
        }

        audioFocusRequest =
            null
    }

    // ========================================================
    // BINDER
    // ========================================================

    override fun onBind(
        intent: Intent?
    ): IBinder? {

        return null
    }

    // ========================================================
    // DESTROY
    // ========================================================

    override fun onDestroy() {

        Log.d(
            TAG,
            "onDestroy"
        )

        if (
            running.get()
        ) {

            stopEverything(
                notifyBackend = false
            )
        }

        super.onDestroy()
    }
}
