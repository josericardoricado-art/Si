package com.si.tradutor

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

class GeminiAudioPlayer(
    private val context: Context,
    private val backendUrl: String
) {

    companion object {
        private const val TAG = "GeminiAudioPlayer"

        // Áudio enviado pelo Gemini Live
        private const val SAMPLE_RATE = 24000
        private const val CHANNEL_MASK = AudioFormat.CHANNEL_OUT_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT

        // Consulta o Render rapidamente
        private const val POLL_INTERVAL_MS = 80L

        // Limite máximo da fila de áudio
        private const val MAX_QUEUE_BYTES = 10 * 1024 * 1024

        // Aproximadamente 1 segundo de PCM 24 kHz / 16 bit / mono
        private const val BYTES_PER_SECOND = SAMPLE_RATE * 2

        // Tamanho usado em cada escrita no AudioTrack
        private const val WRITE_CHUNK_BYTES = 48000

        // Volume da voz traduzida
        private const val VOLUME = 1.0f
    }

    private val running =
        AtomicBoolean(false)

    private var jobId: String? = null

    private var audioTrack: AudioTrack? = null

    private var pollThread: Thread? = null
    private var playbackThread: Thread? = null

    private val audioQueue =
        LinkedBlockingQueue<ByteArray>()

    private var queuedBytes = 0L

    private var lastOutputSeq = 0L

    private val lock = Any()

    fun start(jobId: String) {

        if (running.get()) {
            Log.d(
                TAG,
                "Player já estava funcionando"
            )
            return
        }

        this.jobId = jobId

        running.set(true)

        Log.d(
            TAG,
            "Iniciando GeminiAudioPlayer. jobId=$jobId"
        )

        criarAudioTrack()

        iniciarPlaybackThread()

        iniciarPollThread()
    }

    fun stop() {

        if (!running.getAndSet(false)) {
            return
        }

        Log.d(
            TAG,
            "Parando GeminiAudioPlayer"
        )

        try {
            pollThread?.interrupt()
        } catch (_: Exception) {
        }

        try {
            playbackThread?.interrupt()
        } catch (_: Exception) {
        }

        pollThread = null
        playbackThread = null

        synchronized(lock) {

            audioQueue.clear()

            queuedBytes = 0
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

        lastOutputSeq = 0L
    }

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
                BYTES_PER_SECOND * 2
            )

        Log.d(
            TAG,
            "Criando AudioTrack. " +
                    "minBuffer=$minBuffer " +
                    "buffer=$bufferSize"
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

        try {

            audioTrack?.play()

            Log.d(
                TAG,
                "AudioTrack PLAY iniciado"
            )

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Erro ao iniciar AudioTrack",
                e
            )
        }
    }

    private fun iniciarPlaybackThread() {

        playbackThread =
            Thread {

                Log.d(
                    TAG,
                    "Playback thread iniciado"
                )

                while (running.get()) {

                    try {

                        val data =
                            audioQueue.take()

                        synchronized(lock) {

                            queuedBytes -=
                                data.size.toLong()

                            if (queuedBytes < 0) {
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

                    } catch (e: Exception) {

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

                if (escritos > 0) {

                    offset += escritos

                } else {

                    Log.w(
                        TAG,
                        "AudioTrack.write retornou $escritos"
                    )

                    break
                }

            } catch (e: Exception) {

                Log.e(
                    TAG,
                    "Erro escrevendo áudio",
                    e
                )

                break
            }
        }
    }

    private fun iniciarPollThread() {

        pollThread =
            Thread {

                Log.d(
                    TAG,
                    "Poll thread iniciado"
                )

                while (running.get()) {

                    try {

                        buscarAudioTraduzido()

                    } catch (
                        e: InterruptedException
                    ) {

                        break

                    } catch (e: Exception) {

                        Log.e(
                            TAG,
                            "Erro buscando áudio traduzido",
                            e
                        )
                    }

                    try {

                        Thread.sleep(
                            POLL_INTERVAL_MS
                        )

                    } catch (
                        _: InterruptedException
                    ) {

                        break
                    }
                }

                Log.d(
                    TAG,
                    "Poll thread finalizado"
                )

            }.apply {

                name =
                    "SI-Gemini-AudioPoll"

                start()
            }
    }

    private fun buscarAudioTraduzido() {

        val id =
            jobId
                ?: return

        val urlString =
            "$backendUrl/api/audio/output/$id" +
                    "?after=$lastOutputSeq&limit=30"

        var connection:
            HttpURLConnection? = null

        try {

            val url =
                URL(urlString)

            connection =
                url.openConnection()
                    as HttpURLConnection

            connection.requestMethod =
                "GET"

            connection.connectTimeout =
                5000

            connection.readTimeout =
                5000

            connection.useCaches =
                false

            val code =
                connection.responseCode

            if (code != 200) {

                Log.w(
                    TAG,
                    "Render respondeu HTTP $code"
                )

                return
            }

            val text =
                connection.inputStream
                    .bufferedReader()
                    .use {
                        it.readText()
                    }

            if (text.isBlank()) {
                return
            }

            processarResposta(
                text
            )

        } catch (e: Exception) {

            if (running.get()) {

                Log.e(
                    TAG,
                    "Erro HTTP buscando áudio",
                    e
                )
            }

        } finally {

            connection?.disconnect()
        }
    }

    private fun processarResposta(
        text: String
    ) {

        val root =
            JSONObject(text)

        if (!root.optBoolean(
                "ok",
                true
            )
        ) {

            Log.w(
                TAG,
                "Backend informou ok=false"
            )

            return
        }

        val chunks =
            root.optJSONArray(
                "chunks"
            )
                ?: return

        if (chunks.length() == 0) {
            return
        }

        var novos = 0

        for (
            i in 0 until chunks.length()
        ) {

            val chunk =
                chunks.optJSONObject(i)
                    ?: continue

            val seq =
                chunk.optLong(
                    "seq",
                    -1
                )

            if (seq < 0) {
                continue
            }

            if (seq <= lastOutputSeq) {
                continue
            }

            val audioBase64 =
                chunk.optString(
                    "audio",
                    ""
                )

            if (audioBase64.isBlank()) {
                continue
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
                        "Erro decodificando Base64 seq=$seq",
                        e
                    )

                    continue
                }

            if (audio.isEmpty()) {
                continue
            }

            adicionarAudioNaFila(
                audio
            )

            if (
                seq >
                lastOutputSeq
            ) {

                lastOutputSeq =
                    seq
            }

            novos++
        }

        if (novos > 0) {

            Log.d(
                TAG,
                "Recebidos $novos chunks novos. " +
                        "seq=$lastOutputSeq " +
                        "fila=${getQueuedBytes()} bytes"
            )
        }
    }

    private fun adicionarAudioNaFila(
        audio: ByteArray
    ) {

        synchronized(lock) {

            if (!running.get()) {
                return
            }

            val novoTotal =
                queuedBytes +
                        audio.size

            if (
                novoTotal >
                MAX_QUEUE_BYTES
            ) {

                Log.w(
                    TAG,
                    "Fila cheia. " +
                            "Descartando áudio antigo."
                )

                while (
                    queuedBytes + audio.size >
                    MAX_QUEUE_BYTES
                ) {

                    val antigo =
                        audioQueue.poll()
                            ?: break

                    queuedBytes -=
                        antigo.size.toLong()

                    if (queuedBytes < 0) {
                        queuedBytes = 0
                    }
                }
            }

            audioQueue.offer(
                audio
            )

            queuedBytes +=
                audio.size.toLong()
        }
    }

    private fun getQueuedBytes(): Long {

        synchronized(lock) {
            return queuedBytes
        }
    }
}
