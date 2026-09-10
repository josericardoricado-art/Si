package com.si.tradutor

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.Base64
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.LinkedBlockingQueue
import kotlin.concurrent.thread


class AudioCaptureService : Service() {

    companion object {

        private const val TAG = "SI_AUDIO"

        private const val BACKEND_URL =
            "https://si-u2ul.onrender.com"

        private const val CHANNEL_ID =
            "si_audio_capture"

        private const val NOTIFICATION_ID =
            1001

        private const val SAMPLE_RATE =
            16000

        private const val CHANNEL_CONFIG =
            AudioFormat.CHANNEL_IN_MONO

        private const val AUDIO_FORMAT =
            AudioFormat.ENCODING_PCM_16BIT

        private const val CHUNK_SIZE =
            3200

        const val ACTION_STOP =
            "com.si.tradutor.STOP_AUDIO"

        const val EXTRA_JOB_ID =
            "jobId"

        const val EXTRA_RESULT_CODE =
            "resultCode"

        const val EXTRA_RESULT_DATA =
            "resultData"
    }


    private var mediaProjection: MediaProjection? =
        null

    private var audioRecord: AudioRecord? =
        null

    private var audioTrack: AudioTrack? =
        null


    @Volatile
    private var running = false


    @Volatile
    private var captureStarted = false


    private var jobId: String = ""


    private val audioQueue =
        LinkedBlockingQueue<ByteArray>(30)


    @Volatile
    private var readCount = 0


    @Volatile
    private var lastRead = 0


    @Volatile
    private var capturedBytes = 0L


    @Volatile
    private var diagnosticError: String? =
        null


    private var captureThread: Thread? =
        null

    private var sendThread: Thread? =
        null

    private var outputThread: Thread? =
        null

    private var diagnosticThread: Thread? =
        null


    // ========================================================
    // SERVICE
    // ========================================================

    override fun onCreate() {
        super.onCreate()

        Log.d(TAG, "================================")
        Log.d(TAG, "SI AUDIO SERVICE CRIADO")
        Log.d(TAG, "================================")

        createNotificationChannel()
    }


    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        Log.d(
            TAG,
            "onStartCommand recebido"
        )


        if (
            intent?.action ==
            ACTION_STOP
        ) {

            stopCapture()

            return START_NOT_STICKY
        }


        jobId =
            intent?.getStringExtra(
                EXTRA_JOB_ID
            ) ?: ""


        val resultCode =
            intent?.getIntExtra(
                EXTRA_RESULT_CODE,
                -1
            ) ?: -1


        val resultData =
            if (
                Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.TIRAMISU
            ) {

                intent?.getParcelableExtra(
                    EXTRA_RESULT_DATA,
                    Intent::class.java
                )

            } else {

                @Suppress("DEPRECATION")
                intent?.getParcelableExtra(
                    EXTRA_RESULT_DATA
                )
            }


        Log.d(
            TAG,
            "jobId=\$jobId"
        )

        Log.d(
            TAG,
            "resultCode=\$resultCode"
        )

        Log.d(
            TAG,
            "resultData=\${resultData != null}"
        )


        startForeground(
            NOTIFICATION_ID,
            createNotification()
        )


        if (
            resultCode == -1 ||
            resultData == null
        ) {

            diagnosticError =
                "MediaProjection inválido"

            Log.e(
                TAG,
                diagnosticError!!
            )

            sendDiagnostic()

            return START_NOT_STICKY
        }


        if (
            checkSelfPermission(
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {

            diagnosticError =
                "RECORD_AUDIO não autorizado"

            Log.e(
                TAG,
                diagnosticError!!
            )

            sendDiagnostic()

            return START_NOT_STICKY
        }


        if (running) {

            Log.d(
                TAG,
                "Captura já está rodando"
            )

            return START_STICKY
        }


        startCapture(
            resultCode,
            resultData
        )


        return START_STICKY
    }


    // ========================================================
    // INICIAR CAPTURA
    // ========================================================

    private fun startCapture(
        resultCode: Int,
        resultData: Intent
    ) {

        try {

            diagnosticError = null

            running = true


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

                diagnosticError =
                    "MediaProjection retornou null"

                Log.e(
                    TAG,
                    diagnosticError!!
                )

                running = false

                sendDiagnostic()

                return
            }


            Log.d(
                TAG,
                "MediaProjection criada"
            )


            if (
                Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.Q
            ) {

                mediaProjection?.registerCallback(
                    object :
                        MediaProjection.Callback() {

                        override fun onStop() {

                            Log.d(
                                TAG,
                                "MediaProjection foi parada"
                            )

                            diagnosticError =
                                "MediaProjection parada pelo Android"

                            stopCapture()
                        }
                    },
                    null
                )
            }


            // =================================================
            // CONFIGURAÇÃO DE CAPTURA DO ÁUDIO INTERNO
            // =================================================

            val playbackConfig =
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


            val audioFormat =
                AudioFormat.Builder()
                    .setEncoding(
                        AUDIO_FORMAT
                    )
                    .setSampleRate(
                        SAMPLE_RATE
                    )
                    .setChannelMask(
                        CHANNEL_CONFIG
                    )
                    .build()


            val minBuffer =
                AudioRecord.getMinBufferSize(
                    SAMPLE_RATE,
                    CHANNEL_CONFIG,
                    AUDIO_FORMAT
                )


            Log.d(
                TAG,
                "minBuffer=\$minBuffer"
            )


            if (
                minBuffer <= 0
            ) {

                diagnosticError =
                    "AudioRecord.getMinBufferSize inválido: \$minBuffer"

                Log.e(
                    TAG,
                    diagnosticError!!
                )

                running = false

                sendDiagnostic()

                return
            }


            val bufferSize =
                maxOf(
                    minBuffer * 2,
                    CHUNK_SIZE * 4
                )


            Log.d(
                TAG,
                "bufferSize=\$bufferSize"
            )


            // =================================================
            // AUDIO RECORD
            // =================================================

            audioRecord =
                if (
                    Build.VERSION.SDK_INT >=
                    Build.VERSION_CODES.Q
                ) {

                    AudioRecord.Builder()
                        .setAudioFormat(
                            audioFormat
                        )
                        .setBufferSizeInBytes(
                            bufferSize
                        )
                        .setAudioPlaybackCaptureConfig(
                            playbackConfig
                        )
                        .build()

                } else {

                    diagnosticError =
                        "Android abaixo da versão 10 não suporta captura interna"

                    Log.e(
                        TAG,
                        diagnosticError!!
                    )

                    running = false

                    sendDiagnostic()

                    return
                }


            Log.d(
                TAG,
                "AudioRecord criado"
            )


            if (
                audioRecord?.state !=
                AudioRecord.STATE_INITIALIZED
            ) {

                diagnosticError =
                    "AudioRecord NÃO foi inicializado"

                Log.e(
                    TAG,
                    diagnosticError!!
                )

                running = false

                sendDiagnostic()

                return
            }


            Log.d(
                TAG,
                "AudioRecord STATE_INITIALIZED = OK"
            )


            // =================================================
            // INICIAR AUDIORECORD
            // =================================================

            audioRecord?.startRecording()


            val recordState =
                audioRecord?.recordingState


            Log.d(
                TAG,
                "recordingState=\$recordState"
            )


            if (
                recordState !=
                AudioRecord.RECORDSTATE_RECORDING
            ) {

                diagnosticError =
                    "AudioRecord não entrou em RECORDING"

                Log.e(
                    TAG,
                    diagnosticError!!
                )

                running = false

                sendDiagnostic()

                return
            }


            captureStarted = true


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
                "Aguardando áudio do YouTube..."
            )

            Log.d(
                TAG,
                "================================"
            )


            sendDiagnostic()


            startCaptureThread()

            startSendThread()

            startOutputThread()

            startDiagnosticThread()

        } catch (e: Exception) {

            diagnosticError =
                e.javaClass.simpleName +
                ": " +
                (e.message ?: "erro desconhecido")


            Log.e(
                TAG,
                "ERRO AO INICIAR CAPTURA",
                e
            )


            running = false

            sendDiagnostic()
        }
    }


    // ========================================================
    // THREAD DE CAPTURA
    // ========================================================

    private fun startCaptureThread() {

        captureThread =
            thread(
                name = "SI-AudioCapture"
            ) {

                val buffer =
                    ByteArray(
                        CHUNK_SIZE
                    )


                while (running) {

                    try {

                        val read =
                            audioRecord?.read(
                                buffer,
                                0,
                                buffer.size,
                                AudioRecord.READ_BLOCKING
                            ) ?: -999


                        readCount++

                        lastRead = read


                        Log.d(
                            TAG,
                            "AudioRecord.read=\$read"
                        )


                        if (read > 0) {

                            val chunk =
                                buffer.copyOf(
                                    read
                                )


                            capturedBytes +=
                                read.toLong()


                            audioQueue.offer(
                                chunk
                            )


                            if (
                                readCount == 1 ||
                                readCount % 20 == 0
                            ) {

                                Log.d(
                                    TAG,
                                    "ÁUDIO CAPTURADO: " +
                                    "read=\$read " +
                                    "total=\$capturedBytes"
                                )
                            }

                        } else if (
                            read ==
                            AudioRecord.ERROR_INVALID_OPERATION
                        ) {

                            diagnosticError =
                                "ERROR_INVALID_OPERATION"

                            Log.e(
                                TAG,
                                diagnosticError!!
                            )

                            break

                        } else if (
                            read ==
                            AudioRecord.ERROR_BAD_VALUE
                        ) {

                            diagnosticError =
                                "ERROR_BAD_VALUE"

                            Log.e(
                                TAG,
                                diagnosticError!!
                            )

                            break

                        } else if (
                            read ==
                            AudioRecord.ERROR_DEAD_OBJECT
                        ) {

                            diagnosticError =
                                "ERROR_DEAD_OBJECT"

                            Log.e(
                                TAG,
                                diagnosticError!!
                            )

                            break
                        }

                    } catch (
                        e: Exception
                    ) {

                        diagnosticError =
                            e.javaClass.simpleName +
                            ": " +
                            (e.message ?: "")


                        Log.e(
                            TAG,
                            "Erro no AudioRecord",
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
    }


    // ========================================================
    // THREAD DE ENVIO
    // ========================================================

    private fun startSendThread() {

        sendThread =
            thread(
                name = "SI-AudioSend"
            ) {

                while (running) {

                    try {

                        val chunk =
                            audioQueue.take()


                        val base64 =
                            Base64.encodeToString(
                                chunk,
                                Base64.NO_WRAP
                            )


                        sendAudioChunk(
                            base64
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
                            "Erro enviando áudio",
                            e
                        )
                    }
                }
            }
    }


    // ========================================================
    // ENVIAR CHUNK
    // ========================================================

    private fun sendAudioChunk(
        base64: String
    ) {

        try {

            val url =
                URL(
                    "\$BACKEND_URL/api/audio/chunk"
                )


            val connection =
                url.openConnection()
                    as HttpURLConnection


            connection.requestMethod =
                "POST"

            connection.doOutput =
                true

            connection.connectTimeout =
                8000

            connection.readTimeout =
                10000

            connection.setRequestProperty(
                "Content-Type",
                "application/json"
            )

            connection.setRequestProperty(
                "Connection",
                "close"
            )


            val json =
                """
                {
                  "jobId":"\${escapeJson(jobId)}",
                  "audio":"\$base64"
                }
                """.trimIndent()


            connection.outputStream.use { stream: OutputStream ->
                stream.write(
                    json.toByteArray(
                        Charsets.UTF_8
                    )
                )
            }


            val code =
                connection.responseCode


            if (
                code !in 200..299
            ) {

                Log.e(
                    TAG,
                    "Servidor respondeu HTTP \$code"
                )

            } else if (
                readCount == 1 ||
                readCount % 20 == 0
            ) {

                Log.d(
                    TAG,
                    "Chunk enviado para Render"
                )
            }


            connection.disconnect()

        } catch (
            e: Exception
        ) {

            Log.e(
                TAG,
                "Falha ao enviar chunk",
                e
            )
        }
    }


    // ========================================================
    // DIAGNÓSTICO
    // ========================================================

    private fun startDiagnosticThread() {

        diagnosticThread =
            thread(
                name = "SI-AudioDiagnostic"
            ) {

                while (running) {

                    try {

                        Thread.sleep(
                            2000
                        )

                    } catch (
                        e: InterruptedException
                    ) {

                        break
                    }


                    sendDiagnostic()
                }
            }
    }


    private fun sendDiagnostic() {

        if (
            jobId.isBlank()
        ) {
            return
        }


        thread(
            name = "SI-DiagnosticRequest"
        ) {

            try {

                val url =
                    URL(
                        "\$BACKEND_URL/api/audio/diagnostic"
                    )


                val connection =
                    url.openConnection()
                        as HttpURLConnection


                connection.requestMethod =
                    "POST"

                connection.doOutput =
                    true

                connection.connectTimeout =
                    5000

                connection.readTimeout =
                    8000

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
                    """
                    {
                      "jobId":"\${escapeJson(jobId)}",
                      "recording":\$recording,
                      "readCount":\$readCount,
                      "lastRead":\$lastRead,
                      "capturedBytes":\$capturedBytes,
                      "error":\${jsonStringOrNull(diagnosticError)}
                    }
                    """.trimIndent()


                connection.outputStream.use { stream: OutputStream ->
                    stream.write(
                        json.toByteArray(
                            Charsets.UTF_8
                        )
                    )
                }


                val code =
                    connection.responseCode


                Log.d(
                    TAG,
                    "Diagnóstico enviado HTTP \$code " +
                    "reads=\$readCount " +
                    "lastRead=\$lastRead " +
                    "bytes=\$capturedBytes"
                )


                connection.disconnect()

            } catch (
                e: Exception
            ) {

                Log.e(
                    TAG,
                    "Falha no diagnóstico",
                    e
                )
            }
        }
    }


    // ========================================================
    // RECEBER ÁUDIO TRADUZIDO
    // ========================================================

    private fun startOutputThread() {

        outputThread =
            thread(
                name = "SI-AudioOutput"
            ) {

                while (running) {

                    try {

                        Thread.sleep(
                            150
                        )

                    } catch (
                        e: InterruptedException
                    ) {

                        break
                    }


                    try {

                        getTranslatedAudio()

                    } catch (
                        e: Exception
                    ) {

                        Log.e(
                            TAG,
                            "Erro no áudio de saída",
                            e
                        )
                    }
                }
            }
    }


    private fun getTranslatedAudio() {

        val url =
            URL(
                "\$BACKEND_URL/api/audio/output/\$jobId"
            )


        val connection =
            url.openConnection()
                as HttpURLConnection


        connection.requestMethod =
            "GET"

        connection.connectTimeout =
            5000

        connection.readTimeout =
            8000

        connection.setRequestProperty(
            "Connection",
            "close"
        )


        val code =
            connection.responseCode


        if (
            code != 200
        ) {

            connection.disconnect()

            return
        }


        val response =
            BufferedReader(
                InputStreamReader(
                    connection.inputStream
                )
            ).use { reader: BufferedReader ->
                reader.readText()
            }


        connection.disconnect()


        val audio =
            extractJsonString(
                response,
                "audio"
            )


        if (
            audio.isNullOrBlank() ||
            audio == "null"
        ) {

            return
        }


        try {

            val pcm =
                Base64.decode(
                    audio,
                    Base64.DEFAULT
                )


            if (
                pcm.isNotEmpty()
            ) {

                playAudio(
                    pcm
                )
            }

        } catch (
            e: Exception
        ) {

            Log.e(
                TAG,
                "Erro decodificando áudio",
                e
            )
        }
    }


    // ========================================================
    // REPRODUZIR DUBLAGEM
    // ========================================================

    private fun playAudio(
        pcm: ByteArray
    ) {

        try {

            if (
                Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.M
            ) {

                if (
                    audioTrack == null
                ) {

                    val minBuffer =
                        AudioTrack.getMinBufferSize(
                            24000,
                            AudioFormat.CHANNEL_OUT_MONO,
                            AudioFormat.ENCODING_PCM_16BIT
                        )


                    audioTrack =
                        AudioTrack(
                            AudioAttributes.Builder()
                                .setUsage(
                                    AudioAttributes.USAGE_MEDIA
                                )
                                .build(),
                            AudioFormat.Builder()
                                .setEncoding(
                                    AudioFormat.ENCODING_PCM_16BIT
                                )
                                .setSampleRate(
                                    24000
                                )
                                .setChannelMask(
                                    AudioFormat.CHANNEL_OUT_MONO
                                )
                                .build(),
                            maxOf(minBuffer, pcm.size),
                            AudioTrack.MODE_STREAM,
                            AudioManager.AUDIO_SESSION_ID_GENERATE
                        )

                    audioTrack?.play()
                }


                audioTrack?.write(
                    pcm,
                    0,
                    pcm.size,
                    AudioTrack.WRITE_NON_BLOCKING
                )
            }

        } catch (
            e: Exception
        ) {

            Log.e(
                TAG,
                "Erro ao reproduzir áudio",
                e
            )
        }
    }


    // ========================================================
    // PARAR CAPTURA
    // ========================================================

    private fun stopCapture() {

        running = false

        try {

            audioRecord?.stop()

        } catch (_: Exception) {}

        audioRecord =
            null


        try {

            audioTrack?.stop()

        } catch (_: Exception) {}


        try {

            audioTrack?.release()

        } catch (_: Exception) {}

        audioTrack =
            null


        try {

            mediaProjection?.stop()

        } catch (_: Exception) {}

        mediaProjection =
            null


        captureStarted =
            false


        sendStopToServer()


        stopForeground(
            STOP_FOREGROUND_REMOVE
        )


        stopSelf()
    }


    // ========================================================
    // AVISAR RENDER QUE PAROU
    // ========================================================

    private fun sendStopToServer() {

        if (
            jobId.isBlank()
        ) {
            return
        }


        thread(
            name = "SI-AudioStop"
        ) {

            try {

                val url =
                    URL(
                        "\$BACKEND_URL/api/audio/stop"
                    )


                val connection =
                    url.openConnection()
                        as HttpURLConnection


                connection.requestMethod =
                    "POST"

                connection.doOutput =
                    true

                connection.connectTimeout =
                    5000

                connection.readTimeout =
                    8000

                connection.setRequestProperty(
                    "Content-Type",
                    "application/json"
                )


                val json =
                    """
                    {
                      "jobId":"\${escapeJson(jobId)}"
                    }
                    """.trimIndent()


                connection.outputStream.use { stream: OutputStream ->
                    stream.write(
                        json.toByteArray(
                            Charsets.UTF_8
                        )
                    )
                }


                connection.responseCode

                connection.disconnect()

            } catch (
                e: Exception
            ) {

                Log.e(
                    TAG,
                    "Erro enviando stop",
                    e
                )
            }
        }
    }


    // ========================================================
    // NOTIFICAÇÃO
    // ========================================================

    private fun createNotificationChannel() {

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
                "Captura de áudio para tradução em tempo real"


            val manager =
                getSystemService(
                    NotificationManager::class.java
                )

            manager?.createNotificationChannel(
                channel
            )
        }
    }


    private fun createNotification(): Notification {

        return Notification.Builder(
            this,
            CHANNEL_ID
        )
            .setContentTitle(
                "SI Tradutor Live"
            )
            .setContentText(
                "Capturando áudio da tela..."
            )
            .setSmallIcon(
                android.R.drawable.ic_media_play
            )
            .build()
    }


    // ========================================================
    // HELPERS
    // ========================================================

    private fun escapeJson(
        value: String
    ): String {

        return value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
    }


    private fun extractJsonString(
        json: String,
        key: String
    ): String? {

        return try {

            val pattern =
                "\"\$key\"\\s*:\\s*\"([^\"]*)\"".toRegex()

            pattern.find(
                json
            )?.groupValues?.get(1)

        } catch (e: Exception) {

            null
        }
    }


    private fun jsonStringOrNull(
        value: String?
    ): String {

        return if (
            value.isNullOrBlank()
        ) {

            "null"

        } else {

            "\"\${escapeJson(value)}\""
        }
    }


    override fun onBind(
        intent: Intent?
    ): IBinder? {

        return null
    }


    override fun onDestroy() {

        Log.d(
            TAG,
            "AudioCaptureService destruído"
        )


        if (running) {
            stopCapture()
        }


        super.onDestroy()
    }
}
