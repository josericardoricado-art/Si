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
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.AudioPlaybackCaptureConfiguration
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
         * O Render / ElevenLabs está trabalhando
         * com 16 kHz.
         */
        private const val SAMPLE_RATE =
            16000

        /*
         * Aproximadamente 300 ms de áudio:
         *
         * 16000 samples/s
         * x 2 bytes
         * x 0,3 s
         * = 9600 bytes
         */
        private const val BUFFER_SIZE =
            9600
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
    private var recording = false

    @Volatile
    private var playingOutput = true

    private var jobId: String? = null

    /*
     * Fila de áudio.
     *
     * O captureThread coloca áudio aqui.
     * O sendThread envia um por vez.
     *
     * Assim não criamos centenas de Threads.
     */
    private val audioQueue =
        LinkedBlockingQueue<ByteArray>(
            30
        )


    override fun onCreate() {

        super.onCreate()

        criarCanal()
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

                if (
                    resultCode !=
                    Activity.RESULT_OK ||
                    resultData == null ||
                    jobId.isNullOrEmpty()
                ) {

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

                pararTudo()

                stopSelf()
            }
        }

        return START_NOT_STICKY
    }


    private fun iniciarCaptura(
        resultCode: Int,
        resultData: Intent
    ) {

        if (recording) {
            return
        }

        try {

            iniciarForeground()

            val manager =
                getSystemService(
                    Context.MEDIA_PROJECTION_SERVICE
                ) as MediaProjectionManager

            mediaProjection =
                manager.getMediaProjection(
                    resultCode,
                    resultData
                )

            if (mediaProjection == null) {

                stopSelf()

                return
            }

            /*
             * CAPTURA DO ÁUDIO INTERNO
             *
             * Importante:
             * estamos usando USAGE_MEDIA,
             * que é onde normalmente fica
             * o áudio do YouTube.
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


            val minBuffer =
                AudioRecord.getMinBufferSize(
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )

            if (minBuffer <= 0) {

                pararTudo()

                stopSelf()

                return
            }


            val realBuffer =
                max(
                    minBuffer * 2,
                    BUFFER_SIZE
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

                pararTudo()

                stopSelf()

                return
            }


            /*
             * Se o Android encerrar a projeção,
             * paramos tudo.
             */
            mediaProjection?.registerCallback(
                object : MediaProjection.Callback() {

                    override fun onStop() {

                        pararTudo()

                        stopSelf()
                    }
                },
                null
            )


            /*
             * Limpa qualquer áudio antigo.
             */
            audioQueue.clear()


            audioRecord?.startRecording()

            recording = true

            playingOutput = true


            /*
             * THREAD 1
             *
             * Captura o áudio do YouTube.
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
             * THREAD 2
             *
             * Envia o áudio para o Render
             * em sequência.
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
             * THREAD 3
             *
             * Busca a voz traduzida.
             */
            outputThread =
                Thread {

                    buscarAudioTraduzido()

                }.apply {

                    name =
                        "SI-TranslatedAudio"

                    start()
                }

        } catch (e: Exception) {

            pararTudo()

            stopSelf()
        }
    }


    /*
     * =====================================================
     * CAPTURA
     * =====================================================
     */

    private fun capturarAudio() {

        val buffer =
            ByteArray(
                BUFFER_SIZE
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


                if (quantidade > 0) {

                    val audio =
                        buffer.copyOf(
                            quantidade
                        )

                    /*
                     * Coloca na fila.
                     */
                    if (
                        !audioQueue.offer(
                            audio
                        )
                    ) {

                        /*
                         * Se a fila estiver cheia,
                         * descarta o pedaço mais antigo
                         * para manter o áudio em tempo real.
                         */
                        audioQueue.poll()

                        audioQueue.offer(
                            audio
                        )
                    }
                }

            } catch (
                e: Exception
            ) {

                if (recording) {
                    e.printStackTrace()
                }

                break
            }
        }
    }


    /*
     * =====================================================
     * ENVIO PARA RENDER
     * =====================================================
     */

    private fun enviarFilaParaRender() {

        while (recording) {

            try {

                val audio =
                    audioQueue.take()

                enviarParaRender(
                    audio
                )

            } catch (
                e: InterruptedException
            ) {

                break

            } catch (
                e: Exception
            ) {

                if (recording) {
                    e.printStackTrace()
                }
            }
        }
    }


    private fun enviarParaRender(
        audio: ByteArray
    ) {

        val currentJob =
            jobId ?: return


        try {

            /*
             * Converte para Base64.
             */
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
             * IMPORTANTE!
             *
             * O server.js espera:
             *
             * "audio"
             *
             * e NÃO:
             *
             * "audioBase64"
             */
            json.put(
                "audio",
                base64
            )


            val connection =
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
                "application/json"
            )

            connection.setRequestProperty(
                "Accept",
                "application/json"
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


            /*
             * Se o Render retornar erro,
             * registramos no Logcat.
             */
            if (responseCode !in 200..299) {

                println(
                    "SI Render erro HTTP: $responseCode"
                )
            }


            connection.disconnect()

        } catch (
            e: Exception
        ) {

            /*
             * Um erro isolado não encerra
             * a captura.
             */
            println(
                "SI envio áudio: ${e.message}"
            )
        }
    }


    /*
     * =====================================================
     * BUSCAR ÁUDIO TRADUZIDO
     * =====================================================
     */

    private fun buscarAudioTraduzido() {

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


                val code =
                    connection.responseCode


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

            } catch (
                e: Exception
            ) {

                if (recording) {

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
    }


    /*
     * =====================================================
     * PROCESSAR RESPOSTA
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


            /*
             * O server.js atual retorna:
             *
             * "audio": "base64"
             *
             * ou null.
             */
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


            val audioBytes =
                Base64.decode(
                    audio,
                    Base64.DEFAULT
                )


            if (
                audioBytes.isNotEmpty()
            ) {

                tocarAudio(
                    audioBytes
                )
            }

        } catch (
            e: Exception
        ) {

            println(
                "SI processamento áudio: ${e.message}"
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
                        16000,
                        AudioFormat.CHANNEL_OUT_MONO,
                        AudioFormat.ENCODING_PCM_16BIT
                    )


                audioTrack =
                    AudioTrack.Builder()
                        .setAudioAttributes(
                            AudioAttributes.Builder()
                                /*
                                 * Usamos ASSISTANCE_ACCESSIBILITY
                                 * para evitar que o áudio dublado
                                 * seja capturado novamente como
                                 * áudio do vídeo.
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
                                    16000
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
            }


            audioTrack?.write(
                audioBytes,
                0,
                audioBytes.size
            )

        } catch (
            e: Exception
        ) {

            println(
                "SI reprodução áudio: ${e.message}"
            )
        }
    }


    /*
     * =====================================================
     * PARAR
     * =====================================================
     */

    private fun pararTudo() {

        recording = false

        playingOutput = false


        /*
         * Libera a fila.
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
         * Foreground service
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
    }


    /*
     * =====================================================
     * NOTIFICAÇÃO
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
    }


    override fun onDestroy() {

        pararTudo()

        super.onDestroy()
    }


    override fun onBind(
        intent: Intent?
    ): IBinder? {

        return null
    }
}
