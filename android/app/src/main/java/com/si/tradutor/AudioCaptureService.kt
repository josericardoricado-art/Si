package com.si.tradutor

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.*
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.Base64
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

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

        private const val SAMPLE_RATE =
            48000

        private const val BUFFER_SIZE =
            9600
    }

    private var mediaProjection: MediaProjection? =
        null

    private var audioRecord: AudioRecord? =
        null

    private var captureThread: Thread? =
        null

    private var outputThread: Thread? =
        null

    @Volatile
    private var recording =
        false

    @Volatile
    private var playingOutput =
        true

    private var jobId: String? =
        null

    private var audioTrack: AudioTrack? =
        null


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
             * Captura de áudio interno.
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


            if (
                minBuffer <= 0
            ) {

                pararTudo()

                stopSelf()

                return
            }


            val realBuffer =
                maxOf(
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
             * Inicia captura.
             */

            audioRecord?.startRecording()

            recording =
                true

            playingOutput =
                true


            /*
             * Thread que captura o áudio
             * do vídeo e envia ao Render.
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
             * Thread que busca o áudio
             * traduzido no Render.
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


                if (
                    quantidade > 0
                ) {

                    val audio =
                        buffer.copyOf(
                            quantidade
                        )

                    enviarParaRender(
                        audio
                    )
                }

            } catch (
                e: Exception
            ) {

                break
            }
        }
    }


    /*
     * Envia o áudio capturado para o Render.
     */

    private fun enviarParaRender(
        audio: ByteArray
    ) {

        val currentJob =
            jobId ?: return


        Thread {

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
                    "audioBase64",
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


                connection.setRequestProperty(
                    "Content-Type",
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


                connection.responseCode

                connection.disconnect()


            } catch (
                _: Exception
            ) {

                /*
                 * Um chunk perdido não
                 * encerra a captura.
                 */
            }

        }.start()
    }


    /*
     * Busca continuamente o áudio traduzido.
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
                        500
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


                if (
                    code == 200
                ) {

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


                /*
                 * Pequeno intervalo para
                 * não sobrecarregar o Render.
                 */

                Thread.sleep(
                    150
                )


            } catch (
                _: Exception
            ) {

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


    /*
     * Recebe o JSON do Render.
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


            val audioArray =
                json.optJSONArray(
                    "audio"
                )


            if (
                audioArray == null
            ) {

                return
            }


            for (
                i in 0 until audioArray.length()
            ) {

                val base64 =
                    audioArray.optString(
                        i,
                        ""
                    )


                if (
                    base64.isEmpty()
                ) {

                    continue
                }


                val audioBytes =
                    Base64.decode(
                        base64,
                        Base64.DEFAULT
                    )


                if (
                    audioBytes.isNotEmpty()
                ) {

                    tocarAudio(
                        audioBytes
                    )
                }
            }


        } catch (
            _: Exception
        ) {
        }
    }


    /*
     * Reproduz o PCM 16 kHz recebido.
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
                                .setUsage(
                                    AudioAttributes.USAGE_MEDIA
                                )
                                .setContentType(
                                    AudioAttributes.CONTENT_TYPE_SPEECH
                                )
                                .build()
                        )
                        .setAudioFormat(
                            AudioFormat.Builder()
                                .setEncoding(
                                    AudioFormat.ENCODING_PCM_16BIT
                                )
                                .setSampleRate(
                                    16000
                                )
                                .setChannelMask(
                                    AudioFormat.CHANNEL_OUT_MONO
                                )
                                .build()
                        )
                        .setBufferSizeInBytes(
                            maxOf(
                                minBuffer,
                                4096
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
            _: Exception
        ) {
        }
    }


    private fun pararTudo() {

        recording =
            false

        playingOutput =
            false


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


        try {

            captureThread?.interrupt()

        } catch (
            _: Exception
        ) {
        }

        captureThread =
            null


        try {

            outputThread?.interrupt()

        } catch (
            _: Exception
        ) {
        }

        outputThread =
            null


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


        try {

            mediaProjection?.stop()

        } catch (
            _: Exception
        ) {
        }

        mediaProjection =
            null

        jobId =
            null


        try {

            stopForeground(
                STOP_FOREGROUND_REMOVE
            )

        } catch (
            _: Exception
        ) {
        }
    }


    private fun criarCanal() {

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
                    android.R.drawable.ic_btn_speak_now
                )
                .setOngoing(true)
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
