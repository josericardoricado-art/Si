package com.si.tradutor

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.*
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sin

class AudioCaptureService : Service() {

    companion object {

        private const val TAG = "SI_AUDIO"

        const val ACTION_START =
            "com.si.tradutor.ACTION_START"

        const val ACTION_STOP =
            "com.si.tradutor.ACTION_STOP"

        const val EXTRA_JOB_ID =
            "jobId"

        const val EXTRA_RESULT_CODE =
            "resultCode"

        const val EXTRA_RESULT_DATA =
            "resultData"

        private const val BACKEND_URL =
            "https://si-u2ul.onrender.com"

        private const val CHANNEL_ID =
            "si_audio_capture"

        private const val NOTIFICATION_ID =
            1001

        private const val SAMPLE_RATE_INPUT =
            16000

        private const val SAMPLE_RATE_OUTPUT =
            24000

        private const val CHUNK_SIZE =
            3200

        private const val OUTPUT_LIMIT =
            20
    }

    private var mediaProjection: MediaProjection? = null

    private var audioRecord: AudioRecord? = null

    private var audioTrack: AudioTrack? = null

    private var audioManager: AudioManager? = null

    private var captureThread: Thread? = null

    private var outputThread: Thread? = null

    private var diagnosticThread: Thread? = null

    private val running =
        AtomicBoolean(false)

    private var jobId: String? = null

    private var lastOutputSeq = 0L

    private var readCount = 0L

    private var capturedBytes = 0L

    private var sentChunks = 0L

    private var sentBytes = 0L

    private var receivedOutputChunks = 0L

    private var receivedOutputBytes = 0L

    private var playedOutputChunks = 0L

    private var playedOutputBytes = 0L

    private var lastStage = "created"

    private val projectionCallback =
        object : MediaProjection.Callback() {

            override fun onStop() {

                Log.d(
                    TAG,
                    "MediaProjection foi parada"
                )

                lastStage =
                    "projection_stopped"

                enviarDiagnostico(
                    "projection_stopped"
                )

                pararTudo()
            }
        }

    override fun onCreate() {
        super.onCreate()

        Log.d(
            TAG,
            "AudioCaptureService criado"
        )

        audioManager =
            getSystemService(
                Context.AUDIO_SERVICE
            ) as AudioManager

        criarCanalNotificacao()
    }

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

                val recebidoJobId =
                    intent.getStringExtra(
                        EXTRA_JOB_ID
                    )

                val resultCode =
                    intent.getIntExtra(
                        EXTRA_RESULT_CODE,
                        Activity.RESULT_CANCELED
                    )

                val resultData =
                    intent.getParcelableExtra<Intent>(
                        EXTRA_RESULT_DATA
                    )

                if (
                    recebidoJobId == null ||
                    resultData == null
                ) {

                    Log.e(
                        TAG,
                        "Dados de MediaProjection ausentes"
                    )

                    return START_NOT_STICKY
                }

                jobId =
                    recebidoJobId

                iniciarForeground()

                iniciarCaptura(
                    resultCode,
                    resultData
                )
            }

            ACTION_STOP -> {

                pararTudo()
            }
        }

        return START_NOT_STICKY
    }

    private fun iniciarForeground() {

        val notification =
            NotificationCompat.Builder(
                this,
                CHANNEL_ID
            )
                .setContentTitle(
                    "SI Tradutor Live"
                )
                .setContentText(
                    "Tradução de áudio em tempo real"
                )
                .setSmallIcon(
                    R.drawable.si_logo
                )
                .setOngoing(true)
                .setCategory(
                    NotificationCompat.CATEGORY_SERVICE
                )
                .build()

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

    private fun iniciarCaptura(
        resultCode: Int,
        resultData: Intent
    ) {

        if (running.get()) {

            Log.d(
                TAG,
                "Captura já está rodando"
            )

            return
        }

        running.set(true)

        lastStage =
            "starting_capture"

        try {

            val projectionManager =
                getSystemService(
                    Context.MEDIA_PROJECTION_SERVICE
                ) as MediaProjectionManager

            mediaProjection =
                projectionManager.getMediaProjection(
                    resultCode,
                    resultData
                )

            if (mediaProjection == null) {

                throw Exception(
                    "MediaProjection nula"
                )
            }

            mediaProjection?.registerCallback(
                projectionCallback,
                null
            )

            criarAudioRecord()

            criarAudioTrack()

            tocarTesteAudio()

            iniciarThreadCaptura()

            iniciarThreadSaida()

            iniciarThreadDiagnostico()

            lastStage =
                "capture_threads_started"

            enviarDiagnostico(
                "capture_started"
            )

            Toast.makeText(
                this,
                "SI Tradutor Live iniciado",
                Toast.LENGTH_SHORT
            ).show()

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Erro iniciando captura",
                e
            )

            lastStage =
                "start_error"

            enviarDiagnostico(
                "start_error:${e.message}"
            )

            pararTudo()
        }
    }

    private fun criarAudioRecord() {

        val audioFormat =
            AudioFormat.Builder()
                .setEncoding(
                    AudioFormat.ENCODING_PCM_16BIT
                )
                .setSampleRate(
                    SAMPLE_RATE_INPUT
                )
                .setChannelMask(
                    AudioFormat.CHANNEL_IN_MONO
                )
                .build()

        val config =
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

        val minBuffer =
            AudioRecord.getMinBufferSize(
                SAMPLE_RATE_INPUT,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )

        val bufferSize =
            maxOf(
                minBuffer * 2,
                CHUNK_SIZE * 4
            )

        audioRecord =
            AudioRecord.Builder()
                .setAudioFormat(
                    audioFormat
                )
                .setBufferSizeInBytes(
                    bufferSize
                )
                .setAudioPlaybackCaptureConfig(
                    config
                )
                .build()

        Log.d(
            TAG,
            "AudioRecord criado: $bufferSize bytes"
        )

        enviarDiagnostico(
            "audioRecord_ready"
        )
    }

    private fun criarAudioTrack() {

        val minBuffer =
            AudioTrack.getMinBufferSize(
                SAMPLE_RATE_OUTPUT,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )

        val bufferSize =
            maxOf(
                minBuffer * 2,
                SAMPLE_RATE_OUTPUT / 2
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
                    AudioFormat.ENCODING_PCM_16BIT
                )
                .setSampleRate(
                    SAMPLE_RATE_OUTPUT
                )
                .setChannelMask(
                    AudioFormat.CHANNEL_OUT_MONO
                )
                .build()

        audioTrack =
            AudioTrack.Builder()
                .setAudioAttributes(
                    attributes
                )
                .setAudioFormat(
                    format
                )
                .setBufferSizeInBytes(
                    bufferSize
                )
                .setTransferMode(
                    AudioTrack.MODE_STREAM
                )
                .build()

        configurarRotaAudio()

        configurarVolumeAudio()

        audioTrack?.play()

        Log.d(
            TAG,
            "AudioTrack iniciado"
        )

        Log.d(
            TAG,
            "PlayState=${audioTrack?.playState}"
        )

        Log.d(
            TAG,
            "State=${audioTrack?.state}"
        )

        enviarDiagnostico(
            "audioTrack_ready"
        )
    }

    private fun configurarRotaAudio() {

        try {

            if (
                Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.M
            ) {

                val devices =
                    audioManager?.getDevices(
                        AudioManager.GET_DEVICES_OUTPUTS
                    )

                val speaker =
                    devices?.firstOrNull {

                        it.type ==
                            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                    }

                if (speaker != null) {

                    val sucesso =
                        audioTrack?.setPreferredDevice(
                            speaker
                        )

                    Log.d(
                        TAG,
                        "Alto-falante encontrado: ${speaker.productName}"
                    )

                    Log.d(
                        TAG,
                        "preferredDevice sucesso=$sucesso"
                    )

                } else {

                    Log.w(
                        TAG,
                        "Alto-falante interno não encontrado"
                    )
                }
            }

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Erro configurando rota",
                e
            )
        }
    }

    private fun configurarVolumeAudio() {

        try {

            if (
                Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.LOLLIPOP
            ) {

                audioTrack?.setVolume(
                    1.0f
                )

            } else {

                @Suppress(
                    "DEPRECATION"
                )

                audioTrack?.setStereoVolume(
                    1.0f,
                    1.0f
                )
            }

            val atual =
                audioManager?.getStreamVolume(
                    AudioManager.STREAM_MUSIC
                )

            val maximo =
                audioManager?.getStreamMaxVolume(
                    AudioManager.STREAM_MUSIC
                )

            Log.d(
                TAG,
                "Volume música: $atual/$maximo"
            )

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Erro configurando volume",
                e
            )
        }
    }

    private fun tocarTesteAudio() {

        Thread {

            try {

                val durationMs =
                    1000

                val totalSamples =
                    SAMPLE_RATE_OUTPUT *
                        durationMs / 1000

                val buffer =
                    ShortArray(
                        totalSamples
                    )

                val frequencia =
                    440.0

                val amplitude =
                    0.35

                for (i in buffer.indices) {

                    val valor =
                        sin(
                            2.0 *
                                Math.PI *
                                frequencia *
                                i /
                                SAMPLE_RATE_OUTPUT
                        )

                    buffer[i] =
                        (
                            valor *
                                Short.MAX_VALUE *
                                amplitude
                        ).toInt()
                            .toShort()
                }

                Log.d(
                    TAG,
                    "Tocando TOM DE TESTE"
                )

                audioTrack?.write(
                    buffer,
                    0,
                    buffer.size,
                    AudioTrack.WRITE_BLOCKING
                )

                Log.d(
                    TAG,
                    "TOM DE TESTE enviado"
                )

                verificarRotaAtual()

            } catch (e: Exception) {

                Log.e(
                    TAG,
                    "Erro no tom de teste",
                    e
                )
            }

        }.start()
    }

    private fun verificarRotaAtual() {

        try {

            if (
                Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.M
            ) {

                val device =
                    audioTrack?.routedDevice

                if (device != null) {

                    Log.d(
                        TAG,
                        "Áudio atualmente roteado para:"
                    )

                    Log.d(
                        TAG,
                        "device=${device.productName}"
                    )

                    Log.d(
                        TAG,
                        "type=${device.type}"
                    )

                    Log.d(
                        TAG,
                        "id=${device.id}"
                    )

                } else {

                    Log.d(
                        TAG,
                        "Nenhum routedDevice disponível"
                    )
                }
            }

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Erro verificando rota",
                e
            )
        }
    }

    private fun iniciarThreadCaptura() {

        captureThread =
            Thread {

                try {

                    audioRecord?.startRecording()

                    lastStage =
                        "audio_record_recording"

                    enviarDiagnostico(
                        "audio_record_recording"
                    )

                    val buffer =
                        ByteArray(
                            CHUNK_SIZE
                        )

                    while (
                        running.get()
                    ) {

                        val lidos =
                            audioRecord?.read(
                                buffer,
                                0,
                                buffer.size,
                                AudioRecord.READ_BLOCKING
                            )
                                ?: 0

                        if (lidos > 0) {

                            readCount++

                            capturedBytes +=
                                lidos

                            val audio =
                                buffer.copyOf(
                                    lidos
                                )

                            enviarAudio(
                                audio
                            )

                            if (
                                readCount % 50L ==
                                0L
                            ) {

                                Log.d(
                                    TAG,
                                    "Captura: readCount=$readCount bytes=$capturedBytes"
                                )
                            }
                        }
                    }

                } catch (e: Exception) {

                    Log.e(
                        TAG,
                        "Erro thread captura",
                        e
                    )

                    lastStage =
                        "capture_error"

                    enviarDiagnostico(
                        "capture_error:${e.message}"
                    )
                }
            }

        captureThread?.start()
    }

    private fun enviarAudio(
        audio: ByteArray
    ) {

        Thread {

            try {

                val url =
                    URL(
                        "$BACKEND_URL/api/audio/chunk"
                    )

                val connection =
                    url.openConnection()
                        as HttpURLConnection

                connection.requestMethod =
                    "POST"

                connection.connectTimeout =
                    15000

                connection.readTimeout =
                    15000

                connection.doOutput =
                    true

                connection.setRequestProperty(
                    "Content-Type",
                    "application/octet-stream"
                )

                connection.setRequestProperty(
                    "X-Job-Id",
                    jobId ?: ""
                )

                connection.outputStream.use {
                    it.write(audio)
                }

                val status =
                    connection.responseCode

                if (status in 200..299) {

                    sentChunks++

                    sentBytes +=
                        audio.size

                } else {

                    Log.e(
                        TAG,
                        "Erro enviando áudio: HTTP $status"
                    )
                }

                connection.disconnect()

            } catch (e: Exception) {

                Log.e(
                    TAG,
                    "Falha envio áudio",
                    e
                )
            }

        }.start()
    }

    private fun iniciarThreadSaida() {

        outputThread =
            Thread {

                while (
                    running.get()
                ) {

                    try {

                        buscarAudioTraduzido()

                        Thread.sleep(
                            150
                        )

                    } catch (
                        e: InterruptedException
                    ) {

                        break

                    } catch (e: Exception) {

                        Log.e(
                            TAG,
                            "Erro thread saída",
                            e
                        )

                        Thread.sleep(
                            500
                        )
                    }
                }
            }

        outputThread?.start()
    }

    private fun buscarAudioTraduzido() {

        val id =
            jobId
                ?: return

        val url =
            URL(
                "$BACKEND_URL/api/audio/output/$id" +
                    "?after=$lastOutputSeq" +
                    "&limit=$OUTPUT_LIMIT"
            )

        val connection =
            url.openConnection()
                as HttpURLConnection

        connection.requestMethod =
            "GET"

        connection.connectTimeout =
            10000

        connection.readTimeout =
            10000

        val status =
            connection.responseCode

        if (status != 200) {

            connection.disconnect()

            return
        }

        val reader =
            BufferedReader(
                InputStreamReader(
                    connection.inputStream
                )
            )

        val resposta =
            reader.readText()

        reader.close()

        connection.disconnect()

        processarRespostaAudio(
            resposta
        )
    }

    private fun processarRespostaAudio(
        json: String
    ) {

        try {

            val chunks =
                extrairChunks(
                    json
                )

            for (
                chunk in chunks
            ) {

                val seq =
                    chunk.first

                val audio =
                    chunk.second

                if (
                    seq <= lastOutputSeq
                ) {
                    continue
                }

                val bytes =
                    android.util.Base64.decode(
                        audio,
                        android.util.Base64.NO_WRAP
                    )

                if (
                    bytes.isEmpty()
                ) {
                    continue
                }

                receivedOutputChunks++

                receivedOutputBytes +=
                    bytes.size

                reproduzirAudio(
                    bytes
                )

                playedOutputChunks++

                playedOutputBytes +=
                    bytes.size

                lastOutputSeq =
                    seq
            }

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Erro processando saída",
                e
            )
        }
    }

    private fun extrairChunks(
        json: String
    ): List<Pair<Long, String>> {

        val resultado =
            mutableListOf<Pair<Long, String>>()

        val regex =
            Regex(
                """\{"seq":(\d+),"audio":"([^"]+)""""
            )

        val matches =
            regex.findAll(
                json
            )

        for (
            match in matches
        ) {

            val seq =
                match.groupValues[1]
                    .toLongOrNull()
                    ?: continue

            val audio =
                match.groupValues[2]

            resultado.add(
                Pair(
                    seq,
                    audio
                )
            )
        }

        return resultado
    }

    private fun reproduzirAudio(
        bytes: ByteArray
    ) {

        try {

            if (
                audioTrack == null
            ) {
                return
            }

            if (
                audioTrack?.playState !=
                AudioTrack.PLAYSTATE_PLAYING
            ) {

                configurarRotaAudio()

                configurarVolumeAudio()

                audioTrack?.play()
            }

            val escritos =
                audioTrack?.write(
                    bytes,
                    0,
                    bytes.size,
                    AudioTrack.WRITE_BLOCKING
                )
                    ?: 0

            Log.d(
                TAG,
                "Áudio traduzido reproduzido: $escritos bytes"
            )

            if (
                playedOutputChunks % 10L ==
                0L
            ) {

                verificarRotaAtual()
            }

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Erro reproduzindo áudio",
                e
            )

            enviarDiagnostico(
                "play_error:${e.message}"
            )
        }
    }

    private fun iniciarThreadDiagnostico() {

        diagnosticThread =
            Thread {

                while (
                    running.get()
                ) {

                    try {

                        enviarDiagnostico(
                            "heartbeat"
                        )

                        Thread.sleep(
                            5000
                        )

                    } catch (
                        e: InterruptedException
                    ) {

                        break

                    } catch (e: Exception) {

                        Log.e(
                            TAG,
                            "Erro diagnóstico",
                            e
                        )
                    }
                }
            }

        diagnosticThread?.start()
    }

    private fun enviarDiagnostico(
        stage: String
    ) {

        Thread {

            try {

                val id =
                    jobId
                        ?: return@Thread

                val json =
                    """
                    {
                      "jobId":"$id",
                      "stage":"$stage",
                      "readCount":$readCount,
                      "capturedBytes":$capturedBytes,
                      "sentChunks":$sentChunks,
                      "sentBytes":$sentBytes,
                      "receivedOutputChunks":$receivedOutputChunks,
                      "receivedOutputBytes":$receivedOutputBytes,
                      "playedOutputChunks":$playedOutputChunks,
                      "playedOutputBytes":$playedOutputBytes,
                      "lastOutputSeq":$lastOutputSeq
                    }
                    """.trimIndent()

                val url =
                    URL(
                        "$BACKEND_URL/api/audio/diagnostic"
                    )

                val connection =
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

                connection.setRequestProperty(
                    "Content-Type",
                    "application/json"
                )

                connection.outputStream.use {
                    it.write(
                        json.toByteArray(
                            Charsets.UTF_8
                        )
                    )
                }

                connection.responseCode

                connection.disconnect()

            } catch (e: Exception) {

                Log.e(
                    TAG,
                    "Falha diagnóstico",
                    e
                )
            }
        }.start()
    }

    private fun pararTudo() {

        if (
            !running.getAndSet(false)
        ) {

            stopSelf()

            return
        }

        lastStage =
            "stopping"

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

        try {

            mediaProjection?.unregisterCallback(
                projectionCallback
            )

        } catch (_: Exception) {
        }

        try {

            mediaProjection?.stop()

        } catch (_: Exception) {
        }

        mediaProjection =
            null

        captureThread?.interrupt()
        outputThread?.interrupt()
        diagnosticThread?.interrupt()

        captureThread =
            null

        outputThread =
            null

        diagnosticThread =
            null

        enviarDiagnostico(
            "stop_requested"
        )

        stopForeground(
            STOP_FOREGROUND_REMOVE
        )

        stopSelf()
    }

    private fun criarCanalNotificacao() {

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

            val manager =
                getSystemService(
                    NotificationManager::class.java
                )

            manager.createNotificationChannel(
                channel
            )
        }
    }

    override fun onBind(
        intent: Intent?
    ): android.os.IBinder? {

        return null
    }

    override fun onDestroy() {

        pararTudo()

        super.onDestroy()
    }
}
