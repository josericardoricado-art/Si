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
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.speech.tts.TextToSpeech
import android.util.Base64
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.IntentCompat
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class AudioCaptureService : Service() {

    companion object {

        private const val TAG = "SI_AUDIO"

        // =========================================================
        // BACKEND
        // =========================================================

        private const val BACKEND_URL =
            "https://si-u2ul.onrender.com"

        // =========================================================
        // COMANDOS
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
        // CAPTURA
        // =========================================================

        private const val INPUT_SAMPLE_RATE =
            16000

        private const val INPUT_CHANNEL =
            AudioFormat.CHANNEL_IN_MONO

        private const val INPUT_FORMAT =
            AudioFormat.ENCODING_PCM_16BIT

        /*
         * 3200 bytes = aproximadamente 100 ms
         * de PCM16 mono em 16 kHz.
         */
        private const val INPUT_CHUNK_SIZE =
            3200

        private const val INPUT_QUEUE_CAPACITY =
            120

        // =========================================================
        // POLLING DA TRADUÇÃO
        // =========================================================

        /*
         * O backend atual fornece a tradução pelo endpoint:
         *
         * /api/audio/status/:jobId
         *
         * Portanto consultamos esse endpoint
         * periodicamente.
         */
        private const val STATUS_POLL_MS =
            250L

        // =========================================================
        // TEXT TO SPEECH
        // =========================================================

        private const val TTS_RATE =
            1.0f

        private const val TTS_PITCH =
            1.0f
    }

    // =========================================================
    // MEDIA PROJECTION
    // =========================================================

    private var mediaProjection:
        MediaProjection? = null

    // =========================================================
    // AUDIO RECORD
    // =========================================================

    private var audioRecord:
        AudioRecord? = null

    // =========================================================
    // AUDIO MANAGER
    // =========================================================

    private var audioManager:
        AudioManager? = null

    // =========================================================
    // TEXT TO SPEECH
    // =========================================================

    private var textToSpeech:
        TextToSpeech? = null

    @Volatile
    private var ttsReady =
        false

    // =========================================================
    // THREADS
    // =========================================================

    private var captureThread:
        Thread? = null

    private var sendThread:
        Thread? = null

    private var statusThread:
        Thread? = null

    private var diagnosticThread:
        Thread? = null

    // =========================================================
    // ESTADO
    // =========================================================

    private val running =
        AtomicBoolean(false)

    @Volatile
    private var captureStarted =
        false

    @Volatile
    private var jobId:
        String? = null

    @Volatile
    private var targetLanguage =
        "pt-BR"

    /*
     * Guarda a última tradução que já foi falada.
     *
     * Isso é importante porque o endpoint /status pode
     * retornar a mesma tradução várias vezes.
     *
     * Sem esse controle, o celular repetiria a mesma frase.
     */
    @Volatile
    private var lastSpokenTranslation =
        ""

    @Volatile
    private var lastSourceText =
        ""

    @Volatile
    private var lastStatus =
        ""

    // =========================================================
    // FILA DE ENTRADA
    // =========================================================

    private val inputQueue =
        LinkedBlockingQueue<ByteArray>(
            INPUT_QUEUE_CAPACITY
        )

    // =========================================================
    // DIAGNÓSTICO
    // =========================================================

    @Volatile
    private var readCount =
        0L

    @Volatile
    private var lastRead =
        0

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
    private var sendErrors =
        0L

    @Volatile
    private var translationCount =
        0L

    @Volatile
    private var ttsCount =
        0L

    @Volatile
    private var lastStage =
        "created"

    // =========================================================
    // MEDIA PROJECTION CALLBACK
    // =========================================================

    private val projectionCallback =
        object : MediaProjection.Callback() {

            override fun onStop() {

                Log.w(
                    TAG,
                    "MediaProjection encerrada pelo Android"
                )

                lastStage =
                    "projection_stopped"

                if (
                    running.get()
                ) {
                    pararTudo()
                }
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

        inicializarTextToSpeech()

        Log.d(
            TAG,
            "SI AudioCaptureService criado"
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

        if (
            intent == null
        ) {
            return START_NOT_STICKY
        }

        when (
            intent.action
        ) {

            ACTION_START -> {

                val id =
                    intent.getStringExtra(
                        EXTRA_JOB_ID
                    )

                val resultCode =
                    intent.getIntExtra(
                        EXTRA_RESULT_CODE,
                        Activity.RESULT_CANCELED
                    )

                val resultData =
                    try {

                        if (
                            Build.VERSION.SDK_INT >=
                            Build.VERSION_CODES.TIRAMISU
                        ) {

                            IntentCompat.getParcelableExtra(
                                intent,
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

                    } catch (
                        _: Exception
                    ) {
                        null
                    }

                if (
                    id.isNullOrBlank()
                ) {

                    Log.e(
                        TAG,
                        "jobId vazio"
                    )

                    return START_NOT_STICKY
                }

                if (
                    resultData == null
                ) {

                    Log.e(
                        TAG,
                        "resultData da MediaProjection é nulo"
                    )

                    return START_NOT_STICKY
                }

                jobId =
                    id

                Log.d(
                    TAG,
                    "JOB ID = $jobId"
                )

                iniciarForeground()

                iniciarCaptura(
                    resultCode,
                    resultData
                )
            }

            ACTION_STOP -> {

                Log.d(
                    TAG,
                    "STOP recebido"
                )

                pararTudo()
            }
        }

        return START_NOT_STICKY
    }

    // =========================================================
    // FOREGROUND
    // =========================================================

    private fun iniciarForeground() {

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
                    "Tradução em tempo real ativa"
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
                "Captura já está rodando"
            )

            return
        }

        if (
            Build.VERSION.SDK_INT <
            Build.VERSION_CODES.Q
        ) {

            Log.e(
                TAG,
                "Android abaixo do 10 não suporta captura interna"
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

            // =================================================
            // RESET
            // =================================================

            running.set(true)

            captureStarted =
                false

            readCount =
                0L

            lastRead =
                0

            capturedBytes =
                0L

            sentChunks =
                0L

            sentBytes =
                0L

            sendErrors =
                0L

            translationCount =
                0L

            ttsCount =
                0L

            lastSpokenTranslation =
                ""

            lastSourceText =
                ""

            lastStatus =
                ""

            inputQueue.clear()

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

            Log.d(
                TAG,
                "MediaProjection obtida"
            )

            // =================================================
            // AUDIO RECORD
            // =================================================

            criarAudioRecord()

            // =================================================
            // THREADS
            // =================================================

            iniciarThreadCaptura()

            iniciarThreadEnvio()

            iniciarThreadStatus()

            iniciarThreadDiagnostico()

            lastStage =
                "service_started"

            enviarDiagnostico(
                "service_started"
            )

            Log.d(
                TAG,
                "===================================="
            )

            Log.d(
                TAG,
                "SI DEEPGRAM + DEEPL INICIADO"
            )

            Log.d(
                TAG,
                "Captura: 16 kHz PCM16 mono"
            )

            Log.d(
                TAG,
                "Tradução: DeepL"
            )

            Log.d(
                TAG,
                "Voz: Android TextToSpeech"
            )

            Log.d(
                TAG,
                "===================================="
            )

        } catch (
            e: SecurityException
        ) {

            Log.e(
                TAG,
                "SecurityException",
                e
            )

            lastStage =
                "security_error"

            enviarDiagnostico(
                "security_error:${e.message}"
            )

            pararTudo()

        } catch (
            e: Exception
        ) {

            Log.e(
                TAG,
                "Erro iniciando captura",
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

        val playbackConfig =
            AudioPlaybackCaptureConfiguration
                .Builder(
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

        val audioFormat =
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
                "AudioRecord minBuffer inválido: $minBuffer"
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
                    audioFormat
                )
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
                "AudioRecord não inicializou"
            )
        }

        Log.d(
            TAG,
            "AudioRecord pronto"
        )

        Log.d(
            TAG,
            "sampleRate=$INPUT_SAMPLE_RATE"
        )

        Log.d(
            TAG,
            "buffer=$bufferSize"
        )
    }

    // =========================================================
    // THREAD DE CAPTURA
    // =========================================================

    private fun iniciarThreadCaptura() {

        captureThread =
            Thread {

                try {

                    val record =
                        audioRecord
                            ?: return@Thread

                    record.startRecording()

                    if (
                        record.recordingState !=
                        AudioRecord.RECORDSTATE_RECORDING
                    ) {

                        throw Exception(
                            "AudioRecord não entrou em RECORDING"
                        )
                    }

                    captureStarted =
                        true

                    lastStage =
                        "capture_started"

                    val buffer =
                        ByteArray(
                            INPUT_CHUNK_SIZE
                        )

                    while (
                        running.get()
                    ) {

                        val lidos =
                            record.read(
                                buffer,
                                0,
                                buffer.size,
                                AudioRecord.READ_BLOCKING
                            )

                        lastRead =
                            lidos

                        if (
                            lidos ==
                            AudioRecord.ERROR_DEAD_OBJECT
                        ) {

                            Log.e(
                                TAG,
                                "AudioRecord DEAD_OBJECT"
                            )

                            break
                        }

                        if (
                            lidos <= 0
                        ) {

                            continue
                        }

                        readCount++

                        capturedBytes +=
                            lidos.toLong()

                        /*
                         * IMPORTANTE:
                         *
                         * Cada leitura recebe sua própria
                         * cópia de memória.
                         *
                         * Isso impede que o próximo read()
                         * sobrescreva o áudio que ainda está
                         * esperando na fila.
                         */
                        val chunk =
                            buffer.copyOf(
                                lidos
                            )

                        if (
                            !inputQueue.offer(
                                chunk
                            )
                        ) {

                            /*
                             * Se a rede estiver lenta,
                             * descartamos o bloco mais antigo.
                             */
                            inputQueue.poll()

                            inputQueue.offer(
                                chunk
                            )

                            Log.w(
                                TAG,
                                "Fila de entrada cheia; descartando bloco antigo"
                            )
                        }

                        if (
                            readCount <= 5 ||
                            readCount % 50L == 0L
                        ) {

                            Log.d(
                                TAG,
                                "CAPTURA read=$readCount " +
                                    "bytes=$lidos " +
                                    "fila=${inputQueue.size}"
                            )
                        }
                    }

                } catch (
                    e: InterruptedException
                ) {

                    Log.d(
                        TAG,
                        "Thread captura interrompida"
                    )

                } catch (
                    e: Exception
                ) {

                    Log.e(
                        TAG,
                        "Erro captura",
                        e
                    )

                    lastStage =
                        "capture_error"

                    enviarDiagnostico(
                        "capture_error:${e.message}"
                    )
                }
            }

        captureThread?.start()
    }

    // =========================================================
    // THREAD DE ENVIO
    // =========================================================

    private fun iniciarThreadEnvio() {

        sendThread =
            Thread {

                Log.d(
                    TAG,
                    "THREAD ENVIO INICIADA"
                )

                while (
                    running.get()
                ) {

                    try {

                        /*
                         * poll() remove exatamente um
                         * bloco da fila.
                         */
                        val audio =
                            inputQueue.poll()

                        if (
                            audio == null
                        ) {

                            Thread.sleep(
                                5L
                            )

                            continue
                        }

                        enviarAudio(
                            audio
                        )

                    } catch (
                        e: InterruptedException
                    ) {

                        break

                    } catch (
                        e: Exception
                    ) {

                        Log.e(
                            TAG,
                            "Erro envio",
                            e
                        )
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
    // ENVIO AUDIO → RENDER → DEEPGRAM
    // =========================================================

    private fun enviarAudio(
        audio: ByteArray
    ) {

        val id =
            jobId
                ?: return

        var connection:
            HttpURLConnection? =
            null

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
                15000

            connection.readTimeout =
                15000

            connection.doOutput =
                true

            connection.useCaches =
                false

            connection.setRequestProperty(
                "Content-Type",
                "application/json; charset=UTF-8"
            )

            connection.setRequestProperty(
                "Connection",
                "close"
            )

            val base64 =
                Base64.encodeToString(
                    audio,
                    Base64.NO_WRAP
                )

            val json =
                org.json.JSONObject()

            json.put(
                "jobId",
                id
            )

            json.put(
                "audio",
                base64
            )

            /*
             * Informamos explicitamente ao backend
             * o formato do áudio.
             */
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
                it.write(
                    body
                )

                it.flush()
            }

            val responseCode =
                connection.responseCode

            if (
                responseCode in 200..299
            ) {

                sentChunks++

                sentBytes +=
                    audio.size.toLong()

                if (
                    sentChunks <= 5 ||
                    sentChunks % 50L == 0L
                ) {

                    Log.d(
                        TAG,
                        "ENVIO OK " +
                            "chunks=$sentChunks " +
                            "bytes=$sentBytes"
                    )
                }

            } else {

                sendErrors++

                val erro =
                    try {

                        connection.errorStream
                            ?.bufferedReader()
                            ?.use {
                                it.readText()
                            }

                    } catch (
                        _: Exception
                    ) {
                        ""
                    }

                Log.e(
                    TAG,
                    "ERRO HTTP áudio=$responseCode $erro"
                )
            }

        } catch (
            e: Exception
        ) {

            sendErrors++

            Log.e(
                TAG,
                "Falha enviando áudio",
                e
            )

        } finally {

            try {
                connection?.disconnect()
            } catch (
                _: Exception
            ) {
            }
        }
    }

    // =========================================================
    // THREAD DE STATUS
    // =========================================================

    private fun iniciarThreadStatus() {

        statusThread =
            Thread {

                Log.d(
                    TAG,
                    "THREAD STATUS INICIADA"
                )

                while (
                    running.get()
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

                    } catch (
                        e: Exception
                    ) {

                        if (
                            running.get()
                        ) {

                            Log.w(
                                TAG,
                                "Erro consultando status: ${e.message}"
                            )
                        }

                        try {

                            Thread.sleep(
                                500L
                            )

                        } catch (
                            _: Exception
                        ) {
                        }
                    }
                }

                Log.d(
                    TAG,
                    "THREAD STATUS FINALIZADA"
                )
            }

        statusThread?.start()
    }

    // =========================================================
    // CONSULTAR STATUS
    // =========================================================

    private fun consultarStatus() {

        val id =
            jobId
                ?: return

        var connection:
            HttpURLConnection? =
            null

        try {

            val url =
                URL(
                    "$BACKEND_URL/api/audio/status/$id"
                )

            connection =
                url.openConnection()
                    as HttpURLConnection

            connection.requestMethod =
                "GET"

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

            if (
                code != 200
            ) {

                return
            }

            val resposta =
                BufferedReader(
                    InputStreamReader(
                        connection.inputStream
                    )
                ).use {
                    it.readText()
                }

            if (
                resposta.isBlank()
            ) {

                return
            }

            processarStatus(
                resposta
            )

        } catch (
            e: Exception
        ) {

            if (
                running.get()
            ) {

                Log.w(
                    TAG,
                    "Erro status: ${e.message}"
                )
            }

        } finally {

            try {
                connection?.disconnect()
            } catch (
                _: Exception
            ) {
            }
        }
    }

    // =========================================================
    // PROCESSAR STATUS
    // =========================================================

    private fun processarStatus(
        resposta: String
    ) {

        try {

            val json =
                org.json.JSONObject(
                    resposta
                )

            val status =
                json.optString(
                    "status",
                    ""
                )

            val source =
                json.optString(
                    "sourceText",
                    ""
                )

            val translation =
                json.optString(
                    "translatedText",
                    ""
                )

            if (
                status != lastStatus
            ) {

                lastStatus =
                    status

                Log.d(
                    TAG,
                    "STATUS=$status"
                )
            }

            if (
                source.isNotBlank() &&
                source != lastSourceText
            ) {

                lastSourceText =
                    source

                Log.d(
                    TAG,
                    "TRANSCRIÇÃO: $source"
                )
            }

            /*
             * Só fala uma tradução nova.
             *
             * Isso evita repetir "It's been"
             * centenas de vezes enquanto o backend
             * continua devolvendo o mesmo resultado.
             */
            if (
                translation.isNotBlank() &&
                translation != lastSpokenTranslation
            ) {

                lastSpokenTranslation =
                    translation

                translationCount++

                Log.d(
                    TAG,
                    "TRADUÇÃO NOVA: $translation"
                )

                falarTraducao(
                    translation,
                    targetLanguage
                )
            }

        } catch (
            e: Exception
        ) {

            Log.e(
                TAG,
                "Erro processando status",
                e
            )
        }
    }

    // =========================================================
    // TEXT TO SPEECH
    // =========================================================

    private fun inicializarTextToSpeech() {

        textToSpeech =
            TextToSpeech(
                applicationContext
            ) { resultado ->

                if (
                    resultado ==
                    TextToSpeech.SUCCESS
                ) {

                    ttsReady =
                        true

                    textToSpeech?.setSpeechRate(
                        TTS_RATE
                    )

                    textToSpeech?.setPitch(
                        TTS_PITCH
                    )

                    Log.d(
                        TAG,
                        "TextToSpeech pronto"
                    )

                } else {

                    ttsReady =
                        false

                    Log.e(
                        TAG,
                        "Falha inicializando TextToSpeech"
                    )
                }
            }
    }

    // =========================================================
    // FALAR TRADUÇÃO
    // =========================================================

    private fun falarTraducao(
        texto: String,
        idioma: String
    ) {

        if (
            !ttsReady
        ) {

            Log.w(
                TAG,
                "TTS ainda não está pronto"
            )

            return
        }

        if (
            texto.isBlank()
        ) {

            return
        }

        val locale =
            obterLocaleTTS(
                idioma
            )

        try {

            val resultadoIdioma =
                textToSpeech?.setLanguage(
                    locale
                )

            if (
                resultadoIdioma ==
                TextToSpeech.LANG_MISSING_DATA ||
                resultadoIdioma ==
                TextToSpeech.LANG_NOT_SUPPORTED
            ) {

                Log.w(
                    TAG,
                    "Idioma TTS não suportado: $idioma"
                )

                return
            }

            /*
             * FLUSH:
             *
             * Não deixa frases antigas acumularem
             * indefinidamente.
             */
            textToSpeech?.speak(
                texto,
                TextToSpeech.QUEUE_FLUSH,
                null,
                "si_translation_$translationCount"
            )

            ttsCount++

            lastStage =
                "tts_spoken"

        } catch (
            e: Exception
        ) {

            Log.e(
                TAG,
                "Erro TTS",
                e
            )
        }
    }

    // =========================================================
    // IDIOMA TTS
    // =========================================================

    private fun obterLocaleTTS(
        idioma: String
    ): Locale {

        val normalizado =
            idioma
                .lowercase()
                .replace(
                    "_",
                    "-"
                )

        return when {

            normalizado.startsWith(
                "pt"
            ) -> {
                Locale(
                    "pt",
                    "BR"
                )
            }

            normalizado.startsWith(
                "en"
            ) -> {
                Locale.US
            }

            normalizado.startsWith(
                "es"
            ) -> {
                Locale(
                    "es",
                    "ES"
                )
            }

            normalizado.startsWith(
                "fr"
            ) -> {
                Locale(
                    "fr",
                    "FR"
                )
            }

            normalizado.startsWith(
                "de"
            ) -> {
                Locale(
                    "de",
                    "DE"
                )
            }

            normalizado.startsWith(
                "it"
            ) -> {
                Locale(
                    "it",
                    "IT"
                )
            }

            normalizado.startsWith(
                "ja"
            ) -> {
                Locale(
                    "ja",
                    "JP"
                )
            }

            normalizado.startsWith(
                "ko"
            ) -> {
                Locale(
                    "ko",
                    "KR"
                )
            }

            normalizado.startsWith(
                "zh"
            ) -> {
                Locale(
                    "zh",
                    "CN"
                )
            }

            normalizado.startsWith(
                "ru"
            ) -> {
                Locale(
                    "ru",
                    "RU"
                )
            }

            normalizado.startsWith(
                "ar"
            ) -> {
                Locale(
                    "ar",
                    "SA"
                )
            }

            normalizado.startsWith(
                "hi"
            ) -> {
                Locale(
                    "hi",
                    "IN"
                )
            }

            normalizado.startsWith(
                "tr"
            ) -> {
                Locale(
                    "tr",
                    "TR"
                )
            }

            normalizado.startsWith(
                "nl"
            ) -> {
                Locale(
                    "nl",
                    "NL"
                )
            }

            normalizado.startsWith(
                "pl"
            ) -> {
                Locale(
                    "pl",
                    "PL"
                )
            }

            normalizado.startsWith(
                "uk"
            ) -> {
                Locale(
                    "uk",
                    "UA"
                )
            }

            normalizado.startsWith(
                "th"
            ) -> {
                Locale(
                    "th",
                    "TH"
                )
            }

            normalizado.startsWith(
                "id"
            ) -> {
                Locale(
                    "id",
                    "ID"
                )
            }

            normalizado.startsWith(
                "vi"
            ) -> {
                Locale(
                    "vi",
                    "VN"
                )
            }

            else -> {
                Locale.US
            }
        }
    }

    // =========================================================
    // THREAD DIAGNÓSTICO
    // =========================================================

    private fun iniciarThreadDiagnostico() {

        diagnosticThread =
            Thread {

                while (
                    running.get()
                ) {

                    try {

                        Thread.sleep(
                            5000L
                        )

                        if (
                            running.get()
                        ) {

                            enviarDiagnostico(
                                "heartbeat"
                            )
                        }

                    } catch (
                        e: InterruptedException
                    ) {

                        break

                    } catch (
                        e: Exception
                    ) {

                        Log.w(
                            TAG,
                            "Erro heartbeat",
                            e
                        )
                    }
                }
            }

        diagnosticThread?.start()
    }

    // =========================================================
    // DIAGNÓSTICO
    // =========================================================

    private fun enviarDiagnostico(
        stage: String
    ) {

        val id =
            jobId
                ?: return

        Thread {

            var connection:
                HttpURLConnection? =
                null

            try {

                /*
                 * O server.js atual pode não possuir
                 * POST /api/audio/diagnostic.
                 *
                 * Portanto este diagnóstico é opcional.
                 */
                val url =
                    URL(
                        "$BACKEND_URL/api/audio/diagnostic"
                    )

                connection =
                    url.openConnection()
                        as HttpURLConnection

                connection.requestMethod =
                    "POST"

                connection.connectTimeout =
                    5000

                connection.readTimeout =
                    8000

                connection.doOutput =
                    true

                connection.useCaches =
                    false

                connection.setRequestProperty(
                    "Content-Type",
                    "application/json; charset=UTF-8"
                )

                connection.setRequestProperty(
                    "Connection",
                    "close"
                )

                val recording =
                    audioRecord?.recordingState ==
                        AudioRecord.RECORDSTATE_RECORDING

                val json =
                    org.json.JSONObject()

                json.put(
                    "jobId",
                    id
                )

                json.put(
                    "stage",
                    stage
                )

                json.put(
                    "recording",
                    recording
                )

                json.put(
                    "captureStarted",
                    captureStarted
                )

                json.put(
                    "readCount",
                    readCount
                )

                json.put(
                    "lastRead",
                    lastRead
                )

                json.put(
                    "capturedBytes",
                    capturedBytes
                )

                json.put(
                    "sentChunks",
                    sentChunks
                )

                json.put(
                    "sentBytes",
                    sentBytes
                )

                json.put(
                    "sendErrors",
                    sendErrors
                )

                json.put(
                    "translationCount",
                    translationCount
                )

                json.put(
                    "ttsCount",
                    ttsCount
                )

                json.put(
                    "inputQueueSize",
                    inputQueue.size
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

                /*
                 * Não tratamos 404 como erro fatal.
                 * O endpoint é apenas diagnóstico.
                 */
                val code =
                    connection.responseCode

                if (
                    code != 200 &&
                    code != 201 &&
                    code != 404
                ) {

                    Log.w(
                        TAG,
                        "Diagnóstico HTTP=$code"
                    )
                }

            } catch (
                e: Exception
            ) {

                Log.w(
                    TAG,
                    "Falha diagnóstico",
                    e
                )

            } finally {

                try {
                    connection?.disconnect()
                } catch (
                    _: Exception
                ) {
                }
            }

        }.start()
    }

    // =========================================================
    // PARAR TUDO
    // =========================================================

    private fun pararTudo() {

        val estavaRodando =
            running.getAndSet(
                false
            )

        if (
            !estavaRodando
        ) {

            try {
                textToSpeech?.stop()
            } catch (
                _: Exception
            ) {
            }

            stopSelf()

            return
        }

        lastStage =
            "stop_requested"

        Log.d(
            TAG,
            "================================"
        )

        Log.d(
            TAG,
            "PARANDO SI DEEPGRAM + DEEPL"
        )

        Log.d(
            TAG,
            "================================"
        )

        // =====================================================
        // TTS
        // =====================================================

        try {
            textToSpeech?.stop()
        } catch (
            _: Exception
        ) {
        }

        // =====================================================
        // THREADS
        // =====================================================

        try {
            captureThread?.interrupt()
        } catch (
            _: Exception
        ) {
        }

        try {
            sendThread?.interrupt()
        } catch (
            _: Exception
        ) {
        }

        try {
            statusThread?.interrupt()
        } catch (
            _: Exception
        ) {
        }

        try {
            diagnosticThread?.interrupt()
        } catch (
            _: Exception
        ) {
        }

        // =====================================================
        // AUDIO RECORD
        // =====================================================

        try {
            audioRecord?.stop()
        } catch (
            _: Exception
        ) {
        }

        try {
            audioRecord?.release()
        } catch (
            _: Exception
        ) {
        }

        audioRecord =
            null

        // =====================================================
        // MEDIA PROJECTION
        // =====================================================

        try {

            mediaProjection?.unregisterCallback(
                projectionCallback
            )

        } catch (
            _: Exception
        ) {
        }

        try {
            mediaProjection?.stop()
        } catch (
            _: Exception
        ) {
        }

        mediaProjection =
            null

        // =====================================================
        // FILA
        // =====================================================

        inputQueue.clear()

        captureStarted =
            false

        // =====================================================
        // DIAGNÓSTICO
        // =====================================================

        enviarDiagnostico(
            "stop_requested"
        )

        // =====================================================
        // FOREGROUND
        // =====================================================

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

        } catch (
            _: Exception
        ) {
        }

        stopSelf()
    }

    // =========================================================
    // CANAL DE NOTIFICAÇÃO
    // =========================================================

    private fun criarCanalNotificacao() {

        if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.O
        ) {

            val canal =
                NotificationChannel(
                    CHANNEL_ID,
                    "SI Tradutor Live",
                    NotificationManager.IMPORTANCE_LOW
                )

            canal.description =
                "Captura de áudio do SI Tradutor Live"

            val manager =
                getSystemService(
                    NotificationManager::class.java
                )

            manager.createNotificationChannel(
                canal
            )
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

        if (
            running.get()
        ) {

            pararTudo()
        }

        try {
            textToSpeech?.shutdown()
        } catch (
            _: Exception
        ) {
        }

        textToSpeech =
            null

        ttsReady =
            false

        Log.d(
            TAG,
            "AudioCaptureService destruído"
        )

        super.onDestroy()
    }
}
