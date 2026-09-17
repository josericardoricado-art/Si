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
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
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
         * Entrada:
         * PCM 16-bit mono 16 kHz.
         */
        private const val INPUT_SAMPLE_RATE =
            16000

        /*
         * Saída do Gemini:
         * PCM 16-bit mono 24 kHz.
         */
        private const val OUTPUT_SAMPLE_RATE =
            24000

        /*
         * 3200 bytes =
         * aproximadamente 100 ms
         * de PCM16 mono a 16 kHz.
         */
        private const val INPUT_CHUNK_SIZE =
            3200

        private const val INPUT_QUEUE_SIZE =
            120

        /*
         * Fila exclusiva da voz traduzida.
         *
         * Ela é maior que a fila de entrada
         * porque precisamos acumular alguns
         * segundos para evitar pausas.
         */
        private const val OUTPUT_QUEUE_SIZE =
            300

        private const val OUTPUT_POLL_INTERVAL =
            60L

        private const val OUTPUT_HTTP_LIMIT =
            30

        /*
         * Amplificação digital.
         */
        private const val OUTPUT_GAIN =
            2.5f

        /*
         * Quantidade mínima de áudio que tentamos
         * acumular antes de considerar a reprodução
         * "aquecida".
         */
        private const val PREBUFFER_CHUNKS =
            3
    }

    private var mediaProjection:
        MediaProjection? = null

    private var audioRecord:
        AudioRecord? = null

    private var audioTrack:
        AudioTrack? = null

    private var audioManager:
        AudioManager? = null

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

    private val running =
        AtomicBoolean(false)

    private var jobId:
        String? = null

    /*
     * =========================================
     * FILA DE ENTRADA
     * =========================================
     */
    private val inputQueue =
        LinkedBlockingQueue<ByteArray>(
            INPUT_QUEUE_SIZE
        )

    /*
     * =========================================
     * FILA DE SAÍDA
     *
     * Esta é a parte nova.
     *
     * O polling coloca áudio aqui.
     *
     * O playbackThread retira daqui.
     * =========================================
     */
    private val outputQueue =
        LinkedBlockingQueue<ByteArray>(
            OUTPUT_QUEUE_SIZE
        )

    /*
     * Último seq recebido do Render.
     *
     * Não usamos isso para controlar
     * a reprodução.
     *
     * Ele serve somente para não receber
     * o mesmo áudio duas vezes.
     */
    private var lastOutputSeq =
        0L

    /*
     * Estatísticas.
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

    private var queuedOutputChunks =
        0L

    private var playedOutputChunks =
        0L

    private var playedOutputBytes =
        0L

    private var playbackWriteErrors =
        0L

    private var lastReadValue =
        0

    private var lastStage =
        "created"

    private var playbackStarted =
        false

    /*
     * =========================================
     * CALLBACK MEDIAPROJECTION
     * =========================================
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

        audioManager =
            getSystemService(
                Context.AUDIO_SERVICE
            ) as AudioManager

        criarCanalNotificacao()

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
                        "Dados MediaProjection ausentes"
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
                    "ACTION_STOP recebido"
                )

                pararTudo()
            }
        }

        return START_NOT_STICKY
    }

    /*
     * =========================================
     * FOREGROUND
     * =========================================
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
    }

    /*
     * =========================================
     * INICIAR CAPTURA
     * =========================================
     */
    private fun iniciarCaptura(
        resultCode: Int,
        resultData: Intent
    ) {

        if (running.get()) {

            Log.d(
                TAG,
                "Serviço já está rodando"
            )

            return
        }

        running.set(true)

        limparEstado()

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
             * Teste temporário.
             */
            tocarTesteAudio()

            /*
             * =================================
             * THREADS
             * =================================
             */

            iniciarThreadCaptura()

            iniciarThreadEnvio()

            /*
             * IMPORTANTE:
             *
             * Polling e reprodução agora
             * são threads separadas.
             */
            iniciarThreadPollingSaida()

            iniciarThreadPlayback()

            iniciarThreadDiagnostico()

            lastStage =
                "all_threads_started"

            enviarDiagnostico(
                "all_threads_started"
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

    private fun limparEstado() {

        inputQueue.clear()

        outputQueue.clear()

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

        lastReadValue =
            0

        playbackStarted =
            false
    }

    /*
     * =========================================
     * AUDIO RECORD
     * =========================================
     */
    private fun criarAudioRecord() {

        val audioFormat =
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
                INPUT_CHUNK_SIZE * 4
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
                "AudioRecord não inicializado"
            )
        }

        Log.d(
            TAG,
            "AudioRecord OK"
        )

        enviarDiagnostico(
            "audioRecord_ready"
        )
    }

    /*
     * =========================================
     * AUDIO TRACK
     * =========================================
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
                OUTPUT_SAMPLE_RATE * 2 * 4
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
            "AudioTrack PLAY"
        )

        verificarRotaAtual()

        enviarDiagnostico(
            "audioTrack_ready"
        )
    }

    /*
     * =========================================
     * ROTA DE ÁUDIO
     * =========================================
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
                        "Alto-falante interno selecionado"
                    )

                    Log.d(
                        TAG,
                        "preferredDevice=$sucesso"
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
     * =========================================
     * VOLUME
     * =========================================
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

                @Suppress("DEPRECATION")

                audioTrack?.setStereoVolume(
                    1.0f,
                    1.0f
                )
            }

            val volume =
                audioManager?.getStreamVolume(
                    AudioManager.STREAM_MUSIC
                )

            val max =
                audioManager?.getStreamMaxVolume(
                    AudioManager.STREAM_MUSIC
                )

            Log.d(
                TAG,
                "Volume mídia=$volume/$max"
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
     * =========================================
     * VERIFICAR ROTA
     * =========================================
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
                        "ROTA ATUAL: " +
                            "${device.productName} " +
                            "tipo=${device.type}"
                    )
                }
            }

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Erro rota",
                e
            )
        }
    }

    /*
     * =========================================
     * TOM DE TESTE
     * =========================================
     */
    private fun tocarTesteAudio() {

        Thread {

            try {

                Thread.sleep(500)

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

                    val valor =
                        sin(
                            2.0 *
                                Math.PI *
                                frequency *
                                i /
                                OUTPUT_SAMPLE_RATE
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
                    "TOM DE TESTE INICIANDO"
                )

                val escritos =
                    audioTrack?.write(
                        buffer,
                        0,
                        buffer.size,
                        AudioTrack.WRITE_BLOCKING
                    ) ?: 0

                Log.d(
                    TAG,
                    "TOM DE TESTE: $escritos samples"
                )

                Log.d(
                    TAG,
                    "TOM DE TESTE FINALIZADO"
                )

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
     * =========================================
     * THREAD DE CAPTURA
     * =========================================
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

                    val buffer =
                        ByteArray(
                            INPUT_CHUNK_SIZE
                        )

                    Log.d(
                        TAG,
                        "CAPTURA INTERNA INICIADA"
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

                            if (
                                !inputQueue.offer(
                                    audio
                                )
                            ) {

                                Log.w(
                                    TAG,
                                    "FILA INPUT CHEIA"
                                )

                                enviarDiagnostico(
                                    "input_queue_full"
                                )
                            }

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
     * =========================================
     * THREAD ENVIO
     * =========================================
     */
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
                            inputQueue.poll(
                                100,
                                TimeUnit.MILLISECONDS
                            )

                        if (
                            audio == null
                        ) {
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
                    "THREAD ENVIO FINALIZADA"
                )
            }

        sendThread?.start()
    }

    /*
     * =========================================
     * ENVIA ÁUDIO PARA O RENDER
     * =========================================
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

            val audioBase64 =
                Base64.encodeToString(
                    audio,
                    Base64.NO_WRAP
                )

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

            connection.outputStream.use {
                it.write(body)
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
                        "ENVIO OK chunk=$sentChunks pcm=${audio.size} total=$sentBytes"
                    )
                }

            } else {

                sendErrors++

                Log.e(
                    TAG,
                    "ENVIO HTTP ERRO status=$status"
                )

                try {

                    val erro =
                        connection.errorStream
                            ?.bufferedReader()
                            ?.use {
                                it.readText()
                            }

                    if (
                        !erro.isNullOrEmpty()
                    ) {

                        Log.e(
                            TAG,
                            "RENDER: $erro"
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
                "FALHA ENVIO",
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
     * =========================================
     * THREAD DE POLLING DA SAÍDA
     *
     * ESTA THREAD NÃO TOCA O ÁUDIO.
     *
     * Ela somente:
     *
     * Render → pega chunks
     *             ↓
     *       outputQueue
     *
     * A reprodução acontece em outra thread.
     * =========================================
     */
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
                            OUTPUT_POLL_INTERVAL
                        )

                    } catch (
                        e: InterruptedException
                    ) {

                        break

                    } catch (e: Exception) {

                        Log.e(
                            TAG,
                            "Erro polling saída",
                            e
                        )

                        try {
                            Thread.sleep(300)
                        } catch (_: Exception) {
                        }
                    }
                }

                Log.d(
                    TAG,
                    "THREAD POLLING SAÍDA FINALIZADA"
                )
            }

        outputPollThread?.start()
    }

    /*
     * =========================================
     * BUSCAR ÁUDIO NO RENDER
     * =========================================
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

            val status =
                connection.responseCode

            if (
                status != 200
            ) {

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
                    "Erro buscando saída",
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
     * =========================================
     * PROCESSAR CHUNKS
     *
     * NÃO REPRODUZ DIRETAMENTE.
     *
     * COLOCA NA FILA.
     * =========================================
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
                    try {

                        Base64.decode(
                            audioBase64,
                            Base64.NO_WRAP
                        )

                    } catch (e: Exception) {

                        Log.e(
                            TAG,
                            "Base64 inválido seq=$seq",
                            e
                        )

                        continue
                    }

                if (
                    bytes.size < 2
                ) {

                    continue
                }

                receivedOutputChunks++

                receivedOutputBytes +=
                    bytes.size

                /*
                 * =================================
                 * COLOCA NA FILA DE REPRODUÇÃO.
                 * =================================
                 */
                val colocado =
                    outputQueue.offer(
                        bytes
                    )

                if (
                    colocado
                ) {

                    queuedOutputChunks++

                    /*
                     * Só avançamos o seq quando
                     * conseguimos colocar o áudio
                     * na fila.
                     */
                    lastOutputSeq =
                        seq

                    if (
                        receivedOutputChunks <= 5L ||
                        receivedOutputChunks % 20L == 0L
                    ) {

                        Log.d(
                            TAG,
                            "ÁUDIO ENFILEIRADO " +
                                "seq=$seq " +
                                "bytes=${bytes.size} " +
                                "fila=${outputQueue.size}"
                        )
                    }

                } else {

                    /*
                     * Se a fila estiver cheia,
                     * NÃO avançamos o seq.
                     *
                     * Isso evita perder o chunk.
                     */
                    Log.w(
                        TAG,
                        "FILA OUTPUT CHEIA"
                    )

                    enviarDiagnostico(
                        "output_queue_full"
                    )

                    break
                }
            }

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Erro processando saída",
                e
            )

            enviarDiagnostico(
                "output_process_error:${e.message}"
            )
        }
    }

    /*
     * =========================================
     * EXTRATOR DOS CHUNKS
     * =========================================
     */
    private fun extrairChunks(
        json: String
    ): List<Pair<Long, String>> {

        val resultado =
            mutableListOf<Pair<Long, String>>()

        /*
         * Aceita:
         *
         * {"seq":1,"audio":"..."}
         *
         * mesmo se houver espaços.
         */
        val regex =
            Regex(
                """\{\s*"seq"\s*:\s*(\d+)\s*,\s*"audio"\s*:\s*"([^"]+)""""
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
     * =========================================
     * THREAD DE REPRODUÇÃO CONTÍNUA
     *
     * ESTA É A PRINCIPAL MUDANÇA.
     *
     * O polling pode continuar recebendo
     * novos chunks enquanto esta thread
     * está tocando o áudio.
     * =========================================
     */
    private fun iniciarThreadPlayback() {

        playbackThread =
            Thread {

                Log.d(
                    TAG,
                    "================================"
                )

                Log.d(
                    TAG,
                    "THREAD PLAYBACK INICIADA"
                )

                Log.d(
                    TAG,
                    "================================"
                )

                while (
                    running.get()
                ) {

                    try {

                        /*
                         * Espera até chegar áudio.
                         */
                        val bytes =
                            outputQueue.poll(
                                300,
                                TimeUnit.MILLISECONDS
                            )

                        if (
                            bytes == null
                        ) {

                            /*
                             * Não há áudio neste momento.
                             *
                             * Não encerramos a thread.
                             */
                            continue
                        }

                        /*
                         * Se por algum motivo o AudioTrack
                         * deixou de tocar, recuperamos.
                         */
                        garantirAudioTrackAtivo()

                        val processado =
                            amplificarPcm(
                                bytes
                            )

                        if (
                            processado.isEmpty()
                        ) {

                            continue
                        }

                        val track =
                            audioTrack

                        if (
                            track == null
                        ) {

                            Log.e(
                                TAG,
                                "AudioTrack nulo"
                            )

                            continue
                        }

                        /*
                         * =================================
                         * WRITE BLOCKING
                         *
                         * Agora quem escreve é SOMENTE
                         * esta thread.
                         *
                         * O polling continua livre.
                         * =================================
                         */
                        val escritos =
                            track.write(
                                processado,
                                0,
                                processado.size,
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
                                    "VOZ SI TOCANDO " +
                                        "bytes=$escritos " +
                                        "fila=${outputQueue.size} " +
                                        "total=$playedOutputBytes"
                                )
                            }

                        } else {

                            playbackWriteErrors++

                            Log.e(
                                TAG,
                                "AudioTrack.write=$escritos"
                            )

                            enviarDiagnostico(
                                "playback_write_error:$escritos"
                            )
                        }

                    } catch (
                        e: InterruptedException
                    ) {

                        break

                    } catch (e: Exception) {

                        Log.e(
                            TAG,
                            "ERRO PLAYBACK",
                            e
                        )

                        playbackWriteErrors++

                        try {
                            Thread.sleep(100)
                        } catch (_: Exception) {
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

    /*
     * =========================================
     * GARANTIR AUDIOTRACK ATIVO
     * =========================================
     */
    private fun garantirAudioTrackAtivo() {

        try {

            val track =
                audioTrack
                    ?: return

            if (
                track.state !=
                AudioTrack.STATE_INITIALIZED
            ) {

                return
            }

            if (
                track.playState !=
                AudioTrack.PLAYSTATE_PLAYING
            ) {

                Log.w(
                    TAG,
                    "AudioTrack não estava PLAYING. Reiniciando."
                )

                configurarRotaAudio()

                configurarVolumeAudio()

                track.play()

                playbackStarted =
                    true
            }

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Erro garantindo playback",
                e
            )
        }
    }

    /*
     * =========================================
     * AMPLIFICA PCM
     *
     * Entrada:
     * PCM16 little-endian mono.
     *
     * Saída:
     * PCM16 little-endian mono.
     * =========================================
     */
    private fun amplificarPcm(
        bytes: ByteArray
    ): ByteArray {

        val tamanhoPar =
            bytes.size -
                (bytes.size % 2)

        if (
            tamanhoPar <= 0
        ) {

            return ByteArray(0)
        }

        val resultado =
            ByteArray(
                tamanhoPar
            )

        var maiorAmostra =
            0

        var i =
            0

        while (
            i < tamanhoPar
        ) {

            val low =
                bytes[i]
                    .toInt() and 0xFF

            val high =
                bytes[i + 1]
                    .toInt()

            var sample =
                (high shl 8) or low

            if (
                sample > 32767
            ) {

                sample -= 65536
            }

            maiorAmostra =
                maxOf(
                    maiorAmostra,
                    abs(sample)
                )

            var novo =
                (
                    sample *
                        OUTPUT_GAIN
                ).toInt()

            if (
                novo > 32767
            ) {

                novo = 32767
            }

            if (
                novo < -32768
            ) {

                novo = -32768
            }

            resultado[i] =
                (
                    novo and 0xFF
                ).toByte()

            resultado[i + 1] =
                (
                    (novo shr 8)
                        and 0xFF
                ).toByte()

            i += 2
        }

        if (
            receivedOutputChunks <= 5L ||
            receivedOutputChunks % 20L == 0L
        ) {

            Log.d(
                TAG,
                "PCM VOZ: " +
                    "bytes=$tamanhoPar " +
                    "pico=$maiorAmostra " +
                    "ganho=$OUTPUT_GAIN"
            )
        }

        return resultado
    }

    /*
     * =========================================
     * DIAGNÓSTICO
     * =========================================
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
                      "lastRead":$lastReadValue,
                      "capturedBytes":$capturedBytes,
                      "sentChunks":$sentChunks,
                      "sentBytes":$sentBytes,
                      "sendErrors":$sendErrors,
                      "inputQueueSize":${inputQueue.size},
                      "receivedOutputChunks":$receivedOutputChunks,
                      "receivedOutputBytes":$receivedOutputBytes,
                      "queuedOutputChunks":$queuedOutputChunks,
                      "outputQueueSize":${outputQueue.size},
                      "playedOutputChunks":$playedOutputChunks,
                      "playedOutputBytes":$playedOutputBytes,
                      "playbackWriteErrors":$playbackWriteErrors,
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
                    "application/json; charset=UTF-8"
                )

                val body =
                    json.toByteArray(
                        Charsets.UTF_8
                    )

                connection.outputStream.use {
                    it.write(body)
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
     * =========================================
     * PARAR TUDO
     * =========================================
     */
    private fun pararTudo() {

        val estavaRodando =
            running.getAndSet(false)

        if (
            !estavaRodando
        ) {

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
            "fila input=${inputQueue.size}"
        )

        Log.d(
            TAG,
            "fila output=${outputQueue.size}"
        )

        Log.d(
            TAG,
            "================================"
        )

        /*
         * Interrompe primeiro as threads.
         */
        captureThread?.interrupt()

        sendThread?.interrupt()

        outputPollThread?.interrupt()

        playbackThread?.interrupt()

        diagnosticThread?.interrupt()

        /*
         * Para AudioRecord.
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
         * Para AudioTrack.
         */
        try {
            audioTrack?.stop()
        } catch (_: Exception) {
        }

        try {
            audioTrack?.flush()
        } catch (_: Exception) {
        }

        try {
            audioTrack?.release()
        } catch (_: Exception) {
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

        } catch (_: Exception) {
        }

        try {
            mediaProjection?.stop()
        } catch (_: Exception) {
        }

        mediaProjection =
            null

        /*
         * Limpa filas.
         */
        inputQueue.clear()

        outputQueue.clear()

        captureThread =
            null

        sendThread =
            null

        outputPollThread =
            null

        playbackThread =
            null

        diagnosticThread =
            null

        /*
         * Envia diagnóstico final.
         */
        enviarDiagnostico(
            "stop_requested"
        )

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

        stopSelf()
    }

    /*
     * =========================================
     * CANAL DE NOTIFICAÇÃO
     * =========================================
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
