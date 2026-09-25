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

        private const val OUTPUT_CHANNEL =
            AudioFormat.CHANNEL_OUT_MONO

        private const val PCM_FORMAT =
            AudioFormat.ENCODING_PCM_16BIT

        /*
         * 100 ms:
         *
         * 16000 x 0,1 x 2 = 3200 bytes
         */
        private const val CHUNK_SIZE =
            3200

        private const val INPUT_QUEUE_SIZE =
            120

        private const val OUTPUT_QUEUE_SIZE =
            40

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

    private var mediaProjection:
        MediaProjection? = null

    private var projectionCallback:
        MediaProjection.Callback? = null


    // =========================================================
    // AUDIO RECORD
    // =========================================================

    private var audioRecord:
        AudioRecord? = null


    // =========================================================
    // AUDIO TRACK
    // =========================================================

    private var mediaPlayer:
        MediaPlayer? = null

    private val mediaPlayerLock =
        Any()


    // =========================================================
    // FILAS
    // =========================================================

    private val inputQueue =
        LinkedBlockingQueue<ByteArray>(
            INPUT_QUEUE_SIZE
        )


    private data class OutputAudio(
        val audioId: String,
        val wav: ByteArray
    )


    private val outputQueue =
        LinkedBlockingQueue<OutputAudio>(
            OUTPUT_QUEUE_SIZE
        )


    // =========================================================
    // ÁUDIOS JÁ RECEBIDOS
    // =========================================================

    private val downloadedAudioIds =
        HashSet<String>()

    private val downloadedAudioLock =
        Any()


    // =========================================================
    // THREADS
    // =========================================================

    private var captureThread:
        Thread? = null

    private var sendThread:
        Thread? = null

    private var statusThread:
        Thread? = null

    private var playbackThread:
        Thread? = null


    // =========================================================
    // DIAGNÓSTICO
    // =========================================================

    @Volatile
    private var capturedChunks =
        0L

    @Volatile
    private var sentChunks =
        0L

    @Volatile
    private var silentChunks =
        0L

    @Volatile
    private var receivedAudio =
        0L

    @Volatile
    private var piperAudioCount =
        0L

    @Volatile
    private var playedAudio =
        0L

    @Volatile
    private var lastRms =
        0L

    @Volatile
    private var lastError:
        String? = null


    // =========================================================
    // DIAGNÓSTICO VISÍVEL NO APLICATIVO
    // =========================================================

    private fun publicarDiagnostico(
        mensagem: String
    ) {
        try {
            getSharedPreferences(
                DIAG_PREFS,
                Context.MODE_PRIVATE
            ).edit()
                .putString("status", mensagem)
                .putLong("time", System.currentTimeMillis())
                .putLong("captured", capturedChunks)
                .putLong("sent", sentChunks)
                .putLong("received", piperAudioCount)
                .putLong("played", playedAudio)
                .putLong("rms", lastRms)
                .putInt("queue", outputQueue.size)
                .putString("error", lastError ?: "")
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
            "Captura interna: ATIVA"
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
            "🟢 Serviço de áudio preparado"
        )
    }


    // =========================================================
    // START
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


        if (
            action == ACTION_STOP
        ) {

            pararServico(true)

            return START_NOT_STICKY
        }


        if (
            action != ACTION_START
        ) {

            return START_NOT_STICKY
        }


        if (running) {

            Log.d(
                TAG,
                "Serviço já está rodando"
            )

            return START_STICKY
        }


        jobId =
            intent?.getStringExtra(
                EXTRA_JOB_ID
            ) ?: ""


        if (
            jobId.isEmpty()
        ) {

            Log.e(
                TAG,
                "jobId vazio"
            )

            stopSelf()

            return START_NOT_STICKY
        }


        val resultCode =
            intent.getIntExtra(
                EXTRA_RESULT_CODE,
                Activity.RESULT_CANCELED
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


        if (
            resultCode != Activity.RESULT_OK ||
            resultData == null
        ) {

            Log.e(
                TAG,
                "MediaProjection inválida"
            )

            stopSelf()

            return START_NOT_STICKY
        }


        try {

            iniciarForeground()

            running = true

            stopping = false

            limparEstado()

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

            iniciarPlayback()


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

            pararServico(false)
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
            object : MediaProjection.Callback() {

                override fun onStop() {

                    Log.d(
                        TAG,
                        "MediaProjection encerrada"
                    )


                    if (running) {

                        pararServico(true)
                    }
                }
            }


        mediaProjection?.registerCallback(
            projectionCallback!!,
            null
        )
    }


    // =========================================================
    // CAPTURA DE ÁUDIO INTERNO
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


        if (
            minBuffer <= 0
        ) {

            throw IllegalStateException(
                "AudioRecord.getMinBufferSize falhou"
            )
        }


        val bufferSize =
            maxOf(
                minBuffer * 4,
                CHUNK_SIZE * 8
            )


        /*
         * Capturamos:
         *
         * MEDIA
         * GAME
         * UNKNOWN
         *
         * Isso aumenta a compatibilidade com diferentes
         * aplicativos de vídeo.
         */
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
                        "🎤 Áudio capturado • aguardando fala"
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


                        if (
                            read <= 0
                        ) {

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


                        if (
                            rms <= 2
                        ) {

                            silentChunks++
                        }


                        if (
                            rms > 2
                        ) {

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

                        if (capturedChunks == 1L || capturedChunks % 20L == 0L) {
                            publicarDiagnostico(
                                if (rms > 2) {
                                    "🎤 Áudio capturado • RMS=$rms"
                                } else {
                                    "🎤 Capturando • áudio silencioso"
                                }
                            )
                        }


                        if (
                            capturedChunks <= 5 ||
                            capturedChunks % 50L == 0L
                        ) {

                            Log.d(
                                TAG,
                                "CAPTURA=$capturedChunks " +
                                    "RMS=$rms " +
                                    "silencios=$silentChunks " +
                                    "fila=${inputQueue.size}"
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


        var sum = 0.0

        var count = 0


        var i = 0


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
    // ENVIA AUDIO PARA RENDER
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

                if (sentChunks == 1L || sentChunks % 20L == 0L) {
                    publicarDiagnostico(
                        "📡 Áudio enviado ao Render"
                    )
                }


                if (
                    sentChunks <= 5 ||
                    sentChunks % 50L == 0L
                ) {

                    Log.d(
                        TAG,
                        "ENVIO=$sentChunks"
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

                if (sentChunks > 0L) {
                    publicarDiagnostico(
                        "🟡 Texto/voz ainda não chegou • aguardando Piper"
                    )
                }

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


                downloadedAudioIds.add(
                    audioId
                )
            }


            Log.d(
                TAG,
                "NOVO AUDIO PIPER=$audioId"
            )

            publicarDiagnostico(
                "🔊 Áudio Piper recebido • baixando WAV"
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


            val item =
                OutputAudio(
                    audioId,
                    validWav
                )


            if (
                !outputQueue.offer(
                    item,
                    3,
                    TimeUnit.SECONDS
                )
            ) {

                removerAudioBaixado(
                    audioId
                )

                return
            }


            Log.d(
                TAG,
                "AUDIO PIPER NA FILA"
            )

            piperAudioCount++

            publicarDiagnostico(
                "🔊 Áudio Piper recebido • pronto para reproduzir"
            )


            enviarAck(
                audioId
            )

        } catch (e: Exception) {

            if (running) {

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
    // DOWNLOAD WAV
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
                30000


            if (
                connection.responseCode !in 200..299
            ) {

                Log.e(
                    TAG,
                    "WAV HTTP=${connection.responseCode}"
                )

                null

            } else {

                val output =
                    ByteArrayOutputStream()


                connection.inputStream.use {
                    input ->

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

                Log.e(
                    TAG,
                    "WAV pequeno demais: ${wav.size} bytes"
                )

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
                    "WAV inválido: RIFF/WAVE não encontrado"
                )

                return null
            }


            var offset = 12
            var format = 0
            var channels = 0
            var sampleRate = 0
            var bits = 0
            var dataStart = -1
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

                    dataStart =
                        dataOffset

                    dataSize =
                        safeSize

                    break
                }


                offset =
                    dataOffset + size

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
                dataStart < 0 ||
                dataSize <= 0
            ) {

                Log.e(
                    TAG,
                    "WAV incompatível format=$format channels=$channels rate=$sampleRate bits=$bits data=$dataSize"
                )

                return null
            }


            Log.d(
                TAG,
                "WAV OK rate=$sampleRate channels=$channels bits=$bits bytes=${wav.size}"
            )

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
    // PLAYBACK
    // =========================================================

    private fun iniciarPlayback() {

        playbackThread =
            Thread {

                Log.d(
                    TAG,
                    "THREAD PLAYBACK INICIADA - MediaPlayer WAV"
                )


                while (
                    running &&
                    !stopping
                ) {

                    try {

                        val item =
                            outputQueue.poll(
                                500,
                                TimeUnit.MILLISECONDS
                            )


                        if (
                            item == null
                        ) {

                            continue
                        }


                        publicarDiagnostico(
                            "▶️ Reproduzindo voz Piper"
                        )

                        tocarWav(
                            item
                        )


                        playedAudio++

                        publicarDiagnostico(
                            "✅ Voz reproduzida"
                        )


                        Log.d(
                            TAG,
                            "AUDIO TOCADO #$playedAudio"
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
                                "Erro playback",
                                e
                            )

                            publicarDiagnostico(
                                "❌ Erro ao reproduzir: ${e.message ?: "erro desconhecido"}"
                            )
                        }
                    }
                }


                liberarMediaPlayer()
            }


        playbackThread?.start()
    }


    // =========================================================
    // TOCAR WAV COM MEDIA PLAYER
    // =========================================================

    private fun tocarWav(
        item: OutputAudio
    ) {

        if (
            item.wav.isEmpty()
        ) {

            Log.e(
                TAG,
                "WAV vazio"
            )

            return
        }


        val file =
            File(
                cacheDir,
                "si_piper_${item.audioId}.wav"
            )


        try {

            file.writeBytes(
                item.wav
            )


            Log.d(
                TAG,
                "WAV salvo para reprodução: ${file.absolutePath} bytes=${item.wav.size}"
            )


            synchronized(
                mediaPlayerLock
            ) {

                liberarMediaPlayerInterno()

                val player =
                    MediaPlayer()

                mediaPlayer =
                    player


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


                player.setDataSource(
                    file.absolutePath
                )

                player.prepare()

                player.setVolume(
                    1.0f,
                    1.0f
                )


                Log.d(
                    TAG,
                    "MEDIAPLAYER START audioId=${item.audioId}"
                )

                publicarDiagnostico(
                    "▶️ MediaPlayer iniciou a voz"
                )

                player.start()


                while (
                    running &&
                    !stopping
                ) {

                    val tocando =
                        try {
                            player.isPlaying
                        } catch (_: Exception) {
                            false
                        }


                    if (!tocando) {
                        break
                    }


                    try {
                        Thread.sleep(50)
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        break
                    }
                }


                liberarMediaPlayerInterno()
            }

        } catch (
            e: InterruptedException
        ) {

            Log.d(
                TAG,
                "Playback interrompido"
            )

            Thread.currentThread().interrupt()

        } catch (e: Exception) {

            lastError =
                e.message

            Log.e(
                TAG,
                "Erro reproduzindo WAV com MediaPlayer",
                e
            )

            publicarDiagnostico(
                "❌ MediaPlayer: ${e.message ?: "erro desconhecido"}"
            )

        } finally {

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
            if (player.isPlaying) {
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
                        "Capturando áudio em tempo real"
                    )
                    .setSmallIcon(
                        android.R.drawable.ic_btn_speak_now
                    )
                    .setOngoing(
                        true
                    )
                    .build()

            } else {

                @Suppress("DEPRECATION")
                Notification.Builder(
                    this
                )
                    .setContentTitle(
                        "SI Tradutor Live"
                    )
                    .setContentText(
                        "Capturando áudio em tempo real"
                    )
                    .setSmallIcon(
                        android.R.drawable.ic_btn_speak_now
                    )
                    .setOngoing(
                        true
                    )
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
    // LIMPAR
    // =========================================================

    private fun limparEstado() {

        inputQueue.clear()

        outputQueue.clear()


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
    // REMOVER AUDIO
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
            "================================"
        )

        Log.d(
            TAG,
            "PARANDO SI"
        )

        Log.d(
            TAG,
            "capturados=$capturedChunks"
        )

        Log.d(
            TAG,
            "enviados=$sentChunks"
        )

        Log.d(
            TAG,
            "silenciosos=$silentChunks"
        )

        Log.d(
            TAG,
            "RMS=$lastRms"
        )

        Log.d(
            TAG,
            "tocados=$playedAudio"
        )

        Log.d(
            TAG,
            "erro=$lastError"
        )

        Log.d(
            TAG,
            "================================"
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


        try {
            playbackThread?.interrupt()
        } catch (_: Exception) {
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


        liberarMediaPlayer()


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


        inputQueue.clear()

        outputQueue.clear()


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
                "STOP Render HTTP=${connection.responseCode}"
            )

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Erro STOP",
                e
            )

        } finally {

            connection?.disconnect()
        }
    }


    // =========================================================
    // BIND
    // =========================================================

    override fun onBind(
        intent: Intent?
    ): IBinder? {

        return null
    }


    // =========================================================
    // DESTROY
    // =========================================================

    override fun onDestroy() {

        Log.d(
            TAG,
            "onDestroy"
        )


        if (
            !stopping
        ) {

            pararServico(
                false
            )
        }


        super.onDestroy()
    }
}
