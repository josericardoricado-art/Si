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
import java.io.BufferedReader
import java.io.InputStreamReader
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

        private const val CHANNEL_COUNT =
            1

        private const val BYTES_PER_SAMPLE =
            2
    }

    private var mediaProjection: MediaProjection? = null

    private var audioRecord: AudioRecord? = null

    private var recordingThread: Thread? = null

    @Volatile
    private var isRecording = false

    private var currentJobId: String? = null

    override fun onCreate() {

        super.onCreate()

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

                val resultCode =
                    intent.getIntExtra(
                        EXTRA_RESULT_CODE,
                        Activity.RESULT_CANCELED
                    )

                val resultData =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {

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

                val jobId =
                    intent.getStringExtra(
                        EXTRA_JOB_ID
                    )

                if (
                    resultCode != Activity.RESULT_OK ||
                    resultData == null ||
                    jobId.isNullOrEmpty()
                ) {

                    pararTudo()

                    stopSelf()

                    return START_NOT_STICKY
                }

                currentJobId =
                    jobId

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

        if (isRecording) {
            return
        }

        try {

            iniciarForeground()

            val projectionManager =
                getSystemService(
                    Context.MEDIA_PROJECTION_SERVICE
                ) as MediaProjectionManager

            mediaProjection =
                projectionManager.getMediaProjection(
                    resultCode,
                    resultData
                )

            if (mediaProjection == null) {

                stopSelf()

                return
            }

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

            val channelConfig =
                AudioFormat.CHANNEL_IN_MONO

            val audioFormat =
                AudioFormat.ENCODING_PCM_16BIT

            val minBufferSize =
                AudioRecord.getMinBufferSize(
                    SAMPLE_RATE,
                    channelConfig,
                    audioFormat
                )

            if (
                minBufferSize ==
                AudioRecord.ERROR ||
                minBufferSize ==
                AudioRecord.ERROR_BAD_VALUE
            ) {

                pararTudo()

                stopSelf()

                return
            }

            val bufferSize =
                maxOf(
                    minBufferSize * 2,
                    9600
                )

            audioRecord =
                AudioRecord.Builder()
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(
                                audioFormat
                            )
                            .setSampleRate(
                                SAMPLE_RATE
                            )
                            .setChannelMask(
                                channelConfig
                            )
                            .build()
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

                pararTudo()

                stopSelf()

                return
            }

            mediaProjection?.registerCallback(
                object :
                    MediaProjection.Callback() {

                    override fun onStop() {

                        pararTudo()

                        stopSelf()
                    }
                },
                null
            )

            audioRecord?.startRecording()

            isRecording = true

            recordingThread =
                Thread {

                    capturarAudio()

                }.apply {

                    name =
                        "SI-AudioCapture"

                    start()
                }

        } catch (e: Exception) {

            pararTudo()

            stopSelf()
        }
    }

    private fun capturarAudio() {

        val buffer =
            ByteArray(9600)

        while (isRecording) {

            try {

                val bytesRead =
                    audioRecord?.read(
                        buffer,
                        0,
                        buffer.size,
                        AudioRecord.READ_BLOCKING
                    ) ?: 0

                if (bytesRead > 0) {

                    val audioData =
                        buffer.copyOf(
                            bytesRead
                        )

                    enviarAudioParaRender(
                        audioData
                    )
                }

            } catch (e: Exception) {

                break
            }
        }
    }

    private fun enviarAudioParaRender(
        audioData: ByteArray
    ) {

        val jobId =
            currentJobId ?: return

        Thread {

            try {

                val audioBase64 =
                    Base64.encodeToString(
                        audioData,
                        Base64.NO_WRAP
                    )

                val body =
                    JSONObject()

                body.put(
                    "jobId",
                    jobId
                )

                body.put(
                    "audioBase64",
                    audioBase64
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

                connection.setRequestProperty(
                    "Content-Type",
                    "application/json"
                )

                connection.setRequestProperty(
                    "X-Job-Id",
                    jobId
                )

                connection.setRequestProperty(
                    "X-Client-Id",
                    "android"
                )

                OutputStreamWriter(
                    connection.outputStream
                ).use { writer ->

                    writer.write(
                        body.toString()
                    )

                    writer.flush()
                }

                val responseCode =
                    connection.responseCode

                if (
                    responseCode !in 200..299
                ) {

                    // O servidor respondeu com erro.
                    // O próximo pedaço de áudio continuará normalmente.
                }

                connection.disconnect()

            } catch (_: Exception) {

                // Falha de um pedaço não encerra
                // a captura inteira.
            }

        }.start()
    }

    private fun pararTudo() {

        isRecording = false

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

            recordingThread?.interrupt()

        } catch (_: Exception) {
        }

        recordingThread = null

        try {

            mediaProjection?.stop()

        } catch (_: Exception) {
        }

        mediaProjection = null

        currentJobId = null

        try {

            stopForeground(
                STOP_FOREGROUND_REMOVE
            )

        } catch (_: Exception) {
        }
    }

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
                "Captura de áudio do vídeo"

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
                    "Monitorando o áudio do vídeo..."
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
