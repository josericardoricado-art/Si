package com.si.tradutor

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
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
import android.widget.Toast
import androidx.core.app.NotificationCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sin

class AudioCaptureService : Service() {

    companion object {

        private const val TAG = "SI_AUDIO"

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

        private const val BACKEND_URL =
            "https://si-u2ul.onrender.com"

        private const val CHANNEL_ID =
            "si_audio_capture"

        private const val NOTIFICATION_ID =
            1001

        // =========================================================
        // ÁUDIO DE ENTRADA
        // =========================================================

        private const val SAMPLE_RATE_INPUT =
            16000

        private const val CHUNK_SIZE =
            3200

        /*
         * 3200 bytes =
         * 100 ms de PCM16 mono 16 kHz.
         */
        private const val MAX_INPUT_QUEUE =
            120

        // =========================================================
        // ÁUDIO DE SAÍDA
        // =========================================================

        private const val SAMPLE_RATE_OUTPUT =
            24000

        /*
         * Quantos chunks o Render pode devolver por consulta.
         */
        private const val OUTPUT_HTTP_LIMIT =
            20

        /*
         * Intervalo normal entre consultas.
         */
        private const val OUTPUT_POLL_MS =
            100L

        /*
         * Buffer de pré-carregamento.

         * Cada chunk de Gemini normalmente representa
         * uma pequena parte de áudio.
         *
         * 4 chunks = aproximadamente 1 segundo
         * quando cada chunk tem ~250 ms.
         */
        private const val PREBUFFER_CHUNKS =
            4

        /*
         * Também exigimos uma quantidade mínima de bytes
         * para evitar começar com pouquíssimo áudio.
         *
         * ~1 segundo em PCM16 mono 24 kHz:
         *
         * 24000 x 2 = 48000 bytes.
         */
        private const val PREBUFFER_BYTES =
            48000

        /*
         * Limite de segurança da fila de saída.
         *
         * 8 MB é suficiente para vários segundos
         * de áudio sem consumir memória demais.
         */
        private const val MAX_OUTPUT_QUEUE_BYTES =
            8L * 1024L * 1024L

        /*
         * Tamanho máximo aproximado de um bloco combinado
         * enviado de uma vez para o AudioTrack.
         *
         * ~1 segundo de PCM16 mono 24 kHz.
         */
        private const val PLAYBACK_BATCH_BYTES =
            48000

        /*
         * Ganho.
         */
        private const val OUTPUT_GAIN =
            2.5f
    }

    // =========================================================
    // RECURSOS
    // =========================================================

    private var mediaProjection: MediaProjection? =
        null

    private var audioRecord: AudioRecord? =
        null

    private var audioTrack: AudioTrack? =
        null

    private var audioManager: AudioManager? =
        null

    // =========================================================
    // THREADS
    // =========================================================

    private var captureThread: Thread? =
        null

    private var sendThread: Thread? =
        null

    private var outputPollThread: Thread? =
        null

    private var playbackThread: Thread? =
        null

    private var diagnosticThread: Thread? =
        null

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

    private var jobId: String? =
        null

    @Volatile
    private var lastOutputSeq =
        0L

    // =========================================================
    // FILA DE ENTRADA
    // =========================================================

    private val inputQueue =
        LinkedBlockingQueue<ByteArray>(
            MAX_INPUT_QUEUE
        )

    // =========================================================
    // FILA DE SAÍDA
    // =========================================================

    /*
     * Aqui ficam os pedaços PCM já decodificados.
     *
     * IMPORTANTE:
     * essa fila é independente do polling HTTP.
     */
    private val outputQueue =
        LinkedBlockingQueue<ByteArray>(
            300
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
    private var lastReadValue =
        0

    @Volatile
    private var lastStage =
        "created"

    // =========================================================
    // CALLBACK MEDIA PROJECTION
    // =========================================================

    private val projectionCallback =
        object : MediaProjection.Callback() {

            override fun onStop() {

                Log.d(
                    TAG,
                    "MediaProjection foi parada"
                )

                lastStage =
                    "projection_stopped"

                enviarDiagnostico(
                    "projection_stopped"
                )

                pararTudo()
            }
        }

    // =========================================================
    // ON CREATE
    // =========================================================

    override fun onCreate() {

        super.onCreate()

        Log.d(
            TAG,
            "================================"
        )

        Log.d(
            TAG,
            "SI AudioCaptureService criado"
        )

        Log.d(
            TAG,
            "================================"
        )

        audioManager =
            getSystemService(
                Context.AUDIO_SERVICE
            ) as AudioManager

        criarCanalNotificacao()
    }

    // =========================================================
    // START COMMAND
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

                val recebidoJobId =
                    intent.getStringExtra(
                        EXTRA_JOB_ID
                    )

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
                    recebidoJobId.isNullOrEmpty() ||
                    resultData == null
                ) {

                    Log.e(
                        TAG,
                        "Dados da MediaProjection ausentes"
                    )

                    return START_NOT_STICKY
                }

                jobId =
                    recebidoJobId

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
                    "AÇÃO STOP recebida"
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

        val notification =
            NotificationCompat.Builder(
                this,
                CHANNEL_ID
            )
                .setContentTitle(
                    "SI Tradutor Live"
                )
                .setContentText(
                    "Tradução de áudio em tempo real"
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

        Log.d(
            TAG,
            "Foreground Service iniciado"
        )
    }

    // =========================================================
    // INICIAR CAPTURA
    // =========================================================

    private fun iniciarCaptura(
        resultCode: Int,
        resultData: Intent
    ) {

        if (running.get()) {

            Log.d(
                TAG,
                "Captura já está rodando"
            )

            return
        }

        running.set(true)

        captureStarted =
            false

        playbackStarted =
            false

        lastStage =
            "starting_capture"

        // =====================================================
        // LIMPAR ESTADO
        // =====================================================

        inputQueue.clear()

        outputQueue.clear()

        outputQueueBytes =
            0L

        lastOutputSeq =
            0L

        readCount =
            0L

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

        try {

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
                    "MediaProjection nula"
                )
            }

            mediaProjection?.registerCallback(
                projectionCallback,
                null
            )

            Log.d(
                TAG,
                "MediaProjection criada"
            )

            criarAudioRecord()

            criarAudioTrack()

            /*
             * Mantemos o tom de teste.
             *
             * Ele toca somente no começo.
             */
            tocarTesteAudio()

            iniciarThreadCaptura()

            iniciarThreadEnvio()

            iniciarThreadSaida()

            iniciarThreadPlayback()

            iniciarThreadDiagnostico()

            lastStage =
                "capture_threads_started"

            enviarDiagnostico(
                "capture_threads_started"
            )

            Toast.makeText(
                this,
                "SI Tradutor Live iniciado",
                Toast.LENGTH_SHORT
            ).show()

        } catch (e: Exception) {

            Log.e(
                TAG,
                "ERRO iniciando captura",
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

        val audioFormat =
            AudioFormat.Builder()
                .setEncoding(
                    AudioFormat.ENCODING_PCM_16BIT
                )
                .setSampleRate(
                    SAMPLE_RATE_INPUT
                )
                .setChannelMask(
                    AudioFormat.CHANNEL_IN_MONO
                )
                .build()

        val config =
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

        val minBuffer =
            AudioRecord.getMinBufferSize(
                SAMPLE_RATE_INPUT,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )

        if (
            minBuffer <= 0
        ) {

            throw Exception(
                "AudioRecord.getMinBufferSize inválido: $minBuffer"
            )
        }

        val bufferSize =
            maxOf(
                minBuffer * 2,
                CHUNK_SIZE * 8
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
                    config
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

        Log.d(
            TAG,
            "AudioRecord criado"
        )

        Log.d(
            TAG,
            "Input sampleRate=$SAMPLE_RATE_INPUT"
        )

        Log.d(
            TAG,
            "Input PCM16 mono"
        )

        Log.d(
            TAG,
            "Input buffer=$bufferSize"
        )

        enviarDiagnostico(
            "audioRecord_ready"
        )
    }

    // =========================================================
    // AUDIO TRACK
    // =========================================================

    private fun criarAudioTrack() {

        val minBuffer =
            AudioTrack.getMinBufferSize(
                SAMPLE_RATE_OUTPUT,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )

        if (
            minBuffer <= 0
        ) {

            throw Exception(
                "AudioTrack.getMinBufferSize inválido: $minBuffer"
            )
        }

        /*
         * Aumentamos bastante o buffer físico
         * do AudioTrack.
         */
        val bufferSize =
            maxOf(
                minBuffer * 8,
                SAMPLE_RATE_OUTPUT * 4
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
                    SAMPLE_RATE_OUTPUT
                )
                .setChannelMask(
                    AudioFormat.CHANNEL_OUT_MONO
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
                "AudioTrack não foi inicializado"
            )
        }

        Log.d(
            TAG,
            "AudioTrack criado"
        )

        Log.d(
            TAG,
            "Output sampleRate=$SAMPLE_RATE_OUTPUT"
        )

        Log.d(
            TAG,
            "Output PCM16 mono"
        )

        Log.d(
            TAG,
            "Output buffer=$bufferSize"
        )

        configurarRotaAudio()

        configurarVolumeAudio()

        audioTrack?.play()

        Log.d(
            TAG,
            "AudioTrack PLAY"
        )

        verificarRotaAtual()

        enviarDiagnostico(
            "audioTrack_ready"
        )
    }

    // =========================================================
    // ROTA DE ÁUDIO
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
                        "ALTO-FALANTE encontrado"
                    )

                    Log.d(
                        TAG,
                        "produto=${speaker.productName}"
                    )

                    Log.d(
                        TAG,
                        "tipo=${speaker.type}"
                    )

                    Log.d(
                        TAG,
                        "preferredDevice=$sucesso"
                    )

                } else {

                    Log.w(
                        TAG,
                        "Alto-falante interno não encontrado"
                    )
                }
            }

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Erro configurando rota",
                e
            )
        }
    }

    // =========================================================
    // VOLUME
    // =========================================================

    private fun configurarVolumeAudio() {

        try {

            if (
                Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.LOLLIPOP
            ) {

                audioTrack?.setVolume(
                    1.0f
                )

            } else {

                @Suppress("DEPRECATION")
                audioTrack?.setStereoVolume(
                    1.0f,
                    1.0f
                )
            }

            val atual =
                audioManager?.getStreamVolume(
                    AudioManager.STREAM_MUSIC
                )

            val maximo =
                audioManager?.getStreamMaxVolume(
                    AudioManager.STREAM_MUSIC
                )

            Log.d(
                TAG,
                "Volume mídia=$atual/$maximo"
            )

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Erro configurando volume",
                e
            )
        }
    }

    // =========================================================
    // TESTE DE ÁUDIO
    // =========================================================

    private fun tocarTesteAudio() {

        Thread {

            try {

                Thread.sleep(
                    500
                )

                val durationMs =
                    700

                val totalSamples =
                    SAMPLE_RATE_OUTPUT *
                        durationMs /
                        1000

                val buffer =
                    ShortArray(
                        totalSamples
                    )

                val frequencia =
                    440.0

                val amplitude =
                    0.25

                for (
                    i in buffer.indices
                ) {

                    val valor =
                        sin(
                            2.0 *
                                Math.PI *
                                frequencia *
                                i /
                                SAMPLE_RATE_OUTPUT
                        )

                    buffer[i] =
                        (
                            valor *
                                Short.MAX_VALUE *
                                amplitude
                            )
                            .toInt()
                            .toShort()
                }

                val escritos =
                    audioTrack?.write(
                        buffer,
                        0,
                        buffer.size,
                        AudioTrack.WRITE_BLOCKING
                    )
                        ?: 0

                Log.d(
                    TAG,
                    "TOM TESTE write=$escritos"
                )

            } catch (e: Exception) {

                Log.e(
                    TAG,
                    "Erro no tom de teste",
                    e
                )
            }

        }.start()
    }

    // =========================================================
    // VERIFICAR ROTA
    // =========================================================

    private fun verificarRotaAtual() {

        try {

            if (
                Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.M
            ) {

                val device =
                    audioTrack?.routedDevice

                if (
                    device != null
                ) {

                    Log.d(
                        TAG,
                        "ROTA DE ÁUDIO"
                    )

                    Log.d(
                        TAG,
                        "produto=${device.productName}"
                    )

                    Log.d(
                        TAG,
                        "tipo=${device.type}"
                    )

                    Log.d(
                        TAG,
                        "id=${device.id}"
                    )
                }
            }

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Erro verificando rota",
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
                            ?: throw Exception(
                                "AudioRecord nulo"
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

                    captureStarted =
                        true

                    lastStage =
                        "audio_record_recording"

                    enviarDiagnostico(
                        "audio_record_recording"
                    )

                    Log.d(
                        TAG,
                        "================================"
                    )

                    Log.d(
                        TAG,
                        "CAPTURA DE ÁUDIO INICIADA"
                    )

                    Log.d(
                        TAG,
                        "Esperando áudio do YouTube..."
                    )

                    Log.d(
                        TAG,
                        "================================"
                    )

                    val buffer =
                        ByteArray(
                            CHUNK_SIZE
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

                        lastReadValue =
                            lidos

                        if (
                            lidos > 0
                        ) {

                            readCount++

                            capturedBytes +=
                                lidos

                            val audio =
                                buffer.copyOf(
                                    lidos
                                )

                            val colocado =
                                inputQueue.offer(
                                    audio
                                )

                            if (
                                !colocado
                            ) {

                                Log.w(
                                    TAG,
                                    "FILA DE ENTRADA CHEIA"
                                )

                                enviarDiagnostico(
                                    "input_queue_full"
                                )

                            } else {

                                if (
                                    readCount <= 5L ||
                                    readCount % 50L == 0L
                                ) {

                                    Log.d(
                                        TAG,
                                        "CAPTURA read=$readCount bytes=$lidos fila=${inputQueue.size}"
                                    )
                                }
                            }
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

                    if (
                        running.get()
                    ) {

                        Log.e(
                            TAG,
                            "ERRO thread captura",
                            e
                        )

                        lastStage =
                            "capture_error"

                        enviarDiagnostico(
                            "capture_error:${e.message}"
                        )
                    }
                }
            }

        captureThread?.start()
    }

    // =========================================================
    // THREAD ENVIO
    // =========================================================

    private fun iniciarThreadEnvio() {

        sendThread =
            Thread {

                Log.d(
                    TAG,
                    "THREAD DE ENVIO INICIADA"
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

                        enviarAudioHttp(
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
                            "Erro thread envio",
                            e
                        )
                    }
                }

                Log.d(
                    TAG,
                    "THREAD DE ENVIO FINALIZADA"
                )
            }

        sendThread?.start()
    }

    // =========================================================
    // ENVIO PARA RENDER
    // =========================================================

    private fun enviarAudioHttp(
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

            /*
             * O Render atual espera JSON.
             */
            val base64Audio =
                Base64.encodeToString(
                    audio,
                    Base64.NO_WRAP
                )

            val json =
                JSONObject()

            json.put(
                "jobId",
                id
            )

            json.put(
                "audio",
                base64Audio
            )

            val body =
                json.toString()
                    .toByteArray(
                        Charsets.UTF_8
                    )

            connection.setRequestProperty(
                "Content-Length",
                body.size.toString()
            )

            connection.outputStream.use {
                it.write(
                    body
                )
                it.flush()
            }

            val status =
                connection.responseCode

            if (
                status in 200..299
            ) {

                sentChunks++

                sentBytes +=
                    audio.size

                if (
                    sentChunks <= 5L ||
                    sentChunks % 50L == 0L
                ) {

                    Log.d(
                        TAG,
                        "ENVIO OK chunk=$sentChunks bytes=${audio.size} total=$sentBytes"
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
                    "ENVIO HTTP ERRO status=$status erro=$erro"
                )

                if (
                    sendErrors <= 5L ||
                    sendErrors % 20L == 0L
                ) {

                    enviarDiagnostico(
                        "send_http_error:$status"
                    )
                }
            }

        } catch (
            e: Exception
        ) {

            sendErrors++

            Log.e(
                TAG,
                "FALHA ENVIO ÁUDIO",
                e
            )

            if (
                sendErrors <= 5L ||
                sendErrors % 20L == 0L
            ) {

                enviarDiagnostico(
                    "send_error:${e.message}"
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
    // THREAD POLLING DE SAÍDA
    // =========================================================

    private fun iniciarThreadSaida() {

        outputPollThread =
            Thread {

                Log.d(
                    TAG,
                    "THREAD POLLING DE SAÍDA INICIADA"
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
                            "Erro thread saída",
                            e
                        )

                        try {

                            Thread.sleep(
                                500
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

            val status =
                connection.responseCode

            if (
                status != 200
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

                processarRespostaAudio(
                    resposta
                )
            }

        } catch (
            e: Exception
        ) {

            if (
                running.get()
            ) {

                Log.e(
                    TAG,
                    "Erro buscando áudio traduzido",
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
    // PROCESSAR RESPOSTA
    // =========================================================

    private fun processarRespostaAudio(
        json: String
    ) {

        try {

            /*
             * Formato novo:
             *
             * {
             *   "ok": true,
             *   "chunks": [
             *      {
             *         "seq": 123,
             *         "audio": "BASE64"
             *      }
             *   ]
             * }
             */

            val root =
                JSONObject(
                    json
                )

            if (
                root.has("chunks")
            ) {

                val array =
                    root.optJSONArray(
                        "chunks"
                    )

                if (
                    array != null
                ) {

                    processarListaChunks(
                        array
                    )

                    return
                }
            }

            /*
             * Compatibilidade com formato antigo:
             *
             * {
             *   "audio":"BASE64"
             * }
             */

            val audio =
                root.optString(
                    "audio",
                    ""
                )

            if (
                audio.isNotBlank() &&
                audio != "null"
            ) {

                val bytes =
                    decodificarBase64(
                        audio
                    )

                if (
                    bytes.isNotEmpty()
                ) {

                    receivedOutputChunks++

                    receivedOutputBytes +=
                        bytes.size

                    adicionarNaFilaSaida(
                        bytes
                    )
                }
            }

        } catch (
            e: Exception
        ) {

            Log.e(
                TAG,
                "ERRO processando saída Gemini",
                e
            )

            enviarDiagnostico(
                "output_process_error:${e.message}"
            )
        }
    }

    // =========================================================
    // PROCESSAR LISTA DE CHUNKS
    // =========================================================

    private fun processarListaChunks(
        array: JSONArray
    ) {

        val temporarios =
            mutableListOf<Pair<Long, ByteArray>>()

        for (
            i in 0 until array.length()
        ) {

            try {

                val item =
                    array.optJSONObject(
                        i
                    )
                        ?: continue

                val seq =
                    item.optLong(
                        "seq",
                        -1L
                    )

                if (
                    seq < 0L
                ) {
                    continue
                }

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

                val bytes =
                    decodificarBase64(
                        audioBase64
                    )

                if (
                    bytes.isEmpty()
                ) {
                    continue
                }

                temporarios.add(
                    Pair(
                        seq,
                        bytes
                    )
                )

            } catch (
                e: Exception
            ) {

                Log.e(
                    TAG,
                    "Erro lendo chunk $i",
                    e
                )
            }
        }

        /*
         * Garante ordem.
         */
        temporarios.sortBy {
            it.first
        }

        for (
            item in temporarios
        ) {

            val seq =
                item.first

            val bytes =
                item.second

            if (
                seq <= lastOutputSeq
            ) {
                continue
            }

            receivedOutputChunks++

            receivedOutputBytes +=
                bytes.size

            adicionarNaFilaSaida(
                bytes
            )

            /*
             * A sequência só avança depois
             * que o áudio entrou na fila local.
             */
            lastOutputSeq =
                seq
        }

        if (
            temporarios.isNotEmpty()
        ) {

            Log.d(
                TAG,
                "SAÍDA: recebidos=${temporarios.size} fila=${outputQueue.size} bytesFila=$outputQueueBytes seq=$lastOutputSeq"
            )
        }
    }

    // =========================================================
    // BASE64
    // =========================================================

    private fun decodificarBase64(
        valor: String
    ): ByteArray {

        return try {

            var texto =
                valor

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
                "Erro Base64",
                e
            )

            ByteArray(0)
        }
    }

    // =========================================================
    // COLOCAR NA FILA DE SAÍDA
    // =========================================================

    private fun adicionarNaFilaSaida(
        bytesOriginais: ByteArray
    ) {

        if (
            !running.get()
        ) {
            return
        }

        /*
         * Aplica ganho antes de guardar.
         */
        val bytes =
            amplificarPcm(
                bytesOriginais,
                OUTPUT_GAIN
            )

        if (
            bytes.isEmpty()
        ) {
            return
        }

        /*
         * Se a fila estiver perto do limite,
         * removemos somente os pedaços mais antigos.
         *
         * Isso evita memória infinita.
         */
        synchronized(
            outputQueue
        ) {

            while (
                outputQueueBytes +
                    bytes.size >
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

            val colocado =
                outputQueue.offer(
                    bytes
                )

            if (
                colocado
            ) {

                outputQueueBytes +=
                    bytes.size

                queuedOutputChunks++

            } else {

                Log.w(
                    TAG,
                    "FILA DE SAÍDA CHEIA"
                )
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

            val original =
                (
                    (input[i].toInt() and 0xFF) or
                        (
                            input[i + 1]
                                .toInt()
                                .shl(8)
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
                    "THREAD DE PLAYBACK INICIADA"
                )

                /*
                 * Primeiro espera acumular áudio.
                 */
                while (
                    running.get()
                ) {

                    try {

                        if (
                            !playbackStarted
                        ) {

                            val chunks =
                                outputQueue.size

                            val bytes =
                                outputQueueBytes

                            if (
                                chunks >=
                                    PREBUFFER_CHUNKS &&
                                bytes >=
                                    PREBUFFER_BYTES
                            ) {

                                playbackStarted =
                                    true

                                Log.d(
                                    TAG,
                                    "================================"
                                )

                                Log.d(
                                    TAG,
                                    "BUFFER INICIAL COMPLETO"
                                )

                                Log.d(
                                    TAG,
                                    "chunks=$chunks"
                                )

                                Log.d(
                                    TAG,
                                    "bytes=$bytes"
                                )

                                Log.d(
                                    TAG,
                                    "INICIANDO VOZ CONTÍNUA"
                                )

                                Log.d(
                                    TAG,
                                    "================================"
                                )

                                lastStage =
                                    "playback_buffer_ready"

                                enviarDiagnostico(
                                    "playback_buffer_ready"
                                )
                            }

                            if (
                                !playbackStarted
                            ) {

                                Thread.sleep(
                                    20
                                )

                                continue
                            }
                        }

                        /*
                         * Agora juntamos vários chunks
                         * antes de mandar para AudioTrack.
                         */
                        val lote =
                            ByteArrayOutputStreamHelper()

                        synchronized(
                            outputQueue
                        ) {

                            while (
                                lote.size() <
                                    PLAYBACK_BATCH_BYTES
                            ) {

                                val chunk =
                                    outputQueue.poll()
                                        ?: break

                                outputQueueBytes =
                                    (
                                        outputQueueBytes -
                                            chunk.size
                                        )
                                        .coerceAtLeast(
                                            0L
                                        )

                                lote.write(
                                    chunk
                                )
                            }
                        }

                        val dados =
                            lote.toByteArray()

                        if (
                            dados.isEmpty()
                        ) {

                            /*
                             * A fila esvaziou.
                             *
                             * Não reiniciamos o AudioTrack
                             * nem perdemos o estado.
                             *
                             * Esperamos novos chunks.
                             */
                            Thread.sleep(
                                10
                            )

                            continue
                        }

                        reproduzirAudioContinuo(
                            dados
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
                            "Erro thread playback",
                            e
                        )

                        try {

                            Thread.sleep(
                                100
                            )

                        } catch (
                            _: Exception
                        ) {
                        }
                    }
                }

                Log.d(
                    TAG,
                    "THREAD DE PLAYBACK FINALIZADA"
                )
            }

        playbackThread?.start()
    }

    // =========================================================
    // BYTE ARRAY OUTPUT HELPER
    // =========================================================

    private class ByteArrayOutputStreamHelper {

        private val partes =
            mutableListOf<ByteArray>()

        private var total =
            0

        fun write(
            dados: ByteArray
        ) {

            partes.add(
                dados
            )

            total +=
                dados.size
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

            var posicao =
                0

            for (
                parte in partes
            ) {

                System.arraycopy(
                    parte,
                    0,
                    resultado,
                    posicao,
                    parte.size
                )

                posicao +=
                    parte.size
            }

            return resultado
        }
    }

    // =========================================================
    // REPRODUÇÃO CONTÍNUA
    // =========================================================

    private fun reproduzirAudioContinuo(
        bytes: ByteArray
    ) {

        try {

            var track =
                audioTrack

            if (
                track == null
            ) {

                criarAudioTrack()

                track =
                    audioTrack
            }

            if (
                track == null
            ) {

                return
            }

            if (
                track.state !=
                AudioTrack.STATE_INITIALIZED
            ) {

                playbackWriteErrors++

                enviarDiagnostico(
                    "audio_track_not_initialized"
                )

                return
            }

            /*
             * Garante que o AudioTrack continua tocando.
             */
            if (
                track.playState !=
                AudioTrack.PLAYSTATE_PLAYING
            ) {

                Log.w(
                    TAG,
                    "AudioTrack não estava PLAYING - reiniciando"
                )

                configurarRotaAudio()

                configurarVolumeAudio()

                track.play()
            }

            /*
             * Escrevemos um bloco maior.
             *
             * Isso reduz muito a chance de micro-pausas
             * causadas por vários write() pequenos.
             */
            var offset =
                0

            while (
                offset <
                bytes.size &&
                running.get()
            ) {

                val restante =
                    bytes.size -
                        offset

                val quantidade =
                    minOf(
                        restante,
                        PLAYBACK_BATCH_BYTES
                    )

                val escritos =
                    track.write(
                        bytes,
                        offset,
                        quantidade,
                        AudioTrack.WRITE_BLOCKING
                    )

                if (
                    escritos > 0
                ) {

                    playedOutputChunks++

                    playedOutputBytes +=
                        escritos

                    offset +=
                        escritos

                    if (
                        playedOutputChunks <= 5L ||
                        playedOutputChunks % 20L == 0L
                    ) {

                        Log.d(
                            TAG,
                            "VOZ CONTÍNUA: escritos=$escritos total=$playedOutputBytes fila=${outputQueue.size} bytesFila=$outputQueueBytes"
                        )
                    }

                } else {

                    playbackWriteErrors++

                    Log.e(
                        TAG,
                        "AudioTrack.write retornou $escritos"
                    )

                    enviarDiagnostico(
                        "audio_track_write_error:$escritos"
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
                "ERRO reproduzindo voz Gemini",
                e
            )

            enviarDiagnostico(
                "play_error:${e.message}"
            )
        }
    }

    // =========================================================
    // DIAGNÓSTICO
    // =========================================================

    private fun iniciarThreadDiagnostico() {

        diagnosticThread =
            Thread {

                while (
                    running.get()
                ) {

                    try {

                        enviarDiagnostico(
                            "heartbeat"
                        )

                        Thread.sleep(
                            5000
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
                            "Erro diagnóstico",
                            e
                        )
                    }
                }
            }

        diagnosticThread?.start()
    }

    // =========================================================
    // ENVIAR DIAGNÓSTICO
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
                    "application/json"
                )

                connection.setRequestProperty(
                    "Connection",
                    "close"
                )

                val recording =
                    audioRecord?.recordingState ==
                        AudioRecord.RECORDSTATE_RECORDING

                val json =
                    JSONObject()

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
                    "readCount",
                    readCount
                )

                json.put(
                    "lastRead",
                    lastReadValue
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

                connection.responseCode

            } catch (
                e: Exception
            ) {

                Log.e(
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
                stopSelf()
            } catch (
                _: Exception
            ) {
            }

            return
        }

        lastStage =
            "stopping"

        Log.d(
            TAG,
            "================================"
        )

        Log.d(
            TAG,
            "PARANDO SI TRADUTOR"
        )

        Log.d(
            TAG,
            "================================"
        )

        /*
         * Interrompe primeiro as threads.
         */
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

        /*
         * AudioRecord.
         */
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

        /*
         * AudioTrack.
         */
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

        /*
         * MediaProjection.
         */
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

        /*
         * Limpar filas.
         */
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

        enviarDiagnostico(
            "stop_requested"
        )

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

        } catch (
            _: Exception
        ) {
        }

        stopSelf()
    }

    // =========================================================
    // NOTIFICAÇÃO
    // =========================================================

    private fun criarCanalNotificacao() {

        if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.O
        ) {

            val channel =
                NotificationChannel(
                    CHANNEL_ID,
                    "SI Tradutor Live",
                    NotificationManager.IMPORTANCE_LOW
                )

            channel.description =
                "Captura e tradução de áudio"

            val manager =
                getSystemService(
                    NotificationManager::class.java
                )

            manager.createNotificationChannel(
                channel
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
