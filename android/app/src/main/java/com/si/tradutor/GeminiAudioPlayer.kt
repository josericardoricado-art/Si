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
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class GeminiAudioPlayer(
    private val context: Context,
    private val backendUrl: String
) {

    companion object {

        private const val TAG =
            "SI_GEMINI_AUDIO_PLAYER"

        private const val SAMPLE_RATE =
            24000

        private const val CHANNEL =
            AudioFormat.CHANNEL_OUT_MONO

        private const val FORMAT =
            AudioFormat.ENCODING_PCM_16BIT

        /*
         * 24 kHz / mono / PCM16
         *
         * 48.000 bytes = 1 segundo
         * 192.000 bytes = 4 segundos
         */
        private const val BYTES_PER_SECOND =
            SAMPLE_RATE * 2

        /*
         * Espera aproximadamente 6 segundos
         * antes de começar a reprodução.
         */
        private const val PREBUFFER_BYTES =
            BYTES_PER_SECOND * 6

        /*
         * Limite máximo da fila:
         * aproximadamente 20 segundos.
         */
        private const val MAX_QUEUE_BYTES =
            BYTES_PER_SECOND * 20

        /*
         * Busca novos blocos rapidamente.
         */
        private const val POLL_MS =
            60L

        private const val HTTP_LIMIT =
            30

        /*
         * Cada write envia no máximo 1 segundo.
         */
        private const val WRITE_BLOCK_BYTES =
            BYTES_PER_SECOND
    }

    private val running =
        AtomicBoolean(false)

    /*
     * Fila exclusiva da voz traduzida.
     */
    private val audioQueue =
        LinkedBlockingQueue<ByteArray>(500)

    private var queueBytes =
        0L

    private var audioTrack:
        AudioTrack? = null

    private var pollingThread:
        Thread? = null

    private var playbackThread:
        Thread? = null

    @Volatile
    private var jobId:
        String? = null

    @Volatile
    private var lastSeq =
        0L

    @Volatile
    private var prebufferReady =
        false

    @Volatile
    private var volume =
        1.0f

    fun start(
        jobId: String
    ) {

        stop()

        this.jobId =
            jobId

        lastSeq =
            0L

        prebufferReady =
            false

        synchronized(audioQueue) {

            audioQueue.clear()

            queueBytes =
                0L
        }

        running.set(true)

        criarAudioTrack()

        iniciarPolling()

        iniciarPlayback()

        Log.d(
            TAG,
            "PLAYER START job=$jobId"
        )
    }

    fun stop() {

        running.set(false)

        try {
            pollingThread?.interrupt()
        } catch (_: Exception) {
        }

        try {
            playbackThread?.interrupt()
        } catch (_: Exception) {
        }

        pollingThread =
            null

        playbackThread =
            null

        synchronized(audioQueue) {

            audioQueue.clear()

            queueBytes =
                0L
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

        audioTrack =
            null

        jobId =
            null

        lastSeq =
            0L

        prebufferReady =
            false

        Log.d(
            TAG,
            "PLAYER STOP"
        )
    }

    fun setVolume(
        value: Float
    ) {

        volume =
            value.coerceIn(
                0.0f,
                1.0f
            )

        try {

            audioTrack?.setVolume(
                volume
            )

        } catch (_: Exception) {
        }

        Log.d(
            TAG,
            "PLAYER VOLUME=$volume"
        )
    }

    fun getVolume():
        Float {

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
                    minBuffer * 8,
                    BYTES_PER_SECOND * 4
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
                            .setEncoding(
                                FORMAT
                            )
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
                    "AudioTrack não inicializado"
                )

                track.release()

                return
            }

            track.setVolume(
                volume
            )

            audioTrack =
                track

            Log.d(
                TAG,
                "AudioTrack pronto " +
                    "buffer=$bufferSize"
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
                    "Polling iniciado"
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

                        if (
                            running.get()
                        ) {

                            Log.w(
                                TAG,
                                "Erro polling",
                                e
                            )
                        }

                        try {
                            Thread.sleep(
                                250L
                            )
                        } catch (_: Exception) {
                        }
                    }
                }

                Log.d(
                    TAG,
                    "Polling finalizado"
                )
            }

        pollingThread?.start()
    }

    private fun buscarAudio() {

        val id =
            jobId
                ?: return

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
                5000

            connection.readTimeout =
                5000

            connection.useCaches =
                false

            connection.setRequestProperty(
                "Connection",
                "close"
            )

            if (
                connection.responseCode !=
                HttpURLConnection.HTTP_OK
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
                    "Erro buscando áudio",
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

                    adicionarAudio(
                        audio
                    )

                    lastSeq =
                        seq
                }

                return
            }

            /*
             * Compatibilidade com resposta
             * contendo apenas "audio".
             */
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

                    adicionarAudio(
                        audio
                    )
                }
            }

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Erro JSON do áudio",
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

                val comma =
                    texto.indexOf(",")

                if (
                    comma >= 0
                ) {

                    texto =
                        texto.substring(
                            comma + 1
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
                "Erro decodificando Base64",
                e
            )

            ByteArray(0)
        }
    }

    private fun adicionarAudio(
        audio: ByteArray
    ) {

        if (
            !running.get() ||
            audio.isEmpty()
        ) {
            return
        }

        synchronized(audioQueue) {

            /*
             * Se a fila estiver muito cheia,
             * descarta os blocos mais antigos
             * para evitar memória excessiva.
             */
            while (
                queueBytes +
                    audio.size >
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
                        "PREBUFFER OK " +
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
                    "Playback aguardando buffer"
                )

                /*
                 * Não começa com poucos bytes.
                 */
                while (
                    running.get() &&
                    !prebufferReady
                ) {

                    try {

                        Thread.sleep(
                            20L
                        )

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

                    /*
                     * COMEÇA UMA ÚNICA VEZ.
                     *
                     * O AudioTrack permanece em PLAY
                     * durante toda a sessão.
                     */
                    track.play()

                    Log.d(
                        TAG,
                        "AudioTrack PLAY CONTÍNUO"
                    )

                    while (
                        running.get()
                    ) {

                        /*
                         * Retira um bloco da fila.
                         */
                        val audio =
                            audioQueue.poll(
                                300L,
                                TimeUnit.MILLISECONDS
                            )

                        if (
                            audio == null
                        ) {

                            /*
                             * Não para o AudioTrack.
                             * Apenas continua esperando.
                             */
                            continue
                        }

                        synchronized(
                            audioQueue
                        ) {

                            queueBytes =
                                (
                                    queueBytes -
                                        audio.size
                                    )
                                    .coerceAtLeast(
                                        0L
                                    )
                        }

                        escreverAudio(
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
                        "Erro playback",
                        e
                    )
                }

                Log.d(
                    TAG,
                    "Playback finalizado"
                )
            }

        playbackThread?.start()
    }

    private fun escreverAudio(
        track: AudioTrack,
        audio: ByteArray
    ) {

        var offset =
            0

        while (
            offset < audio.size &&
            running.get()
        ) {

            val restante =
                audio.size -
                    offset

            val tamanho =
                minOf(
                    restante,
                    WRITE_BLOCK_BYTES
                )

            val escritos =
                track.write(
                    audio,
                    offset,
                    tamanho,
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
