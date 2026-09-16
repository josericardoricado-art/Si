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

        /*
         * Gemini Live
         *
         * Entrada:
         * PCM 16-bit
         * MONO
         * 16000 Hz
         *
         * Saída:
         * PCM 16-bit
         * MONO
         * 24000 Hz
         */

        private const val SAMPLE_RATE_INPUT =
            16000

        private const val SAMPLE_RATE_OUTPUT =
            24000

        /*
         * 3200 bytes =
         * 100 ms de PCM 16-bit mono
         * em 16000 Hz.
         */

        private const val CHUNK_SIZE =
            3200

        private const val OUTPUT_LIMIT =
            20

        /*
         * Limite da fila de entrada.
         */

        private const val MAX_INPUT_QUEUE =
            100

        /*
         * Intervalo entre consultas do áudio traduzido.
         */

        private const val OUTPUT_POLL_MS =
            120L
    }

    private var mediaProjection: MediaProjection? =
        null

    private var audioRecord: AudioRecord? =
        null

    private var audioTrack: AudioTrack? =
        null

    private var audioManager: AudioManager? =
        null

    private var captureThread: Thread? =
        null

    private var sendThread: Thread? =
        null

    private var outputThread: Thread? =
        null

    private var diagnosticThread: Thread? =
        null

    private val running =
        AtomicBoolean(false)

    private var jobId: String? =
        null

    private var lastOutputSeq =
        0L

    /*
     * Fila de áudio.
     */

    private val inputQueue =
        LinkedBlockingQueue<ByteArray>(
            MAX_INPUT_QUEUE
        )

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

    private var lastReadValue =
        0

    private var lastStage =
        "created"

    /*
     * Callback da MediaProjection.
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
                    recebidoJobId == null ||
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

    /*
     * =================================
     * FOREGROUND SERVICE
     * =================================
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

    /*
     * =================================
     * INICIAR CAPTURA
     * =================================
     */

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

        lastStage =
            "starting_capture"

        /*
         * Limpa execução anterior.
         */

        inputQueue.clear()

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

        playedOutputChunks =
            0L

        playedOutputBytes =
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
             * Tom de teste.
             */

            tocarTesteAudio()

            iniciarThreadCaptura()

            iniciarThreadEnvio()

            iniciarThreadSaida()

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

    /*
     * =================================
     * AUDIO RECORD
     * =================================
     */

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
                CHUNK_SIZE * 4
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
            "Input mono=true"
        )

        Log.d(
            TAG,
            "Buffer=$bufferSize"
        )

        enviarDiagnostico(
            "audioRecord_ready"
        )
    }

    /*
     * =================================
     * AUDIO TRACK
     * =================================
     */

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

        val bufferSize =
            maxOf(
                minBuffer * 4,
                SAMPLE_RATE_OUTPUT * 2
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

        Log.d(
            TAG,
            "playState=${audioTrack?.playState}"
        )

        Log.d(
            TAG,
            "state=${audioTrack?.state}"
        )

        verificarRotaAtual()

        enviarDiagnostico(
            "audioTrack_ready"
        )
    }

    /*
     * =================================
     * ROTA DE ÁUDIO
     * =================================
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

    /*
     * =================================
     * VOLUME
     * =================================
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

    /*
     * =================================
     * TOM DE TESTE
     * =================================
     */

    private fun tocarTesteAudio() {

        Thread {

            try {

                Thread.sleep(500)

                val durationMs =
                    1000

                val totalSamples =
                    SAMPLE_RATE_OUTPUT *
                        durationMs / 1000

                val buffer =
                    ShortArray(
                        totalSamples
                    )

                val frequencia =
                    440.0

                val amplitude =
                    0.35

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

                Log.d(
                    TAG,
                    "================================"
                )

                Log.d(
                    TAG,
                    "TOM DE TESTE INICIANDO"
                )

                Log.d(
                    TAG,
                    "================================"
                )

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
                    "TOM DE TESTE write=$escritos samples"
                )

                Log.d(
                    TAG,
                    "TOM DE TESTE FINALIZADO"
                )

                Log.d(
                    TAG,
                    "================================"
                )

                verificarRotaAtual()

            } catch (e: Exception) {

                Log.e(
                    TAG,
                    "Erro no tom de teste",
                    e
                )
            }

        }.start()
    }

    /*
     * =================================
     * VERIFICAR ROTA
     * =================================
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
     * =================================
     * CAPTURA DO ÁUDIO INTERNO
     * =================================
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
                            "AudioRecord não entrou em RECORDING"
                        )
                    }

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

                            if (!colocado) {

                                Log.w(
                                    TAG,
                                    "FILA CHEIA - descartando chunk"
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
                                        "CAPTURA: read=$readCount bytes=$lidos fila=${inputQueue.size}"
                                    )
                                }
                            }

                        } else {

                            Log.w(
                                TAG,
                                "AudioRecord.read retornou $lidos"
                            )
                        }
                    }

                } catch (e: Exception) {

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

    /*
     * =================================
     * ENVIO DO ÁUDIO AO RENDER
     *
     * CORREÇÃO PRINCIPAL
     * =================================
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
     * =================================
     * ENVIA PCM COMO JSON + BASE64
     * =================================
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
             * Converte o PCM para Base64.
             */

            val audioBase64 =
                Base64.encodeToString(
                    audio,
                    Base64.NO_WRAP
                )

            /*
             * JSON esperado pelo server.js.
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
             * Envia o JSON.
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
                            "pcmBytes=${audio.size} " +
                            "jsonBytes=${body.size} " +
                            "total=$sentBytes"
                    )
                }

            } else {

                sendErrors++

                Log.e(
                    TAG,
                    "ENVIO HTTP ERRO: " +
                        "status=$status " +
                        "pcmBytes=${audio.size}"
                )

                /*
                 * Lê a resposta do Render
                 * para facilitar diagnóstico.
                 */

                try {

                    val erro =
                        connection
                            .errorStream
                            ?.bufferedReader()
                            ?.use {
                                it.readText()
                            }

                    if (
                        !erro.isNullOrEmpty()
                    ) {

                        Log.e(
                            TAG,
                            "RESPOSTA DO RENDER: $erro"
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
     * =================================
     * RECEBIMENTO DA VOZ GEMINI
     * =================================
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
                            OUTPUT_POLL_MS
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
     * =================================
     * BUSCAR ÁUDIO TRADUZIDO
     * =================================
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
                    "$BACKEND_URL/api/audio/output/$id" +
                        "?after=$lastOutputSeq" +
                        "&limit=$OUTPUT_LIMIT"
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

            val reader =
                BufferedReader(
                    InputStreamReader(
                        connection.inputStream
                    )
                )

            val resposta =
                reader.readText()

            reader.close()

            if (
                resposta.isNotEmpty()
            ) {

                processarRespostaAudio(
                    resposta
                )
            }

        } catch (e: Exception) {

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

            } catch (_: Exception) {
            }
        }
    }

    /*
     * =================================
     * PROCESSAR SAÍDA
     * =================================
     */

    private fun processarRespostaAudio(
        json: String
    ) {

        try {

            val chunks =
                extrairChunks(
                    json
                )

            if (
                chunks.isEmpty()
            ) {

                return
            }

            Log.d(
                TAG,
                "RECEBIDOS ${chunks.size} CHUNKS GEMINI"
            )

            for (
                chunk in chunks
            ) {

                val seq =
                    chunk.first

                val audioBase64 =
                    chunk.second

                if (
                    seq <= lastOutputSeq
                ) {

                    continue
                }

                if (
                    audioBase64.isEmpty()
                ) {

                    continue
                }

                val bytes =
                    Base64.decode(
                        audioBase64,
                        Base64.NO_WRAP
                    )

                if (
                    bytes.isEmpty()
                ) {

                    continue
                }

                receivedOutputChunks++

                receivedOutputBytes +=
                    bytes.size

                Log.d(
                    TAG,
                    "VOZ GEMINI: " +
                        "seq=$seq " +
                        "bytes=${bytes.size}"
                )

                reproduzirAudio(
                    bytes
                )

                /*
                 * Avança depois de processar.
                 */

                lastOutputSeq =
                    seq
            }

        } catch (e: Exception) {

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

    /*
     * =================================
     * EXTRAIR CHUNKS
     * =================================
     */

    private fun extrairChunks(
        json: String
    ): List<Pair<Long, String>> {

        val resultado =
            mutableListOf<Pair<Long, String>>()

        /*
         * Procura:
         *
         * {"seq":123,"audio":"BASE64"}
         */

        val regex =
            Regex(
                """\{"seq":(\d+),"audio":"([^"]+)""""
            )

        val matches =
            regex.findAll(
                json
            )

        for (
            match in matches
        ) {

            val seq =
                match.groupValues[1]
                    .toLongOrNull()
                    ?: continue

            val audio =
                match.groupValues[2]

            resultado.add(
                Pair(
                    seq,
                    audio
                )
            )
        }

        return resultado.sortedBy {
            it.first
        }
    }

    /*
     * =================================
     * REPRODUZIR VOZ GEMINI
     * =================================
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
             * Se parou, reinicia.
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
             * Gemini retorna:
             *
             * PCM 16-bit
             * MONO
             * 24000 Hz
             */

            val escritos =
                track.write(
                    bytes,
                    0,
                    bytes.size,
                    AudioTrack.WRITE_BLOCKING
                )

            if (
                escritos > 0
            ) {

                playedOutputChunks++

                playedOutputBytes +=
                    escritos

                if (
                    playedOutputChunks <= 5L ||
                    playedOutputChunks % 20L == 0L
                ) {

                    Log.d(
                        TAG,
                        "VOZ SI REPRODUZIDA: " +
                            "bytes=$escritos " +
                            "total=$playedOutputBytes"
                    )
                }

            } else {

                Log.e(
                    TAG,
                    "AudioTrack.write retornou $escritos"
                )

                enviarDiagnostico(
                    "audio_track_write_error:$escritos"
                )
            }

        } catch (e: Exception) {

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

    /*
     * =================================
     * DIAGNÓSTICO
     * =================================
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
     * =================================
     * ENVIAR DIAGNÓSTICO
     * =================================
     */

    private fun enviarDiagnostico(
        stage: String
    ) {

        Thread {

            try {

                val id =
                    jobId
                        ?: return@Thread

                val queueSize =
                    inputQueue.size

                val json =
                    """
                    {
                      "jobId":"$id",
                      "stage":"$stage",
                      "readCount":$readCount,
                      "lastRead":$lastReadValue,
                      "capturedBytes":$capturedBytes,
                      "sentChunks":$sentChunks,
                      "sentBytes":$sentBytes,
                      "sendErrors":$sendErrors,
                      "inputQueueSize":$queueSize,
                      "receivedOutputChunks":$receivedOutputChunks,
                      "receivedOutputBytes":$receivedOutputBytes,
                      "playedOutputChunks":$playedOutputChunks,
                      "playedOutputBytes":$playedOutputBytes,
                      "lastOutputSeq":$lastOutputSeq
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
                    "Falha diagnóstico",
                    e
                )
            }

        }.start()
    }

    /*
     * =================================
     * PARAR TUDO
     * =================================
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

        inputQueue.clear()

        enviarDiagnostico(
            "stop_requested"
        )

        stopForeground(
            STOP_FOREGROUND_REMOVE
        )

        stopSelf()
    }

    /*
     * =================================
     * NOTIFICAÇÃO
     * =================================
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

    override fun onBind(
        intent: Intent?
    ): IBinder? {

        return null
    }

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
