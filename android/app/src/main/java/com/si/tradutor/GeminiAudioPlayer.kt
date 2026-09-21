package com.si.tradutor

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Base64
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class GeminiAudioPlayer(
    private val context: Context,
    private val backendUrl: String
) {

    companion object {
        private const val TAG = "GeminiAudioPlayer"

        private const val SAMPLE_RATE = 24000
        private const val CHANNEL_MASK = AudioFormat.CHANNEL_OUT_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT

        private const val MAX_QUEUE_BYTES = 10 * 1024 * 1024
        private const val BYTES_PER_SECOND = SAMPLE_RATE * 2
        private const val WRITE_CHUNK_BYTES = 4096

        // Definida aqui para evitar o erro de Unresolved Reference
        private const val PREBUFFER_BYTES = (BYTES_PER_SECOND * 0.3).toInt()

        private const val VOLUME = 1.0f
    }

    private val running = AtomicBoolean(false)

    private var jobId: String? = null
    private var audioTrack: AudioTrack? = null
    private var playbackThread: Thread? = null

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null

    private val audioQueue = LinkedBlockingQueue<ByteArray>()
    private var queuedBytes = 0L
    private val lock = Any()

    fun start(jobId: String) {
        if (running.get()) {
            Log.d(TAG, "Player já estava funcionando")
            return
        }

        this.jobId = jobId
        running.set(true)

        Log.d(TAG, "Iniciando GeminiAudioPlayer via WebSocket. jobId=$jobId")

        criarAudioTrack()
        iniciarPlaybackThread()
        conectarWebSocket(jobId)
    }

    fun stop() {
        if (!running.getAndSet(false)) {
            return
        }

        Log.d(TAG, "Parando GeminiAudioPlayer")

        try {
            webSocket?.close(1000, "Player parado")
        } catch (_: Exception) {}
        webSocket = null

        try {
            playbackThread?.interrupt()
        } catch (_: Exception) {}
        playbackThread = null

        synchronized(lock) {
            audioQueue.clear()
            queuedBytes = 0
        }

        try { audioTrack?.pause() } catch (_: Exception) {}
        try { audioTrack?.flush() } catch (_: Exception) {}
        try { audioTrack?.stop() } catch (_: Exception) {}
        try { audioTrack?.release() } catch (_: Exception) {}

        audioTrack = null
        jobId = null
    }

    private fun conectarWebSocket(jobId: String) {
        val wsUrl = backendUrl
            .replaceFirst("http://", "ws://")
            .replaceFirst("https://", "wss://") + "/ws/audio?jobId=$jobId"

        Log.d(TAG, "Conectando ao WebSocket: $wsUrl")

        val request = Request.Builder()
            .url(wsUrl)
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket Conectado com sucesso!")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                processarMensagemWebSocket(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "Erro no WebSocket", t)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket fechando: $reason")
            }
        })
    }

    private fun processarMensagemWebSocket(text: String) {
        try {
            val json = JSONObject(text)
            val audioBase64 = json.optString("audio", "")

            if (audioBase64.isBlank()) return

            val audio = Base64.decode(audioBase64, Base64.DEFAULT)
            if (audio.isNotEmpty()) {
                adicionarAudioNaFila(audio)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao processar JSON do WebSocket", e)
        }
    }

    private fun criarAudioTrack() {
        val minBuffer = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            CHANNEL_MASK,
            ENCODING
        )

        val bufferSize = maxOf(minBuffer * 4, BYTES_PER_SECOND * 2)

        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        val format = AudioFormat.Builder()
            .setSampleRate(SAMPLE_RATE)
            .setEncoding(ENCODING)
            .setChannelMask(CHANNEL_MASK)
            .build()

        audioTrack = AudioTrack(
            attributes,
            format,
            bufferSize,
            AudioTrack.MODE_STREAM,
            AudioManager.AUDIO_SESSION_ID_GENERATE
        )

        audioTrack?.setVolume(VOLUME)

        try {
            audioTrack?.play()
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao iniciar AudioTrack", e)
        }
    }

    private fun iniciarPlaybackThread() {
        playbackThread = Thread {
            while (running.get()) {
                try {
                    while (getQueuedBytes() < PREBUFFER_BYTES && running.get()) {
                        Thread.sleep(20)
                    }

                    while (getQueuedBytes() > 0 && running.get()) {
                        val data = audioQueue.poll() ?: break

                        synchronized(lock) {
                            queuedBytes -= data.size.toLong()
                            if (queuedBytes < 0) queuedBytes = 0
                        }

                        tocarAudio(data)
                    }
                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
                    Log.e(TAG, "Erro no playback thread", e)
                }
            }
        }.apply {
            name = "SI-Gemini-AudioPlayback"
            start()
        }
    }

    private fun tocarAudio(data: ByteArray) {
        if (!running.get()) return

        val track = audioTrack ?: return
        var offset = 0

        while (offset < data.size && running.get()) {
            val restante = data.size - offset
            val tamanho = minOf(restante, WRITE_CHUNK_BYTES)

            try {
                val escritos = track.write(
                    data,
                    offset,
                    tamanho,
                    AudioTrack.WRITE_BLOCKING
                )

                if (escritos > 0) {
                    offset += escritos
                } else {
                    break
                }
            } catch (e: Exception) {
                Log.e(TAG, "Erro escrevendo áudio", e)
                break
            }
        }
    }

    private fun adicionarAudioNaFila(audio: ByteArray) {
        synchronized(lock) {
            if (!running.get()) return

            val novoTotal = queuedBytes + audio.size

            if (novoTotal > MAX_QUEUE_BYTES) {
                while (queuedBytes + audio.size > MAX_QUEUE_BYTES) {
                    val antigo = audioQueue.poll() ?: break
                    queuedBytes -= antigo.size.toLong()
                    if (queuedBytes < 0) queuedBytes = 0
                }
            }

            audioQueue.offer(audio)
            queuedBytes += audio.size.toLong()
        }
    }

    private fun getQueuedBytes(): Long {
        synchronized(lock) {
            return queuedBytes
        }
    }
}
