package com.si.tradutor

import android.Manifest
import android.app.Activity
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
import androidx.core.content.IntentCompat
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread


class AudioCaptureService : Service() {

    companion object {

        private const val TAG = "SI_AUDIO"

        private const val BACKEND_URL =
            "https://si-u2ul.onrender.com"

        // =====================================================
        // AÇÕES
        // =====================================================

        const val ACTION_START =
            "com.si.tradutor.ACTION_START"

        const val ACTION_STOP =
            "com.si.tradutor.ACTION_STOP"

        // =====================================================
        // EXTRAS
        // =====================================================

        const val EXTRA_JOB_ID =
            "jobId"

        const val EXTRA_RESULT_CODE =
            "resultCode"

        const val EXTRA_RESULT_DATA =
            "resultData"

        const val EXTRA_TARGET_LANGUAGE =
            "targetLanguage"

        // =====================================================
        // NOTIFICAÇÃO
        // =====================================================

        private const val CHANNEL_ID =
            "si_audio_capture"

        private const val NOTIFICATION_ID =
            1001

        // =====================================================
        // CAPTURA
        // =====================================================

        private const val INPUT_SAMPLE_RATE =
            16000

        private const val INPUT_CHANNEL =
            AudioFormat.CHANNEL_IN_MONO

        private const val INPUT_FORMAT =
            AudioFormat.ENCODING_PCM_16BIT

        /*
         * 100 ms de PCM16 mono 16 kHz
         */
        private const val INPUT_CHUNK_SIZE =
            3200

        private const val INPUT_QUEUE_CAPACITY =
            120

        // =====================================================
        // STATUS
        // =====================================================

        private const val STATUS_POLL_MS =
            250L
    }


    // =========================================================
    // ANDROID
    // =========================================================

    private var mediaProjection:
        MediaProjection? = null

    private var audioRecord:
        AudioRecord? = null

    private var audioManager:
        AudioManager? = null


    // =========================================================
    // ESTADO
    // =========================================================

    private val running =
        AtomicBoolean(false)

    @Volatile
    private var jobId: String = ""

    @Volatile
    private var targetLanguage: String = "pt"

    @Volatile
    private var lastAudioId: String = ""

    @Volatile
    private var lastTranslatedText: String = ""

    @Volatile
    private var lastStage: String = "created"


    // =========================================================
    // FILA DE CAPTURA
    // =========================================================

    private val inputQueue =
        LinkedBlockingQueue<ByteArray>(
            INPUT_QUEUE_CAPACITY
        )


    // =========================================================
    // FILA DE ÁUDIO PIPER
    // =========================================================

    /*
     * Aqui entram as URLs dos WAVs gerados pelo Piper.
     *
     * O áudio não interrompe o áudio anterior.
     */
    private val playbackQueue =
        LinkedBlockingQueue<String>(
            100
        )


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
    private var capturedBytes =
        0L

    @Volatile
    private var sentBytes =
        0L

    @Volatile
    private var sentChunks =
        0L

    @Volatile
    private var sendErrors =
        0L

    @Volatile
    private var playedFiles =
        0L

    @Volatile
    private var playbackErrors =
        0L


    // =========================================================
    // MEDIA PROJECTION CALLBACK
    // =========================================================

    private val projectionCallback =
        object : MediaProjection.Callback() {

            override fun onStop() {

                Log.w(
                    TAG,
                    "MediaProjection foi encerrada"
                )

                lastStage =
                    "projection_stopped"

                pararTudo()
            }
        }


    // =========================================================
    // ON CREATE
    // =========================================================

    override fun onCreate() {

        super.onCreate()

        audioManager =
            getSystemService(
                Context.AUDIO_SERVICE
            ) as AudioManager

        criarCanalNotificacao()

        Log.d(
            TAG,
            "AudioCaptureService criado"
        )
    }


    // =========================================================
    // ON START COMMAND
    // =========================================================

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

                val receivedJobId =
                    intent.getStringExtra(
                        EXTRA_JOB_ID
                    )

                val receivedLanguage =
                    intent.getStringExtra(
                        EXTRA_TARGET_LANGUAGE
                    )

                val resultCode =
                    intent.getIntExtra(
                        EXTRA_RESULT_CODE,
                        Activity.RESULT_CANCELED
                    )

                val resultData =
                    try {

                        IntentCompat.getParcelableExtra(
                            intent,
                            EXTRA_RESULT_DATA,
                            Intent::class.java
                        )

                    } catch (e: Exception) {

                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(
                            EXTRA_RESULT_DATA
                        )
                    }


                if (
                    receivedJobId.isNullOrBlank()
                ) {

                    Log.e(
                        TAG,
                        "jobId não recebido"
                    )

                    return START_NOT_STICKY
                }


                if (
                    resultData == null
                ) {

                    Log.e(
                        TAG,
                        "resultData não recebido"
                    )

                    return START_NOT_STICKY
                }


                jobId =
                    receivedJobId

                targetLanguage =
                    normalizarIdioma(
                        receivedLanguage
                    )


                Log.d(
                    TAG,
                    "JOB: $jobId"
                )

                Log.d(
                    TAG,
                    "IDIOMA: $targetLanguage"
                )


                iniciarForeground()

                iniciarCaptura(
                    resultCode,
                    resultData
                )
            }


            ACTION_STOP -> {

                pararTudo()
            }
        }


        return START_NOT_STICKY
    }


    // =========================================================
    // NORMALIZAR IDIOMA
    // =========================================================

    private fun normalizarIdioma(
        language: String?
    ): String {

        if (
            language.isNullOrBlank()
        ) {
            return "pt"
        }

        return when (
            language.lowercase()
        ) {

            "pt-br",
            "pt_br",
            "portuguese",
            "portugues",
            "português" ->
                "pt"

            "en-us",
            "en_us",
            "english",
            "ingles",
            "inglês" ->
                "en"

            "es-es",
            "es_es",
            "spanish",
            "espanhol" ->
                "es"

            "fr-fr",
            "fr_fr",
            "french",
            "frances",
            "francês" ->
                "fr"

            "de-de",
            "de_de",
            "german",
            "alemao",
            "alemão" ->
                "de"

            "it-it",
            "it_it",
            "italian",
            "italiano" ->
                "it"

            "ja-jp",
            "ja_jp",
            "japanese",
            "japones",
            "japonês" ->
                "ja"

            "ko-kr",
            "ko_kr",
            "korean",
            "coreano" ->
                "ko"

            "zh-cn",
            "zh_cn",
            "chinese",
            "chines",
            "chinês" ->
                "zh"

            "ru",
            "russian",
            "russo" ->
                "ru"

            "ar",
            "arabic",
            "arabe",
            "árabe" ->
                "ar"

            "hi",
            "hindi" ->
                "hi"

            "tr",
            "turkish",
            "turco" ->
                "tr"

            "nl",
            "dutch",
            "holandes",
            "holandês" ->
                "nl"

            "pl",
            "polish",
            "polones",
            "polonês" ->
                "pl"

            "uk",
            "ukrainian",
            "ucraniano" ->
                "uk"

            "id",
            "indonesian",
            "indonesio",
            "indonésio" ->
                "id"

            "vi",
            "vietnamese",
            "vietnamita" ->
                "vi"

            else ->
                "pt"
        }
    }


    // =========================================================
    // FOREGROUND
    // =========================================================

    private fun iniciarForeground() {

        val notification: Notification =
            NotificationCompat.Builder(
                this,
                CHANNEL_ID
            )
                .setContentTitle(
                    "SI Tradutor Live"
                )
                .setContentText(
                    "Tradução e dublagem em tempo real"
                )
                .setSmallIcon(
                    R.drawable.si_logo
                )
                .setOngoing(true)
                .setCategory(
                    NotificationCompat.CATEGORY_SERVICE
                )
                .build()


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
    // INICIAR CAPTURA
    // =========================================================

    private fun iniciarCaptura(
        resultCode: Int,
        resultData: Intent
    ) {

        if (
            running.get()
        ) {

            Log.d(
                TAG,
                "Serviço já está rodando"
            )

            return
        }


        if (
            Build.VERSION.SDK_INT <
            Build.VERSION_CODES.Q
        ) {

            Log.e(
                TAG,
                "Android abaixo do 10 não é suportado"
            )

            return
        }


        if (
            checkSelfPermission(
                Manifest.permission.RECORD_AUDIO
            ) !=
            PackageManager.PERMISSION_GRANTED
        ) {

            Log.e(
                TAG,
                "RECORD_AUDIO não autorizado"
            )

            return
        }


        try {

            running.set(true)

            lastAudioId = ""
            lastTranslatedText = ""

            inputQueue.clear()
            playbackQueue.clear()


            // =================================================
            // MEDIA PROJECTION
            // =================================================

            val projectionManager =
                getSystemService(
                    Context.MEDIA_PROJECTION_SERVICE
                ) as MediaProjectionManager


            mediaProjection =
                projectionManager.getMediaProjection(
                    resultCode,
                    resultData
                )


            if (
                mediaProjection == null
            ) {

                throw Exception(
                    "MediaProjection retornou null"
                )
            }


            mediaProjection?.registerCallback(
                projectionCallback,
                null
            )


            // =================================================
            // AUDIO RECORD
            // =================================================

            criarAudioRecord()


            // =================================================
            // THREAD DE CAPTURA
            // =================================================

            captureThread =
                thread(
                    start = true,
                    name = "SI-Capture"
                ) {

                    capturarAudio()
                }


            // =================================================
            // THREAD DE ENVIO
            // =================================================

            sendThread =
                thread(
                    start = true,
                    name = "SI-Send"
                ) {

                    enviarChunks()
                }


            // =================================================
            // THREAD DE STATUS
            // =================================================

            statusThread =
                thread(
                    start = true,
                    name = "SI-Status"
                ) {

                    monitorarStatus()
                }


            // =================================================
            // THREAD DE PLAYBACK PIPER
            // =================================================

            playbackThread =
                thread(
                    start = true,
                    name = "SI-Piper-Playback"
                ) {

                    reproduzirFilaPiper()
                }


            lastStage =
                "running"


            enviarDiagnostico(
                "running"
            )


            Log.d(
                TAG,
                "================================"
            )

            Log.d(
                TAG,
                "SI LIVE INICIADO"
            )

            Log.d(
                TAG,
                "Entrada: PCM16 / 16kHz / mono"
            )

            Log.d(
                TAG,
                "Voz: Piper"
            )

            Log.d(
                TAG,
                "================================"
            )


        } catch (e: Exception) {

            Log.e(
                TAG,
                "Erro ao iniciar",
                e
            )

            lastStage =
                "start_error"

            enviarDiagnostico(
                "start_error:${e.message}"
            )

            pararTudo()
        }
    }


    // =========================================================
    // AUDIO RECORD
    // =========================================================

    private fun criarAudioRecord() {

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
                .addMatchingUsage(
                    android.media.AudioAttributes.USAGE_UNKNOWN
                )
                .build()


        val format =
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


        val minBuffer =
            AudioRecord.getMinBufferSize(
                INPUT_SAMPLE_RATE,
                INPUT_CHANNEL,
                INPUT_FORMAT
            )


        if (
            minBuffer <= 0
        ) {

            throw Exception(
                "AudioRecord minBuffer inválido"
            )
        }


        val bufferSize =
            maxOf(
                minBuffer * 3,
                INPUT_CHUNK_SIZE * 8
            )


        audioRecord =
            AudioRecord.Builder()
                .setAudioFormat(
                    format
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
                "AudioRecord não inicializou"
            )
        }


        Log.d(
            TAG,
            "AudioRecord pronto: $bufferSize bytes"
        )
    }


    // =========================================================
    // CAPTURAR ÁUDIO
    // =========================================================

    private fun capturarAudio() {

        val record =
            audioRecord
                ?: return


        try {

            record.startRecording()


            Log.d(
                TAG,
                "Captura de áudio iniciada"
            )


            while (
                running.get()
            ) {

                val buffer =
                    ByteArray(
                        INPUT_CHUNK_SIZE
                    )


                val count =
                    record.read(
                        buffer,
                        0,
                        buffer.size
                    )


                if (
                    count <= 0
                ) {

                    continue
                }


                capturedBytes +=
                    count.toLong()


                val data =
                    if (
                        count ==
                        buffer.size
                    ) {

                        buffer

                    } else {

                        buffer.copyOf(
                            count
                        )
                    }


                /*
                 * Se a fila estiver cheia,
                 * descartamos o pedaço mais antigo.
                 *
                 * Isso evita acumular atraso.
                 */

                if (
                    !inputQueue.offer(
                        data
                    )
                ) {

                    inputQueue.poll()

                    inputQueue.offer(
                        data
                    )
                }
            }

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Erro na captura",
                e
            )

            lastStage =
                "capture_error"

        } finally {

            try {
                record.stop()
            } catch (_: Exception) {
            }
        }
    }


    // =========================================================
    // ENVIAR CHUNKS PARA RENDER
    // =========================================================

    private fun enviarChunks() {

        while (
            running.get()
        ) {

            try {

                val data =
                    inputQueue.poll(
                        500,
                        TimeUnit.MILLISECONDS
                    )


                if (
                    data == null
                ) {
                    continue
                }


                val encoded =
                    Base64.encodeToString(
                        data,
                        Base64.NO_WRAP
                    )


                val json =
                    org.json.JSONObject()


                json.put(
                    "jobId",
                    jobId
                )


                json.put(
                    "audio",
                    encoded
                )


                /*
                 * IMPORTANTE:
                 *
                 * Agora o backend sabe qual voz Piper
                 * deve utilizar.
                 */

                json.put(
                    "targetLanguage",
                    targetLanguage
                )


                json.put(
                    "mimeType",
                    "audio/pcm"
                )


                json.put(
                    "sampleRate",
                    INPUT_SAMPLE_RATE
                )


                val url =
                    URL(
                        "$BACKEND_URL/api/audio/chunk"
                    )


                val connection =
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


                connection.outputStream.use {
                    output ->

                    output.write(
                        json.toString()
                            .toByteArray(
                                Charsets.UTF_8
                            )
                    )
                }


                val responseCode =
                    connection.responseCode


                if (
                    responseCode in 200..299
                ) {

                    sentChunks++

                    sentBytes +=
                        data.size.toLong()

                } else {

                    sendErrors++

                    Log.w(
                        TAG,
                        "Backend chunk HTTP $responseCode"
                    )
                }


                connection.disconnect()


            } catch (e: Exception) {

                sendErrors++

                Log.w(
                    TAG,
                    "Erro enviando áudio: ${e.message}"
                )


                /*
                 * Pequena pausa para não fazer
                 * centenas de tentativas por segundo.
                 */

                try {
                    Thread.sleep(150)
                } catch (_: Exception) {
                }
            }
        }
    }


    // =========================================================
    // STATUS DO BACKEND
    // =========================================================

    private fun monitorarStatus() {

        while (
            running.get()
        ) {

            try {

                val url =
                    URL(
                        "$BACKEND_URL/api/audio/status/$jobId"
                    )


                val connection =
                    url.openConnection()
                        as HttpURLConnection


                connection.requestMethod =
                    "GET"

                connection.connectTimeout =
                    8000

                connection.readTimeout =
                    10000


                val responseCode =
                    connection.responseCode


                if (
                    responseCode == 200
                ) {

                    val text =
                        connection.inputStream
                            .bufferedReader()
                            .use {
                                it.readText()
                            }


                    processarStatus(
                        text
                    )
                }


                connection.disconnect()


            } catch (e: Exception) {

                Log.w(
                    TAG,
                    "Erro status: ${e.message}"
                )
            }


            try {

                Thread.sleep(
                    STATUS_POLL_MS
                )

            } catch (_: Exception) {
            }
        }
    }


    // =========================================================
    // PROCESSAR STATUS
    // =========================================================

    private fun processarStatus(
        jsonText: String
    ) {

        try {

            val json =
                org.json.JSONObject(
                    jsonText
                )


            if (
                !json.optBoolean(
                    "ok",
                    false
                )
            ) {
                return
            }


            val status =
                json.optString(
                    "status",
                    ""
                )


            val sourceText =
                json.optString(
                    "sourceText",
                    ""
                )


            val translatedText =
                json.optString(
                    "translatedText",
                    ""
                )


            val audioUrl =
                json.optString(
                    "audioUrl",
                    ""
                )


            val audioId =
                json.optString(
                    "audioId",
                    ""
                )


            if (
                translatedText.isNotBlank() &&
                translatedText != lastTranslatedText
            ) {

                lastTranslatedText =
                    translatedText


                Log.d(
                    TAG,
                    "TRADUÇÃO: $translatedText"
                )
            }


            /*
             * NOVO SISTEMA:
             *
             * O servidor gera:
             *
             * /api/audio/file/xxxx.wav
             *
             * Só colocamos na fila uma vez.
             */

            if (
                audioUrl.isNotBlank() &&
                audioId.isNotBlank() &&
                audioId != lastAudioId
            ) {

                lastAudioId =
                    audioId


                val fullUrl =
                    if (
                        audioUrl.startsWith(
                            "http"
                        )
                    ) {

                        audioUrl

                    } else {

                        BACKEND_URL +
                            audioUrl
                    }


                if (
                    playbackQueue.offer(
                        fullUrl
                    )
                ) {

                    Log.d(
                        TAG,
                        "WAV Piper colocado na fila"
                    )

                } else {

                    Log.w(
                        TAG,
                        "Fila Piper cheia"
                    )
                }
            }


            if (
                status == "error"
            ) {

                val error =
                    json.optString(
                        "error",
                        "erro desconhecido"
                    )


                Log.e(
                    TAG,
                    "Backend erro: $error"
                )


                lastStage =
                    "backend_error"
            }


        } catch (e: Exception) {

            Log.w(
                TAG,
                "JSON inválido: ${e.message}"
            )
        }
    }


    // =========================================================
    // REPRODUÇÃO PIPER
    // =========================================================

    private fun reproduzirFilaPiper() {

        while (
            running.get()
        ) {

            try {

                val audioUrl =
                    playbackQueue.poll(
                        1000,
                        TimeUnit.MILLISECONDS
                    )


                if (
                    audioUrl == null
                ) {
                    continue
                }


                Log.d(
                    TAG,
                    "Baixando WAV Piper:"
                )

                Log.d(
                    TAG,
                    audioUrl
                )


                val wavBytes =
                    baixarAudio(
                        audioUrl
                    )


                if (
                    wavBytes.isEmpty()
                ) {

                    playbackErrors++

                    continue
                }


                val wav =
                    parseWav(
                        wavBytes
                    )


                if (
                    wav == null
                ) {

                    playbackErrors++

                    Log.e(
                        TAG,
                        "WAV inválido"
                    )

                    continue
                }


                Log.d(
                    TAG,
                    "Piper WAV: " +
                        "${wav.sampleRate} Hz, " +
                        "${wav.channels} canais, " +
                        "${wav.pcm.size} bytes"
                )


                tocarWav(
                    wav
                )


                playedFiles++


            } catch (e: Exception) {

                playbackErrors++

                Log.e(
                    TAG,
                    "Erro reproduzindo Piper",
                    e
                )
            }
        }
    }


    // =========================================================
    // DOWNLOAD DO WAV
    // =========================================================

    private fun baixarAudio(
        audioUrl: String
    ): ByteArray {

        val connection =
            URL(
                audioUrl
            ).openConnection()
                as HttpURLConnection


        connection.requestMethod =
            "GET"

        connection.connectTimeout =
            15000

        connection.readTimeout =
            30000

        connection.setRequestProperty(
            "Accept",
            "audio/wav"
        )


        if (
            connection.responseCode !in
            200..299
        ) {

            val code =
                connection.responseCode

            connection.disconnect()

            throw Exception(
                "WAV HTTP $code"
            )
        }


        val output =
            ByteArrayOutputStream()


        connection.inputStream.use {
            input ->

            val buffer =
                ByteArray(
                    8192
                )

            while (
                true
            ) {

                val count =
                    input.read(
                        buffer
                    )

                if (
                    count <= 0
                ) {
                    break
                }


                output.write(
                    buffer,
                    0,
                    count
                )


                /*
                 * Limite de segurança:
                 * 20 MB por arquivo.
                 */

                if (
                    output.size() >
                    20 * 1024 * 1024
                ) {

                    throw Exception(
                        "WAV muito grande"
                    )
                }
            }
        }


        connection.disconnect()


        return output.toByteArray()
    }


    // =========================================================
    // ESTRUTURA WAV
    // =========================================================

    private data class WavData(

        val sampleRate: Int,

        val channels: Int,

        val bitsPerSample: Int,

        val pcm: ByteArray
    )


    // =========================================================
    // LER WAV
    // =========================================================

    private fun parseWav(
        data: ByteArray
    ): WavData? {

        if (
            data.size < 44
        ) {
            return null
        }


        val input =
            DataInputStream(
                ByteArrayInputStream(
                    data
                )
            )


        fun readFourCC(): String {

            val bytes =
                ByteArray(
                    4
                )

            input.readFully(
                bytes
            )

            return String(
                bytes,
                Charsets.US_ASCII
            )
        }


        fun readIntLE(): Int {

            val b1 =
                input.read()

            val b2 =
                input.read()

            val b3 =
                input.read()

            val b4 =
                input.read()


            return (
                b1 and 0xff
            ) or (
                (b2 and 0xff) shl 8
            ) or (
                (b3 and 0xff) shl 16
            ) or (
                (b4 and 0xff) shl 24
            )
        }


        fun readShortLE(): Int {

            val b1 =
                input.read()

            val b2 =
                input.read()


            return (
                b1 and 0xff
            ) or (
                (b2 and 0xff) shl 8
            )
        }


        try {

            val riff =
                readFourCC()


            if (
                riff != "RIFF"
            ) {
                return null
            }


            readIntLE()


            val wave =
                readFourCC()


            if (
                wave != "WAVE"
            ) {
                return null
            }


            var sampleRate =
                0

            var channels =
                0

            var bits =
                0

            var pcmData:
                ByteArray? = null


            while (
                input.available() >= 8
            ) {

                val chunkId =
                    readFourCC()


                val chunkSize =
                    readIntLE()


                if (
                    chunkSize < 0 ||
                    chunkSize >
                    input.available()
                ) {

                    break
                }


                when (
                    chunkId
                ) {

                    "fmt " -> {

                        val audioFormat =
                            readShortLE()


                        channels =
                            readShortLE()


                        sampleRate =
                            readIntLE()


                        readIntLE()
                        readShortLE()


                        bits =
                            readShortLE()


                        val remaining =
                            chunkSize - 16


                        if (
                            remaining > 0
                        ) {

                            input.skipBytes(
                                remaining
                            )
                        }


                        if (
                            audioFormat != 1
                        ) {

                            Log.w(
                                TAG,
                                "WAV não é PCM"
                            )
                        }
                    }


                    "data" -> {

                        pcmData =
                            ByteArray(
                                chunkSize
                            )


                        input.readFully(
                            pcmData
                        )
                    }


                    else -> {

                        input.skipBytes(
                            chunkSize
                        )
                    }
                }


                /*
                 * WAV pode usar padding em chunks ímpares.
                 */

                if (
                    chunkSize % 2 != 0 &&
                    input.available() > 0
                ) {

                    input.read()
                }
            }


            if (
                sampleRate <= 0 ||
                channels <= 0 ||
                bits <= 0 ||
                pcmData == null
            ) {

                return null
            }


            return WavData(
                sampleRate,
                channels,
                bits,
                pcmData
            )


        } catch (
            e: Exception
        ) {

            Log.e(
                TAG,
                "Erro lendo WAV",
                e
            )

            return null
        }
    }


    // =========================================================
    // TOCAR WAV
    // =========================================================

    private fun tocarWav(
        wav: WavData
    ) {

        /*
         * Piper normalmente produz PCM16.
         */

        if (
            wav.bitsPerSample != 16
        ) {

            throw Exception(
                "Piper WAV não é PCM16"
            )
        }


        val channelConfig =
            if (
                wav.channels == 1
            ) {

                AudioFormat.CHANNEL_OUT_MONO

            } else {

                AudioFormat.CHANNEL_OUT_STEREO
            }


        val minBuffer =
            AudioTrack.getMinBufferSize(
                wav.sampleRate,
                channelConfig,
                AudioFormat.ENCODING_PCM_16BIT
            )


        if (
            minBuffer <= 0
        ) {

            throw Exception(
                "AudioTrack não aceita ${wav.sampleRate} Hz"
            )
        }


        val bufferSize =
            maxOf(
                minBuffer * 4,
                wav.sampleRate * wav.channels * 2
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
                    wav.sampleRate
                )
                .setChannelMask(
                    channelConfig
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
                .setTransferMode(
                    AudioTrack.MODE_STREAM
                )
                .setBufferSizeInBytes(
                    bufferSize
                )
                .build()


        try {

            if (
                track.state !=
                AudioTrack.STATE_INITIALIZED
            ) {

                throw Exception(
                    "AudioTrack não inicializou"
                )
            }


            track.play()


            var offset =
                0


            /*
             * Escreve em blocos.
             *
             * Não usa QUEUE_FLUSH.
             * Não interrompe outra fala.
             */

            while (
                offset <
                wav.pcm.size &&
                running.get()
            ) {

                val remaining =
                    wav.pcm.size -
                    offset


                val block =
                    minOf(
                        remaining,
                        8192
                    )


                val written =
                    track.write(
                        wav.pcm,
                        offset,
                        block,
                        AudioTrack.WRITE_BLOCKING
                    )


                if (
                    written < 0
                ) {

                    throw Exception(
                        "AudioTrack.write retornou $written"
                    )
                }


                offset +=
                    written


                /*
                 * Pequena proteção contra
                 * loop infinito.
                 */

                if (
                    written == 0
                ) {

                    Thread.sleep(
                        5
                    )
                }
            }


            /*
             * Espera o hardware terminar
             * antes de destruir o AudioTrack.
             */

            if (
                running.get()
            ) {

                var guard =
                    0

                while (
                    track.playbackHeadPosition
                        .toLong()
                        <
                    wav.pcm.size /
                        (wav.channels * 2) &&
                    guard < 200
                ) {

                    Thread.sleep(
                        10
                    )

                    guard++
                }
            }


            playedOutputChunks++

            playedOutputBytes +=
                wav.pcm.size.toLong()


        } finally {

            try {
                track.stop()
            } catch (_: Exception) {
            }

            try {
                track.release()
            } catch (_: Exception) {
            }
        }
    }


    // =========================================================
    // DIAGNÓSTICO
    // =========================================================

    private fun enviarDiagnostico(
        stage: String
    ) {

        thread(
            start = true,
            name = "SI-Diagnostic"
        ) {

            try {

                val json =
                    org.json.JSONObject()


                json.put(
                    "jobId",
                    jobId
                )

                json.put(
                    "stage",
                    stage
                )

                json.put(
                    "targetLanguage",
                    targetLanguage
                )

                json.put(
                    "capturedBytes",
                    capturedBytes
                )

                json.put(
                    "sentBytes",
                    sentBytes
                )

                json.put(
                    "sentChunks",
                    sentChunks
                )

                json.put(
                    "sendErrors",
                    sendErrors
                )

                json.put(
                    "playedFiles",
                    playedFiles
                )

                json.put(
                    "playbackErrors",
                    playbackErrors
                )

                json.put(
                    "queueSize",
                    playbackQueue.size
                )


                val connection =
                    URL(
                        "$BACKEND_URL/api/audio/diagnostic"
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
                    output ->

                    output.write(
                        json.toString()
                            .toByteArray(
                                Charsets.UTF_8
                            )
                    )
                }


                connection.responseCode

                connection.disconnect()


            } catch (
                _: Exception
            ) {
                // diagnóstico não pode parar o app
            }
        }
    }


    // =========================================================
    // CANAL DE NOTIFICAÇÃO
    // =========================================================

    private fun criarCanalNotificacao() {

        if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.O
        ) {

            val channel =
                NotificationChannel(
                    CHANNEL_ID,
                    "SI Tradutor",
                    NotificationManager.IMPORTANCE_LOW
                )


            channel.description =
                "Captura e tradução de áudio"


            val manager =
                getSystemService(
                    Context.NOTIFICATION_SERVICE
                ) as NotificationManager


            manager.createNotificationChannel(
                channel
            )
        }
    }


    // =========================================================
    // PARAR TUDO
    // =========================================================

    private fun pararTudo() {

        if (
            !running.getAndSet(false)
        ) {

            return
        }


        Log.d(
            TAG,
            "Parando SI AudioCaptureService"
        )


        lastStage =
            "stopping"


        try {
            inputQueue.clear()
        } catch (_: Exception) {
        }


        try {
            playbackQueue.clear()
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


        captureThread =
            null

        sendThread =
            null

        statusThread =
            null

        playbackThread =
            null


        enviarDiagnostico(
            "stopped"
        )


        try {
            stopForeground(
                STOP_FOREGROUND_REMOVE
            )
        } catch (_: Exception) {
        }


        stopSelf()
    }


    // =========================================================
    // ON DESTROY
    // =========================================================

    override fun onDestroy() {

        pararTudo()

        super.onDestroy()

        Log.d(
            TAG,
            "AudioCaptureService destruído"
        )
    }


    // =========================================================
    // BINDER
    // =========================================================

    override fun onBind(
        intent: Intent?
    ): IBinder? {

        return null
    }
}
