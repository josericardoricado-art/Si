package com.si.tradutor

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
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

        // 100 ms em PCM16 mono 16 kHz
        private const val CHUNK_SIZE =
            3200

        /*
         * COMANDOS DO SERVIÇO
         */
        const val ACTION_START =
            "com.si.tradutor.START_AUDIO"

        const val ACTION_STOP =
            "com.si.tradutor.STOP_AUDIO"

        /*
         * EXTRAS
         */
        const val EXTRA_JOB_ID =
            "jobId"

        const val EXTRA_RESULT_CODE =
            "resultCode"

        const val EXTRA_RESULT_DATA =
            "resultData"
    }

    /*
     * MEDIA PROJECTION
     */
    private var mediaProjection: MediaProjection? =
        null

    /*
     * CAPTURA
     */
    private var audioRecord: AudioRecord? =
        null

    /*
     * SAÍDA DA DUBLAGEM
     */
    private var audioTrack: AudioTrack? =
        null

    /*
     * ESTADO
     */
    @Volatile
    private var running = false

    @Volatile
    private var captureStarted = false

    /*
     * JOB DO RENDER
     */
    private var jobId: String = ""

    /*
     * FILA DE ÁUDIO
     */
    private val audioQueue =
        LinkedBlockingQueue<ByteArray>(40)

    /*
     * DIAGNÓSTICO
     */
    @Volatile
    private var readCount = 0

    @Volatile
    private var lastRead = 0

    @Volatile
    private var capturedBytes = 0L

    @Volatile
    private var diagnosticError: String? =
        null

    /*
     * THREADS
     */
    private var captureThread: Thread? =
        null

    private var sendThread: Thread? =
        null

    private var outputThread: Thread? =
        null

    private var diagnosticThread: Thread? =
        null


    // ============================================================
    // CRIAR SERVIÇO
    // ============================================================

    override fun onCreate() {

        super.onCreate()

        createNotificationChannel()

        Log.d(
            TAG,
            "SI Tradutor Live - serviço criado"
        )
    }


    // ============================================================
    // COMANDO DO SERVIÇO
    // ============================================================

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        Log.d(
            TAG,
            "onStartCommand"
        )

        /*
         * PARAR
         */
        if (
            intent?.action ==
            ACTION_STOP
        ) {

            Log.d(
                TAG,
                "Recebido ACTION_STOP"
            )

            stopCapture(
                sendServerStop = true
            )

            return START_NOT_STICKY
        }

        /*
         * INICIAR
         */
        jobId =
            intent?.getStringExtra(
                EXTRA_JOB_ID
            ) ?: ""

        val resultCode =
            intent?.getIntExtra(
                EXTRA_RESULT_CODE,
                -1
            ) ?: -1

        val resultData: Intent? =
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
            "jobId=$jobId"
        )

        Log.d(
            TAG,
            "resultCode=$resultCode"
        )

        Log.d(
            TAG,
            "resultData=${resultData != null}"
        )

        /*
         * FOREGROUND SERVICE
         */
        startForegroundCompat()

        /*
         * VALIDAR JOB
         */
        if (
            jobId.isBlank()
        ) {

            diagnosticError =
                "jobId vazio"

            Log.e(
                TAG,
                diagnosticError!!
            )

            sendDiagnostic()

            stopSelf()

            return START_NOT_STICKY
        }

        /*
         * VALIDAR MEDIA PROJECTION
         */
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

            stopSelf()

            return START_NOT_STICKY
        }

        /*
         * VALIDAR MICROFONE/PERMISSÃO
         */
        if (
            checkSelfPermission(
                Manifest.permission.RECORD_AUDIO
            ) !=
            PackageManager.PERMISSION_GRANTED
        ) {

            diagnosticError =
                "RECORD_AUDIO não autorizado"

            Log.e(
                TAG,
                diagnosticError!!
            )

            sendDiagnostic()

            stopSelf()

            return START_NOT_STICKY
        }

        /*
         * EVITAR DUPLICAR CAPTURA
         */
        if (running) {

            Log.d(
                TAG,
                "Captura já está rodando"
            )

            return START_STICKY
        }

        /*
         * INICIAR
         */
        startCapture(
            resultCode,
            resultData
        )

        return START_STICKY
    }


    // ============================================================
    // INICIAR CAPTURA
    // ============================================================

    private fun startCapture(
        resultCode: Int,
        resultData: Intent
    ) {

        try {

            diagnosticError = null

            readCount = 0

            lastRead = 0

            capturedBytes = 0L

            audioQueue.clear()

            running = true


            /*
             * MEDIA PROJECTION MANAGER
             */
            val projectionManager =
                getSystemService(
                    Context.MEDIA_PROJECTION_SERVICE
                ) as MediaProjectionManager


            /*
             * PEGAR MEDIA PROJECTION
             */
            mediaProjection =
                projectionManager.getMediaProjection(
                    resultCode,
                    resultData
                )


            if (
                mediaProjection == null
            ) {

                failCapture(
                    "MediaProjection retornou null"
                )

                return
            }


            /*
             * CALLBACK DO ANDROID
             */
            if (
                Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.Q
            ) {

                mediaProjection?.registerCallback(
                    object : MediaProjection.Callback() {

                        override fun onStop() {

                            Log.w(
                                TAG,
                                "MediaProjection encerrada pelo Android"
                            )

                            diagnosticError =
                                "MediaProjection encerrada pelo Android"

                            stopCapture(
                                sendServerStop = false
                            )
                        }
                    },
                    null
                )
            }


            /*
             * CONFIGURAÇÃO DE CAPTURA
             */
            val playbackConfig =
                if (
                    Build.VERSION.SDK_INT >=
                    Build.VERSION_CODES.Q
                ) {

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

                } else {

                    failCapture(
                        "Android abaixo da versão 10 não suporta captura interna"
                    )

                    return
                }


            /*
             * FORMATO
             */
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


            /*
             * BUFFER MÍNIMO
             */
            val minBuffer =
                AudioRecord.getMinBufferSize(
                    SAMPLE_RATE,
                    CHANNEL_CONFIG,
                    AUDIO_FORMAT
                )


            Log.d(
                TAG,
                "AudioRecord minBuffer=$minBuffer"
            )


            if (
                minBuffer <= 0
            ) {

                failCapture(
                    "getMinBufferSize inválido: $minBuffer"
                )

                return
            }


            /*
             * BUFFER REAL
             */
            val bufferSize =
                maxOf(
                    minBuffer * 2,
                    CHUNK_SIZE * 4
                )


            /*
             * AUDIO RECORD
             */
            audioRecord =
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


            /*
             * VALIDAR
             */
            if (
                audioRecord?.state !=
                AudioRecord.STATE_INITIALIZED
            ) {

                failCapture(
                    "AudioRecord não foi inicializado"
                )

                return
            }


            Log.d(
                TAG,
                "AudioRecord inicializado"
            )


            /*
             * COMEÇAR GRAVAÇÃO
             */
            audioRecord?.startRecording()


            /*
             * VALIDAR ESTADO
             */
            if (
                audioRecord?.recordingState !=
                AudioRecord.RECORDSTATE_RECORDING
            ) {

                failCapture(
                    "AudioRecord não entrou em RECORDING"
                )

                return
            }


            captureStarted = true


            Log.d(
                TAG,
                "================================"
            )

            Log.d(
                TAG,
                "CAPTURA INTERNA DE ÁUDIO INICIADA"
            )

            Log.d(
                TAG,
                "Aguardando áudio do YouTube..."
            )

            Log.d(
                TAG,
                "================================"
            )


            /*
             * PRIMEIRO DIAGNÓSTICO
             */
            sendDiagnostic()


            /*
             * THREAD DE CAPTURA
             */
            startCaptureThread()


            /*
             * THREAD DE ENVIO
             */
            startSendThread()


            /*
             * THREAD DE SAÍDA
             */
            startOutputThread()


            /*
             * THREAD DE DIAGNÓSTICO
             */
            startDiagnosticThread()

        } catch (
            e: Exception
        ) {

            failCapture(
                "${e.javaClass.simpleName}: ${e.message ?: "erro desconhecido"}"
            )
        }
    }


    // ============================================================
    // THREAD DE CAPTURA
    // ============================================================

    private fun startCaptureThread() {

        captureThread =
            thread(
                name = "SI-AudioCapture"
            ) {

                val buffer =
                    ByteArray(
                        CHUNK_SIZE
                    )


                while (
                    running
                ) {

                    try {

                        val record =
                            audioRecord


                        if (
                            record == null
                        ) {

                            diagnosticError =
                                "AudioRecord ficou null"

                            break
                        }


                        /*
                         * LER ÁUDIO
                         */
                        val read =
                            record.read(
                                buffer,
                                0,
                                buffer.size,
                                AudioRecord.READ_BLOCKING
                            )


                        readCount++

                        lastRead =
                            read


                        /*
                         * ÁUDIO RECEBIDO
                         */
                        if (
                            read > 0
                        ) {

                            val chunk =
                                buffer.copyOf(
                                    read
                                )


                            capturedBytes +=
                                read.toLong()


                            /*
                             * COLOCAR NA FILA
                             */
                            if (
                                !audioQueue.offer(
                                    chunk
                                )
                            ) {

                                audioQueue.poll()

                                audioQueue.offer(
                                    chunk
                                )
                            }


                            if (
                                readCount == 1 ||
                                readCount % 20 == 0
                            ) {

                                Log.d(
                                    TAG,
                                    "ÁUDIO CAPTURADO read=$read bytes=$capturedBytes"
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


                        } else if (
                            read == 0
                        ) {

                            diagnosticError =
                                "AudioRecord retornou 0 bytes"

                            Log.w(
                                TAG,
                                diagnosticError!!
                            )
                        }

                    } catch (
                        e: Exception
                    ) {

                        diagnosticError =
                            "${e.javaClass.simpleName}: ${e.message ?: ""}"

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


    // ============================================================
    // THREAD DE ENVIO
    // ============================================================

    private fun startSendThread() {

        sendThread =
            thread(
                name = "SI-AudioSend"
            ) {

                while (
                    running
                ) {

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


    // ============================================================
    // ENVIAR ÁUDIO PARA RENDER
    // ============================================================

    private fun sendAudioChunk(
        base64: String
    ) {

        if (
            jobId.isBlank()
        ) {
            return
        }


        var connection:
            HttpURLConnection? = null


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
                  "jobId":"${escapeJson(jobId)}",
                  "audio":"${escapeJson(base64)}"
                }
                """.trimIndent()


            val output:
                OutputStream =
                connection.outputStream


            output.use { stream: OutputStream ->

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
                    "Render respondeu HTTP $code"
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

        } catch (
            e: Exception
        ) {

            Log.e(
                TAG,
                "Falha ao enviar chunk",
                e
            )

        } finally {

            connection?.disconnect()
        }
    }


    // ============================================================
    // DIAGNÓSTICO
    // ============================================================

    private fun startDiagnosticThread() {

        diagnosticThread =
            thread(
                name = "SI-AudioDiagnostic"
            ) {

                while (
                    running
                ) {

                    try {

                        Thread.sleep(
                            3000
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

            var connection:
                HttpURLConnection? = null


            try {

                val url =
                    URL(
                        "$BACKEND_URL/api/audio/diagnostic"
                    )


                connection =
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
                      "jobId":"${escapeJson(jobId)}",
                      "recording":$recording,
                      "captureStarted":$captureStarted,
                      "readCount":$readCount,
                      "lastRead":$lastRead,
                      "capturedBytes":$capturedBytes,
                      "queueSize":${audioQueue.size},
                      "error":${jsonStringOrNull(diagnosticError)}
                    }
                    """.trimIndent()


                val output:
                    OutputStream =
                    connection.outputStream


                output.use { stream: OutputStream ->

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
                    "Diagnóstico HTTP $code reads=$readCount lastRead=$lastRead bytes=$capturedBytes"
                )

            } catch (
                e: Exception
            ) {

                Log.e(
                    TAG,
                    "Falha no diagnóstico",
                    e
                )

            } finally {

                connection?.disconnect()
            }
        }
    }


    // ============================================================
    // RECEBER ÁUDIO DUBLADO
    // ============================================================

    private fun startOutputThread() {

        outputThread =
            thread(
                name = "SI-AudioOutput"
            ) {

                while (
                    running
                ) {

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

        if (
            jobId.isBlank()
        ) {
            return
        }


        var connection:
            HttpURLConnection? = null


        try {

            val url =
                URL(
                    "$BACKEND_URL/api/audio/output/$jobId"
                )


            connection =
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

                return
            }


            val input =
                InputStreamReader(
                    connection.inputStream
                )


            val response =
                BufferedReader(
                    input
                ).use { reader: BufferedReader ->

                    reader.readText()
                }


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

        } finally {

            connection?.disconnect()
        }
    }


    // ============================================================
    // REPRODUZIR DUBLAGEM
    // ============================================================

    private fun playAudio(
        pcm: ByteArray
    ) {

        try {

            if (
                audioTrack == null
            ) {

                val minBuffer =
                    AudioTrack.getMinBufferSize(
                        24000,
                        AudioFormat.CHANNEL_OUT_MONO,
                        AudioFormat.ENCODING_PCM_16BIT
                    )


                if (
                    minBuffer <= 0
                ) {

                    Log.e(
                        TAG,
                        "AudioTrack minBuffer inválido"
                    )

                    return
                }


                audioTrack =
                    AudioTrack(
                        AudioAttributes.Builder()
                            .setUsage(
                                AudioAttributes.USAGE_MEDIA
                            )
                            .setContentType(
                                AudioAttributes.CONTENT_TYPE_SPEECH
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

                        maxOf(
                            minBuffer * 4,
                            pcm.size
                        ),

                        AudioTrack.MODE_STREAM,

                        AudioManager.AUDIO_SESSION_ID_GENERATE
                    )


                audioTrack?.play()


                Log.d(
                    TAG,
                    "AudioTrack 24 kHz iniciado"
                )
            }


            if (
                audioTrack?.playState ==
                AudioTrack.PLAYSTATE_PLAYING
            ) {

                val written =
                    audioTrack?.write(
                        pcm,
                        0,
                        pcm.size,
                        AudioTrack.WRITE_BLOCKING
                    ) ?: 0


                Log.d(
                    TAG,
                    "Dublagem reproduzida: $written bytes"
                )
            }

        } catch (
            e: Exception
        ) {

            Log.e(
                TAG,
                "Erro ao reproduzir dublagem",
                e
            )
        }
    }


    // ============================================================
    // PARAR
    // ============================================================

    private fun stopCapture(
        sendServerStop: Boolean
    ) {

        running = false


        captureThread?.interrupt()

        sendThread?.interrupt()

        outputThread?.interrupt()

        diagnosticThread?.interrupt()


        captureThread = null

        sendThread = null

        outputThread = null

        diagnosticThread = null


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
            audioTrack?.stop()
        } catch (_: Exception) {
        }


        try {
            audioTrack?.release()
        } catch (_: Exception) {
        }


        audioTrack = null


        if (
            sendServerStop &&
            jobId.isNotBlank()
        ) {

            sendStopToServer()
        }


        try {
            mediaProjection?.stop()
        } catch (_: Exception) {
        }


        mediaProjection = null

        captureStarted = false

        audioQueue.clear()


        try {
            stopForeground(
                STOP_FOREGROUND_REMOVE
            )
        } catch (_: Exception) {
        }


        stopSelf()
    }


    // ============================================================
    // AVISAR RENDER QUE PAROU
    // ============================================================

    private fun sendStopToServer() {

        thread(
            name = "SI-AudioStop"
        ) {

            var connection:
                HttpURLConnection? = null


            try {

                val url =
                    URL(
                        "$BACKEND_URL/api/audio/stop"
                    )


                connection =
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


                val json =
                    """
                    {
                      "jobId":"${escapeJson(jobId)}"
                    }
                    """.trimIndent()


                val output:
                    OutputStream =
                    connection.outputStream


                output.use { stream: OutputStream ->

                    stream.write(
                        json.toByteArray(
                            Charsets.UTF_8
                        )
                    )
                }


                Log.d(
                    TAG,
                    "Stop enviado HTTP ${connection.responseCode}"
                )

            } catch (
                e: Exception
            ) {

                Log.e(
                    TAG,
                    "Erro enviando stop",
                    e
                )

            } finally {

                connection?.disconnect()
            }
        }
    }


    // ============================================================
    // ERRO
    // ============================================================

    private fun failCapture(
        message: String
    ) {

        diagnosticError =
            message

        Log.e(
            TAG,
            message
        )

        running = false

        sendDiagnostic()


        try {
            audioRecord?.stop()
        } catch (_: Exception) {
        }


        try {
            audioRecord?.release()
        } catch (_: Exception) {
        }


        audioRecord = null
    }


    // ============================================================
    // FOREGROUND SERVICE
    // ============================================================

    private fun startForegroundCompat() {

        val notification =
            createNotification()


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


    // ============================================================
    // NOTIFICAÇÃO
    // ============================================================

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


    private fun createNotification():
        Notification {

        return if (
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
                    "Capturando áudio da tela..."
                )
                .setSmallIcon(
                    android.R.drawable.ic_media_play
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
                    "Capturando áudio da tela..."
                )
                .setSmallIcon(
                    android.R.drawable.ic_media_play
                )
                .setOngoing(true)
                .build()
        }
    }


    // ============================================================
    // HELPERS
    // ============================================================

    private fun escapeJson(
        value: String
    ): String {

        return value
            .replace(
                "\\",
                "\\\\"
            )
            .replace(
                "\"",
                "\\\""
            )
            .replace(
                "\n",
                "\\n"
            )
            .replace(
                "\r",
                "\\r"
            )
    }


    private fun extractJsonString(
        json: String,
        key: String
    ): String? {

        return try {

            val pattern =
                "\"$key\"\\s*:\\s*\"([^\"]*)\""
                    .toRegex()


            pattern
                .find(json)
                ?.groupValues
                ?.getOrNull(1)

        } catch (_: Exception) {

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

            "\"${escapeJson(value)}\""
        }
    }


    // ============================================================
    // BIND
    // ============================================================

    override fun onBind(
        intent: Intent?
    ): IBinder? {

        return null
    }


    // ============================================================
    // DESTROY
    // ============================================================

    override fun onDestroy() {

        Log.d(
            TAG,
            "AudioCaptureService destruído"
        )


        if (
            running
        ) {

            stopCapture(
                sendServerStop = false
            )
        }


        super.onDestroy()
    }
}
