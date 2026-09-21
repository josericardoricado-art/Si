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

        // Gemini Live:
        // PCM 16-bit / mono / 24 kHz
        private const val SAMPLE_RATE = 24000
        private const val CHANNEL_MASK =
            AudioFormat.CHANNEL_OUT_MONO
        private const val ENCODING =
            AudioFormat.ENCODING_PCM_16BIT

        // 150 ms entre consultas
        private const val POLL_INTERVAL_MS = 150L

        // Aproximadamente 300 ms antes de iniciar/retomar
        private const val PREBUFFER_BYTES =
            14400

        // Aproximadamente 200 ms por escrita
        private const val WRITE_CHUNK_BYTES =
            9600

        // Máximo de áudio guardado na memória
        private const val MAX_QUEUE_BYTES =
            10 * 1024 * 1024

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

    private var playbackStarted = false

    private val lock = Any()


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

        lastOutputSeq = 0L

        playbackStarted = false

        running.set(true)

        Log.d(
            TAG,
            "Iniciando player. jobId=$jobId"
        )

        criarAudioTrack()

        iniciarPlaybackThread()

        iniciarPollThread()
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
            "Parando player"
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

        lastOutputSeq = 0L
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
    // PLAYBACK THREAD
    // ============================================================

    private fun iniciarPlaybackThread() {

        playbackThread =
            Thread {

                Log.d(
                    TAG,
                    "Playback thread iniciado"
                )

                while (running.get()) {

                    try {

                        // ------------------------------------------------
                        // PRÉ-BUFFER
                        // ------------------------------------------------

                        if (!playbackStarted) {

                            while (
                                running.get() &&
                                getQueuedBytes() <
                                PREBUFFER_BYTES
                            ) {

                                Thread.sleep(20)
                            }

                            if (!running.get()) {
                                break
                            }

                            val track =
                                audioTrack

                            if (track != null) {

                                try {

                                    track.play()

                                    playbackStarted =
                                        true

                                    Log.d(
                                        TAG,
                                        "Playback iniciado com " +
                                                "${getQueuedBytes()} bytes"
                                    )

                                } catch (e: Exception) {

                                    Log.e(
                                        TAG,
                                        "Erro ao iniciar AudioTrack",
                                        e
                                    )

                                    Thread.sleep(100)

                                    continue
                                }
                            }
                        }


                        // ------------------------------------------------
                        // PEGA O PRÓXIMO BLOCO
                        // ------------------------------------------------

                        val data =
                            audioQueue.take()

                        synchronized(lock) {

                            queuedBytes -=
                                data.size.toLong()

                            if (queuedBytes < 0) {
                                queuedBytes = 0
                            }
                        }


                        // ------------------------------------------------
                        // TOCA
                        // ------------------------------------------------

                        tocarAudio(data)


                        // ------------------------------------------------
                        // SE A FILA FICAR MUITO VAZIA,
                        // NÃO REINICIA O AUDIO TRACK.
                        // ESPERA NOVOS DADOS.
                        // ------------------------------------------------

                        if (
                            getQueuedBytes() <
                            PREBUFFER_BYTES
                        ) {

                            Log.d(
                                TAG,
                                "Fila baixa: " +
                                        "${getQueuedBytes()} bytes"
                            )
                        }

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
                    "Erro escrevendo PCM",
                    e
                )

                break
            }
        }
    }


    // ============================================================
    // POLLING
    // ============================================================

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
                            "Erro buscando áudio",
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


    // ============================================================
    // BUSCAR AUDIO DO RENDER
    // ============================================================

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
                    "Render HTTP $code"
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

            processarResposta(text)

        } catch (e: Exception) {

            if (running.get()) {

                Log.e(
                    TAG,
                    "Erro HTTP",
                    e
                )
            }

        } finally {

            connection?.disconnect()
        }
    }


    // ============================================================
    // PROCESSAR JSON
    // ============================================================

    private fun processarResposta(
        text: String
    ) {

        val root =
            JSONObject(text)

        if (
            !root.optBoolean(
                "ok",
                true
            )
        ) {

            Log.w(
                TAG,
                "Backend retornou ok=false"
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

            if (
                seq <=
                lastOutputSeq
            ) {
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
                        "Erro Base64 seq=$seq",
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
                "Novos chunks=$novos " +
                        "seq=$lastOutputSeq " +
                        "fila=${getQueuedBytes()} bytes"
            )
        }
    }


    // ============================================================
    // FILA
    // ============================================================

    private fun adicionarAudioNaFila(
        audio: ByteArray
    ) {

        synchronized(lock) {

            if (!running.get()) {
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

                if (queuedBytes < 0) {
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
