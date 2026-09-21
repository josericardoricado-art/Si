package com.si.tradutor

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Base64
import android.util.Log
import org.json.JSONObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class GeminiAudioPlayer(
    private val context: Context,
    private val backendUrl: String
) {

    companion object {

        private const val TAG = "GeminiAudioPlayer"

        // Gemini Live envia:
        // PCM 16-bit / mono / 24 kHz
        private const val SAMPLE_RATE = 24000

        private const val CHANNEL_MASK =
            AudioFormat.CHANNEL_OUT_MONO

        private const val ENCODING =
            AudioFormat.ENCODING_PCM_16BIT

        // Aproximadamente 300 ms.
        // 24000 samples/s * 2 bytes = 48000 bytes/s
        private const val PREBUFFER_BYTES = 14400

        // Aproximadamente 200 ms.
        private const val WRITE_CHUNK_BYTES = 9600

        private const val MAX_QUEUE_BYTES =
            10 * 1024 * 1024

        private const val VOLUME = 1.0f
    }

    private val running =
        AtomicBoolean(false)

    private var jobId: String? = null

    private var audioTrack: AudioTrack? = null

    private var playbackThread: Thread? = null

    private var webSocket: WebSocket? = null

    private val audioQueue =
        LinkedBlockingQueue<ByteArray>()

    private var queuedBytes = 0L

    private var playbackStarted = false

    private val lock = Any()

    private val httpClient =
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .build()


    // ============================================================
    // START
    // ============================================================

    fun start(jobId: String) {

        if (running.get()) {

            Log.d(
                TAG,
                "Player já está funcionando"
            )

            return
        }

        this.jobId = jobId

        running.set(true)

        synchronized(lock) {

            audioQueue.clear()

            queuedBytes = 0L

            playbackStarted = false
        }

        Log.d(
            TAG,
            "Iniciando player WebSocket. jobId=$jobId"
        )

        criarAudioTrack()

        iniciarPlaybackThread()

        conectarWebSocket()
    }


    // ============================================================
    // STOP
    // ============================================================

    fun stop() {

        if (!running.getAndSet(false)) {
            return
        }

        Log.d(
            TAG,
            "Parando player WebSocket"
        )

        try {
            webSocket?.close(
                1000,
                "Player parado"
            )
        } catch (_: Exception) {
        }

        webSocket = null

        try {
            playbackThread?.interrupt()
        } catch (_: Exception) {
        }

        playbackThread = null

        synchronized(lock) {

            audioQueue.clear()

            queuedBytes = 0L

            playbackStarted = false
        }

        try {
            audioTrack?.pause()
        } catch (_: Exception) {
        }

        try {
            audioTrack?.flush()
        } catch (_: Exception) {
        }

        try {
            audioTrack?.stop()
        } catch (_: Exception) {
        }

        try {
            audioTrack?.release()
        } catch (_: Exception) {
        }

        audioTrack = null

        jobId = null
    }


    // ============================================================
    // AUDIO TRACK
    // ============================================================

    private fun criarAudioTrack() {

        val minBuffer =
            AudioTrack.getMinBufferSize(
                SAMPLE_RATE,
                CHANNEL_MASK,
                ENCODING
            )

        val bufferSize =
            maxOf(
                minBuffer * 4,
                48000
            )

        Log.d(
            TAG,
            "AudioTrack minBuffer=$minBuffer " +
                    "bufferSize=$bufferSize"
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
                .setSampleRate(
                    SAMPLE_RATE
                )
                .setEncoding(
                    ENCODING
                )
                .setChannelMask(
                    CHANNEL_MASK
                )
                .build()

        audioTrack =
            AudioTrack(
                attributes,
                format,
                bufferSize,
                AudioTrack.MODE_STREAM,
                AudioManager.AUDIO_SESSION_ID_GENERATE
            )

        audioTrack?.setVolume(
            VOLUME
        )

        Log.d(
            TAG,
            "AudioTrack criado"
        )
    }


    // ============================================================
    // WEBSOCKET
    // ============================================================

    private fun conectarWebSocket() {

        val id =
            jobId
                ?: return

        if (!running.get()) {
            return
        }

        val wsUrl =
            backendUrl
                .replace(
                    "https://",
                    "wss://"
                )
                .replace(
                    "http://",
                    "ws://"
                ) +
                "/ws"

        Log.d(
            TAG,
            "Conectando WebSocket: $wsUrl"
        )

        val request =
            Request.Builder()
                .url(wsUrl)
                .build()

        webSocket =
            httpClient.newWebSocket(
                request,
                object : WebSocketListener() {

                    override fun onOpen(
                        webSocket: WebSocket,
                        response: okhttp3.Response
                    ) {

                        if (!running.get()) {
                            return
                        }

                        Log.d(
                            TAG,
                            "WebSocket conectado"
                        )

                        val subscribe =
                            JSONObject().apply {

                                put(
                                    "type",
                                    "subscribe"
                                )

                                put(
                                    "jobId",
                                    id
                                )
                            }

                        val enviado =
                            webSocket.send(
                                subscribe.toString()
                            )

                        Log.d(
                            TAG,
                            "Subscribe enviado=$enviado jobId=$id"
                        )
                    }


                    override fun onMessage(
                        webSocket: WebSocket,
                        text: String
                    ) {

                        if (!running.get()) {
                            return
                        }

                        try {

                            processarMensagemWebSocket(
                                text
                            )

                        } catch (e: Exception) {

                            Log.e(
                                TAG,
                                "Erro processando WebSocket",
                                e
                            )
                        }
                    }


                    override fun onFailure(
                        webSocket: WebSocket,
                        t: Throwable,
                        response: okhttp3.Response?
                    ) {

                        Log.e(
                            TAG,
                            "Falha WebSocket: ${t.message}",
                            t
                        )

                        if (
                            running.get()
                        ) {

                            Log.d(
                                TAG,
                                "WebSocket falhou"
                            )
                        }
                    }


                    override fun onClosed(
                        webSocket: WebSocket,
                        code: Int,
                        reason: String
                    ) {

                        Log.d(
                            TAG,
                            "WebSocket fechado. code=$code reason=$reason"
                        )
                    }
                }
            )
    }


    // ============================================================
    // PROCESSAR MENSAGEM WEBSOCKET
    // ============================================================

    private fun processarMensagemWebSocket(
        text: String
    ) {

        if (text.isBlank()) {
            return
        }

        val root =
            JSONObject(text)

        val type =
            root.optString(
                "type",
                ""
            )

        when (type) {

            "subscribed" -> {

                Log.d(
                    TAG,
                    "WebSocket inscrito no jobId=" +
                            root.optString(
                                "jobId",
                                ""
                            )
                )
            }

            "audio" -> {

                val messageJobId =
                    root.optString(
                        "jobId",
                        ""
                    )

                val currentJobId =
                    jobId ?: ""

                if (
                    messageJobId != currentJobId
                ) {

                    Log.w(
                        TAG,
                        "Áudio de outro job ignorado"
                    )

                    return
                }

                val seq =
                    root.optLong(
                        "seq",
                        -1L
                    )

                val audioBase64 =
                    root.optString(
                        "audio",
                        ""
                    )

                if (
                    audioBase64.isBlank()
                ) {

                    return
                }

                val audio =
                    try {

                        Base64.decode(
                            audioBase64,
                            Base64.DEFAULT
                        )

                    } catch (e: Exception) {

                        Log.e(
                            TAG,
                            "Erro Base64 seq=$seq",
                            e
                        )

                        return
                    }

                if (
                    audio.isEmpty()
                ) {

                    return
                }

                adicionarAudioNaFila(
                    audio
                )

                Log.d(
                    TAG,
                    "Áudio WebSocket recebido: " +
                            "seq=$seq " +
                            "bytes=${audio.size} " +
                            "fila=${getQueuedBytes()}"
                )
            }

            "error" -> {

                Log.e(
                    TAG,
                    "Erro recebido do backend: " +
                            root.optString(
                                "message",
                                "erro desconhecido"
                            )
                )
            }

            else -> {

                Log.d(
                    TAG,
                    "Mensagem WebSocket: type=$type"
                )
            }
        }
    }


    // ============================================================
    // PLAYBACK THREAD
    // ============================================================

    private fun iniciarPlaybackThread() {

        playbackThread =
            Thread {

                Log.d(
                    TAG,
                    "Playback thread iniciado"
                )

                while (
                    running.get()
                ) {

                    try {

                        if (
                            !playbackStarted
                        ) {

                            while (
                                running.get() &&
                                getQueuedBytes() <
                                PREBUFFER_BYTES
                            ) {

                                Thread.sleep(
                                    20
                                )
                            }

                            if (
                                !running.get()
                            ) {

                                break
                            }

                            val track =
                                audioTrack

                            if (
                                track != null
                            ) {

                                try {

                                    track.play()

                                    playbackStarted =
                                        true

                                    Log.d(
                                        TAG,
                                        "Playback iniciado. " +
                                                "fila=${getQueuedBytes()} bytes"
                                    )

                                } catch (
                                    e: Exception
                                ) {

                                    Log.e(
                                        TAG,
                                        "Erro ao iniciar AudioTrack",
                                        e
                                    )

                                    Thread.sleep(
                                        100
                                    )

                                    continue
                                }
                            }
                        }

                        val data =
                            audioQueue.take()

                        synchronized(lock) {

                            queuedBytes -=
                                data.size.toLong()

                            if (
                                queuedBytes < 0
                            ) {

                                queuedBytes = 0
                            }
                        }

                        tocarAudio(
                            data
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
                            "Erro no playback",
                            e
                        )
                    }
                }

                Log.d(
                    TAG,
                    "Playback thread finalizado"
                )

            }.apply {

                name =
                    "SI-Gemini-AudioPlayback"

                start()
            }
    }


    // ============================================================
    // TOCAR PCM
    // ============================================================

    private fun tocarAudio(
        data: ByteArray
    ) {

        if (!running.get()) {
            return
        }

        val track =
            audioTrack
                ?: return

        var offset = 0

        while (
            offset < data.size &&
            running.get()
        ) {

            val restante =
                data.size - offset

            val tamanho =
                minOf(
                    restante,
                    WRITE_CHUNK_BYTES
                )

            try {

                val escritos =
                    track.write(
                        data,
                        offset,
                        tamanho,
                        AudioTrack.WRITE_BLOCKING
                    )

                if (
                    escritos > 0
                ) {

                    offset +=
                        escritos

                } else {

                    Log.w(
                        TAG,
                        "AudioTrack.write retornou $escritos"
                    )

                    break
                }

            } catch (
                e: Exception
            ) {

                Log.e(
                    TAG,
                    "Erro escrevendo PCM",
                    e
                )

                break
            }
        }
    }


    // ============================================================
    // FILA
    // ============================================================

    private fun adicionarAudioNaFila(
        audio: ByteArray
    ) {

        synchronized(lock) {

            if (
                !running.get()
            ) {

                return
            }

            while (
                queuedBytes +
                audio.size >
                MAX_QUEUE_BYTES
            ) {

                val antigo =
                    audioQueue.poll()
                        ?: break

                queuedBytes -=
                    antigo.size.toLong()

                if (
                    queuedBytes < 0
                ) {

                    queuedBytes = 0
                }
            }

            audioQueue.offer(
                audio
            )

            queuedBytes +=
                audio.size.toLong()
        }
    }


    // ============================================================
    // TAMANHO DA FILA
    // ============================================================

    private fun getQueuedBytes(): Long {

        synchronized(lock) {

            return queuedBytes
        }
    }
}
