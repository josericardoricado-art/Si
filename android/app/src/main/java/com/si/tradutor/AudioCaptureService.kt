package com.si.tradutor

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
import android.media.projection.MediaProjection
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat

class AudioCaptureService : Service() {

    companion object {

        private const val TAG = "SI_AUDIO"

        const val ACTION_START = "com.si.tradutor.START_CAPTURE"
        const val ACTION_STOP = "com.si.tradutor.STOP_CAPTURE"

        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"

        private const val CHANNEL_ID = "si_audio_capture"
        private const val NOTIFICATION_ID = 1001

        private const val SAMPLE_RATE = 48000
        private const val CHANNEL_COUNT = 1
        private const val BYTES_PER_SAMPLE = 2
    }

    private var mediaProjection: MediaProjection? = null
    private var audioRecord: AudioRecord? = null

    @Volatile
    private var isCapturing = false

    private var captureThread: Thread? = null

    override fun onCreate() {
        super.onCreate()

        Log.d(TAG, "AudioCaptureService criado")

        criarCanalNotificacao()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        if (intent?.action == ACTION_STOP) {
            pararCaptura()
            stopSelf()
            return START_NOT_STICKY
        }

        if (intent?.action == ACTION_START) {

            val resultCode =
                intent.getIntExtra(EXTRA_RESULT_CODE, -1)

            val resultData: Intent? =
                if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(
                        EXTRA_RESULT_DATA,
                        Intent::class.java
                    )
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_RESULT_DATA)
                }

            if (resultCode == -1 || resultData == null) {

                Log.e(
                    TAG,
                    "Autorização do MediaProjection não recebida"
                )

                stopSelf()
                return START_NOT_STICKY
            }

            iniciarForeground()

            iniciarCaptura(
                resultCode,
                resultData
            )
        }

        return START_NOT_STICKY
    }

    private fun iniciarForeground() {

        val notification = criarNotificacao()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {

            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )

        } else {

            @Suppress("DEPRECATION")
            startForeground(
                NOTIFICATION_ID,
                notification
            )
        }

        Log.d(TAG, "Foreground Service iniciado")
    }

    private fun iniciarCaptura(
        resultCode: Int,
        resultData: Intent
    ) {

        if (isCapturing) {
            Log.d(TAG, "Captura já está ativa")
            return
        }

        try {

            val projectionManager =
                getSystemService(
                    Context.MEDIA_PROJECTION_SERVICE
                ) as android.media.projection.MediaProjectionManager

            mediaProjection =
                projectionManager.getMediaProjection(
                    resultCode,
                    resultData
                )

            if (mediaProjection == null) {

                Log.e(
                    TAG,
                    "Não foi possível criar MediaProjection"
                )

                stopSelf()
                return
            }

            if (Build.VERSION.SDK_INT >= 29) {

                val captureConfig =
                    AudioPlaybackCaptureConfiguration
                        .Builder(mediaProjection!!)
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
                            AudioFormat.ENCODING_PCM_16BIT
                        )
                        .setSampleRate(
                            SAMPLE_RATE
                        )
                        .setChannelMask(
                            AudioFormat.CHANNEL_IN_MONO
                        )
                        .build()

                val minBuffer =
                    AudioRecord.getMinBufferSize(
                        SAMPLE_RATE,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT
                    )

                val bufferSize =
                    maxOf(
                        minBuffer,
                        SAMPLE_RATE *
                                CHANNEL_COUNT *
                                BYTES_PER_SAMPLE /
                                2
                    )

                audioRecord =
                    AudioRecord.Builder()
                        .setAudioFormat(audioFormat)
                        .setBufferSizeInBytes(bufferSize)
                        .setAudioPlaybackCaptureConfig(
                            captureConfig
                        )
                        .build()

                if (
                    audioRecord?.state !=
                    AudioRecord.STATE_INITIALIZED
                ) {

                    Log.e(
                        TAG,
                        "AudioRecord não foi inicializado"
                    )

                    pararCaptura()
                    stopSelf()
                    return
                }

                mediaProjection?.registerCallback(
                    object :
                        MediaProjection.Callback() {

                        override fun onStop() {

                            Log.d(
                                TAG,
                                "MediaProjection foi encerrado"
                            )

                            pararCaptura()

                            stopSelf()
                        }
                    },
                    null
                )

                isCapturing = true

                audioRecord?.startRecording()

                Log.d(
                    TAG,
                    "CAPTURA DE ÁUDIO INICIADA"
                )

                iniciarThreadDeCaptura()

            } else {

                Log.e(
                    TAG,
                    "AudioPlaybackCapture requer Android 10 ou superior"
                )

                stopSelf()
            }

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Erro ao iniciar captura",
                e
            )

            pararCaptura()
            stopSelf()
        }
    }

    private fun iniciarThreadDeCaptura() {

        captureThread = Thread {

            val bufferSize =
                48000 * 2 / 10

            val buffer =
                ByteArray(bufferSize)

            Log.d(
                TAG,
                "Thread de captura iniciada"
            )

            while (isCapturing) {

                try {

                    val bytesRead =
                        audioRecord?.read(
                            buffer,
                            0,
                            buffer.size
                        ) ?: 0

                    if (bytesRead > 0) {

                        Log.d(
                            TAG,
                            "Áudio capturado: $bytesRead bytes"
                        )

                        /*
                         * PRÓXIMA ETAPA:
                         *
                         * Aqui vamos enviar os bytes
                         * para o Render.
                         *
                         * Render:
                         * https://si-u2ul.onrender.com
                         *
                         * Depois o Render encaminhará
                         * o áudio para o sistema de
                         * tradução/dublagem.
                         */

                        processarAudio(
                            buffer,
                            bytesRead
                        )
                    }

                } catch (e: Exception) {

                    Log.e(
                        TAG,
                        "Erro durante captura de áudio",
                        e
                    )

                    break
                }
            }

            Log.d(
                TAG,
                "Thread de captura encerrada"
            )

        }

        captureThread?.start()
    }

    private fun processarAudio(
        audio: ByteArray,
        tamanho: Int
    ) {

        /*
         * Por enquanto apenas recebemos
         * o áudio capturado.
         *
         * NÃO vamos usar microfone.
         *
         * O áudio vem do playback de outro
         * aplicativo, quando esse aplicativo
         * permite captura.
         *
         * No próximo passo vamos transformar
         * esse bloco em envio para o Render.
         */

        // Evita warning de parâmetro ainda não utilizado.
        if (tamanho <= 0) {
            return
        }
    }

    private fun pararCaptura() {

        if (!isCapturing &&
            audioRecord == null &&
            mediaProjection == null
        ) {
            return
        }

        Log.d(
            TAG,
            "Parando captura de áudio"
        )

        isCapturing = false

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
            mediaProjection?.stop()
        } catch (_: Exception) {
        }

        mediaProjection = null

        try {
            captureThread?.interrupt()
        } catch (_: Exception) {
        }

        captureThread = null

        Log.d(
            TAG,
            "Captura encerrada"
        )
    }

    private fun criarCanalNotificacao() {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {

            val channel =
                NotificationChannel(
                    CHANNEL_ID,
                    "SI Tradutor Live",
                    NotificationManager.IMPORTANCE_LOW
                )

            channel.description =
                "Captura de áudio do SI Tradutor Live"

            val manager =
                getSystemService(
                    NotificationManager::class.java
                )

            manager.createNotificationChannel(
                channel
            )
        }
    }

    private fun criarNotificacao(): Notification {

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {

            Notification.Builder(
                this,
                CHANNEL_ID
            )
                .setContentTitle(
                    "SI Tradutor Live"
                )
                .setContentText(
                    "Monitorando o áudio da tela"
                )
                .setSmallIcon(
                    android.R.drawable.ic_btn_speak_now
                )
                .setOngoing(true)
                .build()

        } else {

            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle(
                    "SI Tradutor Live"
                )
                .setContentText(
                    "Monitorando o áudio da tela"
                )
                .setSmallIcon(
                    android.R.drawable.ic_btn_speak_now
                )
                .setOngoing(true)
                .build()
        }
    }

    override fun onDestroy() {

        Log.d(
            TAG,
            "AudioCaptureService destruído"
        )

        pararCaptura()

        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}
