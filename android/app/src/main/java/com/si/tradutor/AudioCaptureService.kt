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
import androidx.core.content.IntentCompat
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

        /*
         * ÁUDIO DE ENTRADA
         *
         * Gemini recebe:
         * PCM16
         * mono
         * 16 kHz
         */
        private const val SAMPLE_RATE = 16000

        private const val CHANNEL_CONFIG =
            AudioFormat.CHANNEL_IN_MONO

        private const val AUDIO_FORMAT =
            AudioFormat.ENCODING_PCM_16BIT

        /*
         * 100 ms:
         *
         * 16000 samples/sec
         * x 0,1 sec
         * x 2 bytes
         * = 3200 bytes
         */
        private const val CHUNK_SIZE = 3200

        /*
         * COMANDOS
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
    private var mediaProjection: MediaProjection? = null

    /*
     * CAPTURA DO ÁUDIO INTERNO
     */
    private var audioRecord: AudioRecord? = null

    /*
     * REPRODUÇÃO DA DUBLAGEM
     */
    private var audioTrack: AudioTrack? = null

    /*
     * ESTADO
     */
    @Volatile
    private var running = false

    @Volatile
    private var captureStarted = false

    /*
     * JOB NO RENDER
     */
    @Volatile
    private var jobId = ""

    /*
     * FILA
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
    private var diagnosticError: String? = null

    /*
     * THREADS
     */
    private var captureThread: Thread? = null
    private var sendThread: Thread? = null
    private var outputThread: Thread? = null
    private var diagnosticThread: Thread? = null

    /*
     * CALLBACK DO MEDIAPROJECTION
     */
    private var projectionCallback: MediaProjection.Callback? = null


    // ============================================================
    // CREATE
    // ============================================================

    override fun onCreate() {
        super.onCreate()

        createNotificationChannel()

        Log.d(
            TAG,
            "======================================"
        )

        Log.d(
            TAG,
            "SI Tradutor Live - serviço criado"
        )

        Log.d(
            TAG,
            "Android SDK=${Build.VERSION.SDK_INT}"
        )

        Log.d(
            TAG,
            "======================================"
        )
    }


    // ============================================================
    // START COMMAND
    // ============================================================

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        Log.d(
            TAG,
            "onStartCommand action=${intent?.action}"
        )

        /*
         * PARAR
         */
        if (intent?.action == ACTION_STOP) {

            Log.d(
                TAG,
                "ACTION_STOP recebido"
            )

            stopCapture(
                sendServerStop = true
            )

            return START_NOT_STICKY
        }

        /*
         * JOB ID
         */
        jobId =
            intent?.getStringExtra(
                EXTRA_JOB_ID
            ) ?: ""

        /*
         * RESULT CODE
         */
        val resultCode =
            intent?.getIntExtra(
                EXTRA_RESULT_CODE,
                -1
            ) ?: -1

        /*
         * ========================================================
         * CORREÇÃO PRINCIPAL
         * ========================================================
         *
         * IntentCompat é usado para recuperar corretamente
         * o Parcelable em Android 13+.
         */
        val resultData: Intent? =
            if (intent != null) {

                try {

                    IntentCompat.getParcelableExtra(
                        intent,
                        EXTRA_RESULT_DATA,
                        Intent::class.java
                    )

                } catch (e: Exception) {

                    Log.e(
                        TAG,
                        "Erro lendo resultData com IntentCompat",
                        e
                    )

                    /*
                     * FALLBACK
                     */
                    try {

                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(
                            EXTRA_RESULT_DATA
                        )

                    } catch (e2: Exception) {

                        Log.e(
                            TAG,
                            "Fallback do resultData também falhou",
                            e2
                        )

                        null
                    }
                }

            } else {
                null
            }

        /*
         * LOGS IMPORTANTES
         */
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
            "resultDataExiste=${resultData != null}"
        )

        Log.d(
            TAG,
            "intentExiste=${intent != null}"
        )

        if (intent != null) {

            Log.d(
                TAG,
                "extras=${intent.extras?.keySet()?.joinToString(",")}"
            )
        }

        /*
         * FOREGROUND
         */
        try {

            startForegroundCompat()

        } catch (e: Exception) {

            diagnosticError =
                "Erro foreground: ${e.javaClass.simpleName}: ${e.message}"

            Log.e(
                TAG,
                diagnosticError!!,
                e
            )

            sendDiagnostic()

            stopSelf()

            return START_NOT_STICKY
        }

        /*
         * VALIDAR JOB
         */
        if (jobId.isBlank()) {

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
         * VALIDAR RESULTADO DO MEDIAPROJECTION
         */
        if (resultCode != android.app.Activity.RESULT_OK) {

            diagnosticError =
                "MediaProjection inválido: resultCode=$resultCode"

            Log.e(
                TAG,
                diagnosticError!!
            )

            sendDiagnostic()

            stopSelf()

            return START_NOT_STICKY
        }

        /*
         * VALIDAR TOKEN
         */
        if (resultData == null) {

            diagnosticError =
                "MediaProjection inválido: resultData=null"

            Log.e(
                TAG,
                diagnosticError!!
            )

            sendDiagnostic()

            stopSelf()

            return START_NOT_STICKY
        }

        /*
         * VALIDAR RECORD_AUDIO
         */
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

            stopSelf()

            return START_NOT_STICKY
        }

        /*
         * NÃO DUPLICAR
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

            captureStarted = false

            audioQueue.clear()

            running = true

            Log.d(
                TAG,
                "Iniciando MediaProjection..."
            )

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
            val projection =
                projectionManager.getMediaProjection(
                    resultCode,
                    resultData
                )

            if (projection == null) {

                failCapture(
                    "MediaProjection retornou null"
                )

                return
            }

            mediaProjection = projection

            Log.d(
                TAG,
                "MediaProjection obtida com sucesso"
            )

            /*
             * CALLBACK
             */
            if (
                Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.Q
            ) {

                projectionCallback =
                    object : MediaProjection.Callback() {

                        override fun onStop() {

                            Log.w(
                                TAG,
                                "MediaProjection encerrada pelo Android"
                            )

                            diagnosticError =
                                "MediaProjection encerrada pelo Android"

                            if (running) {

                                stopCapture(
                                    sendServerStop = false
                                )
                            }
                        }
                    }

                mediaProjection?.registerCallback(
                    projectionCallback!!,
                    null
                )
            }

            /*
             * ====================================================
             * CONFIGURAÇÃO DE CAPTURA
             * ====================================================
             */
            if (
                Build.VERSION.SDK_INT <
                Build.VERSION_CODES.Q
            ) {

                failCapture(
                    "Android abaixo do 10 não suporta captura interna"
                )

                return
            }

            val playbackConfig =
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

            Log.d(
                TAG,
                "PlaybackCaptureConfiguration criada"
            )

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

            if (minBuffer <= 0) {

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

            Log.d(
                TAG,
                "AudioRecord bufferSize=$bufferSize"
            )

            /*
             * ====================================================
             * AUDIO RECORD
             * ====================================================
             */
            val record =
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

            audioRecord = record

            /*
             * VALIDAR
             */
            if (
                record.state !=
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
             * COMEÇAR
             */
            record.startRecording()

            /*
             * VALIDAR
             */
            if (
                record.recordingState !=
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
                "======================================"
            )

            Log.d(
                TAG,
                "CAPTURA INTERNA INICIADA"
            )

            Log.d(
                TAG,
                "PCM16 / MONO / 16000 Hz"
            )

            Log.d(
                TAG,
                "Aguardando áudio do aplicativo..."
            )

            Log.d(
                TAG,
                "======================================"
            )

            /*
             * DIAGNÓSTICO INICIAL
             */
            sendDiagnostic()

            /*
             * THREAD CAPTURA
             */
            startCaptureThread()

            /*
             * THREAD ENVIO
             */
            startSendThread()

            /*
             * THREAD SAÍDA
             */
            startOutputThread()

            /*
             * THREAD DIAGNÓSTICO
             */
            startDiagnosticThread()

        } catch (e: SecurityException) {

            failCapture(
                "SecurityException: ${e.message ?: "permissão negada"}"
            )

        } catch (e: Exception) {

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

                while (running) {

                    try {

                        val record =
                            audioRecord

                        if (record == null) {

                            diagnosticError =
                                "AudioRecord ficou null"

                            break
                        }

                        val read =
                            record.read(
                                buffer,
                                0,
                                buffer.size,
                                AudioRecord.READ_BLOCKING
                            )

                        readCount++

                        lastRead = read

                        /*
                         * ÁUDIO RECEBIDO
                         */
                        if (read > 0) {

                            val chunk =
                                buffer.copyOf(read)

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

                                /*
                                 * Se estiver cheia,
                                 * remove o mais antigo.
                                 */
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
                                    "Áudio capturado: read=$read bytes=$capturedBytes queue=${audioQueue.size}"
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

                        } else if (read == 0) {

                            diagnosticError =
                                "AudioRecord retornou 0 bytes"

                            Log.w(
                                TAG,
                                diagnosticError!!
                            )
                        }

                    } catch (
                        e: InterruptedException
                    ) {

                        break

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


    // ============================================================
    // ENVIAR CHUNK PARA RENDER
    // ============================================================

    private fun sendAudioChunk(
        base64: String
    ) {

        if (jobId.isBlank()) {
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

            connection.outputStream.use { output ->

                output.write(
                    json.toByteArray(
                        Charsets.UTF_8
                    )
                )

                output.flush()
            }

            val code =
                connection.responseCode

            if (code !in 200..299) {

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

        } catch (e: Exception) {

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
    // THREAD DE DIAGNÓSTICO
    // ============================================================

    private fun startDiagnosticThread() {

        diagnosticThread =
            thread(
                name = "SI-AudioDiagnostic"
            ) {

                while (running) {

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


    // ============================================================
    // DIAGNÓSTICO
    // ============================================================

    private fun sendDiagnostic() {

        if (jobId.isBlank()) {
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

                connection.outputStream.use { output ->

                    output.write(
                        json.toByteArray(
                            Charsets.UTF_8
                        )
                    )

                    output.flush()
                }

                val code =
                    connection.responseCode

                Log.d(
                    TAG,
                    "Diagnóstico HTTP $code reads=$readCount lastRead=$lastRead bytes=$capturedBytes"
                )

            } catch (e: Exception) {

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
    // THREAD DE SAÍDA
    // ============================================================

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


    // ============================================================
    // RECEBER ÁUDIO TRADUZIDO
    // ============================================================

    private fun getTranslatedAudio() {

        if (jobId.isBlank()) {
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

            if (code != 200) {
                return
            }

            val response =
                BufferedReader(
                    InputStreamReader(
                        connection.inputStream
                    )
                ).use { reader ->
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
                try {

                    Base64.decode(
                        audio,
                        Base64.DEFAULT
                    )

                } catch (e: Exception) {

                    Log.e(
                        TAG,
                        "Base64 inválido recebido do Render",
                        e
                    )

                    return
                }

            if (pcm.isNotEmpty()) {

                playAudio(
                    pcm
                )
            }

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Erro recebendo áudio traduzido",
                e
            )

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

            if (audioTrack == null) {

                /*
                 * GEMINI LIVE TRANSLATION
                 *
                 * saída:
                 * PCM16
                 * mono
                 * 24 kHz
                 */
                val minBuffer =
                    AudioTrack.getMinBufferSize(
                        24000,
                        AudioFormat.CHANNEL_OUT_MONO,
                        AudioFormat.ENCODING_PCM_16BIT
                    )

                if (minBuffer <= 0) {

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

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Erro ao reproduzir dublagem",
                e
            )
        }
    }


    // ============================================================
    // PARAR CAPTURA
    // ============================================================

    private fun stopCapture(
        sendServerStop: Boolean
    ) {

        if (!running && !captureStarted) {

            /*
             * Mesmo parado, tenta limpar recursos.
             */
            releaseAudioResources()

            return
        }

        running = false

        /*
         * INTERROMPER THREADS
         */
        captureThread?.interrupt()
        sendThread?.interrupt()
        outputThread?.interrupt()
        diagnosticThread?.interrupt()

        captureThread = null
        sendThread = null
        outputThread = null
        diagnosticThread = null

        /*
         * AUDIO RECORD
         */
        try {

            audioRecord?.stop()

        } catch (_: Exception) {
        }

        try {

            audioRecord?.release()

        } catch (_: Exception) {
        }

        audioRecord = null

        /*
         * AUDIO TRACK
         */
        try {

            audioTrack?.stop()

        } catch (_: Exception) {
        }

        try {

            audioTrack?.release()

        } catch (_: Exception) {
        }

        audioTrack = null

        /*
         * AVISAR RENDER
         */
        if (
            sendServerStop &&
            jobId.isNotBlank()
        ) {

            sendStopToServer()
        }

        /*
         * MEDIA PROJECTION
         */
        try {

            if (
                Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.Q
            ) {

                try {

                    projectionCallback?.let {
                        mediaProjection?.unregisterCallback(
                            it
                        )
                    }

                } catch (_: Exception) {
                }
            }

        } catch (_: Exception) {
        }

        projectionCallback = null

        /*
         * PARAR PROJECTION
         */
        try {

            mediaProjection?.stop()

        } catch (_: Exception) {
        }

        mediaProjection = null

        captureStarted = false

        /*
         * LIMPAR FILA
         */
        audioQueue.clear()

        /*
         * FOREGROUND
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

        } catch (_: Exception) {
        }

        Log.d(
            TAG,
            "Captura encerrada"
        )

        stopSelf()
    }


    // ============================================================
    // LIBERAR RECURSOS
    // ============================================================

    private fun releaseAudioResources() {

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

        try {

            projectionCallback?.let {
                mediaProjection?.unregisterCallback(
                    it
                )
            }

        } catch (_: Exception) {
        }

        projectionCallback = null

        try {

            mediaProjection?.stop()

        } catch (_: Exception) {
        }

        mediaProjection = null

        audioQueue.clear()

        captureStarted = false
    }


    // ============================================================
    // AVISAR RENDER QUE PAROU
    // ============================================================

    private fun sendStopToServer() {

        val currentJob =
            jobId

        if (currentJob.isBlank()) {
            return
        }

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
                      "jobId":"${escapeJson(currentJob)}"
                    }
                    """.trimIndent()

                connection.outputStream.use { output ->

                    output.write(
                        json.toByteArray(
                            Charsets.UTF_8
                        )
                    )

                    output.flush()
                }

                Log.d(
                    TAG,
                    "Stop enviado HTTP ${connection.responseCode}"
                )

            } catch (e: Exception) {

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
            "======================================"
        )

        Log.e(
            TAG,
            "FALHA NA CAPTURA"
        )

        Log.e(
            TAG,
            message
        )

        Log.e(
            TAG,
            "======================================"
        )

        running = false

        captureStarted = false

        /*
         * Diagnóstico.
         */
        sendDiagnostic()

        /*
         * Liberar AudioRecord.
         */
        try {

            audioRecord?.stop()

        } catch (_: Exception) {
        }

        try {

            audioRecord?.release()

        } catch (_: Exception) {
        }

        audioRecord = null

        /*
         * Liberar AudioTrack.
         */
        try {

            audioTrack?.stop()

        } catch (_: Exception) {
        }

        try {

            audioTrack?.release()

        } catch (_: Exception) {
        }

        audioTrack = null

        /*
         * Projection.
         */
        try {

            projectionCallback?.let {
                mediaProjection?.unregisterCallback(
                    it
                )
            }

        } catch (_: Exception) {
        }

        projectionCallback = null

        try {

            mediaProjection?.stop()

        } catch (_: Exception) {
        }

        mediaProjection = null

        audioQueue.clear()

        stopSelf()
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
    // NOTIFICATION CHANNEL
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


    // ============================================================
    // NOTIFICAÇÃO
    // ============================================================

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
    // ESCAPAR JSON
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


    // ============================================================
    // EXTRAIR STRING DO JSON
    // ============================================================

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


    // ============================================================
    // JSON NULL
    // ============================================================

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
            running ||
            captureStarted ||
            audioRecord != null ||
            mediaProjection != null
        ) {

            stopCapture(
                sendServerStop = false
            )
        } else {

            releaseAudioResources()
        }

        super.onDestroy()
    }
}
