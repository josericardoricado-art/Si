package com.si.tradutor

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

class GeminiAudioPlayer(
    private val context: Context,
    private val backendUrl: String
) {

    companion object {
        private const val TAG = "SI_GEMINI_PLAYER"

        private const val SAMPLE_RATE = 24000
        private const val CHANNEL =
            AudioFormat.CHANNEL_OUT_MONO
        private const val FORMAT =
            AudioFormat.ENCODING_PCM_16BIT

        /*
         * O player espera acumular áudio antes
         * de começar. Isso evita cortes.
         */
        private const val PREBUFFER_BYTES = 288000

        /*
         * Aproximadamente 6 segundos de PCM.
         */
        private const val MAX_QUEUE_BYTES =
            10L * 1024L * 1024L

        private const val POLL_MS = 80L

        private const val HTTP_LIMIT = 30
    }

    private val running =
        AtomicBoolean(false)

    private val audioQueue =
        LinkedBlockingQueue<ByteArray>(500)

    private var queueBytes = 0L

    private var audioTrack: AudioTrack? = null

    private var pollingThread: Thread? = null

    private var playbackThread: Thread? = null

    @Volatile
    private var jobId: String? = null

    @Volatile
    private var lastSeq = 0L

    @Volatile
    private var prebufferReady = false

    @Volatile
    private var volume = 1.0f

    fun start(jobId: String) {

        stop()

        this.jobId = jobId
        this.lastSeq = 0L
        this.prebufferReady = false

        synchronized(audioQueue) {
            audioQueue.clear()
            queueBytes = 0L
        }

        running.set(true)

        criarAudioTrack()

        iniciarPolling()

        iniciarPlayback()

        Log.d(
            TAG,
            "GeminiAudioPlayer iniciado jobId=$jobId"
        )
    }

    fun stop() {

        running.set(false)

        pollingThread?.interrupt()
        playbackThread?.interrupt()

        pollingThread = null
        playbackThread = null

        synchronized(audioQueue) {
            audioQueue.clear()
            queueBytes = 0L
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
            audioTrack?.release()
        } catch (_: Exception) {
        }

        audioTrack = null

        jobId = null
        lastSeq = 0L
        prebufferReady = false

        Log.d(
            TAG,
            "GeminiAudioPlayer parado"
        )
    }

    fun setVolume(value: Float) {

        volume =
            value.coerceIn(
                0.0f,
                1.0f
            )

        try {
            audioTrack?.setVolume(volume)
        } catch (_: Exception) {
        }

        Log.d(
            TAG,
            "Volume dublagem=$volume"
        )
    }

    fun getVolume(): Float {
        return volume
    }

    private fun criarAudioTrack() {

        try {

            val minBuffer =
                AudioTrack.getMinBufferSize(
                    SAMPLE_RATE,
                    CHANNEL,
                    FORMAT
                )

            val bufferSize =
                maxOf(
                    minBuffer * 4,
                    192000
                )

            val track =
                AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(
                                AudioAttributes.USAGE_MEDIA
                            )
                            .setContentType(
                                AudioAttributes.CONTENT_TYPE_SPEECH
                            )
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(FORMAT)
                            .setSampleRate(
                                SAMPLE_RATE
                            )
                            .setChannelMask(
                                CHANNEL
                            )
                            .build()
                    )
                    .setBufferSizeInBytes(
                        bufferSize
                    )
                    .setTransferMode(
                        AudioTrack.MODE_STREAM
                    )
                    .build()

            if (
                track.state !=
                AudioTrack.STATE_INITIALIZED
            ) {

                Log.e(
                    TAG,
                    "AudioTrack não inicializou"
                )

                track.release()

                return
            }

            track.setVolume(volume)

            audioTrack = track

            Log.d(
                TAG,
                "AudioTrack Gemini pronto"
            )

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Erro criando AudioTrack",
                e
            )
        }
    }

    private fun iniciarPolling() {

        pollingThread =
            Thread {

                Log.d(
                    TAG,
                    "Polling Gemini iniciado"
                )

                while (
                    running.get()
                ) {

                    try {

                        buscarAudio()

                        Thread.sleep(
                            POLL_MS
                        )

                    } catch (
                        _: InterruptedException
                    ) {

                        break

                    } catch (
                        e: Exception
                    ) {

                        Log.w(
                            TAG,
                            "Erro polling Gemini",
                            e
                        )

                        try {
                            Thread.sleep(300L)
                        } catch (_: Exception) {
                        }
                    }
                }

                Log.d(
                    TAG,
                    "Polling Gemini finalizado"
                )
            }

        pollingThread?.start()
    }

    private fun buscarAudio() {

        val id =
            jobId ?: return

        var connection:
            HttpURLConnection? =
            null

        try {

            val url =
                URL(
                    "$backendUrl/api/audio/output/$id" +
                        "?after=$lastSeq" +
                        "&limit=$HTTP_LIMIT"
                )

            connection =
                url.openConnection()
                    as HttpURLConnection

            connection.requestMethod =
                "GET"

            connection.connectTimeout =
                10000

            connection.readTimeout =
                10000

            connection.useCaches =
                false

            connection.setRequestProperty(
                "Connection",
                "close"
            )

            if (
                connection.responseCode !=
                200
            ) {
                return
            }

            val resposta =
                BufferedReader(
                    InputStreamReader(
                        connection.inputStream
                    )
                ).use {
                    it.readText()
                }

            if (
                resposta.isNotBlank()
            ) {

                processarResposta(
                    resposta
                )
            }

        } catch (e: Exception) {

            if (
                running.get()
            ) {

                Log.w(
                    TAG,
                    "Falha buscando áudio",
                    e
                )
            }

        } finally {

            try {
                connection?.disconnect()
            } catch (_: Exception) {
            }
        }
    }

    private fun processarResposta(
        resposta: String
    ) {

        try {

            val root =
                JSONObject(
                    resposta
                )

            val chunks =
                root.optJSONArray(
                    "chunks"
                )

            if (
                chunks != null
            ) {

                for (
                    i in 0 until chunks.length()
                ) {

                    val item =
                        chunks.optJSONObject(
                            i
                        )
                            ?: continue

                    val seq =
                        item.optLong(
                            "seq",
                            -1L
                        )

                    if (
                        seq <= lastSeq
                    ) {
                        continue
                    }

                    val base64 =
                        item.optString(
                            "audio",
                            ""
                        )

                    if (
                        base64.isBlank()
                    ) {
                        continue
                    }

                    val audio =
                        decodificar(
                            base64
                        )

                    if (
                        audio.isEmpty()
                    ) {
                        continue
                    }

                    adicionar(
                        audio
                    )

                    lastSeq = seq
                }

                return
            }

            val base64 =
                root.optString(
                    "audio",
                    ""
                )

            if (
                base64.isNotBlank()
            ) {

                val audio =
                    decodificar(
                        base64
                    )

                if (
                    audio.isNotEmpty()
                ) {
                    adicionar(audio)
                }
            }

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Erro processando resposta Gemini",
                e
            )
        }
    }

    private fun decodificar(
        textoOriginal: String
    ): ByteArray {

        return try {

            var texto =
                textoOriginal.trim()

            if (
                texto.startsWith(
                    "data:"
                )
            ) {

                val virgula =
                    texto.indexOf(",")

                if (
                    virgula >= 0
                ) {

                    texto =
                        texto.substring(
                            virgula + 1
                        )
                }
            }

            Base64.decode(
                texto,
                Base64.DEFAULT
            )

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Erro Base64",
                e
            )

            ByteArray(0)
        }
    }

    private fun adicionar(
        audio: ByteArray
    ) {

        if (
            !running.get() ||
            audio.isEmpty()
        ) {
            return
        }

        synchronized(audioQueue) {

            while (
                queueBytes + audio.size >
                MAX_QUEUE_BYTES
            ) {

                val antigo =
                    audioQueue.poll()
                        ?: break

                queueBytes =
                    (
                        queueBytes -
                            antigo.size
                        )
                        .coerceAtLeast(
                            0L
                        )
            }

            if (
                audioQueue.offer(
                    audio
                )
            ) {

                queueBytes +=
                    audio.size.toLong()

                if (
                    !prebufferReady &&
                    queueBytes >=
                    PREBUFFER_BYTES
                ) {

                    prebufferReady =
                        true

                    Log.d(
                        TAG,
                        "PRÉ-BUFFER PRONTO " +
                            "bytes=$queueBytes"
                    )
                }
            }
        }
    }

    private fun iniciarPlayback() {

        playbackThread =
            Thread {

                Log.d(
                    TAG,
                    "Playback Gemini iniciado"
                )

                /*
                 * Espera o buffer ficar cheio.
                 */
                while (
                    running.get() &&
                    !prebufferReady
                ) {

                    try {
                        Thread.sleep(20L)
                    } catch (
                        _: InterruptedException
                    ) {
                        return@Thread
                    }
                }

                if (
                    !running.get()
                ) {
                    return@Thread
                }

                val track =
                    audioTrack
                        ?: return@Thread

                try {

                    track.play()

                    Log.d(
                        TAG,
                        "AudioTrack PLAY"
                    )

                    while (
                        running.get()
                    ) {

                        val audio =
                            audioQueue.poll(
                                200L,
                                java.util.concurrent.TimeUnit.MILLISECONDS
                            )

                        if (
                            audio == null
                        ) {
                            continue
                        }

                        synchronized(audioQueue) {
                            queueBytes =
                                (
                                    queueBytes -
                                        audio.size
                                    )
                                    .coerceAtLeast(
                                        0L
                                    )
                        }

                        escreverContinuamente(
                            track,
                            audio
                        )
                    }

                } catch (
                    _: InterruptedException
                ) {

                    return@Thread

                } catch (
                    e: Exception
                ) {

                    Log.e(
                        TAG,
                        "Erro playback Gemini",
                        e
                    )
                }

                Log.d(
                    TAG,
                    "Playback Gemini finalizado"
                )
            }

        playbackThread?.start()
    }

    private fun escreverContinuamente(
        track: AudioTrack,
        dados: ByteArray
    ) {

        var offset = 0

        while (
            offset < dados.size &&
            running.get()
        ) {

            val restante =
                dados.size -
                    offset

            val quantidade =
                minOf(
                    restante,
                    48000
                )

            val escritos =
                track.write(
                    dados,
                    offset,
                    quantidade,
                    AudioTrack.WRITE_BLOCKING
                )

            if (
                escritos <= 0
            ) {

                Log.w(
                    TAG,
                    "AudioTrack.write=$escritos"
                )

                break
            }

            offset +=
                escritos
        }
    }
}
