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
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.Base64
import android.util.Log
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

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

        private const val TAG = "SI_AUDIO"

        private const val BACKEND_URL =
            "https://si-u2ul.onrender.com"

        private const val CHANNEL_ID =
            "si_audio_channel"

        private const val NOTIFICATION_ID = 1001

        private const val SAMPLE_RATE = 16000

        private const val OUTPUT_SAMPLE_RATE = 24000

        private const val CHUNK_SIZE = 3200

        private const val OUTPUT_POLL_MS = 100L

        private const val DIAGNOSTIC_MS = 5000L
    }

    private val running =
        AtomicBoolean(false)

    private val captureRunning =
        AtomicBoolean(false)

    private val outputRunning =
        AtomicBoolean(false)

    private var mediaProjection: MediaProjection? = null

    private var audioRecord: AudioRecord? = null

    private var audioTrack: AudioTrack? = null

    private var audioManager: AudioManager? = null

    private var audioFocusRequest: AudioFocusRequest? = null

    private var captureThread: Thread? = null

    private var sendThread: Thread? = null

    private var outputPollThread: Thread? = null

    private var outputPlayThread: Thread? = null

    private var diagnosticThread: Thread? = null

    private val audioQueue =
        LinkedBlockingQueue<ByteArray>(100)

    private val outputQueue =
        LinkedBlockingQueue<ByteArray>(100)

    private var jobId: String? = null

    private var lastOutputSeq = 0L

    @Volatile
    private var readCount = 0L

    @Volatile
    private var capturedBytes = 0L

    @Volatile
    private var sentChunks = 0L

    @Volatile
    private var sentBytes = 0L

    @Volatile
    private var outputChunksReceived = 0L

    @Volatile
    private var outputBytesReceived = 0L

    @Volatile
    private var playedOutputBytes = 0L

    @Volatile
    private var outputChunksPlayed = 0L

    @Volatile
    private var lastReadBytes = 0

    @Volatile
    private var lastOutputBytes = 0

    @Volatile
    private var lastHttpError = ""

    @Volatile
    private var lastCaptureError = ""

    private val projectionCallback =
        object : MediaProjection.Callback() {

            override fun onStop() {

                Log.d(
                    TAG,
                    "MediaProjection encerrada"
                )

                stopEverything(true)
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

        when (intent.action) {

            ACTION_START -> {

                val resultCode =
                    intent.getIntExtra(
                        EXTRA_RESULT_CODE,
                        -1
                    )

                val resultData =
                    if (
                        Build.VERSION.SDK_INT >=
                        Build.VERSION_CODES.TIRAMISU
                    ) {

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

                val receivedJobId =
                    intent.getStringExtra(
                        EXTRA_JOB_ID
                    )

                if (
                    resultCode != -1 &&
                    resultData != null &&
                    !receivedJobId.isNullOrBlank()
                ) {

                    jobId = receivedJobId

                    startForegroundServiceNotification()

                    if (!running.get()) {

                        startCapture(
                            resultCode,
                            resultData,
                            receivedJobId
                        )
                    }

                } else {

                    Log.e(
                        TAG,
                        "Dados inválidos para iniciar captura"
                    )

                    stopSelf()
                }
            }

            ACTION_STOP -> {

                Log.d(
                    TAG,
                    "STOP recebido"
                )

                stopEverything(true)
            }
        }

        return START_NOT_STICKY
    }

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

    private fun createNotification(): Notification {

        return NotificationCompat
            .Builder(
                this,
                CHANNEL_ID
            )
            .setContentTitle(
                "SI Tradutor Live"
            )
            .setContentText(
                "Capturando e traduzindo o áudio..."
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

    private fun startCapture(
        resultCode: Int,
        resultData: Intent,
        currentJobId: String
    ) {

        if (
            Build.VERSION.SDK_INT <
            Build.VERSION_CODES.Q
        ) {

            lastCaptureError =
                "Android 10 ou superior é necessário"

            Log.e(
                TAG,
                lastCaptureError
            )

            stopSelf()

            return
        }

        try {

            running.set(true)

            captureRunning.set(false)

            outputRunning.set(false)

            lastOutputSeq = 0L

            readCount = 0L
            capturedBytes = 0L
            sentChunks = 0L
            sentBytes = 0L
            outputChunksReceived = 0L
            outputBytesReceived = 0L
            playedOutputBytes = 0L
            outputChunksPlayed = 0L

            lastCaptureError = ""
            lastHttpError = ""

            audioQueue.clear()
            outputQueue.clear()

            Log.d(
                TAG,
                "Iniciando MediaProjection"
            )

            val projectionManager =
                getSystemService(
                    Context.MEDIA_PROJECTION_SERVICE
                ) as MediaProjectionManager

            val projection =
                projectionManager.getMediaProjection(
                    resultCode,
                    resultData
                )
                    ?: throw Exception(
                        "MediaProjection retornou null"
                    )

            mediaProjection =
                projection

            projection.registerCallback(
                projectionCallback,
                null
            )

            Log.d(
                TAG,
                "MediaProjection OK"
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
                "CAPTURA TOTAL INICIADA"
            )

        } catch (error: Exception) {

            lastCaptureError =
                error.message ?: "erro desconhecido"

            Log.e(
                TAG,
                "Erro iniciando captura",
                error
            )

            stopEverything(true)
        }
    }

    private fun setupAudioCapture(
        projection: MediaProjection
    ) {

        Log.d(
            TAG,
            "Configurando AudioPlaybackCapture"
        )

        val captureConfig =
            AudioPlaybackCaptureConfiguration
                .Builder(projection)
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

        if (
            minBuffer <= 0
        ) {

            throw Exception(
                "AudioRecord.getMinBufferSize falhou: $minBuffer"
            )
        }

        val bufferSize =
            maxOf(
                minBuffer * 2,
                CHUNK_SIZE * 8
            )

        Log.d(
            TAG,
            "minBuffer=$minBuffer bufferSize=$bufferSize"
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
                "AudioRecord não inicializado"
            )
        }

        audioRecord =
            record

        Log.d(
            TAG,
            "AudioRecord INITIALIZED"
        )
    }

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

        if (
            minBuffer <= 0
        ) {

            throw Exception(
                "AudioTrack.getMinBufferSize falhou"
            )
        }

        val bufferSize =
            maxOf(
                minBuffer * 2,
                24000
            )

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
                "AudioTrack não inicializado"
            )
        }

        track.setVolume(
            1.0f
        )

        try {

            val devices =
                audioManager?.getDevices(
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

                track.setPreferredDevice(
                    speaker
                )
            }

        } catch (error: Exception) {

            Log.w(
                TAG,
                "Não foi possível selecionar alto-falante",
                error
            )
        }

        audioTrack =
            track

        requestAudioFocus()

        track.play()

        outputRunning.set(true)

        Log.d(
            TAG,
            "AudioTrack PLAYING"
        )
    }

    private fun requestAudioFocus() {

        val manager =
            audioManager ?: return

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
                "Audio focus falhou",
                error
            )
        }
    }

    private fun startCaptureThread() {

        captureRunning.set(true)

        captureThread =
            Thread {

                val record =
                    audioRecord

                if (
                    record == null
                ) {

                    lastCaptureError =
                        "AudioRecord null"

                    return@Thread
                }

                val buffer =
                    ByteArray(
                        CHUNK_SIZE
                    )

                try {

                    Log.d(
                        TAG,
                        "Chamando startRecording()"
                    )

                    record.startRecording()

                    if (
                        record.recordingState !=
                        AudioRecord.RECORDSTATE_RECORDING
                    ) {

                        throw Exception(
                            "AudioRecord não entrou em RECORDING"
                        )
                    }

                    Log.d(
                        TAG,
                        "AudioRecord está RECORDING"
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

                            if (
                                readCount <= 5 ||
                                readCount % 100 == 0L
                            ) {

                                Log.d(
                                    TAG,
                                    "ÁUDIO CAPTURADO: " +
                                        "read=$readCount " +
                                        "bytes=$bytes"
                                )
                            }

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

                        } else if (
                            bytes < 0
                        ) {

                            lastCaptureError =
                                "AudioRecord.read=$bytes"

                            Log.e(
                                TAG,
                                lastCaptureError
                            )

                            break
                        }
                    }

                } catch (error: Exception) {

                    lastCaptureError =
                        error.message
                            ?: "capture error"

                    Log.e(
                        TAG,
                        "ERRO NA CAPTURA",
                        error
                    )

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
                        error: InterruptedException
                    ) {

                        break

                    } catch (
                        error: Exception
                    ) {

                        lastHttpError =
                            error.message
                                ?: "send error"

                        Log.e(
                            TAG,
                            "ERRO ENVIANDO ÁUDIO",
                            error
                        )
                    }
                }

            }.apply {

                name =
                    "SI-Send"

                start()
            }
    }

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

            if (
                sentChunks <= 5 ||
                sentChunks % 100 == 0L
            ) {

                Log.d(
                    TAG,
                    "ÁUDIO ENVIADO: " +
                        "chunks=$sentChunks " +
                        "bytes=$sentBytes"
                )
            }

        } else {

            lastHttpError =
                response.second

            Log.e(
                TAG,
                "ERRO HTTP /api/audio/chunk: " +
                    response.second
            )
        }
    }

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
                        error: InterruptedException
                    ) {

                        break

                    } catch (
                        error: Exception
                    ) {

                        Log.e(
                            TAG,
                            "Erro polling saída",
                            error
                        )

                        try {
                            Thread.sleep(300L)
                        } catch (_: Exception) {
                        }
                    }
                }

            }.apply {

                name =
                    "SI-Output-Poll"

                start()
            }
    }

    private fun pollOutput(
        currentJobId: String
    ) {

        val url =
            "$BACKEND_URL/api/audio/output/" +
                "$currentJobId" +
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
                JSONObject(result.second)
            } catch (_: Exception) {
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

            val encoded =
                item.optString(
                    "audio",
                    ""
                )

            if (
                seq <= lastOutputSeq
            ) {
                continue
            }

            if (
                encoded.isBlank()
            ) {
                continue
            }

            try {

                val pcm =
                    Base64.decode(
                        encoded,
                        Base64.NO_WRAP
                    )

                if (
                    pcm.isEmpty()
                ) {
                    continue
                }

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
                        seq > highestSeq
                    ) {

                        highestSeq =
                            seq
                    }

                    if (
                        outputChunksReceived <= 5 ||
                        outputChunksReceived % 50 == 0L
                    ) {

                        Log.d(
                            TAG,
                            "SAÍDA RECEBIDA: " +
                                "seq=$seq " +
                                "bytes=${pcm.size}"
                        )
                    }
                }

            } catch (error: Exception) {

                Log.e(
                    TAG,
                    "Erro decodificando saída",
                    error
                )
            }
        }

        if (
            highestSeq >
            lastOutputSeq
        ) {

            lastOutputSeq =
                highestSeq
        }
    }

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
                        error: InterruptedException
                    ) {

                        break

                    } catch (
                        error: Exception
                    ) {

                        lastHttpError =
                            error.message
                                ?: "play error"

                        Log.e(
                            TAG,
                            "ERRO REPRODUZINDO",
                            error
                        )
                    }
                }

            }.apply {

                name =
                    "SI-Output-Play"

                start()
            }
    }

    private fun playPcm(
        pcm: ByteArray
    ) {

        val track =
            audioTrack
                ?: return

        try {

            if (
                track.playState !=
                AudioTrack.PLAYSTATE_PLAYING
            ) {

                track.play()
            }

            var offset =
                0

            while (
                offset < pcm.size &&
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
                        "AudioTrack.write=$written"
                    )

                    break
                }
            }

            outputChunksPlayed++

            if (
                outputChunksPlayed <= 5 ||
                outputChunksPlayed % 50 == 0L
            ) {

                Log.d(
                    TAG,
                    "ÁUDIO REPRODUZIDO: " +
                        "chunks=$outputChunksPlayed " +
                        "bytes=$playedOutputBytes"
                )
            }

        } catch (error: Exception) {

            lastHttpError =
                error.message
                    ?: "play error"

            Log.e(
                TAG,
                "Erro AudioTrack",
                error
            )
        }
    }

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
                        error: InterruptedException
                    ) {

                        break

                    } catch (
                        error: Exception
                    ) {

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

        val record =
            audioRecord

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
                    "recordingState",
                    record?.recordingState ?: -1
                )

                put(
                    "audioRecordState",
                    record?.state ?: -1
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
                    "lastCaptureError",
                    lastCaptureError
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

    private fun postJson(
        endpoint: String,
        body: String
    ): Pair<Boolean, String> {

        var connection:
            HttpURLConnection? = null

        return try {

            val url =
                URL(
                    BACKEND_URL + endpoint
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

                writer.write(body)
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
                error.message ?: "network error"
            )

        } finally {

            connection?.disconnect()
        }
    }

    private fun getJson(
        fullUrl: String
    ): Pair<Boolean, String> {

        var connection:
            HttpURLConnection? = null

        return try {

            val url =
                URL(fullUrl)

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
                error.message ?: "network error"
            )

        } finally {

            connection?.disconnect()
        }
    }

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
            "Parando AudioCaptureService"
        )

        captureRunning.set(false)

        outputRunning.set(false)

        val currentJobId =
            jobId

        if (
            notifyBackend &&
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

            mediaProjection?.unregisterCallback(
                projectionCallback
            )

        } catch (_: Exception) {
        }

        try {

            mediaProjection?.stop()

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

        captureThread = null
        sendThread = null
        outputPollThread = null
        outputPlayThread = null
        diagnosticThread = null

        stopForeground(
            STOP_FOREGROUND_REMOVE
        )

        stopSelf()

        Log.d(
            TAG,
            "AudioCaptureService parado"
        )
    }

    private fun abandonAudioFocus() {

        val manager =
            audioManager
                ?: return

        try {

            if (
                Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.O
            ) {

                audioFocusRequest?.let {
                    manager.abandonAudioFocusRequest(
                        it
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

    override fun onBind(
        intent: Intent?
    ): IBinder? {

        return null
    }

    override fun onDestroy() {

        Log.d(
            TAG,
            "onDestroy"
        )

        if (
            running.get()
        ) {

            stopEverything(false)
        }

        super.onDestroy()
    }
}
