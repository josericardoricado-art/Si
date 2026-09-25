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
import android.media.AudioManager
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.AudioTrack
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max


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

        private const val SAMPLE_RATE =
            16000

        private const val CHANNEL_IN =
            AudioFormat.CHANNEL_IN_MONO

        private const val CHANNEL_OUT =
            AudioFormat.CHANNEL_OUT_MONO

        private const val PCM_FORMAT =
            AudioFormat.ENCODING_PCM_16BIT

        /*
         * 200 ms por pacote.
         *
         * 16000 samples x 0,2 s x 2 bytes
         * = 6400 bytes.
         *
         * Isso reduz bastante a quantidade de requisições HTTP.
         */
        private const val CHUNK_SIZE =
            6400

        private const val INPUT_QUEUE_SIZE =
            100

        private const val OUTPUT_QUEUE_SIZE =
            30

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

    @Volatile
    private var captureStarted = false


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

    private var audioTrack:
        AudioTrack? = null

    private var audioTrackRate =
        22050

    private val audioTrackLock =
        Any()


    // =========================================================
    // FILA DE ENTRADA
    // =========================================================

    private val inputQueue =
        LinkedBlockingQueue<ByteArray>(
            INPUT_QUEUE_SIZE
        )


    // =========================================================
    // FILA DE SAÍDA
    // =========================================================

    private data class OutputAudio(
        val audioId: String,
        val pcm: ByteArray,
        val sampleRate: Int
    )

    private val outputQueue =
        LinkedBlockingQueue<OutputAudio>(
            OUTPUT_QUEUE_SIZE
        )


    // =========================================================
    // ÁUDIOS PROCESSADOS
    // =========================================================

    private val downloadedIds =
        HashSet<String>()

    private val downloadedLock =
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
    private var audioReceived =
        0L

    @Volatile
    private var audioPlayed =
        0L

    @Volatile
    private var lastRms =
        0L

    @Volatile
    private var lastError:
        String? = null


    // =========================================================
    // ON CREATE
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
            "STT: Deepgram"
        )

        Log.d(
            TAG,
            "Translation: DeepL Text"
        )

        Log.d(
            TAG,
            "TTS: Piper"
        )

        Log.d(
            TAG,
            "Backend: $BACKEND_URL"
        )

        Log.d(
            TAG,
            "================================"
        )
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


        if (
            action == ACTION_STOP
        ) {

            pararServico(
                true
            )

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
                "Serviço já está ativo"
            )

            return START_STICKY
        }


        jobId =
            intent.getStringExtra(
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
                "================================"
            )

            Log.d(
                TAG,
                "SI INICIADO"
            )

            Log.d(
                TAG,
                "jobId=$jobId"
            )

            Log.d(
                TAG,
                "================================"
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
            object : MediaProjection.Callback() {

                override fun onStop() {

                    Log.e(
                        TAG,
                        "MediaProjection foi encerrada"
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
    // CAPTURA DE ÁUDIO INTERNO
    // =========================================================

    private fun iniciarCaptura() {

        val projection =
            mediaProjection
                ?: throw IllegalStateException(
                    "MediaProjection não disponível"
                )


        val minBuffer =
            AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                CHANNEL_IN,
                PCM_FORMAT
            )


        if (
            minBuffer <= 0
        ) {

            throw IllegalStateException(
                "getMinBufferSize falhou: $minBuffer"
            )
        }


        val bufferSize =
            max(
                minBuffer * 4,
                CHUNK_SIZE * 8
            )


        /*
         * IMPORTANTE:
         *
         * Antes capturávamos somente MEDIA e GAME.
         *
         * Agora também capturamos UNKNOWN.
         *
         * Isso é importante porque diferentes players
         * podem declarar usos diferentes.
         */
        val captureConfig =
            AudioPlaybackCaptureConfiguration
                .Builder(projection)

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
                            CHANNEL_IN
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


        /*
         * Diagnóstico do Android.
         *
         * Se o Android estiver entregando silêncio,
         * conseguimos detectar.
         */
        if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.Q
        ) {

            try {

                audioRecord?.registerAudioRecordingCallback(
                    object :
                        AudioManager.AudioRecordingCallback() {

                        override fun onRecordingConfigChanged(
                            config:
                            AudioRecordingConfiguration
                        ) {

                            Log.d(
                                TAG,
                                "CONFIG ÁUDIO: " +
                                    "silenciado=" +
                                    config.isClientSilenced +
                                    " device=" +
                                    config.audioDevice
                            )
                        }
                    },
                    android.os.Handler(
                        mainLooper
                    )
                )

            } catch (e: Exception) {

                Log.w(
                    TAG,
                    "Não foi possível registrar callback de áudio",
                    e
                )
            }
        }


        captureThread =
            Thread {

                val buffer =
                    ByteArray(
                        CHUNK_SIZE
                    )


                try {

                    audioRecord?.startRecording()

                    captureStarted =
                        true


                    Log.d(
                        TAG,
                        "================================"
                    )

                    Log.d(
                        TAG,
                        "CAPTURA INTERNA INICIADA"
                    )

                    Log.d(
                        TAG,
                        "sampleRate=$SAMPLE_RATE"
                    )

                    Log.d(
                        TAG,
                        "chunk=$CHUNK_SIZE"
                    )

                    Log.d(
                        TAG,
                        "================================"
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
                                    "AUDIO DEAD OBJECT"
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

                        } else {

                            audioReceived++
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


                        /*
                         * Log detalhado no começo e depois
                         * a cada 25 blocos.
                         */
                        if (
                            capturedChunks <= 10 ||
                            capturedChunks % 25L == 0L
                        ) {

                            Log.d(
                                TAG,
                                "CAPTURA #" +
                                    capturedChunks +
                                    " rms=" +
                                    rms +
                                    " silencios=" +
                                    silentChunks +
                                    " fila=" +
                                    inputQueue.size
                            )
                        }
                    }

                } catch (e: Exception) {

                    if (running) {

                        lastError =
                            e.message

                        Log.e(
                            TAG,
                            "ERRO NA CAPTURA",
                            e
                        )
                    }

                } finally {

                    try {

                        audioRecord?.stop()

                    } catch (_: Exception) {
                    }


                    captureStarted =
                        false
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


        var total = 0.0

        var count = 0


        var i = 0


        while (
            i + 1 < data.size
        ) {

            val value =
                (
                    (data[i + 1].toInt() shl 8)
                    or
                    (data[i].toInt() and 0xff)
                ).toShort().toInt()


            total +=
                value.toDouble() *
                value.toDouble()


            count++

            i += 2
        }


        if (
            count == 0
        ) {

            return 0
        }


        return kotlin.math.sqrt(
            total / count
        ).toLong()
    }


    // =========================================================
    // THREAD DE ENVIO
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
                                "ERRO NO ENVIO",
                                e
                            )
                        }
                    }
                }


                Log.d(
                    TAG,
                    "THREAD ENVIO FINALIZADA"
                )
            }


        sendThread?.start()
    }


    // =========================================================
    // ENVIA ÁUDIO
    // =========================================================

    private fun enviarAudio(
        audio: ByteArray
    ) {

        if (
            audio.isEmpty() ||
            jobId.isEmpty()
        ) {

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
                "application/json; charset=UTF-8"
            )

            connection.setRequestProperty(
                "Accept",
                "application/json"
            )


            val base64 =
                Base64.encodeToString(
                    audio,
                    Base64.NO_WRAP
                )


            val json =
                JSONObject()


            json.put(
                "jobId",
                jobId
            )

            json.put(
                "audio",
                base64
            )

            json.put(
                "mimeType",
                "audio/pcm"
            )

            json.put(
                "sampleRate",
                SAMPLE_RATE
            )


            val body =
                json.toString()
                    .toByteArray(
                        Charsets.UTF_8
                    )


            connection.outputStream.use {

                it.write(
                    body
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
                    sentChunks <= 5 ||
                    sentChunks % 25L == 0L
                ) {

                    Log.d(
                        TAG,
                        "ENVIO #" +
                            sentChunks
                    )
                }

            } else {

                val error =
                    try {

                        connection.errorStream
                            ?.bufferedReader()
                            ?.use {
                                it.readText()
                            }

                    } catch (_: Exception) {

                        null
                    }


                Log.e(
                    TAG,
                    "RENDER HTTP $code $error"
                )
            }

        } catch (e: Exception) {

            if (running) {

                lastError =
                    e.message

                Log.e(
                    TAG,
                    "ERRO HTTP",
                    e
                )
            }

        } finally {

            connection?.disconnect()
        }
    }


    // =========================================================
    // STATUS / DOWNLOAD
    // =========================================================

    private fun iniciarStatus() {

        statusThread =
            Thread {

                Log.d(
                    TAG,
                    "THREAD STATUS INICIADA"
                )


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

                            lastError =
                                e.message

                            Log.e(
                                TAG,
                                "ERRO STATUS",
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

        if (
            jobId.isEmpty()
        ) {

            return
        }


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

            connection.useCaches =
                false


            val code =
                connection.responseCode


            if (
                code !in 200..299
            ) {

                Log.w(
                    TAG,
                    "STATUS HTTP=$code"
                )

                return
            }


            val text =
                connection.inputStream
                    .bufferedReader()
                    .use {
                        it.readText()
                    }


            val json =
                JSONObject(
                    text
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
                downloadedLock
            ) {

                if (
                    downloadedIds.contains(
                        audioId
                    )
                ) {

                    return
                }


                downloadedIds.add(
                    audioId
                )
            }


            Log.d(
                TAG,
                "NOVO PIPER AUDIO"
            )


            Log.d(
                TAG,
                "audioId=$audioId"
            )


            Log.d(
                TAG,
                "url=$audioUrl"
            )


            val wav =
                baixarWav(
                    audioUrl
                )


            if (
                wav == null ||
                wav.isEmpty()
            ) {

                removerDownloaded(
                    audioId
                )

                return
            }


            val parsed =
                parseWav(
                    wav
                )


            if (
                parsed == null
            ) {

                removerDownloaded(
                    audioId
                )

                Log.e(
                    TAG,
                    "WAV Piper inválido"
                )

                return
            }


            val item =
                OutputAudio(
                    audioId,
                    parsed.first,
                    parsed.second
                )


            if (
                !outputQueue.offer(
                    item,
                    3,
                    TimeUnit.SECONDS
                )
            ) {

                removerDownloaded(
                    audioId
                )

                Log.e(
                    TAG,
                    "Fila de reprodução cheia"
                )

                return
            }


            audioReceived++


            Log.d(
                TAG,
                "ÁUDIO NA FILA DE REPRODUÇÃO"
            )

        } catch (e: Exception) {

            if (running) {

                Log.e(
                    TAG,
                    "Erro consultando status",
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
                20000

            connection.useCaches =
                false


            if (
                connection.responseCode !in 200..299
            ) {

                Log.e(
                    TAG,
                    "Erro baixando WAV: " +
                        connection.responseCode
                )

                null

            } else {

                connection.inputStream.use {
                    it.readBytes()
                }
            }

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Falha download WAV",
                e
            )

            null

        } finally {

            connection?.disconnect()
        }
    }


    // =========================================================
    // PARSER WAV
    // =========================================================

    private fun parseWav(
        data: ByteArray
    ): Pair<ByteArray, Int>? {

        if (
            data.size < 44
        ) {

            return null
        }


        if (
            String(
                data,
                0,
                4,
                Charsets.US_ASCII
            ) != "RIFF"
        ) {

            return null
        }


        if (
            String(
                data,
                8,
                4,
                Charsets.US_ASCII
            ) != "WAVE"
        ) {

            return null
        }


        var position =
            12

        var sampleRate =
            22050

        var channels =
            1

        var bits =
            16

        var audioData:
            ByteArray? = null


        while (
            position + 8 <= data.size
        ) {

            val chunkId =
                String(
                    data,
                    position,
                    4,
                    Charsets.US_ASCII
                )


            val chunkSize =
                ByteBuffer
                    .wrap(
                        data,
                        position + 4,
                        4
                    )
                    .order(
                        ByteOrder.LITTLE_ENDIAN
                    )
                    .int


            if (
                chunkSize < 0
            ) {

                break
            }


            val start =
                position + 8

            val end =
                minOf(
                    start + chunkSize,
                    data.size
                )


            if (
                chunkId == "fmt "
            ) {

                if (
                    end - start >= 16
                ) {

                    val fmt =
                        ByteBuffer
                            .wrap(
                                data,
                                start,
                                16
                            )
                            .order(
                                ByteOrder.LITTLE_ENDIAN
                            )


                    fmt.short

                    channels =
                        fmt.short
                            .toInt()


                    sampleRate =
                        fmt.int


                    fmt.int

                    fmt.short

                    bits =
                        fmt.short
                            .toInt()
                }

            } else if (
                chunkId == "data"
            ) {

                audioData =
                    data.copyOfRange(
                        start,
                        end
                    )

                break
            }


            position =
                start + chunkSize


            if (
                position % 2 != 0
            ) {

                position++
            }
        }


        if (
            audioData == null
        ) {

            return null
        }


        if (
            bits != 16
        ) {

            Log.e(
                TAG,
                "WAV não é PCM16: bits=$bits"
            )

            return null
        }


        var pcm =
            audioData


        /*
         * Piper normalmente gera mono.
         *
         * Se vier estéreo, convertemos para mono.
         */
        if (
            channels == 2
        ) {

            pcm =
                stereoParaMono(
                    pcm
                )

            channels =
                1
        }


        if (
            channels != 1
        ) {

            return null
        }


        return Pair(
            pcm,
            sampleRate
        )
    }


    // =========================================================
    // ESTÉREO -> MONO
    // =========================================================

    private fun stereoParaMono(
        data: ByteArray
    ): ByteArray {

        val samples =
            data.size / 4


        val result =
            ByteArray(
                samples * 2
            )


        var input =
            0

        var output =
            0


        while (
            input + 3 < data.size
        ) {

            val left =
                (
                    (data[input + 1].toInt() shl 8)
                    or
                    (data[input].toInt() and 0xff)
                ).toShort().toInt()


            val right =
                (
                    (data[input + 3].toInt() shl 8)
                    or
                    (data[input + 2].toInt() and 0xff)
                ).toShort().toInt()


            val mono =
                ((left + right) / 2)
                .coerceIn(
                    Short.MIN_VALUE.toInt(),
                    Short.MAX_VALUE.toInt()
                )
                .toShort()


            result[output] =
                (mono.toInt() and 0xff)
                    .toByte()


            result[output + 1] =
                (mono.toInt() shr 8)
                    .toByte()


            input += 4

            output += 2
        }


        return result
    }


    // =========================================================
    // PLAYBACK
    // =========================================================

    private fun iniciarPlayback() {

        playbackThread =
            Thread {

                Log.d(
                    TAG,
                    "THREAD PLAYBACK INICIADA"
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


                        reproduzirAudio(
                            item
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
                                "ERRO PLAYBACK",
                                e
                            )
                        }
                    }
                }
            }


        playbackThread?.start()
    }


    // =========================================================
    // REPRODUZIR AUDIO
    // =========================================================

    private fun reproduzirAudio(
        item: OutputAudio
    ) {

        if (
            item.pcm.isEmpty()
        ) {

            return
        }


        synchronized(
            audioTrackLock
        ) {

            prepararAudioTrack(
                item.sampleRate
            )


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
                offset < item.pcm.size &&
                running &&
                !stopping
            ) {

                val written =
                    track.write(
                        item.pcm,
                        offset,
                        item.pcm.size - offset,
                        AudioTrack.WRITE_BLOCKING
                    )


                if (
                    written <= 0
                ) {

                    Log.e(
                        TAG,
                        "AudioTrack write=$written"
                    )

                    break
                }


                offset +=
                    written
            }


            audioPlayed++


            Log.d(
                TAG,
                "ÁUDIO REPRODUZIDO #" +
                    audioPlayed +
                    " bytes=" +
                    item.pcm.size +
                    " rate=" +
                    item.sampleRate
            )


            /*
             * Só avisamos o backend depois que o áudio
             * entrou no player.
             */
            enviarAck(
                item.audioId
            )
        }
    }


    // =========================================================
    // PREPARAR AUDIO TRACK
    // =========================================================

    private fun prepararAudioTrack(
        sampleRate: Int
    ) {

        if (
            audioTrack != null &&
            audioTrackRate == sampleRate &&
            audioTrack?.state ==
                AudioTrack.STATE_INITIALIZED
        ) {

            return
        }


        try {

            audioTrack?.stop()

        } catch (_: Exception) {
        }


        try {

            audioTrack?.release()

        } catch (_: Exception) {
        }


        audioTrack =
            null


        val minBuffer =
            AudioTrack.getMinBufferSize(
                sampleRate,
                CHANNEL_OUT,
                PCM_FORMAT
            )


        if (
            minBuffer <= 0
        ) {

            throw IllegalStateException(
                "AudioTrack buffer inválido"
            )
        }


        val bufferSize =
            max(
                minBuffer * 4,
                16384
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

                .setSampleRate(
                    sampleRate
                )

                .setEncoding(
                    PCM_FORMAT
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

                .build()


        if (
            audioTrack?.state !=
            AudioTrack.STATE_INITIALIZED
        ) {

            throw IllegalStateException(
                "AudioTrack não inicializado"
            )
        }


        audioTrackRate =
            sampleRate


        Log.d(
            TAG,
            "AudioTrack criado rate=$sampleRate buffer=$bufferSize"
        )
    }


    // =========================================================
    // ACK
    // =========================================================

    private fun enviarAck(
        audioId: String
    ) {

        if (
            jobId.isEmpty() ||
            audioId.isEmpty()
        ) {

            return
        }


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


            connection.useCaches =
                false


            connection.setRequestProperty(
                "Connection",
                "close"
            )


            val code =
                connection.responseCode


            Log.d(
                TAG,
                "ACK audio=$audioId HTTP=$code"
            )

        } catch (e: Exception) {

            Log.w(
                TAG,
                "Erro ACK",
                e
            )

        } finally {

            connection?.disconnect()
        }
    }


    // =========================================================
    // FOREGROUND
    // =========================================================

    private fun iniciarForeground() {

        criarCanal()


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
                        "Capturando e traduzindo áudio em tempo real"
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
                Notification.Builder(this)

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
    // CANAL
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


        channel.description =
            "Captura e tradução de áudio em tempo real"


        manager.createNotificationChannel(
            channel
        )
    }


    // =========================================================
    // LIMPAR ESTADO
    // =========================================================

    private fun limparEstado() {

        inputQueue.clear()

        outputQueue.clear()


        synchronized(
            downloadedLock
        ) {

            downloadedIds.clear()
        }


        capturedChunks =
            0

        sentChunks =
            0

        silentChunks =
            0

        audioReceived =
            0

        audioPlayed =
            0

        lastRms =
            0

        lastError =
            null
    }


    // =========================================================
    // REMOVER ID
    // =========================================================

    private fun removerDownloaded(
        id: String
    ) {

        synchronized(
            downloadedLock
        ) {

            downloadedIds.remove(
                id
            )
        }
    }


    // =========================================================
    // PARAR
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
            "rms=$lastRms"
        )

        Log.d(
            TAG,
            "audios tocados=$audioPlayed"
        )

        Log.d(
            TAG,
            "================================"
        )


        if (
            enviarStop &&
            jobId.isNotEmpty()
        ) {

            enviarStopAoServidor()
        }


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


        try {

            audioTrack?.stop()

        } catch (_: Exception) {
        }


        try {

            audioTrack?.release()

        } catch (_: Exception) {
        }


        audioTrack =
            null


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


        inputQueue.clear()

        outputQueue.clear()


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

        } catch (_: Exception)
