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
import android.media.AudioTrack
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.Base64
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.LinkedBlockingQueue
import kotlin.math.max

class AudioCaptureService : Service() {

    companion object {

        const val ACTION_START =
            "com.si.tradutor.ACTION_START"

        const val ACTION_STOP =
            "com.si.tradutor.ACTION_STOP"

        const val EXTRA_RESULT_CODE =
            "resultCode"

        const val EXTRA_RESULT_DATA =
            "resultData"

        const val EXTRA_JOB_ID =
            "jobId"

        private const val CHANNEL_ID =
            "si_audio_channel"

        private const val NOTIFICATION_ID =
            1001

        private const val BACKEND_URL =
            "https://si-u2ul.onrender.com"

        /*
         * ÁUDIO DE ENTRADA
         *
         * 16 kHz
         * Mono
         * PCM 16-bit
         */
        private const val SAMPLE_RATE =
            16000

        /*
         * 3200 bytes:
         *
         * 16000 samples/s
         * x 2 bytes/sample
         * = 32000 bytes/s
         *
         * 3200 bytes = aproximadamente 100 ms
         */
        private const val BUFFER_SIZE =
            3200

        /*
         * ÁUDIO DE SAÍDA DO GEMINI
         *
         * O Gemini Live Translation
         * retorna PCM 16-bit mono 24 kHz.
         */
        private const val OUTPUT_SAMPLE_RATE =
            24000
    }

    private var mediaProjection:
            MediaProjection? = null

    private var audioRecord:
            AudioRecord? = null

    private var audioTrack:
            AudioTrack? = null

    private var captureThread:
            Thread? = null

    private var sendThread:
            Thread? = null

    private var outputThread:
            Thread? = null

    @Volatile
    private var recording =
        false

    @Volatile
    private var playingOutput =
        true

    private var jobId:
            String? = null

    /*
     * Fila de áudio.
     */
    private val audioQueue =
        LinkedBlockingQueue<ByteArray>(30)


    override fun onCreate() {

        super.onCreate()

        println("SI: AudioCaptureService criado")

        criarCanal()
    }


    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        if (intent == null) {

            println(
                "SI: onStartCommand recebeu intent nulo"
            )

            return START_NOT_STICKY
        }

        when (intent.action) {

            ACTION_START -> {

                println(
                    "SI: ACTION_START recebido"
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

                jobId =
                    intent.getStringExtra(
                        EXTRA_JOB_ID
                    )

                println(
                    "SI: jobId = $jobId"
                )

                println(
                    "SI: resultCode = $resultCode"
                )

                if (
                    resultCode !=
                    Activity.RESULT_OK ||
                    resultData == null ||
                    jobId.isNullOrEmpty()
                ) {

                    println(
                        "SI: dados inválidos para iniciar captura"
                    )

                    pararTudo()

                    stopSelf()

                    return START_NOT_STICKY
                }

                iniciarCaptura(
                    resultCode,
                    resultData
                )
            }

            ACTION_STOP -> {

                println(
                    "SI: ACTION_STOP recebido"
                )

                pararTudo()

                stopSelf()
            }
        }

        return START_NOT_STICKY
    }


    /*
     * =====================================================
     * INICIAR CAPTURA
     * =====================================================
     */

    private fun iniciarCaptura(
        resultCode: Int,
        resultData: Intent
    ) {

        if (recording) {

            println(
                "SI: captura já está funcionando"
            )

            return
        }

        try {

            iniciarForeground()

            println(
                "SI: Foreground iniciado"
            )

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

                println(
                    "SI: MediaProjection é nulo"
                )

                stopSelf()

                return
            }

            println(
                "SI: MediaProjection criada"
            )


            /*
             * =================================================
             * CAPTURA DE ÁUDIO INTERNO
             * =================================================
             */

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


            println(
                "SI: configuração de captura criada"
            )


            val minBuffer =
                AudioRecord.getMinBufferSize(
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )


            println(
                "SI: minBuffer = $minBuffer"
            )


            if (minBuffer <= 0) {

                println(
                    "SI: AudioRecord.getMinBufferSize falhou"
                )

                pararTudo()

                stopSelf()

                return
            }


            val realBuffer =
                max(
                    minBuffer * 2,
                    BUFFER_SIZE
                )


            println(
                "SI: realBuffer = $realBuffer"
            )


            audioRecord =
                AudioRecord.Builder()
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(
                                AudioFormat.ENCODING_PCM_16BIT
                            )
                            .setSampleRate(
                                SAMPLE_RATE
                            )
                            .setChannelMask(
                                AudioFormat.CHANNEL_IN_MONO
                            )
                            .build()
                    )
                    .setBufferSizeInBytes(
                        realBuffer
                    )
                    .setAudioPlaybackCaptureConfig(
                        config
                    )
                    .build()


            if (
                audioRecord?.state !=
                AudioRecord.STATE_INITIALIZED
            ) {

                println(
                    "SI: AudioRecord NÃO inicializou"
                )

                pararTudo()

                stopSelf()

                return
            }


            println(
                "SI: AudioRecord inicializado"
            )


            /*
             * Callback da MediaProjection.
             */
            mediaProjection?.registerCallback(
                object : MediaProjection.Callback() {

                    override fun onStop() {

                        println(
                            "SI: MediaProjection foi encerrada"
                        )

                        pararTudo()

                        stopSelf()
                    }
                },
                null
            )


            /*
             * Limpa áudio antigo.
             */
            audioQueue.clear()


            /*
             * Começa a gravação.
             */
            audioRecord?.startRecording()


            println(
                "SI: AudioRecord.startRecording executado"
            )


            recording = true

            playingOutput = true


            /*
             * =================================================
             * THREAD DE CAPTURA
             * =================================================
             */

            captureThread =
                Thread {

                    capturarAudio()

                }.apply {

                    name =
                        "SI-Capture"

                    start()
                }


            /*
             * =================================================
             * THREAD DE ENVIO
             * =================================================
             */

            sendThread =
                Thread {

                    enviarFilaParaRender()

                }.apply {

                    name =
                        "SI-AudioSender"

                    start()
                }


            /*
             * =================================================
             * THREAD DE SAÍDA
             * =================================================
             */

            outputThread =
                Thread {

                    buscarAudioTraduzido()

                }.apply {

                    name =
                        "SI-TranslatedAudio"

                    start()
                }


            println(
                "SI: TODAS AS THREADS INICIADAS"
            )

        } catch (e: Exception) {

            println(
                "SI: erro ao iniciar captura: ${e.message}"
            )

            e.printStackTrace()

            pararTudo()

            stopSelf()
        }
    }


    /*
     * =====================================================
     * CAPTURAR ÁUDIO
     * =====================================================
     */

    private fun capturarAudio() {

        val buffer =
            ByteArray(
                BUFFER_SIZE
            )

        println(
            "SI: captura de áudio iniciada"
        )


        while (recording) {

            try {

                val quantidade =
                    audioRecord?.read(
                        buffer,
                        0,
                        buffer.size,
                        AudioRecord.READ_BLOCKING
                    ) ?: 0


                println(
                    "SI: AudioRecord.read = $quantidade"
                )


                if (quantidade > 0) {

                    val audio =
                        buffer.copyOf(
                            quantidade
                        )


                    val entrou =
                        audioQueue.offer(
                            audio
                        )


                    if (!entrou) {

                        audioQueue.poll()

                        audioQueue.offer(
                            audio
                        )

                        println(
                            "SI: fila cheia; áudio antigo descartado"
                        )

                    } else {

                        println(
                            "SI: áudio capturado: $quantidade bytes"
                        )
                    }
                }


                if (quantidade < 0) {

                    println(
                        "SI: AudioRecord erro = $quantidade"
                    )

                    break
                }

            } catch (e: Exception) {

                println(
                    "SI: erro na captura: ${e.message}"
                )

                if (recording) {

                    e.printStackTrace()
                }

                break
            }
        }


        println(
            "SI: captura de áudio encerrada"
        )
    }


    /*
     * =====================================================
     * ENVIAR FILA PARA RENDER
     * =====================================================
     */

    private fun enviarFilaParaRender() {

        println(
            "SI: thread de envio iniciada"
        )


        while (recording) {

            try {

                val audio =
                    audioQueue.take()


                println(
                    "SI: retirando áudio da fila: ${audio.size} bytes"
                )


                enviarParaRender(
                    audio
                )

            } catch (
                e: InterruptedException
            ) {

                println(
                    "SI: thread de envio interrompida"
                )

                break

            } catch (
                e: Exception
            ) {

                println(
                    "SI: erro thread envio: ${e.message}"
                )

                if (recording) {

                    e.printStackTrace()
                }
            }
        }


        println(
            "SI: thread de envio encerrada"
        )
    }


    /*
     * =====================================================
     * ENVIAR ÁUDIO
     * =====================================================
     */

    private fun enviarParaRender(
        audio: ByteArray
    ) {

        val currentJob =
            jobId


        if (
            currentJob.isNullOrEmpty()
        ) {

            println(
                "SI: não existe jobId"
            )

            return
        }


        try {

            val base64 =
                Base64.encodeToString(
                    audio,
                    Base64.NO_WRAP
                )


            val json =
                JSONObject()


            json.put(
                "jobId",
                currentJob
            )


            /*
             * O server.js espera "audio".
             */
            json.put(
                "audio",
                base64
            )


            val url =
                URL(
                    "$BACKEND_URL/api/audio/chunk"
                )


            println(
                "SI: enviando ${audio.size} bytes para Render"
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


            connection.setRequestProperty(
                "Accept",
                "application/json"
            )


            connection.setRequestProperty(
                "Connection",
                "close"
            )


            connection.setRequestProperty(
                "X-Job-Id",
                currentJob
            )


            OutputStreamWriter(
                connection.outputStream
            ).use { writer ->

                writer.write(
                    json.toString()
                )

                writer.flush()
            }


            val responseCode =
                connection.responseCode


            println(
                "SI: Render respondeu HTTP $responseCode"
            )


            if (
                responseCode !in 200..299
            ) {

                println(
                    "SI: erro HTTP no envio = $responseCode"
                )

            } else {

                println(
                    "SI: áudio enviado com sucesso"
                )
            }


            connection.disconnect()

        } catch (e: Exception) {

            println(
                "SI: erro enviando áudio: ${e.message}"
            )
        }
    }


    /*
     * =====================================================
     * BUSCAR ÁUDIO TRADUZIDO
     * =====================================================
     */

    private fun buscarAudioTraduzido() {

        println(
            "SI: thread de saída iniciada"
        )


        while (
            recording &&
            playingOutput
        ) {

            try {

                val currentJob =
                    jobId


                if (
                    currentJob.isNullOrEmpty()
                ) {

                    Thread.sleep(
                        300
                    )

                    continue
                }


                val connection =
                    URL(
                        "$BACKEND_URL/api/audio/output/$currentJob"
                    )
                        .openConnection()
                        as HttpURLConnection


                connection.requestMethod =
                    "GET"


                connection.connectTimeout =
                    5000


                connection.readTimeout =
                    10000


                connection.setRequestProperty(
                    "Accept",
                    "application/json"
                )


                val code =
                    connection.responseCode


                println(
                    "SI: output HTTP $code"
                )


                if (code == 200) {

                    val text =
                        connection.inputStream
                            .bufferedReader()
                            .use {
                                it.readText()
                            }


                    processarAudioTraduzido(
                        text
                    )
                }


                connection.disconnect()


                Thread.sleep(
                    150
                )

            } catch (e: Exception) {

                if (recording) {

                    println(
                        "SI: erro buscando áudio: ${e.message}"
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
        }


        println(
            "SI: thread de saída encerrada"
        )
    }


    /*
     * =====================================================
     * PROCESSAR ÁUDIO TRADUZIDO
     * =====================================================
     */

    private fun processarAudioTraduzido(
        text: String
    ) {

        try {

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


            val audio =
                json.optString(
                    "audio",
                    ""
                )


            if (
                audio.isEmpty()
            ) {

                return
            }


            println(
                "SI: áudio traduzido recebido"
            )


            val audioBytes =
                Base64.decode(
                    audio,
                    Base64.DEFAULT
                )


            if (
                audioBytes.isNotEmpty()
            ) {

                println(
                    "SI: reproduzindo ${audioBytes.size} bytes"
                )


                tocarAudio(
                    audioBytes
                )
            }

        } catch (e: Exception) {

            println(
                "SI: erro processando áudio: ${e.message}"
            )
        }
    }


    /*
     * =====================================================
     * REPRODUÇÃO
     * =====================================================
     */

    private fun tocarAudio(
        audioBytes: ByteArray
    ) {

        try {

            if (
                audioTrack == null
            ) {

                val minBuffer =
                    AudioTrack.getMinBufferSize(
                        OUTPUT_SAMPLE_RATE,
                        AudioFormat.CHANNEL_OUT_MONO,
                        AudioFormat.ENCODING_PCM_16BIT
                    )


                println(
                    "SI: AudioTrack minBuffer = $minBuffer"
                )


                audioTrack =
                    AudioTrack.Builder()
                        .setAudioAttributes(
                            AudioAttributes.Builder()
                                /*
                                 * Não usamos USAGE_MEDIA
                                 * para diminuir a chance de
                                 * o próprio áudio ser capturado.
                                 */
                                .setUsage(
                                    AudioAttributes
                                        .USAGE_ASSISTANCE_ACCESSIBILITY
                                )
                                .setContentType(
                                    AudioAttributes
                                        .CONTENT_TYPE_SPEECH
                                )
                                .build()
                        )
                        .setAudioFormat(
                            AudioFormat.Builder()
                                .setEncoding(
                                    AudioFormat
                                        .ENCODING_PCM_16BIT
                                )
                                .setSampleRate(
                                    OUTPUT_SAMPLE_RATE
                                )
                                .setChannelMask(
                                    AudioFormat
                                        .CHANNEL_OUT_MONO
                                )
                                .build()
                        )
                        .setBufferSizeInBytes(
                            max(
                                minBuffer,
                                8192
                            )
                        )
                        .setTransferMode(
                            AudioTrack.MODE_STREAM
                        )
                        .build()


                audioTrack?.play()


                println(
                    "SI: AudioTrack iniciado em 24 kHz"
                )
            }


            val escrito =
                audioTrack?.write(
                    audioBytes,
                    0,
                    audioBytes.size
                ) ?: 0


            println(
                "SI: AudioTrack escreveu $escrito bytes"
            )

        } catch (e: Exception) {

            println(
                "SI: erro reprodução áudio: ${e.message}"
            )

            e.printStackTrace()
        }
    }


    /*
     * =====================================================
     * PARAR TUDO
     * =====================================================
     */

    private fun pararTudo() {

        println(
            "SI: parando tudo"
        )


        recording = false

        playingOutput = false


        /*
         * Limpa fila.
         */
        audioQueue.clear()


        /*
         * AudioRecord
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


        audioRecord = null


        /*
         * Threads
         */
        try {

            captureThread?.interrupt()

        } catch (
            _: Exception
        ) {
        }


        captureThread = null


        try {

            sendThread?.interrupt()

        } catch (
            _: Exception
        ) {
        }


        sendThread = null


        try {

            outputThread?.interrupt()

        } catch (
            _: Exception
        ) {
        }


        outputThread = null


        /*
         * AudioTrack
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


        audioTrack = null


        /*
         * MediaProjection
         */
        try {

            mediaProjection?.stop()

        } catch (
            _: Exception
        ) {
        }


        mediaProjection = null


        jobId = null


        /*
         * Foreground
         */
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


        println(
            "SI: tudo parado"
        )
    }


    /*
     * =====================================================
     * CANAL DE NOTIFICAÇÃO
     * =====================================================
     */

    private fun criarCanal() {

        if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.O
        ) {

            val channel =
                NotificationChannel(
                    CHANNEL_ID,
                    "SI Tradutor Live",
                    NotificationManager
                        .IMPORTANCE_LOW
                )


            channel.description =
                "Tradução de áudio em tempo real"


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
     * =====================================================
     * FOREGROUND SERVICE
     * =====================================================
     */

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
                        "Traduzindo o áudio do vídeo..."
                    )
                    .setSmallIcon(
                        android.R.drawable
                            .ic_btn_speak_now
                    )
                    .setOngoing(true)
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
                        "Traduzindo o áudio do vídeo..."
                    )
                    .setSmallIcon(
                        android.R.drawable
                            .ic_btn_speak_now
                    )
                    .setOngoing(true)
                    .build()
            }


        if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.Q
        ) {

            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo
                    .FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )

        } else {

            startForeground(
                NOTIFICATION_ID,
                notification
            )
        }


        println(
            "SI: foreground service ativo"
        )
    }


    override fun onDestroy() {

        println(
            "SI: AudioCaptureService destruído"
        )

        pararTudo()

        super.onDestroy()
    }


    override fun onBind(
        intent: Intent?
    ): IBinder? {

        return null
    }
}
