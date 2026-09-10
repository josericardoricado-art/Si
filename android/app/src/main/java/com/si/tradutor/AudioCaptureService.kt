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
import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.LinkedBlockingQueue
import kotlin.math.max

class AudioCaptureService : Service() {

    companion object {

        private const val TAG = "SI_AUDIO"

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

        // Entrada do Gemini
        private const val INPUT_SAMPLE_RATE =
            16000

        // 100 ms de PCM16 mono:
        // 16000 x 0,1 x 2 = 3200 bytes
        private const val CHUNK_SIZE =
            3200

        // Saída do Gemini
        private const val OUTPUT_SAMPLE_RATE =
            24000
    }

    private var mediaProjection: MediaProjection? = null

    private var audioRecord: AudioRecord? = null

    private var audioTrack: AudioTrack? = null

    private var captureThread: Thread? = null

    private var sendThread: Thread? = null

    private var outputThread: Thread? = null

    @Volatile
    private var recording = false

    @Volatile
    private var playingOutput = true

    private var jobId: String? = null

    private val audioQueue =
        LinkedBlockingQueue<ByteArray>(50)


    override fun onCreate() {
        super.onCreate()

        criarCanal()

        Log.d(
            TAG,
            "Service criado"
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

                Log.d(
                    TAG,
                    "ACTION_START jobId=$jobId"
                )

                if (
                    resultCode !=
                    Activity.RESULT_OK ||
                    resultData == null ||
                    jobId.isNullOrEmpty()
                ) {

                    Log.e(
                        TAG,
                        "Dados inválidos para iniciar captura"
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

                Log.d(
                    TAG,
                    "ACTION_STOP"
                )

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
            Log.d(
                TAG,
                "Captura já está ativa"
            )
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

                Log.e(
                    TAG,
                    "MediaProjection = null"
                )

                stopSelf()

                return
            }


            /*
             * CAPTURA DO ÁUDIO INTERNO
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
                    INPUT_SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )

            Log.d(
                TAG,
                "AudioRecord minBuffer=$minBuffer"
            )

            if (minBuffer <= 0) {

                Log.e(
                    TAG,
                    "getMinBufferSize inválido"
                )

                pararTudo()

                stopSelf()

                return
            }


            val realBuffer =
                max(
                    minBuffer * 2,
                    CHUNK_SIZE * 4
                )


            audioRecord =
                AudioRecord.Builder()
                    .setAudioFormat(
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

                Log.e(
                    TAG,
                    "AudioRecord NÃO inicializou"
                )

                pararTudo()

                stopSelf()

                return
            }


            Log.d(
                TAG,
                "AudioRecord inicializado"
            )


            mediaProjection?.registerCallback(
                object : MediaProjection.Callback() {

                    override fun onStop() {

                        Log.d(
                            TAG,
                            "MediaProjection foi encerrado"
                        )

                        pararTudo()

                        stopSelf()
                    }
                },
                null
            )


            audioQueue.clear()

            audioRecord?.startRecording()

            val recordState =
                audioRecord?.recordingState

            Log.d(
                TAG,
                "AudioRecord recordingState=$recordState"
            )

            recording = true

            playingOutput = true


            /*
             * CAPTURA
             */
            captureThread =
                Thread {

                    capturarAudio()

                }.apply {

                    name = "SI-Capture"

                    start()
                }


            /*
             * ENVIO
             */
            sendThread =
                Thread {

                    enviarFilaParaRender()

                }.apply {

                    name = "SI-AudioSender"

                    start()
                }


            /*
             * RECEBIMENTO DA VOZ
             */
            outputThread =
                Thread {

                    buscarAudioTraduzido()

                }.apply {

                    name = "SI-TranslatedAudio"

                    start()
                }


            Log.d(
                TAG,
                "CAPTURA INICIADA COM SUCESSO"
            )

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Erro iniciarCaptura",
                e
            )

            pararTudo()

            stopSelf()
        }
    }


    /*
     * =====================================================
     * CAPTURA DO ÁUDIO
     * =====================================================
     */

    private fun capturarAudio() {

        val buffer =
            ByteArray(
                CHUNK_SIZE
            )

        var contador = 0

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

                    contador++

                    val audio =
                        buffer.copyOf(
                            quantidade
                        )

                    if (
                        !audioQueue.offer(
                            audio
                        )
                    ) {

                        audioQueue.poll()

                        audioQueue.offer(
                            audio
                        )
                    }


                    /*
                     * Mostra diagnóstico a cada 20 blocos.
                     */
                    if (
                        contador % 20 == 0
                    ) {

                        Log.d(
                            TAG,
                            "ÁUDIO CAPTURADO: blocos=$contador bytes=$quantidade fila=${audioQueue.size}"
                        )
                    }

                } else {

                    Log.e(
                        TAG,
                        "AudioRecord.read retornou $quantidade"
                    )

                    Thread.sleep(50)
                }

            } catch (e: Exception) {

                Log.e(
                    TAG,
                    "Erro na captura",
                    e
                )

                break
            }
        }

        Log.d(
            TAG,
            "Thread de captura terminou"
        )
    }


    /*
     * =====================================================
     * ENVIO
     * =====================================================
     */

    private fun enviarFilaParaRender() {

        var enviados = 0

        while (recording) {

            try {

                val audio =
                    audioQueue.take()

                enviarParaRender(
                    audio
                )

                enviados++

                if (
                    enviados % 20 == 0
                ) {

                    Log.d(
                        TAG,
                        "ÁUDIO ENVIADO: $enviados"
                    )
                }

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
            "Thread de envio terminou"
        )
    }


    private fun enviarParaRender(
        audio: ByteArray
    ) {

        val currentJob =
            jobId ?: return

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

            json.put(
                "audio",
                base64
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


            if (
                responseCode !in 200..299
            ) {

                Log.e(
                    TAG,
                    "Render respondeu HTTP $responseCode"
                )

            } else {

                Log.d(
                    TAG,
                    "Chunk enviado HTTP $responseCode"
                )
            }


            connection.disconnect()

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Falha enviando áudio",
                e
            )
        }
    }


    /*
     * =====================================================
     * BUSCAR ÁUDIO GEMINI
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

                    Thread.sleep(300)

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
                    "Connection",
                    "close"
                )


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

                } else {

                    Log.d(
                        TAG,
                        "Output HTTP $code"
                    )
                }


                connection.disconnect()


                Thread.sleep(100)

            } catch (e: Exception) {

                if (recording) {

                    Log.e(
                        TAG,
                        "Erro buscando áudio traduzido",
                        e
                    )

                    try {

                        Thread.sleep(500)

                    } catch (_: Exception) {
                    }
                }
            }
        }
    }


    /*
     * =====================================================
     * PROCESSAR ÁUDIO RECEBIDO
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


            val audioBytes =
                Base64.decode(
                    audio,
                    Base64.DEFAULT
                )


            Log.d(
                TAG,
                "ÁUDIO TRADUZIDO RECEBIDO: ${audioBytes.size} bytes"
            )


            if (
                audioBytes.isNotEmpty()
            ) {

                tocarAudio(
                    audioBytes
                )
            }

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Erro processando áudio Gemini",
                e
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


                Log.d(
                    TAG,
                    "AudioTrack minBuffer=$minBuffer"
                )


                audioTrack =
                    AudioTrack.Builder()
                        .setAudioAttributes(
                            AudioAttributes.Builder()
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
                                16384
                            )
                        )
                        .setTransferMode(
                            AudioTrack.MODE_STREAM
                        )
                        .build()


                audioTrack?.play()


                Log.d(
                    TAG,
                    "AudioTrack iniciado em 24 kHz"
                )
            }


            val escrito =
                audioTrack?.write(
                    audioBytes,
                    0,
                    audioBytes.size
                ) ?: 0


            Log.d(
                TAG,
                "ÁUDIO REPRODUZIDO: $escrito bytes"
            )

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Erro reprodução",
                e
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

        audioQueue.clear()


        try {
            audioRecord?.stop()
        } catch (_: Exception) {
        }


        try {
            audioRecord?.release()
        } catch (_: Exception) {
        }

        audioRecord = null


        try {
            captureThread?.interrupt()
        } catch (_: Exception) {
        }

        captureThread = null


        try {
            sendThread?.interrupt()
        } catch (_: Exception) {
        }

        sendThread = null


        try {
            outputThread?.interrupt()
        } catch (_: Exception) {
        }

        outputThread = null


        try {
            audioTrack?.stop()
        } catch (_: Exception) {
        }


        try {
            audioTrack?.release()
        } catch (_: Exception) {
        }

        audioTrack = null


        try {
            mediaProjection?.stop()
        } catch (_: Exception) {
        }

        mediaProjection = null


        jobId = null


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
                stopForeground(true)
            }

        } catch (_: Exception) {
        }


        Log.d(
            TAG,
            "Tudo parado"
        )
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
                        "Capturando e traduzindo áudio..."
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
                        "Capturando e traduzindo áudio..."
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
