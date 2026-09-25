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
import android.media.AudioTrack
import android.media.projection.MediaProjection
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

class AudioCaptureService : Service() {

    companion object {

        private const val TAG = "SI_AUDIO"

        // =========================================================
        // BACKEND
        // =========================================================

        private const val BACKEND_URL =
            "https://si-u2ul.onrender.com"

        // =========================================================
        // ACTIONS
        // =========================================================

        const val ACTION_START =
            "com.si.tradutor.ACTION_START"

        const val ACTION_STOP =
            "com.si.tradutor.ACTION_STOP"

        // =========================================================
        // EXTRAS
        // =========================================================

        const val EXTRA_JOB_ID =
            "jobId"

        const val EXTRA_RESULT_CODE =
            "resultCode"

        const val EXTRA_RESULT_DATA =
            "resultData"

        // =========================================================
        // NOTIFICAÇÃO
        // =========================================================

        private const val CHANNEL_ID =
            "si_audio_capture"

        private const val NOTIFICATION_ID =
            1001

        // =========================================================
        // ENTRADA
        // =========================================================

        private const val INPUT_SAMPLE_RATE =
            16000

        private const val INPUT_CHANNEL =
            AudioFormat.CHANNEL_IN_MONO

        private const val INPUT_FORMAT =
            AudioFormat.ENCODING_PCM_16BIT

        /*
         * 100 ms:
         *
         * 16000 samples/s
         * x 0,1 s
         * x 2 bytes
         * = 3200 bytes
         */
        private const val INPUT_CHUNK_SIZE =
            3200

        private const val INPUT_QUEUE_CAPACITY =
            120

        // =========================================================
        // SAÍDA
        // =========================================================

        private const val OUTPUT_CHANNEL =
            AudioFormat.CHANNEL_OUT_MONO

        private const val OUTPUT_FORMAT =
            AudioFormat.ENCODING_PCM_16BIT

        private const val OUTPUT_QUEUE_CAPACITY =
            40

        /*
         * O backend Piper pode produzir um novo WAV
         * enquanto o anterior ainda está tocando.
         */
        private const val STATUS_POLL_MS =
            120L
    }

    // =============================================================
    // MEDIA PROJECTION
    // =============================================================

    private var mediaProjection: MediaProjection? = null

    private var projectionCallback:
        MediaProjection.Callback? = null

    // =============================================================
    // AUDIO RECORD
    // =============================================================

    private var audioRecord: AudioRecord? = null

    // =============================================================
    // AUDIO TRACK
    // =============================================================

    private var audioTrack: AudioTrack? = null

    private var outputSampleRate = 22050

    private val audioTrackLock =
        Any()

    // =============================================================
    // ESTADO
    // =============================================================

    @Volatile
    private var running = false

    @Volatile
    private var stopping = false

    @Volatile
    private var captureStarted = false

    // =============================================================
    // JOB
    // =============================================================

    @Volatile
    private var jobId = ""

    // =============================================================
    // FILA DE ENTRADA
    // =============================================================

    private val inputQueue =
        LinkedBlockingQueue<ByteArray>(
            INPUT_QUEUE_CAPACITY
        )

    // =============================================================
    // FILA DE SAÍDA
    // =============================================================

    private data class OutputAudio(
        val audioId: String,
        val pcmData: ByteArray,
        val sampleRate: Int
    )

    private val outputQueue =
        LinkedBlockingQueue<OutputAudio>(
            OUTPUT_QUEUE_CAPACITY
        )

    // =============================================================
    // ÁUDIOS JÁ BAIXADOS
    // =============================================================

    private val downloadedAudioIds =
        HashSet<String>()

    private val downloadedAudioLock =
        Any()

    // =============================================================
    // THREADS
    // =============================================================

    private var captureThread: Thread? = null

    private var sendThread: Thread? = null

    private var downloadThread: Thread? = null

    private var playbackThread: Thread? = null

    // =============================================================
    // DIAGNÓSTICO
    // =============================================================

    @Volatile
    private var capturedChunks = 0L

    @Volatile
    private var sentChunks = 0L

    @Volatile
    private var downloadedAudios = 0L

    @Volatile
    private var playedAudios = 0L

    @Volatile
    private var lastError: String? = null

    // =============================================================
    // CREATE
    // =============================================================

    override fun onCreate() {
        super.onCreate()

        criarCanalNotificacao()

        Log.d(TAG, "====================================")
        Log.d(TAG, "SI TRADUTOR LIVE")
        Log.d(TAG, "PLAYER PIPER ATIVO")
        Log.d(TAG, "Backend: $BACKEND_URL")
        Log.d(TAG, "STT: Deepgram")
        Log.d(TAG, "Translation: DeepL Text")
        Log.d(TAG, "TTS: Piper")
        Log.d(TAG, "====================================")
    }

    // =============================================================
    // START COMMAND
    // =============================================================

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        val action =
            intent?.action

        Log.d(
            TAG,
            "onStartCommand action=$action"
        )

        // ---------------------------------------------------------
        // STOP
        // ---------------------------------------------------------

        if (action == ACTION_STOP) {

            Log.d(
                TAG,
                "ACTION_STOP recebido"
            )

            pararServico(
                enviarStop = true
            )

            return START_NOT_STICKY
        }

        // ---------------------------------------------------------
        // START
        // ---------------------------------------------------------

        if (action != ACTION_START) {

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
            intent.getStringExtra(
                EXTRA_JOB_ID
            ) ?: ""

        if (jobId.isEmpty()) {

            Log.e(
                TAG,
                "ERRO: jobId vazio"
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
                "MediaProjection inválido"
            )

            stopSelf()

            return START_NOT_STICKY
        }

        try {

            // -----------------------------------------------------
            // FOREGROUND
            // -----------------------------------------------------

            iniciarForeground()

            running = true
            stopping = false

            limparEstado()

            // -----------------------------------------------------
            // MEDIA PROJECTION
            // -----------------------------------------------------

            iniciarMediaProjection(
                resultCode,
                resultData
            )

            // -----------------------------------------------------
            // CAPTURA
            // -----------------------------------------------------

            iniciarCaptura()

            // -----------------------------------------------------
            // ENVIO
            // -----------------------------------------------------

            iniciarEnvio()

            // -----------------------------------------------------
            // DOWNLOAD
            // -----------------------------------------------------

            iniciarDownload()

            // -----------------------------------------------------
            // REPRODUÇÃO
            // -----------------------------------------------------

            iniciarPlayback()

            Log.d(
                TAG,
                "===================================="
            )

            Log.d(
                TAG,
                "SI iniciado"
            )

            Log.d(
                TAG,
                "jobId=$jobId"
            )

            Log.d(
                TAG,
                "===================================="
            )

        } catch (e: Exception) {

            lastError =
                e.message

            Log.e(
                TAG,
                "Erro iniciando SI",
                e
            )

            pararServico(
                enviarStop = false
            )
        }

        return START_STICKY
    }

    // =============================================================
    // MEDIA PROJECTION
    // =============================================================

    private fun iniciarMediaProjection(
        resultCode: Int,
        resultData: Intent
    ) {

        val manager =
            getSystemService(
                Context.MEDIA_PROJECTION_SERVICE
            ) as android.media.projection.MediaProjectionManager

        mediaProjection =
            manager.getMediaProjection(
                resultCode,
                resultData
            )

        if (mediaProjection == null) {

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

                        pararServico(
                            enviarStop = true
                        )
                    }
                }
            }

        mediaProjection?.registerCallback(
            projectionCallback!!,
            null
        )
    }

    // =============================================================
    // CAPTURA DO ÁUDIO INTERNO
    // =============================================================

    private fun iniciarCaptura() {

        if (mediaProjection == null) {

            throw IllegalStateException(
                "MediaProjection não disponível"
            )
        }

        val minBuffer =
            AudioRecord.getMinBufferSize(
                INPUT_SAMPLE_RATE,
                INPUT_CHANNEL,
                INPUT_FORMAT
            )

        if (minBuffer <= 0) {

            throw IllegalStateException(
                "AudioRecord.getMinBufferSize falhou"
            )
        }

        val bufferSize =
            maxOf(
                minBuffer * 2,
                INPUT_CHUNK_SIZE * 4
            )

        val captureConfig =
            AudioPlaybackCaptureConfiguration
                .Builder(
                    mediaProjection!!
                )
                .addMatchingUsage(
                    android.media.AudioAttributes.USAGE_MEDIA
                )
                .addMatchingUsage(
                    android.media.AudioAttributes.USAGE_GAME
                )
                .build()

        audioRecord =
            if (
                Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.Q
            ) {

                AudioRecord.Builder()
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(
                                INPUT_FORMAT
                            )
                            .setSampleRate(
                                INPUT_SAMPLE_RATE
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

            } else {

                throw IllegalStateException(
                    "Android abaixo do 10 não suporta captura interna"
                )
            }

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
                        INPUT_CHUNK_SIZE
                    )

                try {

                    audioRecord?.startRecording()

                    captureStarted = true

                    Log.d(
                        TAG,
                        "CAPTURA DE ÁUDIO INICIADA"
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

                        if (
                            !inputQueue.offer(
                                chunk
                            )
                        ) {

                            inputQueue.poll()

                            inputQueue.offer(
                                chunk
                            )

                            Log.w(
                                TAG,
                                "Fila de entrada cheia"
                            )
                        }

                        capturedChunks++

                        if (
                            capturedChunks <= 5 ||
                            capturedChunks % 50L == 0L
                        ) {

                            Log.d(
                                TAG,
                                "CAPTURA=$capturedChunks " +
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

                    captureStarted = false
                }
            }

        captureThread?.start()
    }

    // =============================================================
    // THREAD DE ENVIO
    // =============================================================

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

                        if (chunk == null) {
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

                Log.d(
                    TAG,
                    "THREAD ENVIO FINALIZADA"
                )
            }

        sendThread?.start()
    }

    // =============================================================
    // ENVIA ÁUDIO PARA RENDER
    // =============================================================

    private fun enviarAudio(
        audio: ByteArray
    ) {

        if (
            jobId.isEmpty() ||
            audio.isEmpty()
        ) {
            return
        }

        var connection:
            HttpURLConnection? = null

        try {

            val url =
                URL(
                    "$BACKEND_URL/api/audio/chunk"
                )

            connection =
                url.openConnection()
                    as HttpURLConnection

            connection.requestMethod =
                "POST"

            connection.connectTimeout =
                10000

            connection.readTimeout =
                10000

            connection.doOutput = true
            connection.useCaches = false

            connection.setRequestProperty(
                "Content-Type",
                "application/json; charset=UTF-8"
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
                INPUT_SAMPLE_RATE
            )

            val body =
                json.toString()
                    .toByteArray(
                        Charsets.UTF_8
                    )

            connection.outputStream.use {
                it.write(body)
                it.flush()
            }

            val code =
                connection.responseCode

            if (code in 200..299) {

                sentChunks++

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

                val errorText =
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
                    "Render HTTP $code $errorText"
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

    // =============================================================
    // THREAD DOWNLOAD
    // =============================================================

    private fun iniciarDownload() {

        downloadThread =
            Thread {

                Log.d(
                    TAG,
                    "THREAD DOWNLOAD PIPER INICIADA"
                )

                while (
                    running &&
                    !stopping
                ) {

                    try {

                        consultarStatus()

                        Thread.sleep(
                            STATUS_POLL_MS
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
                                "Erro no download",
                                e
                            )
                        }

                        try {
                            Thread.sleep(500)
                        } catch (_: Exception) {
                        }
                    }
                }

                Log.d(
                    TAG,
                    "THREAD DOWNLOAD FINALIZADA"
                )
            }

        downloadThread?.start()
    }

    // =============================================================
    // CONSULTA STATUS
    // =============================================================

    private fun consultarStatus() {

        if (jobId.isEmpty()) {
            return
        }

        var connection:
            HttpURLConnection? = null

        try {

            val url =
                URL(
                    "$BACKEND_URL/api/audio/status/$jobId"
                )

            connection =
                url.openConnection()
                    as HttpURLConnection

            connection.requestMethod =
                "GET"

            connection.connectTimeout =
                8000

            connection.readTimeout =
                8000

            connection.useCaches = false

            val code =
                connection.responseCode

            if (code !in 200..299) {

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
                JSONObject(text)

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

                downloadedAudioIds.add(
                    audioId
                )
            }

            Log.d(
                TAG,
                "NOVO ÁUDIO PIPER"
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

                removerAudioBaixado(
                    audioId
                )

                return
            }

            Log.d(
                TAG,
                "WAV baixado bytes=${wav.size}"
            )

            val parsed =
                parseWav(
                    wav
                )

            if (parsed == null) {

                removerAudioBaixado(
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
                    audioId =
                        audioId,
                    pcmData =
                        parsed.first,
                    sampleRate =
                        parsed.second
                )

            /*
             * Coloca o áudio na fila local.
             */
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

                Log.e(
                    TAG,
                    "Fila de reprodução cheia"
                )

                return
            }

            downloadedAudios++

            Log.d(
                TAG,
                "ÁUDIO NA FILA"
            )

            Log.d(
                TAG,
                "id=$audioId"
            )

            Log.d(
                TAG,
                "pcm=${item.pcmData.size}"
            )

            Log.d(
                TAG,
                "rate=${item.sampleRate}"
            )

            Log.d(
                TAG,
                "fila=${outputQueue.size}"
            )

            /*
             * Só liberamos o áudio do servidor depois
             * de ele estar seguramente na fila local.
             */
            enviarAck(
                audioId
            )

        } catch (e: Exception) {

            if (running) {

                lastError =
                    e.message

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

    // =============================================================
    // DOWNLOAD WAV
    // =============================================================

    private fun baixarWav(
        urlString: String
    ): ByteArray? {

        var connection:
            HttpURLConnection? = null

        try {

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
                30000

            connection.useCaches = false

            connection.setRequestProperty(
                "Accept",
                "audio/wav,audio/*"
            )

            val code =
                connection.responseCode

            if (code !in 200..299) {

                Log.e(
                    TAG,
                    "Download WAV HTTP=$code"
                )

                return null
            }

            val output =
                ByteArrayOutputStream()

            connection.inputStream.use {
                input ->

                val buffer =
                    ByteArray(8192)

                while (true) {

                    val read =
                        input.read(
                            buffer
                        )

                    if (read == -1) {
                        break
                    }

                    if (read > 0) {

                        output.write(
                            buffer,
                            0,
                            read
                        )
                    }
                }
            }

            return output.toByteArray()

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Erro baixando WAV",
                e
            )

            return null

        } finally {

            connection?.disconnect()
        }
    }

    // =============================================================
    // PARSER WAV
    // =============================================================

    /*
     * Converte WAV PCM16 para:
     *
     * PCM16 MONO
     *
     * retornando:
     *
     * Pair(
     *     pcm,
     *     sampleRate
     * )
     */
    private fun parseWav(
        wav: ByteArray
    ): Pair<ByteArray, Int>? {

        try {

            if (wav.size < 44) {

                Log.e(
                    TAG,
                    "WAV muito pequeno"
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

            fun cc(
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
                cc(0) != "RIFF" ||
                cc(8) != "WAVE"
            ) {

                Log.e(
                    TAG,
                    "Não é WAV RIFF"
                )

                return null
            }

            var offset = 12

            var audioFormat = 0
            var channels = 0
            var sampleRate = 0
            var bits = 0

            var dataStart = -1
            var dataSize = 0

            while (
                offset + 8 <= wav.size
            ) {

                val chunkId =
                    cc(offset)

                val chunkSize =
                    intLE(
                        offset + 4
                    )

                if (chunkSize < 0) {
                    return null
                }

                val dataOffset =
                    offset + 8

                if (
                    dataOffset > wav.size
                ) {
                    return null
                }

                if (
                    chunkId == "fmt "
                ) {

                    if (
                        chunkSize >= 16 &&
                        dataOffset + 16 <= wav.size
                    ) {

                        audioFormat =
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
                }

                if (
                    chunkId == "data"
                ) {

                    dataStart =
                        dataOffset

                    dataSize =
                        minOf(
                            chunkSize,
                            wav.size -
                                dataOffset
                        )

                    break
                }

                var next =
                    dataOffset +
                        chunkSize

                /*
                 * WAV chunks podem ter padding
                 * para tamanho par.
                 */
                if (
                    chunkSize % 2 != 0
                ) {
                    next++
                }

                if (
                    next <= offset
                ) {
                    return null
                }

                offset = next
            }

            if (
                audioFormat != 1
            ) {

                Log.e(
                    TAG,
                    "WAV não é PCM: format=$audioFormat"
                )

                return null
            }

            if (
                sampleRate <= 0
            ) {

                Log.e(
                    TAG,
                    "Sample rate inválido"
                )

                return null
            }

            if (
                channels <= 0
            ) {

                Log.e(
                    TAG,
                    "Número de canais inválido"
                )

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

            if (
                dataStart < 0 ||
                dataSize <= 0 ||
                dataStart + dataSize > wav.size
            ) {

                Log.e(
                    TAG,
                    "Chunk data inválido"
                )

                return null
            }

            var pcm =
                wav.copyOfRange(
                    dataStart,
                    dataStart + dataSize
                )

            /*
             * PCM16 precisa ter quantidade par.
             */
            if (
                pcm.size % 2 != 0
            ) {

                pcm =
                    pcm.copyOf(
                        pcm.size - 1
                    )
            }

            if (pcm.isEmpty()) {
                return null
            }

            /*
             * Piper normalmente já entrega mono.
             *
             * Se vier estéreo, fazemos downmix
             * para mono.
             */
            if (channels > 1) {

                val frameBytes =
                    channels * 2

                val frames =
                    pcm.size / frameBytes

                val mono =
                    ByteArray(
                        frames * 2
                    )

                var frame = 0

                while (
                    frame < frames
                ) {

                    var sum = 0L

                    var channel = 0

                    while (
                        channel < channels
                    ) {

                        val index =
                            frame *
                                frameBytes +
                                channel * 2

                        val sample =
                            (
                                (pcm[index].toInt() and 0xff) or
                                    (pcm[index + 1].toInt() shl 8)
                                )

                        val signed =
                            if (
                                sample and 0x8000 != 0
                            ) {
                                sample - 65536
                            } else {
                                sample
                            }

                        sum += signed

                        channel++
                    }

                    var monoSample =
                        (
                            sum /
                                channels
                            ).toInt()

                    if (
                        monoSample > 32767
                    ) {
                        monoSample = 32767
                    }

                    if (
                        monoSample < -32768
                    ) {
                        monoSample = -32768
                    }

                    val outputIndex =
                        frame * 2

                    mono[outputIndex] =
                        (
                            monoSample and 0xff
                            ).toByte()

                    mono[outputIndex + 1] =
                        (
                            (monoSample shr 8)
                            and 0xff
                            ).toByte()

                    frame++
                }

                pcm = mono
            }

            Log.d(
                TAG,
                "WAV OK: " +
                    "rate=$sampleRate " +
                    "channels=$channels " +
                    "bits=$bits " +
                    "pcm=${pcm.size}"
            )

            return Pair(
                pcm,
                sampleRate
            )

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Erro analisando WAV",
                e
            )

            return null
        }
    }

    // =============================================================
    // ACK
    // =============================================================

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

            val url =
                URL(
                    "$BACKEND_URL/api/audio/ack/" +
                        "$jobId/$audioId"
                )

            connection =
                url.openConnection()
                    as HttpURLConnection

            connection.requestMethod =
                "POST"

            connection.connectTimeout =
                5000

            connection.readTimeout =
                5000

            connection.doOutput = true
            connection.useCaches = false

            connection.setRequestProperty(
                "Content-Type",
                "application/json"
            )

            connection.outputStream.use {
                it.write(
                    "{}".toByteArray()
                )
                it.flush()
            }

            val code =
                connection.responseCode

            Log.d(
                TAG,
                "ACK audio=$audioId HTTP=$code"
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
    }

    // =============================================================
    // THREAD DE PLAYBACK
    // =============================================================

    private fun iniciarPlayback() {

        playbackThread =
            Thread {

                Log.d(
                    TAG,
                    "===================================="
                )

                Log.d(
                    TAG,
                    "THREAD PLAYBACK PIPER INICIADA"
                )

                Log.d(
                    TAG,
                    "===================================="
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

                        if (item == null) {
                            continue
                        }

                        Log.d(
                            TAG,
                            "TOCANDO ÁUDIO"
                        )

                        Log.d(
                            TAG,
                            "id=${item.audioId}"
                        )

                        Log.d(
                            TAG,
                            "bytes=${item.pcmData.size}"
                        )

                        Log.d(
                            TAG,
                            "sampleRate=${item.sampleRate}"
                        )

                        tocarAudio(
                            item
                        )

                        playedAudios++

                        Log.d(
                            TAG,
                            "ÁUDIO TERMINOU"
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
                                "ERRO NO PLAYBACK",
                                e
                            )
                        }
                    }
                }

                liberarAudioTrack()

                Log.d(
                    TAG,
                    "THREAD PLAYBACK FINALIZADA"
                )
            }

        playbackThread?.start()
    }

    // =============================================================
    // TOCAR PCM
    // =============================================================

    private fun tocarAudio(
        item: OutputAudio
    ) {

        if (
            item.pcmData.isEmpty()
        ) {
            return
        }

        synchronized(
            audioTrackLock
        ) {

            /*
             * Se mudou o sample rate,
             * recria o AudioTrack.
             */
            if (
                audioTrack == null ||
                outputSampleRate !=
                item.sampleRate
            ) {

                liberarAudioTrack()

                outputSampleRate =
                    item.sampleRate

                criarAudioTrack(
                    item.sampleRate
                )
            }

            val track =
                audioTrack
                    ?: throw IllegalStateException(
                        "AudioTrack não existe"
                    )

            /*
             * Garante que está tocando.
             */
            if (
                track.playState !=
                AudioTrack.PLAYSTATE_PLAYING
            ) {

                Log.d(
                    TAG,
                    "AudioTrack não estava PLAYING. Iniciando."
                )

                track.play()
            }

            /*
             * Volume máximo normal do AudioTrack.
             *
             * O volume final do aparelho continua
             * sendo controlado pelo volume de mídia.
             */
            try {
                track.setVolume(1.0f)
            } catch (_: Exception) {
            }

            var offset = 0

            /*
             * Blocos pequenos o suficiente para
             * manter a reprodução estável.
             */
            val blockSize =
                4096

            while (
                offset <
                    item.pcmData.size &&
                running &&
                !stopping
            ) {

                val remaining =
                    item.pcmData.size -
                        offset

                val writeSize =
                    minOf(
                        remaining,
                        blockSize
                    )

                val written =
                    track.write(
                        item.pcmData,
                        offset,
                        writeSize,
                        AudioTrack.WRITE_BLOCKING
                    )

                if (written <= 0) {

                    Log.e(
                        TAG,
                        "AudioTrack.write=$written"
                    )

                    break
                }

                offset += written
            }

            Log.d(
                TAG,
                "Playback concluído " +
                    "id=${item.audioId} " +
                    "bytes=$offset/${item.pcmData.size}"
            )
        }
    }

    // =============================================================
    // CRIAR AUDIO TRACK
    // =============================================================

    private fun criarAudioTrack(
        sampleRate: Int
    ) {

        if (sampleRate <= 0) {

            throw IllegalArgumentException(
                "Sample rate inválido: $sampleRate"
            )
        }

        val minBuffer =
            AudioTrack.getMinBufferSize(
                sampleRate,
                OUTPUT_CHANNEL,
                OUTPUT_FORMAT
            )

        if (minBuffer <= 0) {

            throw IllegalStateException(
                "AudioTrack.getMinBufferSize falhou"
            )
        }

        /*
         * Buffer maior para evitar cortes/pipocos.
         */
        val bufferSize =
            maxOf(
                minBuffer * 4,
                16384
            )

        Log.d(
            TAG,
            "Criando AudioTrack"
        )

        Log.d(
            TAG,
            "sampleRate=$sampleRate"
        )

        Log.d(
            TAG,
            "minBuffer=$minBuffer"
        )

        Log.d(
            TAG,
            "buffer=$bufferSize"
        )

        val track =
            if (
                Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.M
            ) {

                AudioTrack.Builder()
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
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(
                                OUTPUT_FORMAT
                            )
                            .setSampleRate(
                                sampleRate
                            )
                            .setChannelMask(
                                OUTPUT_CHANNEL
                            )
                            .build()
                    )
                    .setBufferSizeInBytes(
                        bufferSize
                    )
                    .setTransferMode(
                        AudioTrack.MODE_STREAM
                    )
                    .build()

            } else {

                @Suppress("DEPRECATION")
                AudioTrack(
                    AudioManager.STREAM_MUSIC,
                    sampleRate,
                    OUTPUT_CHANNEL,
                    OUTPUT_FORMAT,
                    bufferSize,
                    AudioTrack.MODE_STREAM
                )
            }

        if (
            track.state !=
            AudioTrack.STATE_INITIALIZED
        ) {

            try {
                track.release()
            } catch (_: Exception) {
            }

            throw IllegalStateException(
                "AudioTrack não foi inicializado"
            )
        }

        try {
            track.setVolume(1.0f)
        } catch (_: Exception) {
        }

        track.play()

        audioTrack =
            track

        Log.d(
            TAG,
            "AudioTrack PLAYING"
        )
    }

    // =============================================================
    // LIBERAR AUDIO TRACK
    // =============================================================

    private fun liberarAudioTrack() {

        synchronized(
            audioTrackLock
        ) {

            val track =
                audioTrack

            audioTrack =
                null

            if (track == null) {
                return
            }

            try {
                track.pause()
            } catch (_: Exception) {
            }

            try {
                track.flush()
            } catch (_: Exception) {
            }

            try {
                track.stop()
            } catch (_: Exception) {
            }

            try {
                track.release()
            } catch (_: Exception) {
            }

            Log.d(
                TAG,
                "AudioTrack liberado"
            )
        }
    }

    // =============================================================
    // NOTIFICAÇÃO
    // =============================================================

    private fun criarCanalNotificacao() {

        if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.O
        ) {

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
    }

    // =============================================================
    // FOREGROUND
    // =============================================================

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
                        "SI Tradutor"
                    )
                    .setContentText(
                        "Tradução de áudio em tempo real"
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
                        "SI Tradutor"
                    )
                    .setContentText(
                        "Tradução de áudio em tempo real"
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

    // =============================================================
    // LIMPAR ESTADO
    // =============================================================

    private fun limparEstado() {

        inputQueue.clear()

        outputQueue.clear()

        synchronized(
            downloadedAudioLock
        ) {

            downloadedAudioIds.clear()
        }

        capturedChunks = 0
        sentChunks = 0
        downloadedAudios = 0
        playedAudios = 0

        lastError = null
    }

    // =============================================================
    // REMOVER ID
    // =============================================================

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

    // =============================================================
    // PARAR SERVIÇO
    // =============================================================

    private fun pararServico(
        enviarStop: Boolean
    ) {

        if (stopping) {
            return
        }

        stopping = true
        running = false

        Log.d(
            TAG,
            "===================================="
        )

        Log.d(
            TAG,
            "PARANDO SI"
        )

        Log.d(
            TAG,
            "captured=$capturedChunks"
        )

        Log.d(
            TAG,
            "sent=$sentChunks"
        )

        Log.d(
            TAG,
            "downloaded=$downloadedAudios"
        )

        Log.d(
            TAG,
            "played=$playedAudios"
        )

        Log.d(
            TAG,
            "lastError=$lastError"
        )

        Log.d(
            TAG,
            "===================================="
        )

        // ---------------------------------------------------------
        // THREADS
        // ---------------------------------------------------------

        try {
            captureThread?.interrupt()
        } catch (_: Exception) {
        }

        try {
            sendThread?.interrupt()
        } catch (_: Exception) {
        }

        try {
            downloadThread?.interrupt()
        } catch (_: Exception) {
        }

        try {
            playbackThread?.interrupt()
        } catch (_: Exception) {
        }

        // ---------------------------------------------------------
        // AUDIO RECORD
        // ---------------------------------------------------------

        try {
            audioRecord?.stop()
        } catch (_: Exception) {
        }

        try {
            audioRecord?.release()
        } catch (_: Exception) {
        }

        audioRecord = null

        captureStarted = false

        // ---------------------------------------------------------
        // AUDIO TRACK
        // ---------------------------------------------------------

        liberarAudioTrack()

        // ---------------------------------------------------------
        // MEDIA PROJECTION
        // ---------------------------------------------------------

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

        mediaProjection = null
        projectionCallback = null

        // ---------------------------------------------------------
        // FILAS
        // ---------------------------------------------------------

        inputQueue.clear()
        outputQueue.clear()

        // ---------------------------------------------------------
        // BACKEND
        // ---------------------------------------------------------

        if (
            enviarStop &&
            jobId.isNotEmpty()
        ) {

            val oldJob =
                jobId

            Thread {

                enviarStopAoServidor(
                    oldJob
                )

            }.start()
        }

        // ---------------------------------------------------------
        // FOREGROUND
        // ---------------------------------------------------------

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

        Log.d(
            TAG,
            "SI PARADO"
        )
    }

    // =============================================================
    // STOP NO BACKEND
    // =============================================================

    private fun enviarStopAoServidor(
        stopJobId: String
    ) {

        var connection:
            HttpURLConnection? = null

        try {

            val url =
                URL(
                    "$BACKEND_URL/api/audio/stop/$stopJobId"
                )

            connection =
                url.openConnection()
                    as HttpURLConnection

            connection.requestMethod =
                "POST"

            connection.connectTimeout =
                5000

            connection.readTimeout =
                5000

            connection.doOutput = true

            connection.setRequestProperty(
                "Content-Type",
                "application/json"
            )

            connection.outputStream.use {
                it.write(
                    "{}".toByteArray()
                )
                it.flush()
            }

            Log.d(
                TAG,
                "STOP Render HTTP=${connection.responseCode}"
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

    // =============================================================
    // DESTROY
    // =============================================================

    override fun onDestroy() {

        Log.d(
            TAG,
            "onDestroy"
        )

        if (!stopping) {

            pararServico(
                enviarStop = false
            )
        }

        super.onDestroy()
    }

    // =============================================================
    // BIND
    // =============================================================

    override fun onBind(
        intent: Intent?
    ): IBinder? {

        return null
    }
}
