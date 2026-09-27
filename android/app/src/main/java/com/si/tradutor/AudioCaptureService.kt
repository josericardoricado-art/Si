package com.si.tradutor

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt

class AudioCaptureService : Service() {

    companion object {

        private const val TAG = "SI_AUDIO"

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
            "si_audio_capture"

        private const val NOTIFICATION_ID =
            1001

        private const val DIAG_PREFS =
            "si_diagnostic"

        private const val SAMPLE_RATE =
            16000

        private const val INPUT_CHANNEL =
            AudioFormat.CHANNEL_IN_MONO

        private const val PCM_FORMAT =
            AudioFormat.ENCODING_PCM_16BIT

        /*
         * 3200 bytes =
         * 1600 samples =
         * aproximadamente 100 ms em 16 kHz / 16 bit / mono.
         */
        private const val CHUNK_SIZE =
            3200

        private const val INPUT_QUEUE_SIZE =
            120

        private const val STATUS_INTERVAL =
            150L
    }

    // =========================================================
    // ESTADO
    // =========================================================

    @Volatile
    private var running = false

    @Volatile
    private var stopping = false

    @Volatile
    private var jobId = ""

    // =========================================================
    // MEDIA PROJECTION
    // =========================================================

    private var mediaProjection: MediaProjection? = null

    private var projectionCallback:
        MediaProjection.Callback? = null

    // =========================================================
    // AUDIO RECORD
    // =========================================================

    private var audioRecord: AudioRecord? = null

    // =========================================================
    // MEDIA PLAYER
    // =========================================================

    private var mediaPlayer: MediaPlayer? = null

    private val mediaPlayerLock =
        Any()

    // =========================================================
    // FILA DE ENTRADA
    // =========================================================

    private val inputQueue =
        LinkedBlockingQueue<ByteArray>(
            INPUT_QUEUE_SIZE
        )

    // =========================================================
    // ÁUDIOS RECEBIDOS
    // =========================================================

    private val downloadedAudioIds =
        HashSet<String>()

    private val downloadedAudioLock =
        Any()

    // =========================================================
    // THREADS
    // =========================================================

    private var captureThread: Thread? = null

    private var sendThread: Thread? = null

    private var statusThread: Thread? = null

    // =========================================================
    // DIAGNÓSTICO
    // =========================================================

    @Volatile
    private var capturedChunks = 0L

    @Volatile
    private var sentChunks = 0L

    @Volatile
    private var silentChunks = 0L

    @Volatile
    private var receivedAudio = 0L

    @Volatile
    private var piperAudioCount = 0L

    @Volatile
    private var playedAudio = 0L

    @Volatile
    private var lastRms = 0L

    @Volatile
    private var lastError: String? = null

    // =========================================================
    // OBJETO DE ÁUDIO
    // =========================================================

    private data class OutputAudio(
        val audioId: String,
        val wav: ByteArray
    )

    // =========================================================
    // DIAGNÓSTICO VISÍVEL
    // =========================================================

    private fun publicarDiagnostico(
        mensagem: String
    ) {

        try {

            getSharedPreferences(
                DIAG_PREFS,
                Context.MODE_PRIVATE
            )
                .edit()
                .putString(
                    "status",
                    mensagem
                )
                .putLong(
                    "time",
                    System.currentTimeMillis()
                )
                .putLong(
                    "captured",
                    capturedChunks
                )
                .putLong(
                    "sent",
                    sentChunks
                )
                .putLong(
                    "received",
                    piperAudioCount
                )
                .putLong(
                    "played",
                    playedAudio
                )
                .putLong(
                    "rms",
                    lastRms
                )
                .putInt(
                    "queue",
                    inputQueue.size
                )
                .putString(
                    "error",
                    lastError ?: ""
                )
                .apply()

        } catch (_: Exception) {
        }
    }

    // =========================================================
    // CREATE
    // =========================================================

    override fun onCreate() {

        super.onCreate()

        criarCanal()

        Log.d(
            TAG,
            "================================"
        )

        Log.d(
            TAG,
            "SI TRADUTOR LIVE"
        )

        Log.d(
            TAG,
            "Deepgram + DeepL + Piper"
        )

        Log.d(
            TAG,
            "Backend=$BACKEND_URL"
        )

        Log.d(
            TAG,
            "================================"
        )

        publicarDiagnostico(
            "🟢 Serviço SI preparado"
        )
    }

    // =========================================================
    // BIND
    //
    // CORREÇÃO PRINCIPAL DO ERRO:
    //
    // Class 'AudioCaptureService' ... does not implement
    // abstract base class member 'onBind'
    // =========================================================

    override fun onBind(
        intent: Intent?
    ): IBinder? {

        return null
    }

    // =========================================================
    // START COMMAND
    // =========================================================

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        val action =
            intent?.action

        Log.d(
            TAG,
            "onStartCommand=$action"
        )

        // -----------------------------------------------------
        // STOP
        // -----------------------------------------------------

        if (action == ACTION_STOP) {

            pararServico(
                true
            )

            return START_NOT_STICKY
        }

        // -----------------------------------------------------
        // SOMENTE START
        // -----------------------------------------------------

        if (action != ACTION_START) {

            return START_NOT_STICKY
        }

        // -----------------------------------------------------
        // JÁ RODANDO
        // -----------------------------------------------------

        if (running) {

            Log.d(
                TAG,
                "Serviço já está rodando"
            )

            return START_STICKY
        }

        // -----------------------------------------------------
        // JOB
        // -----------------------------------------------------

        jobId =
            intent.getStringExtra(
                EXTRA_JOB_ID
            ) ?: ""

        if (jobId.isEmpty()) {

            lastError =
                "jobId vazio"

            Log.e(
                TAG,
                "jobId vazio"
            )

            stopSelf()

            return START_NOT_STICKY
        }

        // -----------------------------------------------------
        // RESULT CODE
        // -----------------------------------------------------

        val resultCode =
            intent.getIntExtra(
                EXTRA_RESULT_CODE,
                Activity.RESULT_CANCELED
            )

        // -----------------------------------------------------
        // RESULT DATA
        // -----------------------------------------------------

        val resultData: Intent? =

            if (
                Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.TIRAMISU
            ) {

                intent.getParcelableExtra(
                    EXTRA_RESULT_DATA,
                    Intent::class.java
                )

            } else {

                @Suppress(
                    "DEPRECATION"
                )

                intent.getParcelableExtra(
                    EXTRA_RESULT_DATA
                )
            }

        if (
            resultCode !=
                Activity.RESULT_OK ||
            resultData == null
        ) {

            lastError =
                "MediaProjection inválida"

            Log.e(
                TAG,
                "MediaProjection inválida"
            )

            stopSelf()

            return START_NOT_STICKY
        }

        // -----------------------------------------------------
        // INICIAR
        // -----------------------------------------------------

        try {

            running = true

            stopping = false

            limparEstado()

            iniciarForeground()

            publicarDiagnostico(
                "🟢 Render conectado • iniciando captura"
            )

            iniciarMediaProjection(
                resultCode,
                resultData
            )

            iniciarCaptura()

            iniciarEnvio()

            iniciarStatus()

            Log.d(
                TAG,
                "SI INICIADO jobId=$jobId"
            )

        } catch (e: Exception) {

            lastError =
                e.message

            Log.e(
                TAG,
                "Erro iniciando serviço",
                e
            )

            pararServico(
                false
            )
        }

        return START_STICKY
    }

    // =========================================================
    // MEDIA PROJECTION
    // =========================================================

    private fun iniciarMediaProjection(
        resultCode: Int,
        resultData: Intent
    ) {

        val manager =
            getSystemService(
                Context.MEDIA_PROJECTION_SERVICE
            ) as MediaProjectionManager

        mediaProjection =
            manager.getMediaProjection(
                resultCode,
                resultData
            )

        if (
            mediaProjection == null
        ) {

            throw IllegalStateException(
                "MediaProjection não criada"
            )
        }

        projectionCallback =
            object :
                MediaProjection.Callback() {

                override fun onStop() {

                    Log.d(
                        TAG,
                        "MediaProjection encerrada"
                    )

                    if (running) {

                        pararServico(
                            true
                        )
                    }
                }
            }

        mediaProjection?.registerCallback(
            projectionCallback!!,
            null
        )
    }

    // =========================================================
    // CAPTURA
    // =========================================================

    private fun iniciarCaptura() {

        val projection =
            mediaProjection
                ?: throw IllegalStateException(
                    "MediaProjection não disponível"
                )

        if (
            Build.VERSION.SDK_INT <
            Build.VERSION_CODES.Q
        ) {

            throw IllegalStateException(
                "Android abaixo do 10 não suporta captura interna"
            )
        }

        val minBuffer =
            AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                INPUT_CHANNEL,
                PCM_FORMAT
            )

        if (minBuffer <= 0) {

            throw IllegalStateException(
                "AudioRecord.getMinBufferSize falhou"
            )
        }

        val bufferSize =
            maxOf(
                minBuffer * 4,
                CHUNK_SIZE * 8
            )

        val captureConfig =
            AudioPlaybackCaptureConfiguration
                .Builder(
                    projection
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

        audioRecord =
            AudioRecord.Builder()
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(
                            PCM_FORMAT
                        )
                        .setSampleRate(
                            SAMPLE_RATE
                        )
                        .setChannelMask(
                            INPUT_CHANNEL
                        )
                        .build()
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

            throw IllegalStateException(
                "AudioRecord não inicializado"
            )
        }

        captureThread =
            Thread {

                val buffer =
                    ByteArray(
                        CHUNK_SIZE
                    )

                try {

                    audioRecord?.startRecording()

                    Log.d(
                        TAG,
                        "CAPTURA DE ÁUDIO INICIADA"
                    )

                    publicarDiagnostico(
                        "🎤 Captura de áudio iniciada"
                    )

                    while (
                        running &&
                        !stopping
                    ) {

                        val read =
                            audioRecord?.read(
                                buffer,
                                0,
                                buffer.size,
                                AudioRecord.READ_BLOCKING
                            ) ?: -1

                        if (read <= 0) {

                            if (
                                read ==
                                AudioRecord.ERROR_DEAD_OBJECT
                            ) {

                                Log.e(
                                    TAG,
                                    "AudioRecord DEAD_OBJECT"
                                )

                                break
                            }

                            continue
                        }

                        val chunk =
                            buffer.copyOf(
                                read
                            )

                        val rms =
                            calcularRms(
                                chunk
                            )

                        lastRms =
                            rms

                        if (rms <= 2) {

                            silentChunks++
                        }

                        if (rms > 2) {

                            receivedAudio++
                        }

                        if (
                            !inputQueue.offer(
                                chunk
                            )
                        ) {

                            inputQueue.poll()

                            inputQueue.offer(
                                chunk
                            )
                        }

                        capturedChunks++

                        if (
                            capturedChunks == 1L ||
                            capturedChunks % 20L == 0L
                        ) {

                            publicarDiagnostico(

                                if (rms > 2) {

                                    "🎤 Áudio capturado • RMS=$rms"

                                } else {

                                    "🎤 Capturando • áudio silencioso"
                                }
                            )
                        }
                    }

                } catch (e: Exception) {

                    if (running) {

                        lastError =
                            e.message

                        Log.e(
                            TAG,
                            "Erro na captura",
                            e
                        )
                    }

                } finally {

                    try {

                        audioRecord?.stop()

                    } catch (_: Exception) {
                    }
                }
            }

        captureThread?.start()
    }

    // =========================================================
    // RMS
    // =========================================================

    private fun calcularRms(
        data: ByteArray
    ): Long {

        if (
            data.size < 2
        ) {

            return 0
        }

        var sum =
            0.0

        var count =
            0

        var i =
            0

        while (
            i + 1 < data.size
        ) {

            val sample =
                (
                    (data[i + 1].toInt() shl 8) or
                        (data[i].toInt() and 0xff)
                    )
                    .toShort()
                    .toInt()

            sum +=
                sample.toDouble() *
                    sample.toDouble()

            count++

            i += 2
        }

        if (
            count == 0
        ) {

            return 0
        }

        return sqrt(
            sum / count
        ).toLong()
    }

    // =========================================================
    // ENVIO
    // =========================================================

    private fun iniciarEnvio() {

        sendThread =
            Thread {

                Log.d(
                    TAG,
                    "THREAD ENVIO INICIADA"
                )

                while (
                    running &&
                    !stopping
                ) {

                    try {

                        val chunk =
                            inputQueue.poll(
                                500,
                                TimeUnit.MILLISECONDS
                            )

                        if (
                            chunk == null
                        ) {

                            continue
                        }

                        enviarAudio(
                            chunk
                        )

                    } catch (
                        e: InterruptedException
                    ) {

                        break

                    } catch (e: Exception) {

                        if (running) {

                            lastError =
                                e.message

                            Log.e(
                                TAG,
                                "Erro no envio",
                                e
                            )
                        }
                    }
                }
            }

        sendThread?.start()
    }

    // =========================================================
    // ENVIAR AUDIO
    // =========================================================

    private fun enviarAudio(
        audio: ByteArray
    ) {

        var connection:
            HttpURLConnection? = null

        try {

            connection =
                URL(
                    "$BACKEND_URL/api/audio/chunk"
                )
                    .openConnection()
                    as HttpURLConnection

            connection.requestMethod =
                "POST"

            connection.connectTimeout =
                10000

            connection.readTimeout =
                10000

            connection.doOutput =
                true

            connection.useCaches =
                false

            connection.setRequestProperty(
                "Content-Type",
                "application/json"
            )

            val json =
                JSONObject()

            json.put(
                "jobId",
                jobId
            )

            json.put(
                "audio",
                Base64.encodeToString(
                    audio,
                    Base64.NO_WRAP
                )
            )

            json.put(
                "mimeType",
                "audio/pcm"
            )

            json.put(
                "sampleRate",
                SAMPLE_RATE
            )

            connection.outputStream.use {

                it.write(
                    json.toString()
                        .toByteArray(
                            Charsets.UTF_8
                        )
                )

                it.flush()
            }

            val code =
                connection.responseCode

            if (
                code in 200..299
            ) {

                sentChunks++

                if (
                    sentChunks == 1L ||
                    sentChunks % 20L == 0L
                ) {

                    publicarDiagnostico(
                        "📡 Áudio enviado ao Render"
                    )
                }

            } else {

                Log.e(
                    TAG,
                    "Render HTTP=$code"
                )
            }

        } catch (e: Exception) {

            if (running) {

                lastError =
                    e.message

                Log.e(
                    TAG,
                    "Erro HTTP",
                    e
                )
            }

        } finally {

            connection?.disconnect()
        }
    }

    // =========================================================
    // STATUS
    // =========================================================

    private fun iniciarStatus() {

        statusThread =
            Thread {

                while (
                    running &&
                    !stopping
                ) {

                    try {

                        consultarStatus()

                        Thread.sleep(
                            STATUS_INTERVAL
                        )

                    } catch (
                        e: InterruptedException
                    ) {

                        break

                    } catch (e: Exception) {

                        if (running) {

                            Log.e(
                                TAG,
                                "Erro status",
                                e
                            )
                        }

                        try {

                            Thread.sleep(
                                500
                            )

                        } catch (_: Exception) {
                        }
                    }
                }
            }

        statusThread?.start()
    }

    // =========================================================
    // CONSULTAR STATUS
    // =========================================================

    private fun consultarStatus() {

        var connection:
            HttpURLConnection? = null

        try {

            connection =
                URL(
                    "$BACKEND_URL/api/audio/status/$jobId"
                )
                    .openConnection()
                    as HttpURLConnection

            connection.requestMethod =
                "GET"

            connection.connectTimeout =
                8000

            connection.readTimeout =
                8000

            val code =
                connection.responseCode

            if (
                code !in 200..299
            ) {

                return
            }

            val response =
                connection.inputStream
                    .bufferedReader()
                    .use {
                        it.readText()
                    }

            val json =
                JSONObject(
                    response
                )

            if (
                !json.optBoolean(
                    "ok",
                    false
                )
            ) {

                return
            }

            val audioId =
                json.optString(
                    "audioId",
                    ""
                )

            var audioUrl =
                json.optString(
                    "audioUrl",
                    ""
                )

            if (
                audioId.isEmpty() ||
                audioUrl.isEmpty()
            ) {

                return
            }

            if (
                audioUrl.startsWith("/")
            ) {

                audioUrl =
                    BACKEND_URL +
                        audioUrl
            }

            synchronized(
                downloadedAudioLock
            ) {

                if (
                    downloadedAudioIds.contains(
                        audioId
                    )
                ) {

                    return
                }
            }

            publicarDiagnostico(
                "🔊 Áudio Piper recebido"
            )

            val wav =
                baixarWav(
                    audioUrl
                )

            if (
                wav == null
            ) {

                removerAudioBaixado(
                    audioId
                )

                return
            }

            val validWav =
                validarWav(
                    wav
                )

            if (
                validWav == null
            ) {

                removerAudioBaixado(
                    audioId
                )

                return
            }

            synchronized(
                downloadedAudioLock
            ) {

                downloadedAudioIds.add(
                    audioId
                )
            }

            piperAudioCount++

            val item =
                OutputAudio(
                    audioId,
                    validWav
                )

            publicarDiagnostico(
                "📦 WAV baixado • reproduzindo"
            )

            val tocou =
                tocarWav(
                    item
                )

            if (tocou) {

                playedAudio++

                publicarDiagnostico(
                    "✅ VOZ PIPER REPRODUZIDA"
                )

                enviarAck(
                    audioId
                )

            } else {

                removerAudioBaixado(
                    audioId
                )

                publicarDiagnostico(
                    "❌ Piper não conseguiu tocar"
                )
            }

        } catch (e: Exception) {

            if (running) {

                lastError =
                    e.message

                Log.e(
                    TAG,
                    "Erro status",
                    e
                )
            }

        } finally {

            connection?.disconnect()
        }
    }

    // =========================================================
    // BAIXAR WAV
    // =========================================================

    private fun baixarWav(
        urlString: String
    ): ByteArray? {

        var connection:
            HttpURLConnection? = null

        return try {

            connection =
                URL(
                    urlString
                )
                    .openConnection()
                    as HttpURLConnection

            connection.requestMethod =
                "GET"

            connection.connectTimeout =
                10000

            connection.readTimeout =
                10000

            val code =
                connection.responseCode

            if (
                code !in 200..299
            ) {

                Log.e(
                    TAG,
                    "WAV HTTP=$code"
                )

                null

            } else {

                val output =
                    ByteArrayOutputStream()

                connection.inputStream.use { input ->

                    val buffer =
                        ByteArray(
                            8192
                        )

                    while (true) {

                        val read =
                            input.read(
                                buffer
                            )

                        if (
                            read == -1
                        ) {

                            break
                        }

                        if (
                            read > 0
                        ) {

                            output.write(
                                buffer,
                                0,
                                read
                            )
                        }
                    }
                }

                output.toByteArray()
            }

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Erro baixando WAV",
                e
            )

            null

        } finally {

            connection?.disconnect()
        }
    }

    // =========================================================
    // VALIDAR WAV
    // =========================================================

    private fun validarWav(
        wav: ByteArray
    ): ByteArray? {

        try {

            if (
                wav.size < 44
            ) {

                return null
            }

            fun intLE(
                offset: Int
            ): Int {

                return ByteBuffer
                    .wrap(
                        wav,
                        offset,
                        4
                    )
                    .order(
                        ByteOrder.LITTLE_ENDIAN
                    )
                    .int
            }

            fun shortLE(
                offset: Int
            ): Int {

                return ByteBuffer
                    .wrap(
                        wav,
                        offset,
                        2
                    )
                    .order(
                        ByteOrder.LITTLE_ENDIAN
                    )
                    .short
                    .toInt() and 0xffff
            }

            fun chunkName(
                offset: Int
            ): String {

                return String(
                    wav,
                    offset,
                    4,
                    Charsets.US_ASCII
                )
            }

            if (
                chunkName(0) != "RIFF" ||
                chunkName(8) != "WAVE"
            ) {

                Log.e(
                    TAG,
                    "WAV inválido"
                )

                return null
            }

            var offset = 12

            var format = 0

            var channels = 0

            var sampleRate = 0

            var bits = 0

            var dataSize = 0

            while (
                offset + 8 <= wav.size
            ) {

                val id =
                    chunkName(
                        offset
                    )

                val size =
                    intLE(
                        offset + 4
                    )

                if (
                    size < 0
                ) {

                    return null
                }

                val dataOffset =
                    offset + 8

                if (
                    dataOffset > wav.size
                ) {

                    return null
                }

                val safeSize =
                    minOf(
                        size,
                        wav.size - dataOffset
                    )

                if (
                    id == "fmt " &&
                    safeSize >= 16
                ) {

                    format =
                        shortLE(
                            dataOffset
                        )

                    channels =
                        shortLE(
                            dataOffset + 2
                        )

                    sampleRate =
                        intLE(
                            dataOffset + 4
                        )

                    bits =
                        shortLE(
                            dataOffset + 14
                        )
                }

                if (
                    id == "data"
                ) {

                    dataSize =
                        safeSize

                    break
                }

                offset =
                    dataOffset +
                        size

                if (
                    offset % 2 != 0
                ) {

                    offset++
                }
            }

            if (
                format != 1 ||
                channels <= 0 ||
                sampleRate <= 0 ||
                bits != 16 ||
                dataSize <= 0
            ) {

                Log.e(
                    TAG,
                    "WAV incompatível"
                )

                return null
            }

            return wav

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Erro validando WAV",
                e
            )

            return null
        }
    }

    // =========================================================
    // ACK
    // =========================================================

    private fun enviarAck(
        audioId: String
    ) {

        Thread {

            var connection:
                HttpURLConnection? = null

            try {

                connection =
                    URL(
                        "$BACKEND_URL/api/audio/ack/$jobId/$audioId"
                    )
                        .openConnection()
                        as HttpURLConnection

                connection.requestMethod =
                    "POST"

                connection.connectTimeout =
                    5000

                connection.readTimeout =
                    5000

                connection.doOutput =
                    true

                connection.setRequestProperty(
                    "Content-Type",
                    "application/json"
                )

                connection.outputStream.use {

                    it.write(
                        "{}".toByteArray()
                    )
                }

                Log.d(
                    TAG,
                    "ACK=$audioId HTTP=${connection.responseCode}"
                )

            } catch (e: Exception) {

                Log.e(
                    TAG,
                    "Erro ACK",
                    e
                )

            } finally {

                connection?.disconnect()
            }

        }.start()
    }

    // =========================================================
    // TOCAR WAV
    // =========================================================

    private fun tocarWav(
        item: OutputAudio
    ): Boolean {

        if (
            item.wav.isEmpty()
        ) {

            return false
        }

        val file =
            File(
                cacheDir,
                "si_piper_${item.audioId}.wav"
            )

        var audioManager:
            AudioManager? = null

        var focusRequest:
            AudioFocusRequest? = null

        try {

            file.writeBytes(
                item.wav
            )

            audioManager =
                getSystemService(
                    Context.AUDIO_SERVICE
                ) as AudioManager

            audioManager.mode =
                AudioManager.MODE_NORMAL

            // -------------------------------------------------
            // AUDIO FOCUS
            // -------------------------------------------------

            if (
                Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.O
            ) {

                focusRequest =
                    AudioFocusRequest.Builder(
                        AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
                    )
                        .setAudioAttributes(
                            AudioAttributes.Builder()
                                .setUsage(
                                    AudioAttributes.USAGE_MEDIA
                                )
                                .setContentType(
                                    AudioAttributes.CONTENT_TYPE_SPEECH
                                )
                                .build()
                        )
                        .setAcceptsDelayedFocusGain(
                            false
                        )
                        .build()

                audioManager.requestAudioFocus(
                    focusRequest
                )

            } else {

                @Suppress(
                    "DEPRECATION"
                )

                audioManager.requestAudioFocus(
                    null,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
                )
            }

            // -------------------------------------------------
            // MEDIA PLAYER
            // -------------------------------------------------

            synchronized(
                mediaPlayerLock
            ) {

                liberarMediaPlayerInterno()

                val player =
                    MediaPlayer()

                mediaPlayer =
                    player

                if (
                    Build.VERSION.SDK_INT >=
                    Build.VERSION_CODES.LOLLIPOP
                ) {

                    player.setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(
                                AudioAttributes.USAGE_MEDIA
                            )
                            .setContentType(
                                AudioAttributes.CONTENT_TYPE_SPEECH
                            )
                            .build()
                    )

                } else {

                    @Suppress(
                        "DEPRECATION"
                    )

                    player.setAudioStreamType(
                        AudioManager.STREAM_MUSIC
                    )
                }

                player.setVolume(
                    1.0f,
                    1.0f
                )

                // -------------------------------------------------
                // ALTO-FALANTE
                // -------------------------------------------------

                if (
                    Build.VERSION.SDK_INT >=
                    Build.VERSION_CODES.M
                ) {

                    try {

                        val devices =
                            audioManager.getDevices(
                                AudioManager.GET_DEVICES_OUTPUTS
                            )

                        val speaker =
                            devices.firstOrNull {

                                it.type ==
                                    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                            }

                        if (
                            speaker != null
                        ) {

                            player.setPreferredDevice(
                                speaker
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

                // -------------------------------------------------
                // ERRO
                // -------------------------------------------------

                player.setOnErrorListener {

                    _,
                    what,
                    extra ->

                    Log.e(
                        TAG,
                        "MediaPlayer ERROR $what/$extra"
                    )

                    true
                }

                // -------------------------------------------------
                // ARQUIVO
                // -------------------------------------------------

                player.setDataSource(
                    file.absolutePath
                )

                player.prepare()

                if (
                    player.duration <= 0
                ) {

                    liberarMediaPlayerInterno()

                    return false
                }

                publicarDiagnostico(
                    "🔊 VOZ PIPER FALANDO"
                )

                player.start()

                // -------------------------------------------------
                // ESPERAR TERMINAR
                // -------------------------------------------------

                while (
                    running &&
                    !stopping
                ) {

                    val playing =
                        try {

                            player.isPlaying

                        } catch (_: Exception) {

                            false
                        }

                    if (
                        !playing
                    ) {

                        break
                    }

                    try {

                        Thread.sleep(
                            30
                        )

                    } catch (
                        e: InterruptedException
                    ) {

                        Thread.currentThread()
                            .interrupt()

                        break
                    }
                }

                liberarMediaPlayerInterno()
            }

            return true

        } catch (e: Exception) {

            lastError =
                e.message

            Log.e(
                TAG,
                "Erro reproduzindo Piper",
                e
            )

            synchronized(
                mediaPlayerLock
            ) {

                liberarMediaPlayerInterno()
            }

            return false

        } finally {

            // -------------------------------------------------
            // LIBERAR AUDIO FOCUS
            // -------------------------------------------------

            try {

                if (
                    audioManager != null &&
                    Build.VERSION.SDK_INT >=
                    Build.VERSION_CODES.O &&
                    focusRequest != null
                ) {

                    audioManager
                        .abandonAudioFocusRequest(
                            focusRequest
                        )

                } else if (
                    audioManager != null
                ) {

                    @Suppress(
                        "DEPRECATION"
                    )

                    audioManager.abandonAudioFocus(
                        null
                    )
                }

            } catch (_: Exception) {
            }

            // -------------------------------------------------
            // APAGAR WAV
            // -------------------------------------------------

            try {

                file.delete()

            } catch (_: Exception) {
            }
        }
    }

    // =========================================================
    // LIBERAR MEDIA PLAYER
    // =========================================================

    private fun liberarMediaPlayer() {

        synchronized(
            mediaPlayerLock
        ) {

            liberarMediaPlayerInterno()
        }
    }

    private fun liberarMediaPlayerInterno() {

        val player =
            mediaPlayer

        mediaPlayer =
            null

        if (
            player == null
        ) {

            return
        }

        try {

            if (
                player.isPlaying
            ) {

                player.stop()
            }

        } catch (_: Exception) {
        }

        try {

            player.reset()

        } catch (_: Exception) {
        }

        try {

            player.release()

        } catch (_: Exception) {
        }
    }

    // =========================================================
    // NOTIFICAÇÃO
    // =========================================================

    private fun criarCanal() {

        if (
            Build.VERSION.SDK_INT <
            Build.VERSION_CODES.O
        ) {

            return
        }

        val manager =
            getSystemService(
                NotificationManager::class.java
            )

        val channel =
            NotificationChannel(
                CHANNEL_ID,
                "SI Tradutor",
                NotificationManager.IMPORTANCE_LOW
            )

        manager.createNotificationChannel(
            channel
        )
    }

    // =========================================================
    // FOREGROUND
    // =========================================================

    private fun iniciarForeground() {

        val notification =
            if (
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
                        "Traduzindo áudio em tempo real"
                    )
                    .setSmallIcon(
                        android.R.drawable.ic_btn_speak_now
                    )
                    .setOngoing(true)
                    .build()

            } else {

                @Suppress(
                    "DEPRECATION"
                )

                Notification.Builder(
                    this
                )
                    .setContentTitle(
                        "SI Tradutor Live"
                    )
                    .setContentText(
                        "Traduzindo áudio em tempo real"
                    )
                    .setSmallIcon(
                        android.R.drawable.ic_btn_speak_now
                    )
                    .setOngoing(true)
                    .build()
            }

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

    // =========================================================
    // LIMPAR ESTADO
    // =========================================================

    private fun limparEstado() {

        inputQueue.clear()

        synchronized(
            downloadedAudioLock
        ) {

            downloadedAudioIds.clear()
        }

        capturedChunks =
            0

        sentChunks =
            0

        silentChunks =
            0

        receivedAudio =
            0

        piperAudioCount =
            0

        playedAudio =
            0

        lastRms =
            0

        lastError =
            null
    }

    // =========================================================
    // REMOVER AUDIO BAIXADO
    // =========================================================

    private fun removerAudioBaixado(
        audioId: String
    ) {

        synchronized(
            downloadedAudioLock
        ) {

            downloadedAudioIds.remove(
                audioId
            )
        }
    }

    // =========================================================
    // PARAR SERVIÇO
    // =========================================================

    private fun pararServico(
        enviarStop: Boolean
    ) {

        if (
            stopping
        ) {

            return
        }

        stopping =
            true

        running =
            false

        Log.d(
            TAG,
            "PARANDO SI"
        )

        publicarDiagnostico(
            "⏹ Monitoramento encerrado"
        )

        try {

            captureThread?.interrupt()

        } catch (_: Exception) {
        }

        try {

            sendThread?.interrupt()

        } catch (_: Exception) {
        }

        try {

            statusThread?.interrupt()

        } catch (_: Exception) {
        }

        // -----------------------------------------------------
        // AUDIO RECORD
        // -----------------------------------------------------

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

        // -----------------------------------------------------
        // MEDIA PLAYER
        // -----------------------------------------------------

        liberarMediaPlayer()

        // -----------------------------------------------------
        // MEDIA PROJECTION
        // -----------------------------------------------------

        try {

            projectionCallback?.let {

                mediaProjection?.unregisterCallback(
                    it
                )
            }

        } catch (_: Exception) {
        }

        try {

            mediaProjection?.stop()

        } catch (_: Exception) {
        }

        mediaProjection =
            null

        projectionCallback =
            null

        // -----------------------------------------------------
        // FILAS
        // -----------------------------------------------------

        inputQueue.clear()

        // -----------------------------------------------------
        // SERVIDOR
        // -----------------------------------------------------

        if (
            enviarStop &&
            jobId.isNotEmpty()
        ) {

            val stopId =
                jobId

            Thread {

                enviarStopAoServidor(
                    stopId
                )

            }.start()
        }

        // -----------------------------------------------------
        // FOREGROUND
        // -----------------------------------------------------

        try {

            if (
                Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.N
            ) {

                stopForeground(
                    STOP_FOREGROUND_REMOVE
                )

            } else {

                @Suppress(
                    "DEPRECATION"
                )

                stopForeground(
                    true
                )
            }

        } catch (_: Exception) {
        }

        stopSelf()
    }

    // =========================================================
    // STOP NO SERVIDOR
    // =========================================================

    private fun enviarStopAoServidor(
        stopJobId: String
    ) {

        var connection:
            HttpURLConnection? = null

        try {

            connection =
                URL(
                    "$BACKEND_URL/api/audio/stop/$stopJobId"
                )
                    .openConnection()
                    as HttpURLConnection

            connection.requestMethod =
                "POST"

            connection.connectTimeout =
                5000

            connection.readTimeout =
                5000

            connection.doOutput =
                true

            connection.setRequestProperty(
                "Content-Type",
                "application/json"
            )

            connection.outputStream.use {

                it.write(
                    "{}".toByteArray()
                )
            }

            Log.d(
                TAG,
                "STOP servidor HTTP=${connection.responseCode}"
            )

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Erro enviando STOP",
                e

            )

        } finally {

            connection?.disconnect()
        }
    }

    // =========================================================
    // DESTROY
    // =========================================================

    override fun onDestroy() {

        if (!stopping) {

            pararServico(
                false
            )
        }

        super.onDestroy()
    }
}
