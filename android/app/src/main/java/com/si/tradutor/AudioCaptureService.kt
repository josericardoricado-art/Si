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
import android.media.AudioTrack
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.Base64
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.IntentCompat
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

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
        // ÁUDIO DE ENTRADA
        // =========================================================

        /*
         * Gemini recebe:
         *
         * PCM16
         * MONO
         * 16000 Hz
         */

        private const val INPUT_SAMPLE_RATE =
            16000

        private const val INPUT_CHANNEL =
            AudioFormat.CHANNEL_IN_MONO

        private const val INPUT_FORMAT =
            AudioFormat.ENCODING_PCM_16BIT

        /*
         * 3200 bytes =
         * aproximadamente 100 ms
         */
        private const val INPUT_CHUNK_SIZE =
            3200

        /*
         * Fila grande para evitar perda de áudio
         * quando a rede estiver momentaneamente lenta.
         */
        private const val INPUT_QUEUE_CAPACITY =
            120

        // =========================================================
        // ÁUDIO DE SAÍDA
        // =========================================================

        /*
         * Gemini retorna:
         *
         * PCM16
         * MONO
         * 24000 Hz
         */

        private const val OUTPUT_SAMPLE_RATE =
            24000

        private const val OUTPUT_CHANNEL =
            AudioFormat.CHANNEL_OUT_MONO

        private const val OUTPUT_FORMAT =
            AudioFormat.ENCODING_PCM_16BIT

        /*
         * Quantos chunks pedir por consulta.
         */
        private const val OUTPUT_HTTP_LIMIT =
            30

        /*
         * Polling rápido.
         */
        private const val OUTPUT_POLL_MS =
            80L

        // =========================================================
        // BUFFER DE DUBLAGEM
        // =========================================================

        /*
         * Não começa a falar assim que chega o primeiro pedaço.
         *
         * Primeiro acumula vários pedaços.
         */
        private const val PREBUFFER_CHUNKS =
            5

        /*
         * Aproximadamente 1 segundo de PCM16
         * mono 24 kHz.
         */
        private const val PREBUFFER_BYTES =
            48000

        /*
         * A cada ciclo, junta até aproximadamente
         * 1 segundo de áudio antes de escrever.
         */
        private const val PLAYBACK_BATCH_BYTES =
            48000

        /*
         * Limite máximo da fila de saída.
         */
        private const val MAX_OUTPUT_QUEUE_BYTES =
            10L * 1024L * 1024L

        /*
         * Ganho do áudio traduzido.
         */
        private const val OUTPUT_GAIN =
            2.0f
    }

    // =========================================================
    // RECURSOS ANDROID
    // =========================================================

    private var mediaProjection:
        MediaProjection? = null

    private var audioRecord:
        AudioRecord? = null

    private var audioTrack:
        AudioTrack? = null

    private var audioManager:
        AudioManager? = null

    // =========================================================
    // THREADS
    // =========================================================

    private var captureThread:
        Thread? = null

    private var sendThread:
        Thread? = null

    private var outputPollThread:
        Thread? = null

    private var playbackThread:
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
    private var playbackStarted =
        false

    @Volatile
    private var playbackPrebufferReady =
        false

    @Volatile
    private var jobId:
        String? = null

    @Volatile
    private var lastOutputSeq =
        0L

    // =========================================================
    // FILA DE ENTRADA
    // =========================================================

    private val inputQueue =
        LinkedBlockingQueue<ByteArray>(
            INPUT_QUEUE_CAPACITY
        )

    // =========================================================
    // FILA DE SAÍDA
    // =========================================================

    /*
     * A saída é independente do polling.
     *
     * Render pode entregar:
     *
     * chunk 1
     * chunk 2
     * chunk 3
     * chunk 4
     *
     * e todos ficam aqui antes de serem reproduzidos.
     */

    private val outputQueue =
        LinkedBlockingQueue<ByteArray>(
            400
        )

    @Volatile
    private var outputQueueBytes =
        0L

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
    private var receivedOutputChunks =
        0L

    @Volatile
    private var receivedOutputBytes =
        0L

    @Volatile
    private var queuedOutputChunks =
        0L

    @Volatile
    private var playedOutputChunks =
        0L

    @Volatile
    private var playedOutputBytes =
        0L

    @Volatile
    private var playbackWriteErrors =
        0L

    @Volatile
    private var lastStage =
        "created"

    // =========================================================
    // CALLBACK MEDIA PROJECTION
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

                        IntentCompat.getParcelableExtra(
                            intent,
                            EXTRA_RESULT_DATA,
                            Intent::class.java
                        )

                    } catch (
                        _: Exception
                    ) {

                        @Suppress(
                            "DEPRECATION"
                        )

                        intent.getParcelableExtra(
                            EXTRA_RESULT_DATA
                        )
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
                    "Dublagem em tempo real ativa"
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

            playbackStarted =
                false

            playbackPrebufferReady =
                false

            lastOutputSeq =
                0L

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

            receivedOutputChunks =
                0L

            receivedOutputBytes =
                0L

            queuedOutputChunks =
                0L

            playedOutputChunks =
                0L

            playedOutputBytes =
                0L

            playbackWriteErrors =
                0L

            outputQueueBytes =
                0L

            inputQueue.clear()

            outputQueue.clear()

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
            // AUDIO TRACK
            // =================================================

            criarAudioTrack()

            // =================================================
            // THREADS
            // =================================================

            iniciarThreadCaptura()

            iniciarThreadEnvio()

            iniciarThreadPollingSaida()

            iniciarThreadPlayback()

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
                "SI DUBLAGEM INICIADA"
            )

            Log.d(
                TAG,
                "Captura: 16 kHz PCM16 mono"
            )

            Log.d(
                TAG,
                "Saída: 24 kHz PCM16 mono"
            )

            Log.d(
                TAG,
                "Pré-buffer: $PREBUFFER_CHUNKS chunks"
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
            "buffer=$bufferSize"
        )
    }

    // =========================================================
    // AUDIO TRACK
    // =========================================================

    private fun criarAudioTrack() {

        val minBuffer =
            AudioTrack.getMinBufferSize(
                OUTPUT_SAMPLE_RATE,
                OUTPUT_CHANNEL,
                OUTPUT_FORMAT
            )

        if (
            minBuffer <= 0
        ) {

            throw Exception(
                "AudioTrack minBuffer inválido: $minBuffer"
            )
        }

        /*
         * Buffer físico maior para reduzir
         * micro interrupções.
         */
        val bufferSize =
            maxOf(
                minBuffer * 8,
                OUTPUT_SAMPLE_RATE * 4
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
                    OUTPUT_FORMAT
                )
                .setSampleRate(
                    OUTPUT_SAMPLE_RATE
                )
                .setChannelMask(
                    OUTPUT_CHANNEL
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

            throw Exception(
                "AudioTrack não inicializou"
            )
        }

        configurarRotaAudio()

        configurarVolume()

        audioTrack?.play()

        Log.d(
            TAG,
            "AudioTrack pronto"
        )

        Log.d(
            TAG,
            "buffer=$bufferSize"
        )
    }

    // =========================================================
    // ROTA DO ALTO-FALANTE
    // =========================================================

    private fun configurarRotaAudio() {

        try {

            if (
                Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.M
            ) {

                val devices =
                    audioManager?.getDevices(
                        AudioManager.GET_DEVICES_OUTPUTS
                    )

                val speaker =
                    devices?.firstOrNull {
                        it.type ==
                            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                    }

                if (
                    speaker != null
                ) {

                    val sucesso =
                        audioTrack?.setPreferredDevice(
                            speaker
                        )

                    Log.d(
                        TAG,
                        "Alto-falante interno selecionado"
                    )

                    Log.d(
                        TAG,
                        "setPreferredDevice=$sucesso"
                    )
                }
            }

        } catch (
            e: Exception
        ) {

            Log.w(
                TAG,
                "Não foi possível selecionar alto-falante",
                e
            )
        }
    }

    // =========================================================
    // VOLUME
    // =========================================================

    private fun configurarVolume() {

        try {

            if (
                Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.LOLLIPOP
            ) {

                audioTrack?.setVolume(
                    1.0f
                )

            } else {

                @Suppress(
                    "DEPRECATION"
                )

                audioTrack?.setStereoVolume(
                    1.0f,
                    1.0f
                )
            }

        } catch (
            e: Exception
        ) {

            Log.w(
                TAG,
                "Erro configurando volume",
                e
            )
        }
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
                            lidos > 0
                        ) {

                            readCount++

                            capturedBytes +=
                                lidos.toLong()

                            val chunk =
                                buffer.copyOf(
                                    lidos
                                )

                            /*
                             * Se a fila estiver cheia,
                             * descartamos o mais antigo,
                             * não o novo.
                             */
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

                            if (
                                readCount <= 5 ||
                                readCount % 50L == 0L
                            ) {

                                Log.d(
                                    TAG,
                                    "CAPTURA read=$readCount bytes=$lidos fila=${inputQueue.size}"
                                )
                            }
                        }

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

                        val audio =
                            inputQueue.poll()

                        if (
                            audio == null
                        ) {

                            Thread.sleep(
                                5
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
    // ENVIO AUDIO → RENDER
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

            /*
             * O backend atual recebe:
             *
             * {
             *   "jobId": "...",
             *   "audio": "BASE64"
             * }
             */

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
                    audio.size

                if (
                    sentChunks <= 5 ||
                    sentChunks % 50L == 0L
                ) {

                    Log.d(
                        TAG,
                        "ENVIO OK chunks=$sentChunks bytes=$sentBytes"
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
    // THREAD POLLING SAÍDA
    // =========================================================

    private fun iniciarThreadPollingSaida() {

        outputPollThread =
            Thread {

                Log.d(
                    TAG,
                    "THREAD POLLING SAÍDA INICIADA"
                )

                while (
                    running.get()
                ) {

                    try {

                        buscarAudioTraduzido()

                        Thread.sleep(
                            OUTPUT_POLL_MS
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
                            "Erro polling saída",
                            e
                        )

                        try {

                            Thread.sleep(
                                300
                            )

                        } catch (
                            _: Exception
                        ) {
                        }
                    }
                }

                Log.d(
                    TAG,
                    "THREAD POLLING FINALIZADA"
                )
            }

        outputPollThread?.start()
    }

    // =========================================================
    // BUSCAR ÁUDIO TRADUZIDO
    // =========================================================

    private fun buscarAudioTraduzido() {

        val id =
            jobId
                ?: return

        var connection:
            HttpURLConnection? =
            null

        try {

            val url =
                URL(
                    "$BACKEND_URL/api/audio/output/$id" +
                        "?after=$lastOutputSeq" +
                        "&limit=$OUTPUT_HTTP_LIMIT"
                )

            connection =
                url.openConnection()
                    as HttpURLConnection

            connection.requestMethod =
                "GET"

            connection.connectTimeout =
                10000

            connection.readTimeout =
                10000

            connection.useCaches =
                false

            connection.setRequestProperty(
                "Connection",
                "close"
            )

            if (
                connection.responseCode !=
                200
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
                resposta.isNotBlank()
            ) {

                processarSaida(
                    resposta
                )
            }

        } catch (
            e: Exception
        ) {

            if (
                running.get()
            ) {

                Log.w(
                    TAG,
                    "Erro buscando saída",
                    e
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
    // PROCESSAR SAÍDA DO RENDER
    // =========================================================

    private fun processarSaida(
        resposta: String
    ) {

        try {

            val root =
                org.json.JSONObject(
                    resposta
                )

            /*
             * Formato principal:
             *
             * {
             *   "chunks":[
             *      {
             *        "seq":1,
             *        "audio":"..."
             *      }
             *   ]
             * }
             */

            val chunks =
                root.optJSONArray(
                    "chunks"
                )

            if (
                chunks != null
            ) {

                processarChunks(
                    chunks
                )

                return
            }

            /*
             * Compatibilidade com resposta
             * contendo apenas "audio".
             */
            val audioBase64 =
                root.optString(
                    "audio",
                    ""
                )

            if (
                audioBase64.isNotBlank()
            ) {

                val audio =
                    decodificarBase64(
                        audioBase64
                    )

                if (
                    audio.isNotEmpty()
                ) {

                    receivedOutputChunks++

                    receivedOutputBytes +=
                        audio.size.toLong()

                    adicionarNaFilaSaida(
                        audio
                    )
                }
            }

        } catch (
            e: Exception
        ) {

            Log.e(
                TAG,
                "Erro processando áudio de saída",
                e
            )

            lastStage =
                "output_parse_error"
        }
    }

    // =========================================================
    // PROCESSAR CHUNKS
    // =========================================================

    private fun processarChunks(
        chunks: org.json.JSONArray
    ) {

        val lista =
            mutableListOf<OutputChunk>()

        for (
            i in 0 until chunks.length()
        ) {

            try {

                val item =
                    chunks.optJSONObject(
                        i
                    )
                        ?: continue

                val seq =
                    item.optLong(
                        "seq",
                        -1L
                    )

                if (
                    seq <= lastOutputSeq
                ) {
                    continue
                }

                val audioBase64 =
                    item.optString(
                        "audio",
                        ""
                    )

                if (
                    audioBase64.isBlank()
                ) {
                    continue
                }

                val audio =
                    decodificarBase64(
                        audioBase64
                    )

                if (
                    audio.isEmpty()
                ) {
                    continue
                }

                lista.add(
                    OutputChunk(
                        seq,
                        audio
                    )
                )

            } catch (
                e: Exception
            ) {

                Log.w(
                    TAG,
                    "Erro lendo chunk $i",
                    e
                )
            }
        }

        /*
         * Ordenar para garantir que a voz
         * seja reproduzida na ordem correta.
         */
        lista.sortBy {
            it.seq
        }

        for (
            item in lista
        ) {

            if (
                item.seq <=
                lastOutputSeq
            ) {
                continue
            }

            receivedOutputChunks++

            receivedOutputBytes +=
                item.audio.size.toLong()

            adicionarNaFilaSaida(
                item.audio
            )

            /*
             * Só avança depois de colocar
             * o áudio na fila local.
             */
            lastOutputSeq =
                item.seq
        }

        if (
            lista.isNotEmpty()
        ) {

            Log.d(
                TAG,
                "SAÍDA recebida=${lista.size} " +
                    "fila=${outputQueue.size} " +
                    "filaBytes=$outputQueueBytes " +
                    "seq=$lastOutputSeq"
            )
        }
    }

    // =========================================================
    // DATA CLASS
    // =========================================================

    private data class OutputChunk(
        val seq: Long,
        val audio: ByteArray
    )

    // =========================================================
    // BASE64
    // =========================================================

    private fun decodificarBase64(
        textoOriginal: String
    ): ByteArray {

        return try {

            var texto =
                textoOriginal

            /*
             * Remove:
             *
             * data:audio/pcm;base64,...
             */
            if (
                texto.startsWith(
                    "data:"
                )
            ) {

                val virgula =
                    texto.indexOf(
                        ","
                    )

                if (
                    virgula >= 0
                ) {

                    texto =
                        texto.substring(
                            virgula + 1
                        )
                }
            }

            Base64.decode(
                texto,
                Base64.DEFAULT
            )

        } catch (
            e: Exception
        ) {

            Log.e(
                TAG,
                "Erro decodificando Base64",
                e
            )

            ByteArray(0)
        }
    }

    // =========================================================
    // ADICIONAR NA FILA DE DUBLAGEM
    // =========================================================

    private fun adicionarNaFilaSaida(
        audioOriginal: ByteArray
    ) {

        if (
            !running.get()
        ) {
            return
        }

        /*
         * Amplifica antes de colocar no buffer.
         */
        val audio =
            amplificarPcm(
                audioOriginal,
                OUTPUT_GAIN
            )

        if (
            audio.isEmpty()
        ) {
            return
        }

        synchronized(
            outputQueue
        ) {

            /*
             * Segurança contra crescimento
             * infinito de memória.
             */
            while (
                outputQueueBytes +
                    audio.size >
                    MAX_OUTPUT_QUEUE_BYTES
            ) {

                val antigo =
                    outputQueue.poll()
                        ?: break

                outputQueueBytes =
                    (
                        outputQueueBytes -
                            antigo.size
                        )
                        .coerceAtLeast(
                            0L
                        )
            }

            if (
                outputQueue.offer(
                    audio
                )
            ) {

                outputQueueBytes +=
                    audio.size.toLong()

                queuedOutputChunks++

                /*
                 * Quando chega ao pré-buffer,
                 * marcamos como pronto.
                 */
                if (
                    !playbackPrebufferReady &&
                    outputQueue.size >=
                    PREBUFFER_CHUNKS &&
                    outputQueueBytes >=
                    PREBUFFER_BYTES
                ) {

                    playbackPrebufferReady =
                        true

                    Log.d(
                        TAG,
                        "================================"
                    )

                    Log.d(
                        TAG,
                        "PRÉ-BUFFER DE DUBLAGEM PRONTO"
                    )

                    Log.d(
                        TAG,
                        "chunks=${outputQueue.size}"
                    )

                    Log.d(
                        TAG,
                        "bytes=$outputQueueBytes"
                    )

                    Log.d(
                        TAG,
                        "================================"
                    )
                }
            }
        }
    }

    // =========================================================
    // AMPLIFICAR PCM
    // =========================================================

    private fun amplificarPcm(
        input: ByteArray,
        ganho: Float
    ): ByteArray {

        if (
            input.size < 2
        ) {
            return input
        }

        val output =
            input.copyOf()

        var i =
            0

        while (
            i + 1 <
            input.size
        ) {

            val low =
                input[i]
                    .toInt() and 0xFF

            val high =
                input[i + 1]
                    .toInt()

            val original =
                (
                    low or
                        (
                            high shl 8
                            )
                    )
                    .toShort()
                    .toInt()

            var novo =
                (
                    original *
                        ganho
                    )
                    .toInt()

            if (
                novo >
                Short.MAX_VALUE
            ) {

                novo =
                    Short.MAX_VALUE.toInt()
            }

            if (
                novo <
                Short.MIN_VALUE
            ) {

                novo =
                    Short.MIN_VALUE.toInt()
            }

            output[i] =
                (
                    novo and
                        0xFF
                    )
                    .toByte()

            output[i + 1] =
                (
                    (novo shr 8)
                        and 0xFF
                    )
                    .toByte()

            i +=
                2
        }

        return output
    }

    // =========================================================
    // THREAD DE PLAYBACK
    // =========================================================

    private fun iniciarThreadPlayback() {

        playbackThread =
            Thread {

                Log.d(
                    TAG,
                    "THREAD PLAYBACK INICIADA"
                )

                /*
                 * =============================================
                 * ETAPA 1
                 * Esperar pré-buffer.
                 * =============================================
                 */

                while (
                    running.get() &&
                    !playbackPrebufferReady
                ) {

                    try {

                        Thread.sleep(
                            20
                        )

                    } catch (
                        e: InterruptedException
                    ) {

                        return@Thread
                    }
                }

                if (
                    !running.get()
                ) {
                    return@Thread
                }

                playbackStarted =
                    true

                lastStage =
                    "playback_started"

                Log.d(
                    TAG,
                    "================================"
                )

                Log.d(
                    TAG,
                    "PLAYBACK DE DUBLAGEM INICIADO"
                )

                Log.d(
                    TAG,
                    "================================"
                )

                /*
                 * =============================================
                 * ETAPA 2
                 * Reprodução contínua.
                 * =============================================
                 */

                while (
                    running.get()
                ) {

                    try {

                        val lote =
                            ByteArrayBatch(
                                PLAYBACK_BATCH_BYTES
                            )

                        /*
                         * Primeiro chunk:
                         *
                         * espera até chegar algum áudio.
                         */
                        val primeiro =
                            outputQueue.poll(
                                500,
                                java.util.concurrent.TimeUnit.MILLISECONDS
                            )

                        if (
                            primeiro == null
                        ) {

                            /*
                             * A fila ficou vazia.
                             *
                             * Não reiniciamos o AudioTrack.
                             * Apenas esperamos o próximo áudio.
                             */
                            continue
                        }

                        synchronized(
                            outputQueue
                        ) {

                            outputQueueBytes =
                                (
                                    outputQueueBytes -
                                        primeiro.size
                                    )
                                    .coerceAtLeast(
                                        0L
                                    )
                        }

                        lote.write(
                            primeiro
                        )

                        /*
                         * Junta mais chunks imediatamente
                         * disponíveis.
                         */
                        synchronized(
                            outputQueue
                        ) {

                            while (
                                lote.size() <
                                    PLAYBACK_BATCH_BYTES
                            ) {

                                val proximo =
                                    outputQueue.poll()
                                        ?: break

                                outputQueueBytes =
                                    (
                                        outputQueueBytes -
                                            proximo.size
                                        )
                                        .coerceAtLeast(
                                            0L
                                        )

                                lote.write(
                                    proximo
                                )
                            }
                        }

                        val dados =
                            lote.toByteArray()

                        if (
                            dados.isNotEmpty()
                        ) {

                            reproduzirLote(
                                dados
                            )
                        }

                    } catch (
                        e: InterruptedException
                    ) {

                        break

                    } catch (
                        e: Exception
                    ) {

                        Log.e(
                            TAG,
                            "Erro playback",
                            e
                        )

                        playbackWriteErrors++

                        try {

                            Thread.sleep(
                                50
                            )

                        } catch (
                            _: Exception
                        ) {
                        }
                    }
                }

                Log.d(
                    TAG,
                    "THREAD PLAYBACK FINALIZADA"
                )
            }

        playbackThread?.start()
    }

    // =========================================================
    // BATCH DE BYTES
    // =========================================================

    private class ByteArrayBatch(
        private val limite: Int
    ) {

        private val partes =
            ArrayList<ByteArray>()

        private var total =
            0

        fun write(
            dados: ByteArray
        ) {

            if (
                total >= limite
            ) {
                return
            }

            val restante =
                limite -
                    total

            if (
                dados.size <= restante
            ) {

                partes.add(
                    dados
                )

                total +=
                    dados.size

            } else {

                partes.add(
                    dados.copyOf(
                        restante
                    )
                )

                total +=
                    restante
            }
        }

        fun size(): Int =
            total

        fun toByteArray(): ByteArray {

            if (
                partes.isEmpty()
            ) {
                return ByteArray(0)
            }

            val resultado =
                ByteArray(
                    total
                )

            var pos =
                0

            for (
                parte in partes
            ) {

                System.arraycopy(
                    parte,
                    0,
                    resultado,
                    pos,
                    parte.size
                )

                pos +=
                    parte.size
            }

            return resultado
        }
    }

    // =========================================================
    // REPRODUZIR LOTE
    // =========================================================

    private fun reproduzirLote(
        dados: ByteArray
    ) {

        var track =
            audioTrack

        if (
            track == null
        ) {

            try {

                criarAudioTrack()

                track =
                    audioTrack

            } catch (
                e: Exception
            ) {

                Log.e(
                    TAG,
                    "Não conseguiu recriar AudioTrack",
                    e
                )

                playbackWriteErrors++

                return
            }
        }

        if (
            track == null
        ) {

            playbackWriteErrors++

            return
        }

        try {

            /*
             * Garante que continua tocando.
             */
            if (
                track.playState !=
                AudioTrack.PLAYSTATE_PLAYING
            ) {

                Log.w(
                    TAG,
                    "AudioTrack não estava PLAYING"
                )

                configurarRotaAudio()

                configurarVolume()

                track.play()
            }

            var offset =
                0

            while (
                offset <
                dados.size &&
                running.get()
            ) {

                val restante =
                    dados.size -
                        offset

                val quantidade =
                    minOf(
                        restante,
                        PLAYBACK_BATCH_BYTES
                    )

                val escritos =
                    track.write(
                        dados,
                        offset,
                        quantidade,
                        AudioTrack.WRITE_BLOCKING
                    )

                if (
                    escritos > 0
                ) {

                    offset +=
                        escritos

                    playedOutputChunks++

                    playedOutputBytes +=
                        escritos.toLong()

                    if (
                        playedOutputChunks <= 5 ||
                        playedOutputChunks % 20L == 0L
                    ) {

                        Log.d(
                            TAG,
                            "PLAYBACK bytes=$escritos " +
                                "total=$playedOutputBytes " +
                                "fila=${outputQueue.size} " +
                                "filaBytes=$outputQueueBytes"
                        )
                    }

                } else {

                    playbackWriteErrors++

                    Log.e(
                        TAG,
                        "AudioTrack.write retornou $escritos"
                    )

                    break
                }
            }

        } catch (
            e: Exception
        ) {

            playbackWriteErrors++

            Log.e(
                TAG,
                "Erro escrevendo AudioTrack",
                e
            )
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
                            5000
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
                    "playbackStarted",
                    playbackStarted
                )

                json.put(
                    "playbackPrebufferReady",
                    playbackPrebufferReady
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
                    "inputQueueSize",
                    inputQueue.size
                )

                json.put(
                    "receivedOutputChunks",
                    receivedOutputChunks
                )

                json.put(
                    "receivedOutputBytes",
                    receivedOutputBytes
                )

                json.put(
                    "queuedOutputChunks",
                    queuedOutputChunks
                )

                json.put(
                    "outputQueueSize",
                    outputQueue.size
                )

                json.put(
                    "outputQueueBytes",
                    outputQueueBytes
                )

                json.put(
                    "playedOutputChunks",
                    playedOutputChunks
                )

                json.put(
                    "playedOutputBytes",
                    playedOutputBytes
                )

                json.put(
                    "playbackWriteErrors",
                    playbackWriteErrors
                )

                json.put(
                    "lastOutputSeq",
                    lastOutputSeq
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
                    code != 200 &&
                    code != 201
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
            "PARANDO DUBLAGEM"
        )

        Log.d(
            TAG,
            "================================"
        )

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
            outputPollThread?.interrupt()
        } catch (
            _: Exception
        ) {
        }

        try {
            playbackThread?.interrupt()
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
        // AUDIO TRACK
        // =====================================================

        try {
            audioTrack?.stop()
        } catch (
            _: Exception
        ) {
        }

        try {
            audioTrack?.release()
        } catch (
            _: Exception
        ) {
        }

        audioTrack =
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
        // LIMPAR FILAS
        // =====================================================

        inputQueue.clear()

        synchronized(
            outputQueue
        ) {

            outputQueue.clear()

            outputQueueBytes =
                0L
        }

        captureStarted =
            false

        playbackStarted =
            false

        playbackPrebufferReady =
            false

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
                "Captura e dublagem de áudio"

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

        Log.d(
            TAG,
            "AudioCaptureService destruído"
        )

        super.onDestroy()
    }
}
