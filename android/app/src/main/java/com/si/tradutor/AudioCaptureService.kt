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

        /*
         * ÁUDIO DE ENTRADA
         *
         * Gemini:
         * PCM 16-bit
         * mono
         * 16000 Hz
         */

        private const val INPUT_SAMPLE_RATE =
            16000

        /*
         * ÁUDIO DE SAÍDA
         *
         * Gemini:
         * PCM 16-bit
         * mono
         * 24000 Hz
         */

        private const val OUTPUT_SAMPLE_RATE =
            24000

        /*
         * 3200 bytes =
         * aproximadamente 100 ms
         * de PCM 16-bit mono
         * em 16000 Hz.
         */

        private const val AUDIO_CHUNK_SIZE =
            3200

        /*
         * Quantidade máxima de áudio
         * aguardando envio.
         */

        private const val MAX_QUEUE_SIZE =
            100

        /*
         * Intervalo de consulta da
         * voz traduzida.
         */

        private const val OUTPUT_POLL_INTERVAL =
            120L
    }

    /*
     * ==========================================
     * OBJETOS DE ÁUDIO
     * ==========================================
     */

    private var mediaProjection:
        MediaProjection? = null

    private var audioRecord:
        AudioRecord? = null

    private var audioTrack:
        AudioTrack? = null

    private var audioManager:
        AudioManager? = null

    /*
     * ==========================================
     * THREADS
     * ==========================================
     */

    private var captureThread:
        Thread? = null

    private var sendThread:
        Thread? = null

    private var outputThread:
        Thread? = null

    private var diagnosticThread:
        Thread? = null

    /*
     * ==========================================
     * CONTROLE
     * ==========================================
     */

    private val running =
        AtomicBoolean(false)

    private var jobId:
        String? = null

    /*
     * ==========================================
     * FILA DE ÁUDIO
     * ==========================================
     */

    private val inputQueue =
        LinkedBlockingQueue<ByteArray>(
            MAX_QUEUE_SIZE
        )

    /*
     * ==========================================
     * ESTATÍSTICAS
     * ==========================================
     */

    private var readCount =
        0L

    private var capturedBytes =
        0L

    private var sentChunks =
        0L

    private var sentBytes =
        0L

    private var sendErrors =
        0L

    private var receivedOutputChunks =
        0L

    private var receivedOutputBytes =
        0L

    private var playedOutputChunks =
        0L

    private var playedOutputBytes =
        0L

    private var lastRead =
        0

    private var lastStage =
        "created"

    /*
     * ==========================================
     * CALLBACK DA MEDIAPROJECTION
     * ==========================================
     */

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

    /*
     * ==========================================
     * ON CREATE
     * ==========================================
     */

    override fun onCreate() {

        super.onCreate()

        Log.d(
            TAG,
            "======================================"
        )

        Log.d(
            TAG,
            "SI Tradutor Live"
        )

        Log.d(
            TAG,
            "AudioCaptureService criado"
        )

        Log.d(
            TAG,
            "======================================"
        )

        audioManager =
            getSystemService(
                Context.AUDIO_SERVICE
            ) as AudioManager

        criarCanalNotificacao()
    }

    /*
     * ==========================================
     * ON START COMMAND
     * ==========================================
     */

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

                iniciarServico(
                    intent
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

    /*
     * ==========================================
     * INICIAR SERVIÇO
     * ==========================================
     */

    private fun iniciarServico(
        intent: Intent
    ) {

        val recebidoJobId =
            intent.getStringExtra(
                EXTRA_JOB_ID
            )

        val resultCode =
            intent.getIntExtra(
                EXTRA_RESULT_CODE,
                Activity.RESULT_CANCELED
            )

        if (
            recebidoJobId.isNullOrBlank()
        ) {

            Log.e(
                TAG,
                "JOB ID ausente"
            )

            return
        }

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

                @Suppress("DEPRECATION")

                intent.getParcelableExtra(
                    EXTRA_RESULT_DATA
                )
            }

        if (
            resultData == null
        ) {

            Log.e(
                TAG,
                "Dados MediaProjection ausentes"
            )

            return
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

    /*
     * ==========================================
     * FOREGROUND
     * ==========================================
     */

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
                    "Traduzindo áudio em tempo real"
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

    /*
     * ==========================================
     * INICIAR CAPTURA
     * ==========================================
     */

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

        /*
         * O AudioPlaybackCaptureConfiguration
         * existe a partir do Android 10.
         */

        if (
            Build.VERSION.SDK_INT <
            Build.VERSION_CODES.Q
        ) {

            Log.e(
                TAG,
                "Android abaixo do 10 não suporta captura de áudio interno"
            )

            Toast.makeText(
                this,
                "Seu Android precisa ser Android 10 ou superior",
                Toast.LENGTH_LONG
            ).show()

            stopSelf()

            return
        }

        running.set(true)

        limparEstatisticas()

        lastStage =
            "starting_capture"

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

            /*
             * Cria entrada.
             */

            criarAudioRecord()

            /*
             * Cria saída.
             */

            criarAudioTrack()

            /*
             * TESTE DE ÁUDIO
             *
             * O tom de 440 Hz continua
             * apenas para confirmar que o
             * AudioTrack está funcionando.
             */

            tocarTesteAudio()

            /*
             * Começa captura.
             */

            iniciarThreadCaptura()

            /*
             * Começa envio.
             */

            iniciarThreadEnvio()

            /*
             * Começa recebimento.
             */

            iniciarThreadSaida()

            /*
             * Começa diagnóstico.
             */

            iniciarThreadDiagnostico()

            lastStage =
                "capture_started"

            enviarDiagnostico(
                "capture_started"
            )

            Toast.makeText(
                this,
                "SI Tradutor Live iniciado",
                Toast.LENGTH_SHORT
            ).show()

        } catch (e: Exception) {

            Log.e(
                TAG,
                "ERRO INICIANDO CAPTURA",
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

    /*
     * ==========================================
     * LIMPAR ESTATÍSTICAS
     * ==========================================
     */

    private fun limparEstatisticas() {

        inputQueue.clear()

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

        playedOutputChunks =
            0L

        playedOutputBytes =
            0L

        lastRead =
            0
    }

    /*
     * ==========================================
     * CRIAR AUDIO RECORD
     * ==========================================
     */

    private fun criarAudioRecord() {

        if (
            Build.VERSION.SDK_INT <
            Build.VERSION_CODES.Q
        ) {

            throw Exception(
                "Captura de áudio interno requer Android 10+"
            )
        }

        val format =
            AudioFormat.Builder()
                .setEncoding(
                    AudioFormat.ENCODING_PCM_16BIT
                )
                .setSampleRate(
                    INPUT_SAMPLE_RATE
                )
                .setChannelMask(
                    AudioFormat.CHANNEL_IN_MONO
                )
                .build()

        /*
         * Captura áudio de aplicativos
         * usando USAGE_MEDIA.
         */

        val config =
            AudioPlaybackCaptureConfiguration.Builder(
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
                INPUT_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )

        if (
            minBuffer <= 0
        ) {

            throw Exception(
                "Buffer AudioRecord inválido: $minBuffer"
            )
        }

        val bufferSize =
            maxOf(
                minBuffer * 2,
                AUDIO_CHUNK_SIZE * 4
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
                    config
                )
                .build()

        if (
            audioRecord?.state !=
            AudioRecord.STATE_INITIALIZED
        ) {

            throw Exception(
                "AudioRecord não inicializado"
            )
        }

        Log.d(
            TAG,
            "AudioRecord OK"
        )

        Log.d(
            TAG,
            "sampleRate=$INPUT_SAMPLE_RATE"
        )

        Log.d(
            TAG,
            "PCM16 mono"
        )

        Log.d(
            TAG,
            "buffer=$bufferSize"
        )

        enviarDiagnostico(
            "audioRecord_ready"
        )
    }

    /*
     * ==========================================
     * CRIAR AUDIO TRACK
     * ==========================================
     */

    private fun criarAudioTrack() {

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
                "Buffer AudioTrack inválido: $minBuffer"
            )
        }

        val bufferSize =
            maxOf(
                minBuffer * 4,
                OUTPUT_SAMPLE_RATE * 2
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
                    OUTPUT_SAMPLE_RATE
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
                "AudioTrack não inicializado"
            )
        }

        configurarRotaAudio()

        configurarVolumeAudio()

        audioTrack?.play()

        Log.d(
            TAG,
            "AudioTrack OK"
        )

        Log.d(
            TAG,
            "sampleRate=$OUTPUT_SAMPLE_RATE"
        )

        Log.d(
            TAG,
            "PCM16 mono"
        )

        Log.d(
            TAG,
            "AudioTrack PLAY"
        )

        verificarRotaAtual()

        enviarDiagnostico(
            "audioTrack_ready"
        )
    }

    /*
     * ==========================================
     * CONFIGURAR ALTO-FALANTE
     * ==========================================
     */

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
                        "Alto-falante encontrado"
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
                        "setPreferredDevice=$sucesso"
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

    /*
     * ==========================================
     * CONFIGURAR VOLUME
     * ==========================================
     */

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

                @Suppress(
                    "DEPRECATION"
                )

                audioTrack?.setStereoVolume(
                    1.0f,
                    1.0f
                )
            }

            val volume =
                audioManager?.getStreamVolume(
                    AudioManager.STREAM_MUSIC
                )

            val maxVolume =
                audioManager?.getStreamMaxVolume(
                    AudioManager.STREAM_MUSIC
                )

            Log.d(
                TAG,
                "Volume mídia=$volume/$maxVolume"
            )

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Erro volume",
                e
            )
        }
    }

    /*
     * ==========================================
     * VERIFICAR ROTA
     * ==========================================
     */

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
                        "ROTA DE SAÍDA"
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

                } else {

                    Log.d(
                        TAG,
                        "routedDevice=null"
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

    /*
     * ==========================================
     * TOM DE TESTE
     * ==========================================
     */

    private fun tocarTesteAudio() {

        Thread {

            try {

                Thread.sleep(
                    500
                )

                val durationMs =
                    1000

                val totalSamples =
                    OUTPUT_SAMPLE_RATE *
                        durationMs /
                        1000

                val buffer =
                    ShortArray(
                        totalSamples
                    )

                val frequency =
                    440.0

                val amplitude =
                    0.35

                for (
                    i in buffer.indices
                ) {

                    val value =
                        sin(
                            2.0 *
                                Math.PI *
                                frequency *
                                i /
                                OUTPUT_SAMPLE_RATE
                        )

                    buffer[i] =
                        (
                            value *
                                Short.MAX_VALUE *
                                amplitude
                            )
                            .toInt()
                            .toShort()
                }

                Log.d(
                    TAG,
                    "======================================"
                )

                Log.d(
                    TAG,
                    "TOM DE TESTE"
                )

                Log.d(
                    TAG,
                    "======================================"
                )

                val written =
                    audioTrack?.write(
                        buffer,
                        0,
                        buffer.size,
                        AudioTrack.WRITE_BLOCKING
                    )
                        ?: 0

                Log.d(
                    TAG,
                    "Tom escrito=$written samples"
                )

                Log.d(
                    TAG,
                    "Tom de teste finalizado"
                )

                verificarRotaAtual()

            } catch (e: Exception) {

                Log.e(
                    TAG,
                    "Erro tom teste",
                    e
                )
            }

        }.start()
    }

    /*
     * ==========================================
     * THREAD DE CAPTURA
     * ==========================================
     */

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
                            "AudioRecord não iniciou"
                        )
                    }

                    lastStage =
                        "audio_record_recording"

                    enviarDiagnostico(
                        "audio_record_recording"
                    )

                    Log.d(
                        TAG,
                        "======================================"
                    )

                    Log.d(
                        TAG,
                        "CAPTURA DE ÁUDIO INTERNO INICIADA"
                    )

                    Log.d(
                        TAG,
                        "======================================"
                    )

                    val buffer =
                        ByteArray(
                            AUDIO_CHUNK_SIZE
                        )

                    while (
                        running.get()
                    ) {

                        val bytesRead =
                            record.read(
                                buffer,
                                0,
                                buffer.size,
                                AudioRecord.READ_BLOCKING
                            )

                        lastRead =
                            bytesRead

                        if (
                            bytesRead > 0
                        ) {

                            readCount++

                            capturedBytes +=
                                bytesRead

                            val audio =
                                buffer.copyOf(
                                    bytesRead
                                )

                            val added =
                                inputQueue.offer(
                                    audio
                                )

                            if (!added) {

                                Log.w(
                                    TAG,
                                    "Fila cheia"
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
                                        "CAPTURA: " +
                                            "read=$readCount " +
                                            "bytes=$bytesRead " +
                                            "fila=${inputQueue.size}"
                                    )
                                }
                            }

                        } else {

                            Log.w(
                                TAG,
                                "AudioRecord.read=$bytesRead"
                            )
                        }
                    }

                } catch (e: Exception) {

                    if (
                        running.get()
                    ) {

                        Log.e(
                            TAG,
                            "ERRO CAPTURA",
                            e
                        )

                        enviarDiagnostico(
                            "capture_error:${e.message}"
                        )
                    }
                }
            }

        captureThread?.start()
    }

    /*
     * ==========================================
     * THREAD DE ENVIO
     * ==========================================
     */

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
                                10
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

                    } catch (e: Exception) {

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

    /*
     * ==========================================
     * ENVIO PARA RENDER
     *
     * FORMATO:
     *
     * {
     *   "jobId":"...",
     *   "audio":"BASE64..."
     * }
     * ==========================================
     */

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

            /*
             * PCM -> Base64
             */

            val audioBase64 =
                Base64.encodeToString(
                    audio,
                    Base64.NO_WRAP
                )

            /*
             * JSON.
             */

            val json =
                """
                {
                  "jobId":"$id",
                  "audio":"$audioBase64"
                }
                """.trimIndent()

            val body =
                json.toByteArray(
                    Charsets.UTF_8
                )

            connection.setRequestProperty(
                "Content-Type",
                "application/json; charset=UTF-8"
            )

            connection.setRequestProperty(
                "Accept",
                "application/json"
            )

            connection.setRequestProperty(
                "Content-Length",
                body.size.toString()
            )

            /*
             * Envia.
             */

            connection.outputStream.use { output ->

                output.write(
                    body
                )

                output.flush()
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
                        "ENVIO OK: " +
                            "chunk=$sentChunks " +
                            "pcm=${audio.size} " +
                            "json=${body.size} " +
                            "total=$sentBytes"
                    )
                }

            } else {

                sendErrors++

                Log.e(
                    TAG,
                    "ENVIO HTTP ERRO: " +
                        "status=$status " +
                        "pcm=${audio.size}"
                )

                try {

                    val erro =
                        connection.errorStream
                            ?.bufferedReader()
                            ?.use {
                                it.readText()
                            }

                    if (
                        !erro.isNullOrBlank()
                    ) {

                        Log.e(
                            TAG,
                            "RESPOSTA RENDER: $erro"
                        )
                    }

                } catch (_: Exception) {
                }

                enviarDiagnostico(
                    "send_http_error:$status"
                )
            }

        } catch (e: Exception) {

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

            } catch (_: Exception) {
            }
        }
    }

    /*
     * ==========================================
     * THREAD DE SAÍDA
     * ==========================================
     */

    private fun iniciarThreadSaida() {

        outputThread =
            Thread {

                Log.d(
                    TAG,
                    "THREAD DE SAÍDA INICIADA"
                )

                while (
                    running.get()
                ) {

                    try {

                        buscarAudioTraduzido()

                        Thread.sleep(
                            OUTPUT_POLL_INTERVAL
                        )

                    } catch (
                        e: InterruptedException
                    ) {

                        break

                    } catch (e: Exception) {

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
                    "THREAD DE SAÍDA FINALIZADA"
                )
            }

        outputThread?.start()
    }

    /*
     * ==========================================
     * BUSCAR VOZ TRADUZIDA
     *
     * O SERVER RETORNA:
     *
     * {
     *   "ok":true,
     *   "available":true,
     *   "audio":"BASE64...",
     *   "sampleRate":24000,
     *   "channels":1,
     *   "format":"pcm_s16le"
     * }
     * ==========================================
     */

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
                    "$BACKEND_URL/api/audio/output/$id"
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
                "Accept",
                "application/json"
            )

            val status =
                connection.responseCode

            if (
                status != 200
            ) {

                Log.w(
                    TAG,
                    "OUTPUT HTTP status=$status"
                )

                return
            }

            val resposta =
                connection.inputStream
                    .bufferedReader()
                    .use {
                        it.readText()
                    }

            if (
                resposta.isBlank()
            ) {

                return
            }

            /*
             * Se não existe áudio ainda,
             * o servidor responde:
             *
             * "available":false
             */

            if (
                resposta.contains(
                    "\"available\":false"
                )
            ) {

                return
            }

            /*
             * Procura o campo audio.
             */

            val regex =
                Regex(
                    """"audio"\s*:\s*"([^"]+)""""
                )

            val match =
                regex.find(
                    resposta
                )

            if (
                match == null
            ) {

                Log.w(
                    TAG,
                    "Campo audio não encontrado"
                )

                return
            }

            val audioBase64 =
                match.groupValues[1]

            if (
                audioBase64.isBlank()
            ) {

                return
            }

            /*
             * Base64 -> PCM.
             */

            val bytes =
                Base64.decode(
                    audioBase64,
                    Base64.DEFAULT
                )

            if (
                bytes.isEmpty()
            ) {

                return
            }

            receivedOutputChunks++

            receivedOutputBytes +=
                bytes.size

            Log.d(
                TAG,
                "======================================"
            )

            Log.d(
                TAG,
                "VOZ TRADUZIDA RECEBIDA"
            )

            Log.d(
                TAG,
                "bytes=${bytes.size}"
            )

            Log.d(
                TAG,
                "sampleRate=24000"
            )

            Log.d(
                TAG,
                "channels=1"
            )

            Log.d(
                TAG,
                "format=PCM16"
            )

            Log.d(
                TAG,
                "======================================"
            )

            /*
             * Reproduz.
             */

            reproduzirAudio(
                bytes
            )

        } catch (e: Exception) {

            if (
                running.get()
            ) {

                Log.e(
                    TAG,
                    "ERRO BUSCANDO VOZ",
                    e
                )

                enviarDiagnostico(
                    "output_error:${e.message}"
                )
            }

        } finally {

            try {

                connection?.disconnect()

            } catch (_: Exception) {
            }
        }
    }

    /*
     * ==========================================
     * REPRODUZIR ÁUDIO GEMINI
     * ==========================================
     */

    private fun reproduzirAudio(
        bytes: ByteArray
    ) {

        try {

            val track =
                audioTrack
                    ?: return

            if (
                track.state !=
                AudioTrack.STATE_INITIALIZED
            ) {

                Log.e(
                    TAG,
                    "AudioTrack não inicializado"
                )

                enviarDiagnostico(
                    "audio_track_not_initialized"
                )

                return
            }

            /*
             * Garante que está tocando.
             */

            if (
                track.playState !=
                AudioTrack.PLAYSTATE_PLAYING
            ) {

                Log.d(
                    TAG,
                    "AudioTrack não estava PLAYING"
                )

                configurarRotaAudio()

                configurarVolumeAudio()

                track.play()
            }

            /*
             * Escreve PCM diretamente.
             *
             * Não converter.
             * Não alterar sample rate.
             * Não fazer nova decodificação.
             *
             * O servidor já informou:
             * 24000 Hz
             * PCM S16LE
             * mono.
             */

            val written =
                track.write(
                    bytes,
                    0,
                    bytes.size,
                    AudioTrack.WRITE_BLOCKING
                )

            if (
                written > 0
            ) {

                playedOutputChunks++

                /*
                 * AudioTrack.write(ByteArray)
                 * retorna quantidade de BYTES.
                 */

                playedOutputBytes +=
                    written

                if (
                    playedOutputChunks <= 5L ||
                    playedOutputChunks % 20L == 0L
                ) {

                    Log.d(
                        TAG,
                        "======================================"
                    )

                    Log.d(
                        TAG,
                        "VOZ SI REPRODUZIDA"
                    )

                    Log.d(
                        TAG,
                        "written=$written"
                    )

                    Log.d(
                        TAG,
                        "total=$playedOutputBytes"
                    )

                    Log.d(
                        TAG,
                        "======================================"
                    )
                }

            } else {

                Log.e(
                    TAG,
                    "AudioTrack.write retornou $written"
                )

                enviarDiagnostico(
                    "audio_track_write_error:$written"
                )
            }

        } catch (e: Exception) {

            Log.e(
                TAG,
                "ERRO REPRODUZINDO VOZ",
                e
            )

            enviarDiagnostico(
                "play_error:${e.message}"
            )
        }
    }

    /*
     * ==========================================
     * DIAGNÓSTICO AUTOMÁTICO
     * ==========================================
     */

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

                    } catch (e: Exception) {

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

    /*
     * ==========================================
     * ENVIAR DIAGNÓSTICO
     * ==========================================
     */

    private fun enviarDiagnostico(
        stage: String
    ) {

        Thread {

            try {

                val id =
                    jobId
                        ?: return@Thread

                val json =
                    """
                    {
                      "jobId":"$id",
                      "stage":"$stage",
                      "readCount":$readCount,
                      "lastRead":$lastRead,
                      "capturedBytes":$capturedBytes,
                      "sentChunks":$sentChunks,
                      "sentBytes":$sentBytes,
                      "sendErrors":$sendErrors,
                      "inputQueueSize":${inputQueue.size},
                      "receivedOutputChunks":$receivedOutputChunks,
                      "receivedOutputBytes":$receivedOutputBytes,
                      "playedOutputChunks":$playedOutputChunks,
                      "playedOutputBytes":$playedOutputBytes
                    }
                    """.trimIndent()

                val url =
                    URL(
                        "$BACKEND_URL/api/audio/diagnostic"
                    )

                val connection =
                    url.openConnection()
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

                connection.outputStream.use {
                    it.write(
                        json.toByteArray(
                            Charsets.UTF_8
                        )
                    )

                    it.flush()
                }

                connection.responseCode

                connection.disconnect()

            } catch (e: Exception) {

                Log.e(
                    TAG,
                    "Falha enviando diagnóstico",
                    e
                )
            }

        }.start()
    }

    /*
     * ==========================================
     * PARAR TUDO
     * ==========================================
     */

    private fun pararTudo() {

        val estavaRodando =
            running.getAndSet(false)

        if (!estavaRodando) {

            stopSelf()

            return
        }

        lastStage =
            "stopping"

        Log.d(
            TAG,
            "======================================"
        )

        Log.d(
            TAG,
            "PARANDO SI TRADUTOR LIVE"
        )

        Log.d(
            TAG,
            "======================================"
        )

        /*
         * Para captura.
         */

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

        /*
         * Para reprodução.
         */

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

        /*
         * Libera MediaProjection.
         */

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

        /*
         * Para threads.
         */

        captureThread?.interrupt()

        sendThread?.interrupt()

        outputThread?.interrupt()

        diagnosticThread?.interrupt()

        captureThread =
            null

        sendThread =
            null

        outputThread =
            null

        diagnosticThread =
            null

        /*
         * Limpa fila.
         */

        inputQueue.clear()

        /*
         * Diagnóstico final.
         */

        enviarDiagnostico(
            "stop_requested"
        )

        /*
         * Remove foreground.
         */

        stopForeground(
            STOP_FOREGROUND_REMOVE
        )

        stopSelf()
    }

    /*
     * ==========================================
     * CANAL DE NOTIFICAÇÃO
     * ==========================================
     */

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
                "Captura e tradução de áudio em tempo real"

            val manager =
                getSystemService(
                    NotificationManager::class.java
                )

            manager.createNotificationChannel(
                channel
            )
        }
    }

    /*
     * ==========================================
     * BIND
     * ==========================================
     */

    override fun onBind(
        intent: Intent?
    ): IBinder? {

        return null
    }

    /*
     * ==========================================
     * DESTROY
     * ==========================================
     */

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
