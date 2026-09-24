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
import android.media.AudioManager
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.projection.MediaProjection
import android.os.Build
import android.os.IBinder
import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit


class AudioCaptureService : Service() {

    companion object {

        private const val TAG = "SI_AUDIO"

        // =========================================================
        // BACKEND
        // =========================================================

        private const val BACKEND_URL =
            "https://si-u2ul.onrender.com"

        // =========================================================
        // ACTIONS
        // =========================================================

        const val ACTION_START =
            "com.si.tradutor.ACTION_START"

        const val ACTION_STOP =
            "com.si.tradutor.ACTION_STOP"

        // =========================================================
        // EXTRAS
        // =========================================================

        const val EXTRA_JOB_ID =
            "jobId"

        const val EXTRA_RESULT_CODE =
            "resultCode"

        const val EXTRA_RESULT_DATA =
            "resultData"

        // =========================================================
        // NOTIFICAÇÃO
        // =========================================================

        private const val CHANNEL_ID =
            "si_audio_capture"

        private const val NOTIFICATION_ID =
            1001

        // =========================================================
        // ÁUDIO DE ENTRADA
        // =========================================================

        private const val INPUT_SAMPLE_RATE =
            16000

        private const val INPUT_CHANNEL =
            AudioFormat.CHANNEL_IN_MONO

        private const val INPUT_FORMAT =
            AudioFormat.ENCODING_PCM_16BIT

        /*
         * 100 ms de PCM16 mono em 16 kHz:
         *
         * 16000 x 0,1 x 2 = 3200 bytes
         */
        private const val INPUT_CHUNK_SIZE =
            3200

        /*
         * Fila de captura.
         *
         * Se a internet ficar alguns segundos mais lenta,
         * não perdemos imediatamente o áudio.
         */
        private const val INPUT_QUEUE_CAPACITY =
            120

        // =========================================================
        // ÁUDIO DE SAÍDA
        // =========================================================

        private const val OUTPUT_CHANNEL =
            AudioFormat.CHANNEL_OUT_MONO

        private const val OUTPUT_FORMAT =
            AudioFormat.ENCODING_PCM_16BIT

        /*
         * Quantos arquivos podem ficar esperando reprodução.
         */
        private const val OUTPUT_QUEUE_CAPACITY =
            80

        /*
         * Intervalo de consulta ao backend.
         */
        private const val STATUS_POLL_MS =
            180L
    }

    // =============================================================
    // MEDIA PROJECTION
    // =============================================================

    private var mediaProjection: MediaProjection? =
        null

    private var projectionCallback:
        MediaProjection.Callback? = null

    // =============================================================
    // AUDIO RECORD
    // =============================================================

    private var audioRecord: AudioRecord? =
        null

    // =============================================================
    // AUDIO TRACK
    // =============================================================

    private var audioTrack: AudioTrack? =
        null

    private var outputSampleRate =
        22050

    // =============================================================
    // ESTADO
    // =============================================================

    @Volatile
    private var running =
        false

    @Volatile
    private var captureStarted =
        false

    @Volatile
    private var stopping =
        false

    // =============================================================
    // JOB
    // =============================================================

    @Volatile
    private var jobId =
        ""

    // =============================================================
    // FILA DE ENTRADA
    // =============================================================

    private val inputQueue =
        LinkedBlockingQueue<ByteArray>(
            INPUT_QUEUE_CAPACITY
        )

    // =============================================================
    // FILA DE SAÍDA
    // =============================================================

    private data class OutputAudio(
        val audioId: String,
        val pcmData: ByteArray,
        val sampleRate: Int
    )

    private val outputQueue =
        LinkedBlockingQueue<OutputAudio>(
            OUTPUT_QUEUE_CAPACITY
        )

    /*
     * IDs que já foram baixados e colocados na fila local.
     *
     * Isso evita baixar duas vezes o mesmo WAV enquanto
     * o backend ainda está mostrando o item.
     */
    private val downloadedAudioIds =
        HashSet<String>()

    private val downloadedAudioLock =
        Any()

    // =============================================================
    // THREADS
    // =============================================================

    private var captureThread:
        Thread? = null

    private var sendThread:
        Thread? = null

    private var outputDownloadThread:
        Thread? = null

    private var playbackThread:
        Thread? = null

    // =============================================================
    // DIAGNÓSTICO
    // =============================================================

    @Volatile
    private var capturedChunks =
        0L

    @Volatile
    private var sentChunks =
        0L

    @Volatile
    private var playedAudios =
        0L

    @Volatile
    private var lastError:
        String? = null


    // =============================================================
    // CREATE
    // =============================================================

    override fun onCreate() {

        super.onCreate()

        createNotificationChannel()

        Log.d(
            TAG,
            "======================================"
        )

        Log.d(
            TAG,
            "SI Tradutor Live - Piper Player"
        )

        Log.d(
            TAG,
            "Backend: $BACKEND_URL"
        )

        Log.d(
            TAG,
            "STT: Deepgram"
        )

        Log.d(
            TAG,
            "Translation: DeepL Text"
        )

        Log.d(
            TAG,
            "TTS: Piper"
        )

        Log.d(
            TAG,
            "======================================"
        )
    }


    // =============================================================
    // START COMMAND
    // =============================================================

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        Log.d(
            TAG,
            "onStartCommand action=${intent?.action}"
        )

        if (
            intent?.action ==
            ACTION_STOP
        ) {

            Log.d(
                TAG,
                "ACTION_STOP"
            )

            stopCapture(
                sendServerStop = true
            )

            return START_NOT_STICKY
        }

        if (
            intent?.action !=
            ACTION_START
        ) {

            return START_NOT_STICKY
        }

        if (running) {

            Log.d(
                TAG,
                "Serviço já está rodando"
            )

            return START_STICKY
        }

        jobId =
            intent.getStringExtra(
                EXTRA_JOB_ID
            ) ?: ""

        if (jobId.isEmpty()) {

            Log.e(
                TAG,
                "jobId vazio"
            )

            stopSelf()

            return START_NOT_STICKY
        }

        val resultCode =
            intent.getIntExtra(
                EXTRA_RESULT_CODE,
                Activity.RESULT_CANCELED
            )

        val resultData =
            if (
                Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.TIRAMISU
            ) {

                intent.getParcelableExtra(
                    EXTRA_RESULT_DATA,
                    Intent::class.java
                )

            } else {

                @Suppress("DEPRECATION")
                intent.getParcelableExtra(
                    EXTRA_RESULT_DATA
                )
            }

        if (
            resultCode !=
            Activity.RESULT_OK ||
            resultData == null
        ) {

            Log.e(
                TAG,
                "MediaProjection inválido"
            )

            stopSelf()

            return START_NOT_STICKY
        }

        try {

            startForegroundServiceNotification()

            iniciarMediaProjection(
                resultCode,
                resultData
            )

            running = true
            stopping = false

            limparFilas()

            iniciarCaptura()

            iniciarEnvio()

            iniciarDownloadDeAudio()

            iniciarReproducao()

            Log.d(
                TAG,
                "Serviço iniciado. jobId=$jobId"
            )

        } catch (e: Exception) {

            lastError =
                e.message

            Log.e(
                TAG,
                "Erro iniciando serviço",
                e
            )

            stopCapture(
                sendServerStop = false
            )
        }

        return START_STICKY
    }


    // =============================================================
    // MEDIA PROJECTION
    // =============================================================

    private fun iniciarMediaProjection(
        resultCode: Int,
        resultData: Intent
    ) {

        val manager =
            getSystemService(
                Context.MEDIA_PROJECTION_SERVICE
            ) as android.media.projection.MediaProjectionManager

        mediaProjection =
            manager.getMediaProjection(
                resultCode,
                resultData
            )

        if (
            mediaProjection == null
        ) {

            throw IllegalStateException(
                "Não foi possível criar MediaProjection"
            )
        }

        projectionCallback =
            object :
                MediaProjection.Callback() {

                override fun onStop() {

                    Log.d(
                        TAG,
                        "MediaProjection foi encerrado"
                    )

                    if (running) {

                        stopCapture(
                            sendServerStop = true
                        )
                    }
                }
            }

        mediaProjection?.registerCallback(
            projectionCallback!!,
            null
        )
    }


    // =============================================================
    // CAPTURA DO ÁUDIO INTERNO
    // =============================================================

    private fun iniciarCaptura() {

        if (
            mediaProjection == null
        ) {

            throw IllegalStateException(
                "MediaProjection não disponível"
            )
        }

        val minBuffer =
            AudioRecord.getMinBufferSize(
                INPUT_SAMPLE_RATE,
                INPUT_CHANNEL,
                INPUT_FORMAT
            )

        if (
            minBuffer <= 0
        ) {

            throw IllegalStateException(
                "AudioRecord.getMinBufferSize falhou"
            )
        }

        val bufferSize =
            maxOf(
                minBuffer * 2,
                INPUT_CHUNK_SIZE * 4
            )

        val config =
            AudioPlaybackCaptureConfiguration
                .Builder(
                    mediaProjection!!
                )
                .addMatchingUsage(
                    android.media.AudioAttributes.USAGE_MEDIA
                )
                .addMatchingUsage(
                    android.media.AudioAttributes.USAGE_GAME
                )
                .build()

        audioRecord =
            if (
                Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.Q
            ) {

                AudioRecord.Builder()
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(
                                INPUT_FORMAT
                            )
                            .setSampleRate(
                                INPUT_SAMPLE_RATE
                            )
                            .setChannelMask(
                                INPUT_CHANNEL
                            )
                            .build()
                    )
                    .setBufferSizeInBytes(
                        bufferSize
                    )
                    .setAudioPlaybackCaptureConfig(
                        config
                    )
                    .build()

            } else {

                throw IllegalStateException(
                    "Android abaixo do 10 não suporta captura interna"
                )
            }

        if (
            audioRecord?.state !=
            AudioRecord.STATE_INITIALIZED
        ) {

            throw IllegalStateException(
                "AudioRecord não foi inicializado"
            )
        }

        captureThread =
            Thread {

                val buffer =
                    ByteArray(
                        INPUT_CHUNK_SIZE
                    )

                try {

                    audioRecord?.startRecording()

                    captureStarted = true

                    Log.d(
                        TAG,
                        "CAPTURA iniciada"
                    )

                    while (
                        running &&
                        !stopping
                    ) {

                        val read =
                            audioRecord?.read(
                                buffer,
                                0,
                                buffer.size,
                                AudioRecord.READ_BLOCKING
                            ) ?: -1

                        if (
                            read <= 0
                        ) {

                            if (
                                read ==
                                AudioRecord.ERROR_DEAD_OBJECT
                            ) {

                                Log.e(
                                    TAG,
                                    "AudioRecord DEAD_OBJECT"
                                )

                                break
                            }

                            continue
                        }

                        val chunk =
                            buffer.copyOf(
                                read
                            )

                        /*
                         * Se a fila estiver cheia,
                         * descartamos o pedaço mais antigo.
                         *
                         * Isso é melhor do que deixar o
                         * aplicativo acumular vários segundos
                         * de atraso.
                         */
                        if (
                            !inputQueue.offer(
                                chunk
                            )
                        ) {

                            inputQueue.poll()

                            inputQueue.offer(
                                chunk
                            )

                            Log.w(
                                TAG,
                                "Fila de entrada cheia"
                            )
                        }

                        capturedChunks++

                        if (
                            capturedChunks <= 5 ||
                            capturedChunks % 50L == 0L
                        ) {

                            Log.d(
                                TAG,
                                "CAPTURA chunks=$capturedChunks fila=${inputQueue.size}"
                            )
                        }
                    }

                } catch (
                    e: InterruptedException
                ) {

                    Log.d(
                        TAG,
                        "Thread de captura interrompida"
                    )

                } catch (
                    e: Exception
                ) {

                    lastError =
                        e.message

                    Log.e(
                        TAG,
                        "Erro na captura",
                        e
                    )

                } finally {

                    try {
                        audioRecord?.stop()
                    } catch (_: Exception) {
                    }

                    captureStarted =
                        false
                }
            }

        captureThread?.start()
    }


    // =============================================================
    // THREAD DE ENVIO
    // =============================================================

    private fun iniciarEnvio() {

        sendThread =
            Thread {

                Log.d(
                    TAG,
                    "THREAD ENVIO iniciada"
                )

                while (
                    running &&
                    !stopping
                ) {

                    try {

                        val chunk =
                            inputQueue.poll(
                                500,
                                TimeUnit.MILLISECONDS
                            )

                        if (
                            chunk == null
                        ) {

                            continue
                        }

                        enviarAudio(
                            chunk
                        )

                    } catch (
                        e: InterruptedException
                    ) {

                        break

                    } catch (
                        e: Exception
                    ) {

                        lastError =
                            e.message

                        Log.e(
                            TAG,
                            "Erro no envio",
                            e
                        )
                    }
                }

                Log.d(
                    TAG,
                    "THREAD ENVIO finalizada"
                )
            }

        sendThread?.start()
    }


    // =============================================================
    // ENVIA PCM PARA O RENDER
    // =============================================================

    private fun enviarAudio(
        audio: ByteArray
    ) {

        if (
            jobId.isEmpty() ||
            audio.isEmpty()
        ) {

            return
        }

        var connection:
            HttpURLConnection? =
            null

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
                10000

            connection.readTimeout =
                10000

            connection.doOutput =
                true

            connection.useCaches =
                false

            connection.setRequestProperty(
                "Content-Type",
                "application/json; charset=UTF-8"
            )

            val base64 =
                Base64.encodeToString(
                    audio,
                    Base64.NO_WRAP
                )

            val json =
                JSONObject()

            json.put(
                "jobId",
                jobId
            )

            json.put(
                "audio",
                base64
            )

            json.put(
                "mimeType",
                "audio/pcm"
            )

            json.put(
                "sampleRate",
                INPUT_SAMPLE_RATE
            )

            val body =
                json.toString()
                    .toByteArray(
                        Charsets.UTF_8
                    )

            connection.outputStream.use {
                it.write(body)
                it.flush()
            }

            val code =
                connection.responseCode

            if (
                code in 200..299
            ) {

                sentChunks++

                if (
                    sentChunks <= 5 ||
                    sentChunks % 50L == 0L
                ) {

                    Log.d(
                        TAG,
                        "ENVIO chunks=$sentChunks"
                    )
                }

            } else {

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
                    "Render HTTP $code $errorText"
                )
            }

        } catch (e: Exception) {

            lastError =
                e.message

            Log.e(
                TAG,
                "Erro HTTP enviando áudio",
                e
            )

        } finally {

            connection?.disconnect()
        }
    }


    // =============================================================
    // DOWNLOAD DOS WAVs PIPER
    // =============================================================

    private fun iniciarDownloadDeAudio() {

        outputDownloadThread =
            Thread {

                Log.d(
                    TAG,
                    "THREAD DOWNLOAD PIPER iniciada"
                )

                while (
                    running &&
                    !stopping
                ) {

                    try {

                        consultarStatus()

                        Thread.sleep(
                            STATUS_POLL_MS
                        )

                    } catch (
                        e: InterruptedException
                    ) {

                        break

                    } catch (
                        e: Exception
                    ) {

                        lastError =
                            e.message

                        Log.e(
                            TAG,
                            "Erro consultando áudio",
                            e
                        )

                        try {
                            Thread.sleep(500)
                        } catch (_: Exception) {
                        }
                    }
                }

                Log.d(
                    TAG,
                    "THREAD DOWNLOAD finalizada"
                )
            }

        outputDownloadThread?.start()
    }


    // =============================================================
    // CONSULTAR STATUS
    // =============================================================

    private fun consultarStatus() {

        if (
            jobId.isEmpty()
        ) {

            return
        }

        var connection:
            HttpURLConnection? =
            null

        try {

            val url =
                URL(
                    "$BACKEND_URL/api/audio/status/$jobId"
                )

            connection =
                url.openConnection()
                    as HttpURLConnection

            connection.requestMethod =
                "GET"

            connection.connectTimeout =
                8000

            connection.readTimeout =
                8000

            connection.useCaches =
                false

            val code =
                connection.responseCode

            if (
                code !in 200..299
            ) {

                return
            }

            val responseText =
                connection.inputStream
                    .bufferedReader()
                    .use {
                        it.readText()
                    }

            val json =
                JSONObject(
                    responseText
                )

            if (
                !json.optBoolean(
                    "ok",
                    false
                )
            ) {

                return
            }

            val audioId =
                json.optString(
                    "audioId",
                    ""
                )

            var audioUrl =
                json.optString(
                    "audioUrl",
                    ""
                )

            if (
                audioId.isEmpty() ||
                audioUrl.isEmpty()
            ) {

                return
            }

            /*
             * O backend pode devolver:
             *
             * /api/audio/file/arquivo.wav
             *
             * ou uma URL absoluta.
             */
            if (
                audioUrl.startsWith("/")
            ) {

                audioUrl =
                    BACKEND_URL +
                    audioUrl
            }

            synchronized(
                downloadedAudioLock
            ) {

                if (
                    downloadedAudioIds.contains(
                        audioId
                    )
                ) {

                    return
                }

                /*
                 * Reservamos o ID imediatamente.
                 *
                 * Isso impede que duas consultas
                 * simultâneas baixem o mesmo WAV.
                 */
                downloadedAudioIds.add(
                    audioId
                )
            }

            Log.d(
                TAG,
                "Novo Piper WAV: $audioId"
            )

            val wav =
                baixarArquivo(
                    audioUrl
                )

            if (
                wav == null ||
                wav.isEmpty()
            ) {

                synchronized(
                    downloadedAudioLock
                ) {

                    downloadedAudioIds.remove(
                        audioId
                    )
                }

                return
            }

            val parsed =
                parseWav(
                    wav
                )

            if (
                parsed == null
            ) {

                synchronized(
                    downloadedAudioLock
                ) {

                    downloadedAudioIds.remove(
                        audioId
                    )
                }

                Log.e(
                    TAG,
                    "WAV Piper inválido: $audioId"
                )

                return
            }

            val output =
                OutputAudio(
                    audioId =
                        audioId,
                    pcmData =
                        parsed.first,
                    sampleRate =
                        parsed.second
                )

            /*
             * Colocamos o PCM na fila de reprodução.
             */
            if (
                !outputQueue.offer(
                    output,
                    2,
                    TimeUnit.SECONDS
                )
            ) {

                synchronized(
                    downloadedAudioLock
                ) {

                    downloadedAudioIds.remove(
                        audioId
                    )
                }

                Log.w(
                    TAG,
                    "Fila de saída cheia"
                )

                return
            }

            /*
             * O áudio já está salvo na fila local.
             *
             * Agora podemos liberar o item no Render.
             * Isso permite que o próximo WAV seja baixado
             * enquanto este ainda está tocando.
             */
            enviarAck(
                audioId
            )

            Log.d(
                TAG,
                "Piper WAV colocado na fila: " +
                    "id=$audioId " +
                    "samples=${parsed.first.size} " +
                    "rate=${parsed.second} " +
                    "fila=${outputQueue.size}"
            )

        } catch (e: Exception) {

            lastError =
                e.message

            Log.e(
                TAG,
                "Erro lendo status",
                e
            )

        } finally {

            connection?.disconnect()
        }
    }


    // =============================================================
    // BAIXAR WAV
    // =============================================================

    private fun baixarArquivo(
        urlString: String
    ): ByteArray? {

        var connection:
            HttpURLConnection? =
            null

        try {

            val url =
                URL(urlString)

            connection =
                url.openConnection()
                    as HttpURLConnection

            connection.requestMethod =
                "GET"

            connection.connectTimeout =
                10000

            connection.readTimeout =
                30000

            connection.useCaches =
                false

            val code =
                connection.responseCode

            if (
                code !in 200..299
            ) {

                Log.e(
                    TAG,
                    "Erro baixando WAV HTTP $code"
                )

                return null
            }

            val output =
                ByteArrayOutputStream()

            connection.inputStream.use {
                input ->

                val buffer =
                    ByteArray(8192)

                while (true) {

                    val count =
                        input.read(
                            buffer
                        )

                    if (
                        count <= 0
                    ) {

                        break
                    }

                    output.write(
                        buffer,
                        0,
                        count
                    )

                    /*
                     * Limite de segurança:
                     * 5 MB por arquivo.
                     */
                    if (
                        output.size() >
                        5 * 1024 * 1024
                    ) {

                        throw IllegalStateException(
                            "WAV muito grande"
                        )
                    }
                }
            }

            return output.toByteArray()

        } catch (e: Exception) {

            lastError =
                e.message

            Log.e(
                TAG,
                "Erro baixando WAV",
                e
            )

            return null

        } finally {

            connection?.disconnect()
        }
    }


    // =============================================================
    // PARSER WAV
    // =============================================================

    /*
     * Retorna:
     *
     * Pair(
     *   PCM16 mono,
     *   sampleRate
     * )
     *
     * O Piper normalmente entrega WAV PCM.
     *
     * Não tocamos o cabeçalho WAV.
     * Enviamos somente o PCM para AudioTrack.
     */
    private fun parseWav(
        wav: ByteArray
    ): Pair<ByteArray, Int>? {

        try {

            if (
                wav.size < 44
            ) {

                return null
            }

            fun readIntLE(
                offset: Int
            ): Int {

                return ByteBuffer
                    .wrap(
                        wav,
                        offset,
                        4
                    )
                    .order(
                        ByteOrder.LITTLE_ENDIAN
                    )
                    .int
            }

            fun readShortLE(
                offset: Int
            ): Short {

                return ByteBuffer
                    .wrap(
                        wav,
                        offset,
                        2
                    )
                    .order(
                        ByteOrder.LITTLE_ENDIAN
                    )
                    .short
            }

            fun fourCC(
                offset: Int
            ): String {

                return String(
                    wav,
                    offset,
                    4,
                    Charsets.US_ASCII
                )
            }

            if (
                fourCC(0) != "RIFF" ||
                fourCC(8) != "WAVE"
            ) {

                Log.e(
                    TAG,
                    "Arquivo não é WAV RIFF"
                )

                return null
            }

            var offset =
                12

            var sampleRate =
                0

            var channels =
                0

            var bitsPerSample =
                0

            var audioFormat =
                0

            var dataStart =
                -1

            var dataSize =
                0

            while (
                offset + 8 <= wav.size
            ) {

                val chunkId =
                    fourCC(
                        offset
                    )

                val chunkSize =
                    readIntLE(
                        offset + 4
                    )

                if (
                    chunkSize < 0
                ) {

                    return null
                }

                val chunkDataStart =
                    offset + 8

                if (
                    chunkDataStart >
                    wav.size
                ) {

                    return null
                }

                if (
                    chunkId == "fmt "
                ) {

                    if (
                        chunkSize >= 16 &&
                        chunkDataStart + 16 <=
                        wav.size
                    ) {

                        audioFormat =
                            readShortLE(
                                chunkDataStart
                            ).toInt() and 0xffff

                        channels =
                            readShortLE(
                                chunkDataStart + 2
                            ).toInt() and 0xffff

                        sampleRate =
                            readIntLE(
                                chunkDataStart + 4
                            )
                    }

                    if (
                        chunkSize >= 16 &&
                        chunkDataStart + 16 <=
                        wav.size
                    ) {

                        bitsPerSample =
                            readShortLE(
                                chunkDataStart + 14
                            ).toInt() and 0xffff
                    }
                }

                if (
                    chunkId == "data"
                ) {

                    dataStart =
                        chunkDataStart

                    dataSize =
                        minOf(
                            chunkSize,
                            wav.size -
                                dataStart
                        )

                    break
                }

                var advance =
                    8 + chunkSize

                /*
                 * WAV chunks normalmente ficam alinhados
                 * em 2 bytes.
                 */
                if (
                    advance % 2 != 0
                ) {

                    advance++
                }

                if (
                    advance <= 0
                ) {

                    return null
                }

                offset +=
                    advance
            }

            if (
                audioFormat != 1
            ) {

                Log.e(
                    TAG,
                    "WAV não é PCM. format=$audioFormat"
                )

                return null
            }

            if (
                channels != 1
            ) {

                Log.e(
                    TAG,
                    "Piper WAV não é mono. channels=$channels"
                )

                return null
            }

            if (
                bitsPerSample != 16
            ) {

                Log.e(
                    TAG,
                    "Piper WAV não é PCM16. bits=$bitsPerSample"
                )

                return null
            }

            if (
                sampleRate <= 0
            ) {

                return null
            }

            if (
                dataStart < 0 ||
                dataSize <= 0 ||
                dataStart + dataSize >
                wav.size
            ) {

                return null
            }

            val pcm =
                wav.copyOfRange(
                    dataStart,
                    dataStart + dataSize
                )

            /*
             * PCM16 precisa ter quantidade par de bytes.
             */
            val evenSize =
                pcm.size -
                    (pcm.size % 2)

            if (
                evenSize <= 0
            ) {

                return null
            }

            val finalPcm =
                if (
                    evenSize == pcm.size
                ) {

                    pcm

                } else {

                    pcm.copyOf(
                        evenSize
                    )
                }

            return Pair(
                finalPcm,
                sampleRate
            )

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Erro analisando WAV",
                e
            )

            return null
        }
    }


    // =============================================================
    // ACK DO ÁUDIO
    // =============================================================

    private fun enviarAck(
        audioId: String
    ) {

        if (
            jobId.isEmpty() ||
            audioId.isEmpty()
        ) {

            return
        }

        var connection:
            HttpURLConnection? =
            null

        try {

            val url =
                URL(
                    "$BACKEND_URL/api/audio/ack/" +
                        "$jobId/$audioId"
                )

            connection =
                url.openConnection()
                    as HttpURLConnection

            connection.requestMethod =
                "POST"

            connection.connectTimeout =
                5000

            connection.readTimeout =
                5000

            connection.doOutput =
                true

            connection.useCaches =
                false

            connection.setRequestProperty(
                "Content-Type",
                "application/json"
            )

            connection.outputStream.use {
                it.write(
                    "{}".toByteArray()
                )
            }

            val code =
                connection.responseCode

            Log.d(
                TAG,
                "ACK audio=$audioId HTTP=$code"
            )

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Erro no ACK $audioId",
                e
            )

        } finally {

            connection?.disconnect()
        }
    }


    // =============================================================
    // THREAD DE REPRODUÇÃO
    // =============================================================

    private fun iniciarReproducao() {

        playbackThread =
            Thread {

                Log.d(
                    TAG,
                    "THREAD REPRODUÇÃO PIPER iniciada"
                )

                while (
                    running &&
                    !stopping
                ) {

                    try {

                        val item =
                            outputQueue.poll(
                                500,
                                TimeUnit.MILLISECONDS
                            )

                        if (
                            item == null
                        ) {

                            continue
                        }

                        tocarPcm(
                            item
                        )

                        playedAudios++

                    } catch (
                        e: InterruptedException
                    ) {

                        break

                    } catch (
                        e: Exception
                    ) {

                        lastError =
                            e.message

                        Log.e(
                            TAG,
                            "Erro reprodução Piper",
                            e
                        )
                    }
                }

                liberarAudioTrack()

                Log.d(
                    TAG,
                    "THREAD REPRODUÇÃO finalizada"
                )
            }

        playbackThread?.start()
    }


    // =============================================================
    // REPRODUÇÃO PCM
    // =============================================================

    private fun tocarPcm(
        item: OutputAudio
    ) {

        if (
            item.pcmData.isEmpty()
        ) {

            return
        }

        /*
         * Se o sample rate mudar, recriamos o AudioTrack.
         *
         * Normalmente todos os modelos Piper usados
         * no SI terão a mesma taxa.
         */
        if (
            audioTrack == null ||
            outputSampleRate !=
            item.sampleRate
        ) {

            liberarAudioTrack()

            outputSampleRate =
                item.sampleRate

            criarAudioTrack(
                item.sampleRate
            )
        }

        val track =
            audioTrack
                ?: return

        var offset =
            0

        /*
         * Escrevemos em blocos.
         *
         * Não usamos QUEUE_FLUSH.
         *
         * O AudioTrack permanece aberto durante a
         * reprodução dos vários WAVs.
         */
        while (
            offset <
            item.pcmData.size &&
            running &&
            !stopping
        ) {

            val remaining =
                item.pcmData.size -
                    offset

            val writeSize =
                minOf(
                    remaining,
                    8192
                )

            val written =
                track.write(
                    item.pcmData,
                    offset,
                    writeSize,
                    AudioTrack.WRITE_BLOCKING
                )

            if (
                written <= 0
            ) {

                Log.e(
                    TAG,
                    "AudioTrack.write retornou $written"
                )

                break
            }

            offset +=
                written
        }

        Log.d(
            TAG,
            "Piper reproduzido " +
                "id=${item.audioId} " +
                "bytes=${item.pcmData.size}"
        )
    }


    // =============================================================
    // CRIAR AUDIO TRACK
    // =============================================================

    private fun criarAudioTrack(
        sampleRate: Int
    ) {

        val minBuffer =
            AudioTrack.getMinBufferSize(
                sampleRate,
                OUTPUT_CHANNEL,
                OUTPUT_FORMAT
            )

        if (
            minBuffer <= 0
        ) {

            throw IllegalStateException(
                "AudioTrack.getMinBufferSize falhou"
            )
        }

        val bufferSize =
            maxOf(
                minBuffer * 2,
                8192
            )

        audioTrack =
            if (
                Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.M
            ) {

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
                                OUTPUT_FORMAT
                            )
                            .setSampleRate(
                                sampleRate
                            )
                            .setChannelMask(
                                OUTPUT_CHANNEL
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

            } else {

                @Suppress("DEPRECATION")
                AudioTrack(
                    AudioManager.STREAM_MUSIC,
                    sampleRate,
                    OUTPUT_CHANNEL,
                    OUTPUT_FORMAT,
                    bufferSize,
                    AudioTrack.MODE_STREAM
                )
            }

        if (
            audioTrack?.state !=
            AudioTrack.STATE_INITIALIZED
        ) {

            liberarAudioTrack()

            throw IllegalStateException(
                "AudioTrack não foi inicializado"
            )
        }

        audioTrack?.play()

        Log.d(
            TAG,
            "AudioTrack iniciado " +
                "sampleRate=$sampleRate " +
                "buffer=$bufferSize"
        )
    }


    // =============================================================
    // LIBERAR AUDIO TRACK
    // =============================================================

    private fun liberarAudioTrack() {

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

        audioTrack =
            null
    }


    // =============================================================
    // NOTIFICAÇÃO
    // =============================================================

    private fun createNotificationChannel() {

        if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.O
        ) {

            val manager =
                getSystemService(
                    NotificationManager::class.java
                )

            val channel =
                NotificationChannel(
                    CHANNEL_ID,
                    "SI Tradutor",
                    NotificationManager.IMPORTANCE_LOW
                )

            channel.description =
                "Captura e tradução de áudio em tempo real"

            manager.createNotificationChannel(
                channel
            )
        }
    }


    // =============================================================
    // FOREGROUND
    // =============================================================

    private fun startForegroundServiceNotification() {

        val notification =
            if (
                Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.O
            ) {

                Notification.Builder(
                    this,
                    CHANNEL_ID
                )
                    .setContentTitle(
                        "SI Tradutor"
                    )
                    .setContentText(
                        "Tradução de áudio em tempo real"
                    )
                    .setSmallIcon(
                        android.R.drawable.ic_btn_speak_now
                    )
                    .setOngoing(
                        true
                    )
                    .build()

            } else {

                @Suppress("DEPRECATION")
                Notification.Builder(
                    this
                )
                    .setContentTitle(
                        "SI Tradutor"
                    )
                    .setContentText(
                        "Tradução de áudio em tempo real"
                    )
                    .setSmallIcon(
                        android.R.drawable.ic_btn_speak_now
                    )
                    .setOngoing(
                        true
                    )
                    .build()
            }

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


    // =============================================================
    // LIMPAR FILAS
    // =============================================================

    private fun limparFilas() {

        inputQueue.clear()

        outputQueue.clear()

        synchronized(
            downloadedAudioLock
        ) {

            downloadedAudioIds.clear()
        }
    }


    // =============================================================
    // PARAR
    // =============================================================

    private fun stopCapture(
        sendServerStop: Boolean
    ) {

        if (
            stopping
        ) {

            return
        }

        stopping = true
        running = false

        Log.d(
            TAG,
            "Parando serviço..."
        )

        /*
         * Primeiro interrompemos as threads.
         */
        try {
            captureThread?.interrupt()
        } catch (_: Exception) {
        }

        try {
            sendThread?.interrupt()
        } catch (_: Exception) {
        }

        try {
            outputDownloadThread?.interrupt()
        } catch (_: Exception) {
        }

        try {
            playbackThread?.interrupt()
        } catch (_: Exception) {
        }

        /*
         * Paramos AudioRecord.
         */
        try {
            audioRecord?.stop()
        } catch (_: Exception) {
        }

        try {
            audioRecord?.release()
        } catch (_: Exception) {
        }

        audioRecord =
            null

        captureStarted =
            false

        /*
         * Liberamos AudioTrack.
         */
        liberarAudioTrack()

        /*
         * MediaProjection.
         */
        try {

            if (
                projectionCallback !=
                null
            ) {

                mediaProjection?.unregisterCallback(
                    projectionCallback!!
                )
            }

        } catch (_: Exception) {
        }

        try {
            mediaProjection?.stop()
        } catch (_: Exception) {
        }

        mediaProjection =
            null

        projectionCallback =
            null

        /*
         * Limpa as filas.
         */
        inputQueue.clear()
        outputQueue.clear()

        /*
         * Informa ao Render.
         */
        if (
            sendServerStop &&
            jobId.isNotEmpty()
        ) {

            Thread {

                enviarStopAoServidor()

            }.start()
        }

        /*
         * Remove foreground.
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

        stopSelf()

        Log.d(
            TAG,
            "Serviço parado"
        )
    }


    // =============================================================
    // STOP NO BACKEND
    // =============================================================

    private fun enviarStopAoServidor() {

        var connection:
            HttpURLConnection? =
            null

        try {

            val url =
                URL(
                    "$BACKEND_URL/api/audio/stop/$jobId"
                )

            connection =
                url.openConnection()
                    as HttpURLConnection

            connection.requestMethod =
                "POST"

            connection.connectTimeout =
                5000

            connection.readTimeout =
                5000

            connection.doOutput =
                true

            connection.setRequestProperty(
                "Content-Type",
                "application/json"
            )

            connection.outputStream.use {
                it.write(
                    "{}".toByteArray()
                )
            }

            Log.d(
                TAG,
                "STOP Render HTTP=${connection.responseCode}"
            )

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Erro enviando STOP",
                e
            )

        } finally {

            connection?.disconnect()
        }
    }


    // =============================================================
    // DESTROY
    // =============================================================

    override fun onDestroy() {

        Log.d(
            TAG,
            "onDestroy"
        )

        stopCapture(
            sendServerStop = false
        )

        super.onDestroy()
    }


    // =============================================================
    // BIND
    // =============================================================

    override fun onBind(
        intent: Intent?
    ): IBinder? {

        return null
    }
}
