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
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.os.Build
import android.os.IBinder
import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min

class AudioCaptureService : Service() {

    companion object {

        private const val TAG = "SI_AUDIO"

        const val ACTION_START =
            "com.si.tradutor.ACTION_START"

        const val ACTION_STOP =
            "com.si.tradutor.ACTION_STOP"

        const val EXTRA_JOB_ID =
            "job_id"

        const val EXTRA_RESULT_CODE =
            "result_code"

        const val EXTRA_RESULT_DATA =
            "result_data"

        private const val BACKEND_URL =
            "https://si-u2ul.onrender.com"

        // ============================================================
        // CAPTURA
        // ============================================================

        private const val INPUT_SAMPLE_RATE = 16000

        private const val INPUT_CHANNEL_MASK =
            AudioFormat.CHANNEL_IN_MONO

        private const val INPUT_ENCODING =
            AudioFormat.ENCODING_PCM_16BIT

        private const val INPUT_CHUNK_BYTES = 3200

        // Aproximadamente 100 ms por pedaço em PCM 16 kHz mono 16-bit.
        private const val INPUT_QUEUE_CAPACITY = 120

        // ============================================================
        // SAÍDA GEMINI
        // ============================================================

        private const val OUTPUT_SAMPLE_RATE = 24000

        private const val OUTPUT_CHANNEL_MASK =
            AudioFormat.CHANNEL_OUT_MONO

        private const val OUTPUT_ENCODING =
            AudioFormat.ENCODING_PCM_16BIT

        /*
         * Buffer inicial:
         *
         * 24.000 samples/s
         * 2 bytes/sample
         * mono
         *
         * 38.400 bytes ≈ 800 ms
         */
        private const val PREBUFFER_BYTES = 38400

        /*
         * Se a fila cair abaixo disso, ainda continuamos tocando.
         * Só paramos quando realmente não houver mais áudio.
         */
        private const val LOW_WATERMARK_BYTES = 19200

        private const val OUTPUT_QUEUE_CAPACITY = 300

        private const val OUTPUT_HTTP_LIMIT = 30

        /*
         * Ganho da voz traduzida.
         *
         * 1.0 = volume original
         * 1.5 = +50%
         * 2.0 = +100%
         */
        private const val OUTPUT_GAIN = 1.8f

        /*
         * O tom de teste fica DESATIVADO.
         * Já verificamos anteriormente que o alto-falante funciona.
         */
        private const val TEST_TONE_ENABLED = false

        // ============================================================
        // NOTIFICAÇÃO
        // ============================================================

        private const val NOTIFICATION_CHANNEL_ID =
            "si_audio_channel"

        private const val NOTIFICATION_ID = 777
    }

    // ================================================================
    // ESTADO
    // ================================================================

    private val running = AtomicBoolean(false)

    private var jobId: String? = null

    private var targetLanguage: String = "pt-BR"

    private var mediaProjection: MediaProjection? = null

    private var audioRecord: AudioRecord? = null

    private var audioTrack: AudioTrack? = null

    // ================================================================
    // FILA DE ENTRADA
    // ================================================================

    private val inputQueue =
        ArrayBlockingQueue<ByteArray>(INPUT_QUEUE_CAPACITY)

    // ================================================================
    // FILA DE SAÍDA
    // ================================================================

    private val outputQueue =
        ArrayBlockingQueue<ByteArray>(OUTPUT_QUEUE_CAPACITY)

    @Volatile
    private var outputQueueBytes: Long = 0

    @Volatile
    private var lastOutputSeq: Long = 0

    @Volatile
    private var playbackStarted = false

    // ================================================================
    // THREADS
    // ================================================================

    private var captureThread: Thread? = null

    private var sendThread: Thread? = null

    private var outputPollThread: Thread? = null

    private var playbackThread: Thread? = null

    // ================================================================
    // CONTADORES
    // ================================================================

    @Volatile
    private var readCount = 0L

    @Volatile
    private var capturedBytes = 0L

    @Volatile
    private var sentChunks = 0L

    @Volatile
    private var sentBytes = 0L

    @Volatile
    private var sendErrors = 0L

    @Volatile
    private var receivedOutputChunks = 0L

    @Volatile
    private var receivedOutputBytes = 0L

    @Volatile
    private var queuedOutputChunks = 0L

    @Volatile
    private var playedOutputChunks = 0L

    @Volatile
    private var playedOutputBytes = 0L

    @Volatile
    private var playbackWriteErrors = 0L

    @Volatile
    private var lastInputTranscript = ""

    @Volatile
    private var lastOutputTranscript = ""

    @Volatile
    private var lastError: String? = null

    // ================================================================
    // DIAGNÓSTICO
    // ================================================================

    private var diagnosticThread: Thread? = null

    // ================================================================
    // ON CREATE
    // ================================================================

    override fun onCreate() {
        super.onCreate()

        criarCanalNotificacao()

        Log.d(TAG, "AudioCaptureService criado")
    }

    // ================================================================
    // ON START COMMAND
    // ================================================================

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

                val novoJobId =
                    intent.getStringExtra(EXTRA_JOB_ID)

                val resultCode =
                    intent.getIntExtra(
                        EXTRA_RESULT_CODE,
                        Activity.RESULT_CANCELED
                    )

                val resultData =
                    intent.getParcelableExtra<Intent>(
                        EXTRA_RESULT_DATA
                    )

                if (novoJobId.isNullOrBlank()) {

                    Log.e(
                        TAG,
                        "JOB_ID não recebido"
                    )

                    pararTudo()
                    return START_NOT_STICKY
                }

                if (resultData == null) {

                    Log.e(
                        TAG,
                        "RESULT_DATA não recebido"
                    )

                    pararTudo()
                    return START_NOT_STICKY
                }

                iniciarForeground()

                iniciarCaptura(
                    novoJobId,
                    resultCode,
                    resultData
                )
            }

            ACTION_STOP -> {

                Log.d(
                    TAG,
                    "Solicitado ACTION_STOP"
                )

                pararTudo()
            }
        }

        return START_NOT_STICKY
    }

    // ================================================================
    // FOREGROUND
    // ================================================================

    private fun criarCanalNotificacao() {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {

            val manager =
                getSystemService(
                    NotificationManager::class.java
                )

            val channel =
                NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    "SI Tradutor Live",
                    NotificationManager.IMPORTANCE_LOW
                )

            channel.description =
                "Captura e tradução de áudio do SI"

            manager.createNotificationChannel(channel)
        }
    }

    private fun criarNotificacao(): Notification {

        return if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.O
        ) {

            Notification.Builder(
                this,
                NOTIFICATION_CHANNEL_ID
            )
                .setContentTitle(
                    "SI Tradutor Live"
                )
                .setContentText(
                    "Traduzindo áudio em tempo real..."
                )
                .setSmallIcon(
                    android.R.drawable.ic_btn_speak_now
                )
                .setOngoing(true)
                .build()

        } else {

            Notification.Builder(this)
                .setContentTitle(
                    "SI Tradutor Live"
                )
                .setContentText(
                    "Traduzindo áudio em tempo real..."
                )
                .setSmallIcon(
                    android.R.drawable.ic_btn_speak_now
                )
                .setOngoing(true)
                .build()
        }
    }

    private fun iniciarForeground() {

        val notification =
            criarNotificacao()

        if (Build.VERSION.SDK_INT >= 29) {

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

    // ================================================================
    // INICIAR CAPTURA
    // ================================================================

    private fun iniciarCaptura(
        novoJobId: String,
        resultCode: Int,
        resultData: Intent
    ) {

        if (running.get()) {

            Log.d(
                TAG,
                "Captura já estava ativa. Reiniciando..."
            )

            pararTudoInterno()
        }

        jobId = novoJobId

        targetLanguage =
            intentTargetLanguage()

        lastError = null

        lastOutputSeq = 0

        outputQueue.clear()

        inputQueue.clear()

        outputQueueBytes = 0

        playbackStarted = false

        running.set(true)

        Log.d(
            TAG,
            "Iniciando captura. jobId=$jobId target=$targetLanguage"
        )

        try {

            val projectionManager =
                getSystemService(
                    MEDIA_PROJECTION_SERVICE
                ) as android.media.projection.MediaProjectionManager

            mediaProjection =
                projectionManager.getMediaProjection(
                    resultCode,
                    resultData
                )

            if (mediaProjection == null) {

                throw Exception(
                    "MediaProjection retornou null"
                )
            }

            if (Build.VERSION.SDK_INT >= 34) {

                mediaProjection?.registerCallback(
                    object : MediaProjection.Callback() {

                        override fun onStop() {

                            Log.d(
                                TAG,
                                "MediaProjection foi interrompida pelo Android"
                            )

                            registrarDiagnostico(
                                "projection_stopped"
                            )

                            pararTudoInterno()
                        }
                    },
                    null
                )
            }

            iniciarAudioRecord()

            iniciarAudioTrack()

            iniciarThreadCaptura()

            iniciarThreadEnvio()

            iniciarThreadPollingSaida()

            iniciarThreadPlayback()

            iniciarThreadDiagnostico()

            registrarDiagnostico(
                "capture_started"
            )

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Erro ao iniciar captura",
                e
            )

            lastError =
                e.message ?: e.toString()

            registrarDiagnostico(
                "start_error"
            )

            pararTudo()
        }
    }

    // ================================================================
    // TARGET LANGUAGE
    // ================================================================

    /*
     * O targetLanguage é enviado pelo MainActivity
     * para /api/audio/start.
     *
     * Como o Service recebe apenas o jobId na versão atual,
     * o backend já possui o idioma associado à sessão.
     *
     * Mantemos pt-BR como padrão local.
     */
    private fun intentTargetLanguage(): String {
        return "pt-BR"
    }

    // ================================================================
    // AUDIO RECORD
    // ================================================================

    private fun iniciarAudioRecord() {

        if (Build.VERSION.SDK_INT < 29) {

            throw Exception(
                "AudioPlaybackCapture requer Android 10 (API 29) ou superior."
            )
        }

        val minBuffer =
            AudioRecord.getMinBufferSize(
                INPUT_SAMPLE_RATE,
                INPUT_CHANNEL_MASK,
                INPUT_ENCODING
            )

        if (minBuffer <= 0) {

            throw Exception(
                "Não foi possível obter buffer mínimo do AudioRecord."
            )
        }

        val bufferSize =
            max(
                minBuffer * 2,
                INPUT_CHUNK_BYTES * 4
            )

        val config =
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

        val format =
            AudioFormat.Builder()
                .setEncoding(
                    INPUT_ENCODING
                )
                .setSampleRate(
                    INPUT_SAMPLE_RATE
                )
                .setChannelMask(
                    INPUT_CHANNEL_MASK
                )
                .build()

        audioRecord =
            AudioRecord.Builder()
                .setAudioFormat(format)
                .setBufferSizeInBytes(bufferSize)
                .setAudioPlaybackCaptureConfig(config)
                .build()

        if (
            audioRecord?.state !=
            AudioRecord.STATE_INITIALIZED
        ) {

            throw Exception(
                "AudioRecord não foi inicializado."
            )
        }

        Log.d(
            TAG,
            "AudioRecord pronto. buffer=$bufferSize"
        )
    }

    // ================================================================
    // AUDIO TRACK
    // ================================================================

    private fun iniciarAudioTrack() {

        val minBuffer =
            AudioTrack.getMinBufferSize(
                OUTPUT_SAMPLE_RATE,
                OUTPUT_CHANNEL_MASK,
                OUTPUT_ENCODING
            )

        if (minBuffer <= 0) {

            throw Exception(
                "Não foi possível obter buffer mínimo do AudioTrack."
            )
        }

        val bufferSize =
            max(
                minBuffer * 4,
                48 * 1024
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
                    OUTPUT_ENCODING
                )
                .setSampleRate(
                    OUTPUT_SAMPLE_RATE
                )
                .setChannelMask(
                    OUTPUT_CHANNEL_MASK
                )
                .build()

        audioTrack =
            AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(format)
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(
                    AudioTrack.MODE_STREAM
                )
                .setPerformanceMode(
                    AudioTrack.PERFORMANCE_MODE_LOW_LATENCY
                )
                .build()

        if (
            audioTrack?.state !=
            AudioTrack.STATE_INITIALIZED
        ) {

            throw Exception(
                "AudioTrack não foi inicializado."
            )
        }

        try {

            audioTrack?.setVolume(1.0f)

        } catch (e: Exception) {

            Log.w(
                TAG,
                "Não foi possível definir volume",
                e
            )
        }

        if (Build.VERSION.SDK_INT >= 23) {

            try {

                val audioManager =
                    getSystemService(
                        AUDIO_SERVICE
                    ) as android.media.AudioManager

                val devices =
                    audioManager.getDevices(
                        android.media.AudioManager.GET_DEVICES_OUTPUTS
                    )

                val speaker =
                    devices.firstOrNull {
                        it.type ==
                            android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                    }

                if (speaker != null) {

                    audioTrack?.preferredDevice =
                        speaker

                    Log.d(
                        TAG,
                        "Saída direcionada para alto-falante interno"
                    )
                }

            } catch (e: Exception) {

                Log.w(
                    TAG,
                    "Não foi possível selecionar alto-falante",
                    e
                )
            }
        }

        Log.d(
            TAG,
            "AudioTrack pronto. buffer=$bufferSize"
        )
    }

    // ================================================================
    // THREAD DE CAPTURA
    // ================================================================

    private fun iniciarThreadCaptura() {

        captureThread =
            Thread {

                try {

                    audioRecord?.startRecording()

                    Log.d(
                        TAG,
                        "AudioRecord começou a gravar"
                    )

                    val buffer =
                        ByteArray(
                            INPUT_CHUNK_BYTES
                        )

                    while (running.get()) {

                        val count =
                            audioRecord?.read(
                                buffer,
                                0,
                                buffer.size,
                                AudioRecord.READ_BLOCKING
                            ) ?: -1

                        if (count > 0) {

                            readCount++

                            capturedBytes +=
                                count.toLong()

                            val chunk =
                                buffer.copyOf(count)

                            if (
                                !inputQueue.offer(
                                    chunk
                                )
                            ) {

                                /*
                                 * Se a fila estiver cheia,
                                 * descartamos somente o pedaço
                                 * mais antigo.
                                 *
                                 * Isso evita que o áudio fique
                                 * vários segundos atrasado.
                                 */
                                inputQueue.poll()

                                inputQueue.offer(
                                    chunk
                                )

                                registrarDiagnostico(
                                    "input_queue_full"
                                )
                            }
                        }

                        if (
                            readCount % 100L == 0L
                        ) {

                            Log.d(
                                TAG,
                                "Captura: reads=$readCount bytes=$capturedBytes queue=${inputQueue.size}"
                            )
                        }
                    }

                } catch (e: Exception) {

                    if (running.get()) {

                        Log.e(
                            TAG,
                            "Erro na thread de captura",
                            e
                        )

                        lastError =
                            e.message ?: e.toString()

                        registrarDiagnostico(
                            "capture_error"
                        )
                    }
                }
            }

        captureThread?.name =
            "SI-Capture"

        captureThread?.start()
    }

    // ================================================================
    // THREAD DE ENVIO
    // ================================================================

    private fun iniciarThreadEnvio() {

        sendThread =
            Thread {

                while (running.get()) {

                    try {

                        val audio =
                            inputQueue.poll(
                                500,
                                java.util.concurrent.TimeUnit.MILLISECONDS
                            )

                        if (audio == null) {
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

                        if (running.get()) {

                            sendErrors++

                            lastError =
                                e.message ?: e.toString()

                            Log.e(
                                TAG,
                                "Erro no envio",
                                e
                            )
                        }
                    }
                }
            }

        sendThread?.name =
            "SI-Send"

        sendThread?.start()
    }

    // ================================================================
    // ENVIO HTTP
    // ================================================================

    private fun enviarAudioHttp(
        audio: ByteArray
    ) {

        val id =
            jobId ?: return

        if (audio.isEmpty()) {
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

            connection.connectTimeout =
                15000

            connection.readTimeout =
                20000

            connection.doOutput =
                true

            connection.setRequestProperty(
                "Content-Type",
                "application/json; charset=UTF-8"
            )

            connection.setRequestProperty(
                "Accept",
                "application/json"
            )

            val base64 =
                Base64.encodeToString(
                    audio,
                    Base64.NO_WRAP
                )

            val json =
                JSONObject().apply {

                    put(
                        "jobId",
                        id
                    )

                    put(
                        "audio",
                        base64
                    )
                }

            OutputStreamWriter(
                connection.outputStream,
                Charsets.UTF_8
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

                sendErrors++

                val errorText =
                    try {

                        connection.errorStream
                            ?.bufferedReader()
                            ?.use {
                                it.readText()
                            }

                    } catch (_: Exception) {
                        null
                    }

                Log.e(
                    TAG,
                    "Erro HTTP áudio: $responseCode ${errorText ?: ""}"
                )

                lastError =
                    "HTTP $responseCode ${errorText ?: ""}"

                return
            }

            sentChunks++

            sentBytes +=
                audio.size.toLong()

        } catch (e: Exception) {

            sendErrors++

            lastError =
                e.message ?: e.toString()

            Log.e(
                TAG,
                "Falha enviando áudio",
                e
            )

        } finally {

            connection?.disconnect()
        }
    }

    // ================================================================
    // POLLING DA SAÍDA
    // ================================================================

    private fun iniciarThreadPollingSaida() {

        outputPollThread =
            Thread {

                while (running.get()) {

                    try {

                        buscarAudioTraduzido()

                        Thread.sleep(80)

                    } catch (
                        e: InterruptedException
                    ) {

                        break

                    } catch (e: Exception) {

                        if (running.get()) {

                            lastError =
                                e.message ?: e.toString()

                            Log.e(
                                TAG,
                                "Erro buscando áudio traduzido",
                                e
                            )

                            Thread.sleep(250)
                        }
                    }
                }
            }

        outputPollThread?.name =
            "SI-Output-Poll"

        outputPollThread?.start()
    }

    // ================================================================
    // BUSCAR SAÍDA
    // ================================================================

    private fun buscarAudioTraduzido() {

        val id =
            jobId ?: return

        var connection:
            HttpURLConnection? = null

        try {

            val urlText =
                "$BACKEND_URL/api/audio/output/" +
                    "$id?after=$lastOutputSeq&limit=$OUTPUT_HTTP_LIMIT"

            val url =
                URL(urlText)

            connection =
                url.openConnection()
                    as HttpURLConnection

            connection.requestMethod =
                "GET"

            connection.connectTimeout =
                10000

            connection.readTimeout =
                15000

            connection.setRequestProperty(
                "Accept",
                "application/json"
            )

            val code =
                connection.responseCode

            if (code !in 200..299) {

                if (code != 404) {

                    Log.w(
                        TAG,
                        "Output HTTP $code"
                    )
                }

                return
            }

            val response =
                BufferedReader(
                    InputStreamReader(
                        connection.inputStream,
                        Charsets.UTF_8
                    )
                ).use {
                    it.readText()
                }

            if (response.isBlank()) {
                return
            }

            processarRespostaAudio(
                response
            )

        } catch (e: Exception) {

            if (running.get()) {

                Log.w(
                    TAG,
                    "Falha polling output: ${e.message}"
                )
            }

        } finally {

            connection?.disconnect()
        }
    }

    // ================================================================
    // PROCESSAR JSON DE SAÍDA
    // ================================================================

    private fun processarRespostaAudio(
        response: String
    ) {

        try {

            val root =
                response.trim()

            /*
             * Formato normal esperado:
             *
             * {
             *   "ok": true,
             *   "chunks": [
             *      {
             *        "seq": 1,
             *        "audio": "BASE64..."
             *      }
             *   ]
             * }
             */

            if (root.startsWith("{")) {

                val obj =
                    JSONObject(root)

                extrairChunksDoObjeto(
                    obj
                )

                extrairTranscricoes(
                    obj
                )

            } else if (
                root.startsWith("[")
            ) {

                val array =
                    JSONArray(root)

                extrairArrayDeChunks(
                    array
                )
            }

        } catch (e: Exception) {

            Log.e(
                TAG,
                "JSON de saída inválido",
                e
            )

            lastError =
                "JSON output inválido: ${e.message}"
        }
    }

    // ================================================================
    // EXTRAIR CHUNKS DO OBJETO
    // ================================================================

    private fun extrairChunksDoObjeto(
        obj: JSONObject
    ) {

        val possibleArrays =
            arrayOf(
                "chunks",
                "outputChunks",
                "audioChunks",
                "data"
            )

        for (name in possibleArrays) {

            val value =
                obj.opt(name)

            if (value is JSONArray) {

                extrairArrayDeChunks(
                    value
                )

                return
            }
        }

        /*
         * Também aceita resposta contendo
         * diretamente seq + audio.
         */
        if (
            obj.has("audio") ||
            obj.has("audioBase64") ||
            obj.has("data")
        ) {

            processarChunkJson(
                obj
            )
        }
    }

    // ================================================================
    // EXTRAIR ARRAY
    // ================================================================

    private fun extrairArrayDeChunks(
        array: JSONArray
    ) {

        for (i in 0 until array.length()) {

            val item =
                array.opt(i)

            when (item) {

                is JSONObject -> {

                    processarChunkJson(
                        item
                    )
                }

                is String -> {

                    adicionarAudioNaFila(
                        item,
                        null
                    )
                }
            }
        }
    }

    // ================================================================
    // PROCESSAR CHUNK JSON
    // ================================================================

    private fun processarChunkJson(
        obj: JSONObject
    ) {

        val seq =
            when {

                obj.has("seq") ->
                    obj.optLong("seq", -1)

                obj.has("sequence") ->
                    obj.optLong("sequence", -1)

                else ->
                    -1
            }

        /*
         * Ignora pedaço que já recebemos.
         */
        if (
            seq >= 0 &&
            seq <= lastOutputSeq
        ) {

            return
        }

        var audioBase64: String? = null

        val audioNames =
            arrayOf(
                "audio",
                "audioBase64",
                "base64",
                "data"
            )

        for (name in audioNames) {

            val value =
                obj.optString(
                    name,
                    ""
                )

            if (value.isNotBlank()) {

                audioBase64 =
                    value

                break
            }
        }

        if (
            !audioBase64.isNullOrBlank()
        ) {

            adicionarAudioNaFila(
                audioBase64,
                if (seq >= 0) seq else null
            )
        }

        extrairTranscricoes(
            obj
        )
    }

    // ================================================================
    // TRANSCRIÇÕES
    // ================================================================

    private fun extrairTranscricoes(
        obj: JSONObject
    ) {

        val input =
            obj.optString(
                "inputTranscript",
                ""
            )

        if (input.isNotBlank()) {

            lastInputTranscript =
                input
        }

        val output =
            obj.optString(
                "outputTranscript",
                ""
            )

        if (output.isNotBlank()) {

            lastOutputTranscript =
                output
        }

        val transcript =
            obj.optString(
                "transcript",
                ""
            )

        if (transcript.isNotBlank()) {

            lastOutputTranscript =
                transcript
        }
    }

    // ================================================================
    // COLOCAR ÁUDIO NA FILA
    // ================================================================

    private fun adicionarAudioNaFila(
        base64: String,
        seq: Long?
    ) {

        try {

            var clean =
                base64.trim()

            /*
             * Remove prefixo de Data URI caso o backend
             * eventualmente envie:
             *
             * data:audio/pcm;base64,AAAA...
             */
            val comma =
                clean.indexOf(',')

            if (
                clean.startsWith(
                    "data:",
                    ignoreCase = true
                ) &&
                comma >= 0
            ) {

                clean =
                    clean.substring(
                        comma + 1
                    )
            }

            if (clean.isBlank()) {
                return
            }

            val bytes =
                Base64.decode(
                    clean,
                    Base64.DEFAULT
                )

            if (bytes.isEmpty()) {
                return
            }

            /*
             * Gemini retorna PCM 16-bit.
             * Garantimos que o tamanho seja par.
             */
            val pcm =
                if (
                    bytes.size % 2 == 0
                ) {

                    bytes

                } else {

                    bytes.copyOf(
                        bytes.size - 1
                    )
                }

            if (pcm.isEmpty()) {
                return
            }

            receivedOutputChunks++

            receivedOutputBytes +=
                pcm.size.toLong()

            /*
             * Se a fila estiver muito cheia,
             * descartamos somente os pedaços
             * mais antigos.
             *
             * Isso evita acumular minutos de atraso.
             */
            while (
                outputQueueBytes +
                    pcm.size >
                4L * 1024L * 1024L
            ) {

                val old =
                    outputQueue.poll()
                        ?: break

                outputQueueBytes -=
                    old.size.toLong()
            }

            if (
                !outputQueue.offer(
                    pcm
                )
            ) {

                val old =
                    outputQueue.poll()

                if (old != null) {

                    outputQueueBytes -=
                        old.size.toLong()
                }

                outputQueue.offer(
                    pcm
                )
            }

            outputQueueBytes +=
                pcm.size.toLong()

            queuedOutputChunks++

            if (
                seq != null &&
                seq > lastOutputSeq
            ) {

                lastOutputSeq =
                    seq
            }

            Log.d(
                TAG,
                "Áudio traduzido: " +
                    "seq=${seq ?: "-"} " +
                    "bytes=${pcm.size} " +
                    "fila=${outputQueueBytes}"
            )

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Erro decodificando áudio",
                e
            )

            lastError =
                "Erro Base64: ${e.message}"
        }
    }

    // ================================================================
    // THREAD DE PLAYBACK
    // ================================================================

    private fun iniciarThreadPlayback() {

        playbackThread =
            Thread {

                try {

                    while (running.get()) {

                        /*
                         * ------------------------------------------------
                         * PRÉ-BUFFER
                         * ------------------------------------------------
                         *
                         * Antes de começar a falar, espera aproximadamente
                         * 800 ms de áudio.
                         */
                        if (!playbackStarted) {

                            if (
                                outputQueueBytes <
                                PREBUFFER_BYTES
                            ) {

                                Thread.sleep(20)

                                continue
                            }

                            iniciarAudioTrackPlayback()

                            playbackStarted =
                                true

                            Log.d(
                                TAG,
                                "PREBUFFER completo: " +
                                    "${outputQueueBytes} bytes. " +
                                    "Iniciando reprodução."
                            )
                        }

                        /*
                         * ------------------------------------------------
                         * PEGAR UM PEDAÇO
                         * ------------------------------------------------
                         */

                        val chunk =
                            outputQueue.poll(
                                200,
                                java.util.concurrent.TimeUnit.MILLISECONDS
                            )

                        if (chunk == null) {

                            /*
                             * Aqui a reprodução chegou ao fim da
                             * reserva disponível.
                             *
                             * Não fazemos busy loop.
                             */
                            playbackStarted =
                                false

                            pararAudioTrackPlayback()

                            Log.d(
                                TAG,
                                "Fila de saída esvaziou. " +
                                    "Aguardando novo prebuffer."
                            )

                            continue
                        }

                        outputQueueBytes -=
                            chunk.size.toLong()

                        /*
                         * ------------------------------------------------
                         * AMPLIFICAÇÃO
                         * ------------------------------------------------
                         */

                        val pcm =
                            amplificarPcm(
                                chunk,
                                OUTPUT_GAIN
                            )

                        /*
                         * ------------------------------------------------
                         * ESCRITA CONTÍNUA
                         * ------------------------------------------------
                         */

                        escreverAudioTrack(
                            pcm
                        )

                        playedOutputChunks++

                        playedOutputBytes +=
                            pcm.size.toLong()
                    }

                } catch (
                    e: InterruptedException
                ) {

                    Log.d(
                        TAG,
                        "Playback interrompido"
                    )

                } catch (e: Exception) {

                    if (running.get()) {

                        playbackWriteErrors++

                        lastError =
                            e.message ?: e.toString()

                        Log.e(
                            TAG,
                            "Erro na reprodução",
                            e
                        )

                        registrarDiagnostico(
                            "playback_error"
                        )
                    }
                }
            }

        playbackThread?.name =
            "SI-Playback"

        playbackThread?.start()
    }

    // ================================================================
    // INICIAR AUDIO TRACK
    // ================================================================

    private fun iniciarAudioTrackPlayback() {

        try {

            audioTrack?.flush()

        } catch (_: Exception) {
        }

        try {

            audioTrack?.play()

            Log.d(
                TAG,
                "AudioTrack PLAY"
            )

        } catch (e: Exception) {

            playbackWriteErrors++

            Log.e(
                TAG,
                "Não foi possível iniciar AudioTrack",
                e
            )
        }

        if (TEST_TONE_ENABLED) {

            tocarTomTeste()
        }
    }

    // ================================================================
    // PARAR PLAYBACK TEMPORARIAMENTE
    // ================================================================

    private fun pararAudioTrackPlayback() {

        try {

            if (
                audioTrack?.playState ==
                AudioTrack.PLAYSTATE_PLAYING
            ) {

                audioTrack?.pause()
            }

        } catch (_: Exception) {
        }

        try {

            audioTrack?.flush()

        } catch (_: Exception) {
        }
    }

    // ================================================================
    // ESCREVER AUDIO TRACK
    // ================================================================

    private fun escreverAudioTrack(
        pcm: ByteArray
    ) {

        val track =
            audioTrack
                ?: return

        if (pcm.isEmpty()) {
            return
        }

        var offset = 0

        while (
            offset < pcm.size &&
            running.get()
        ) {

            try {

                val written =
                    track.write(
                        pcm,
                        offset,
                        pcm.size - offset,
                        AudioTrack.WRITE_BLOCKING
                    )

                if (written > 0) {

                    offset += written

                } else {

                    playbackWriteErrors++

                    Log.w(
                        TAG,
                        "AudioTrack.write retornou $written"
                    )

                    break
                }

            } catch (e: Exception) {

                playbackWriteErrors++

                Log.e(
                    TAG,
                    "Erro AudioTrack.write",
                    e
                )

                break
            }
        }
    }

    // ================================================================
    // AMPLIFICAR PCM
    // ================================================================

    private fun amplificarPcm(
        input: ByteArray,
        gain: Float
    ): ByteArray {

        if (gain == 1.0f) {
            return input
        }

        val output =
            ByteArray(
                input.size
            )

        var i = 0

        while (
            i + 1 < input.size
        ) {

            val low =
                input[i].toInt() and 0xFF

            val high =
                input[i + 1].toInt()

            val sample =
                (high shl 8) or low

            var value =
                (sample * gain)
                    .toInt()

            value =
                value.coerceIn(
                    Short.MIN_VALUE.toInt(),
                    Short.MAX_VALUE.toInt()
                )

            output[i] =
                (
                    value and 0xFF
                ).toByte()

            output[i + 1] =
                (
                    (value shr 8) and 0xFF
                ).toByte()

            i += 2
        }

        return output
    }

    // ================================================================
    // TOM DE TESTE
    // ================================================================

    private fun tocarTomTeste() {

        if (!TEST_TONE_ENABLED) {
            return
        }

        try {

            val durationMs = 180

            val sampleCount =
                OUTPUT_SAMPLE_RATE *
                    durationMs /
                    1000

            val buffer =
                ByteArray(
                    sampleCount * 2
                )

            val frequency = 440.0

            for (i in 0 until sampleCount) {

                val angle =
                    2.0 *
                        Math.PI *
                        frequency *
                        i /
                        OUTPUT_SAMPLE_RATE

                val sample =
                    (
                        kotlin.math.sin(angle) *
                            8000
                    ).toInt()
                        .toShort()

                buffer[i * 2] =
                    (
                        sample.toInt() and
                            0xFF
                    ).toByte()

                buffer[i * 2 + 1] =
                    (
                        (
                            sample.toInt() shr 8
                        ) and
                            0xFF
                    ).toByte()
            }

            audioTrack?.write(
                buffer,
                0,
                buffer.size
            )

        } catch (e: Exception) {

            Log.w(
                TAG,
                "Erro no tom de teste",
                e
            )
        }
    }

    // ================================================================
    // DIAGNÓSTICO
    // ================================================================

    private fun iniciarThreadDiagnostico() {

        diagnosticThread =
            Thread {

                while (running.get()) {

                    try {

                        Thread.sleep(5000)

                        if (running.get()) {

                            registrarDiagnostico(
                                "heartbeat"
                            )
                        }

                    } catch (
                        e: InterruptedException
                    ) {

                        break
                    }
                }
            }

        diagnosticThread?.name =
            "SI-Diagnostic"

        diagnosticThread?.start()
    }

    private fun registrarDiagnostico(
        stage: String
    ) {

        val id =
            jobId ?: return

        Thread {

            enviarDiagnosticoHttp(
                id,
                stage
            )

        }.start()
    }

    // ================================================================
    // HTTP DIAGNÓSTICO
    // ================================================================

    private fun enviarDiagnosticoHttp(
        id: String,
        stage: String
    ) {

        var connection:
            HttpURLConnection? = null

        try {

            val url =
                URL(
                    "$BACKEND_URL/api/audio/diagnostic/$id"
                )

            connection =
                url.openConnection()
                    as HttpURLConnection

            connection.requestMethod =
                "POST"

            connection.connectTimeout =
                8000

            connection.readTimeout =
                10000

            connection.doOutput =
                true

            connection.setRequestProperty(
                "Content-Type",
                "application/json; charset=UTF-8"
            )

            val json =
                JSONObject().apply {

                    put(
                        "stage",
                        stage
                    )

                    put(
                        "recording",
                        running.get()
                    )

                    put(
                        "captureStarted",
                        audioRecord != null
                    )

                    put(
                        "readCount",
                        readCount
                    )

                    put(
                        "lastRead",
                        if (
                            capturedBytes > 0
                        ) {
                            INPUT_CHUNK_BYTES
                        } else {
                            0
                        }
                    )

                    put(
                        "capturedBytes",
                        capturedBytes
                    )

                    put(
                        "sentChunks",
                        sentChunks
                    )

                    put(
                        "sentBytes",
                        sentBytes
                    )

                    put(
                        "sendErrors",
                        sendErrors
                    )

                    put(
                        "inputQueueSize",
                        inputQueue.size
                    )

                    put(
                        "targetLanguage",
                        targetLanguage
                    )

                    put(
                        "audioTrackReady",
                        audioTrack != null
                    )

                    put(
                        "outputPlaying",
                        audioTrack?.playState ==
                            AudioTrack.PLAYSTATE_PLAYING
                    )

                    put(
                        "receivedOutputChunks",
                        receivedOutputChunks
                    )

                    put(
                        "receivedOutputBytes",
                        receivedOutputBytes
                    )

                    put(
                        "queuedOutputChunks",
                        queuedOutputChunks
                    )

                    put(
                        "outputQueueSize",
                        outputQueue.size
                    )

                    put(
                        "outputQueueBytes",
                        outputQueueBytes
                    )

                    put(
                        "playedOutputChunks",
                        playedOutputChunks
                    )

                    put(
                        "playedOutputBytes",
                        playedOutputBytes
                    )

                    put(
                        "playbackWriteErrors",
                        playbackWriteErrors
                    )

                    put(
                        "lastOutputSeq",
                        lastOutputSeq
                    )

                    put(
                        "playbackStarted",
                        playbackStarted
                    )

                    put(
                        "prebufferBytes",
                        PREBUFFER_BYTES
                    )

                    put(
                        "inputTranscript",
                        lastInputTranscript
                    )

                    put(
                        "outputTranscript",
                        lastOutputTranscript
                    )

                    put(
                        "lastError",
                        lastError ?: JSONObject.NULL
                    )
                }

            OutputStreamWriter(
                connection.outputStream,
                Charsets.UTF_8
            ).use { writer ->

                writer.write(
                    json.toString()
                )

                writer.flush()
            }

            connection.responseCode

        } catch (e: Exception) {

            Log.w(
                TAG,
                "Diagnóstico não enviado: ${e.message}"
            )

        } finally {

            connection?.disconnect()
        }
    }

    // ================================================================
    // PARAR TUDO INTERNAMENTE
    // ================================================================

    private fun pararTudoInterno() {

        running.set(false)

        try {
            captureThread?.interrupt()
        } catch (_: Exception) {
        }

        try {
            sendThread?.interrupt()
        } catch (_: Exception) {
        }

        try {
            outputPollThread?.interrupt()
        } catch (_: Exception) {
        }

        try {
            playbackThread?.interrupt()
        } catch (_: Exception) {
        }

        try {
            diagnosticThread?.interrupt()
        } catch (_: Exception) {
        }

        captureThread = null
        sendThread = null
        outputPollThread = null
        playbackThread = null
        diagnosticThread = null

        // ------------------------------------------------------------
        // AudioRecord
        // ------------------------------------------------------------

        try {

            audioRecord?.stop()

        } catch (_: Exception) {
        }

        try {

            audioRecord?.release()

        } catch (_: Exception) {
        }

        audioRecord = null

        // ------------------------------------------------------------
        // AudioTrack
        // ------------------------------------------------------------

        try {

            audioTrack?.pause()

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

        audioTrack = null

        // ------------------------------------------------------------
        // MediaProjection
        // ------------------------------------------------------------

        try {

            mediaProjection?.stop()

        } catch (_: Exception) {
        }

        mediaProjection = null

        // ------------------------------------------------------------
        // Filas
        // ------------------------------------------------------------

        inputQueue.clear()

        outputQueue.clear()

        outputQueueBytes = 0

        playbackStarted = false

        Log.d(
            TAG,
            "Recursos de áudio liberados"
        )
    }

    // ================================================================
    // PARAR TUDO
    // ================================================================

    private fun pararTudo() {

        val idAntes =
            jobId

        val wasRunning =
            running.get()

        pararTudoInterno()

        if (
            wasRunning &&
            idAntes != null
        ) {

            Thread {

                enviarDiagnosticoHttp(
                    idAntes,
                    "stop_requested"
                )

            }.start()
        }

        jobId = null

        stopForeground(
            STOP_FOREGROUND_REMOVE
        )

        stopSelf()

        Log.d(
            TAG,
            "SI Tradutor Live parado"
        )
    }

    // ================================================================
    // BINDER
    // ================================================================

    override fun onBind(
        intent: Intent?
    ): IBinder? {

        return null
    }

    // ================================================================
    // DESTROY
    // ================================================================

    override fun onDestroy() {

        if (running.get()) {

            pararTudoInterno()
        }

        Log.d(
            TAG,
            "AudioCaptureService destruído"
        )

        super.onDestroy()
    }
}
