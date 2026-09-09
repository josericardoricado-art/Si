package com.si.tradutor

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

class MainActivity : AppCompatActivity() {

    companion object {
        private const val BACKEND_URL = "https://si-u2ul.onrender.com"

        private const val REQUEST_RECORD_AUDIO = 1001
        private const val REQUEST_MEDIA_PROJECTION = 1002

        const val EXTRA_JOB_ID = "jobId"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"
    }

    private lateinit var statusText: TextView
    private lateinit var monitorButton: Button
    private lateinit var stopButton: Button
    private lateinit var languageSpinner: Spinner

    private var mediaProjectionResultCode: Int = 0
    private var mediaProjectionData: Intent? = null

    @Volatile
    private var jobId: String? = null

    private val clientId: String by lazy {

        val prefs = getSharedPreferences(
            "si_config",
            MODE_PRIVATE
        )

        var id = prefs.getString(
            "client_id",
            null
        )

        if (id.isNullOrEmpty()) {

            id = UUID.randomUUID().toString()

            prefs.edit()
                .putString(
                    "client_id",
                    id
                )
                .apply()
        }

        id
    }

    private val languageCodes = arrayOf(
        "pt",
        "en",
        "es",
        "fr",
        "de",
        "it",
        "ja",
        "ko",
        "zh",
        "ru",
        "ar",
        "hi",
        "tr",
        "nl",
        "pl",
        "uk",
        "th",
        "id",
        "vi"
    )

    private val languageNames = arrayOf(
        "Português",
        "English",
        "Español",
        "Français",
        "Deutsch",
        "Italiano",
        "日本語",
        "한국어",
        "中文",
        "Русский",
        "العربية",
        "हिन्दी",
        "Türkçe",
        "Nederlands",
        "Polski",
        "Українська",
        "ไทย",
        "Bahasa Indonesia",
        "Tiếng Việt"
    )

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {

        super.onCreate(
            savedInstanceState
        )

        setContentView(
            R.layout.activity_main
        )

        statusText =
            findViewById(
                R.id.statusText
            )

        monitorButton =
            findViewById(
                R.id.monitorButton
            )

        stopButton =
            findViewById(
                R.id.stopButton
            )

        languageSpinner =
            findViewById(
                R.id.languageSpinner
            )

        languageSpinner.adapter =
            ArrayAdapter(
                this,
                android.R.layout.simple_spinner_dropdown_item,
                languageNames
            )

        stopButton.isEnabled = false

        monitorButton.setOnClickListener {

            iniciarMonitoramento()
        }

        stopButton.setOnClickListener {

            pararMonitoramento()
        }

        verificarServidor()
    }

    private fun iniciarMonitoramento() {

        if (
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {

            ActivityCompat.requestPermissions(
                this,
                arrayOf(
                    Manifest.permission.RECORD_AUDIO
                ),
                REQUEST_RECORD_AUDIO
            )

            return
        }

        statusText.text =
            "🔄 Conectando ao servidor..."

        monitorButton.isEnabled = false

        val selectedPosition =
            languageSpinner.selectedItemPosition

        val targetLang =
            languageCodes[selectedPosition]

        Thread {

            var connection:
                HttpURLConnection? = null

            try {

                val url =
                    URL(
                        "$BACKEND_URL/api/audio/start"
                    )

                connection =
                    url.openConnection()
                        as HttpURLConnection

                connection.requestMethod =
                    "POST"

                connection.connectTimeout =
                    8000

                connection.readTimeout =
                    12000

                connection.doOutput =
                    true

                connection.useCaches =
                    false

                connection.setRequestProperty(
                    "Content-Type",
                    "application/json; charset=UTF-8"
                )

                connection.setRequestProperty(
                    "Accept",
                    "application/json"
                )

                connection.setRequestProperty(
                    "Connection",
                    "close"
                )

                connection.setRequestProperty(
                    "X-Client-Id",
                    clientId
                )

                val body =
                    JSONObject()

                body.put(
                    "clientId",
                    clientId
                )

                body.put(
                    "targetLang",
                    targetLang
                )

                connection.outputStream.use { output ->

                    output.write(
                        body.toString()
                            .toByteArray(
                                Charsets.UTF_8
                            )
                    )

                    output.flush()
                }

                val responseCode =
                    connection.responseCode

                val responseText =
                    if (
                        responseCode in 200..299
                    ) {

                        connection.inputStream
                            .bufferedReader()
                            .use {
                                it.readText()
                            }

                    } else {

                        val errorStream =
                            connection.errorStream

                        if (
                            errorStream != null
                        ) {

                            BufferedReader(
                                InputStreamReader(
                                    errorStream
                                )
                            ).use {
                                it.readText()
                            }

                        } else {

                            ""
                        }
                    }

                connection.disconnect()
                connection = null

                if (
                    responseCode !in 200..299
                ) {

                    runOnUiThread {

                        statusText.text =
                            "❌ Render respondeu HTTP $responseCode"

                        monitorButton.isEnabled =
                            true
                    }

                    return@Thread
                }

                if (
                    responseText.isEmpty()
                ) {

                    runOnUiThread {

                        statusText.text =
                            "❌ Render enviou resposta vazia."

                        monitorButton.isEnabled =
                            true
                    }

                    return@Thread
                }

                val response =
                    JSONObject(
                        responseText
                    )

                val ok =
                    response.optBoolean(
                        "ok",
                        false
                    )

                if (!ok) {

                    runOnUiThread {

                        statusText.text =
                            "❌ Servidor recusou a sessão."

                        monitorButton.isEnabled =
                            true
                    }

                    return@Thread
                }

                val newJobId =
                    response.optString(
                        "jobId",
                        ""
                    )

                if (
                    newJobId.isEmpty()
                ) {

                    runOnUiThread {

                        statusText.text =
                            "❌ Render não retornou jobId."

                        monitorButton.isEnabled =
                            true
                    }

                    return@Thread
                }

                jobId =
                    newJobId

                runOnUiThread {

                    statusText.text =
                        "🎬 Preparando captura de áudio..."

                    solicitarCapturaDeTela()
                }

            } catch (
                e: Exception
            ) {

                try {
                    connection?.disconnect()
                } catch (_: Exception) {
                }

                runOnUiThread {

                    val mensagem =
                        e.message ?: "erro desconhecido"

                    statusText.text =
                        "❌ Falha ao conectar: $mensagem"

                    monitorButton.isEnabled =
                        true
                }
            }

        }.start()
    }

    private fun solicitarCapturaDeTela() {

        try {

            val manager =
                getSystemService(
                    MEDIA_PROJECTION_SERVICE
                ) as MediaProjectionManager

            val captureIntent =
                manager.createScreenCaptureIntent()

            startActivityForResult(
                captureIntent,
                REQUEST_MEDIA_PROJECTION
            )

        } catch (
            e: Exception
        ) {

            statusText.text =
                "❌ Não foi possível iniciar captura."

            monitorButton.isEnabled =
                true

            jobId = null
        }
    }

    @Deprecated(
        "Compatibilidade com Android"
    )
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {

        super.onActivityResult(
            requestCode,
            resultCode,
            data
        )

        if (
            requestCode !=
            REQUEST_MEDIA_PROJECTION
        ) {
            return
        }

        if (
            resultCode ==
            Activity.RESULT_OK &&
            data != null &&
            !jobId.isNullOrEmpty()
        ) {

            mediaProjectionResultCode =
                resultCode

            mediaProjectionData =
                data

            iniciarServicoDeAudio()

        } else {

            statusText.text =
                "❌ Captura de tela cancelada."

            monitorButton.isEnabled =
                true

            stopButton.isEnabled =
                false

            jobId = null

            mediaProjectionData =
                null
        }
    }

    private fun iniciarServicoDeAudio() {

        val currentJobId =
            jobId ?: return

        val data =
            mediaProjectionData
                ?: return

        try {

            val serviceIntent =
                Intent(
                    this,
                    AudioCaptureService::class.java
                )

            serviceIntent.action =
                AudioCaptureService.ACTION_START

            serviceIntent.putExtra(
                AudioCaptureService.EXTRA_RESULT_CODE,
                mediaProjectionResultCode
            )

            serviceIntent.putExtra(
                AudioCaptureService.EXTRA_RESULT_DATA,
                data
            )

            serviceIntent.putExtra(
                AudioCaptureService.EXTRA_JOB_ID,
                currentJobId
            )

            ContextCompat.startForegroundService(
                this,
                serviceIntent
            )

            statusText.text =
                "🟢 MONITORANDO ÁUDIO DO VÍDEO"

            monitorButton.isEnabled =
                false

            stopButton.isEnabled =
                true

        } catch (
            e: Exception
        ) {

            statusText.text =
                "❌ Erro ao iniciar captura."

            monitorButton.isEnabled =
                true

            stopButton.isEnabled =
                false
        }
    }

    private fun pararMonitoramento() {

        val serviceIntent =
            Intent(
                this,
                AudioCaptureService::class.java
            )

        serviceIntent.action =
            AudioCaptureService.ACTION_STOP

        try {

            startService(
                serviceIntent
            )

        } catch (_: Exception) {
        }

        try {

            stopService(
                Intent(
                    this,
                    AudioCaptureService::class.java
                )
            )

        } catch (_: Exception) {
        }

        val currentJobId =
            jobId

        if (
            !currentJobId.isNullOrEmpty()
        ) {

            Thread {

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
                        "application/json; charset=UTF-8"
                    )

                    val body =
                        JSONObject()

                    body.put(
                        "jobId",
                        currentJobId
                    )

                    connection.outputStream.use {
                        output ->

                        output.write(
                            body.toString()
                                .toByteArray(
                                    Charsets.UTF_8
                                )
                        )

                        output.flush()
                    }

                    connection.responseCode

                } catch (_: Exception) {

                } finally {

                    try {
                        connection?.disconnect()
                    } catch (_: Exception) {
                    }
                }

            }.start()
        }

        jobId = null

        mediaProjectionData =
            null

        statusText.text =
            "⏹ Monitoramento parado."

        monitorButton.isEnabled =
            true

        stopButton.isEnabled =
            false
    }

    private fun verificarServidor() {

        Thread {

            var connection:
                HttpURLConnection? = null

            try {

                val url =
                    URL(
                        "$BACKEND_URL/api/health"
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

                connection.setRequestProperty(
                    "Accept",
                    "application/json"
                )

                connection.setRequestProperty(
                    "Connection",
                    "close"
                )

                val code =
                    connection.responseCode

                connection.disconnect()
                connection = null

                runOnUiThread {

                    if (
                        code in 200..299
                    ) {

                        statusText.text =
                            "🟢 Servidor conectado"

                    } else {

                        statusText.text =
                            "🟡 Servidor HTTP $code"
                    }
                }

            } catch (
                e: Exception
            ) {

                try {
                    connection?.disconnect()
                } catch (_: Exception) {
                }

                runOnUiThread {

                    statusText.text =
                        "🔴 Servidor indisponível"
                }
            }

        }.start()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {

        super.onRequestPermissionsResult(
            requestCode,
            permissions,
            grantResults
        )

        if (
            requestCode ==
            REQUEST_RECORD_AUDIO
        ) {

            if (
                grantResults.isNotEmpty() &&
                grantResults[0] ==
                PackageManager.PERMISSION_GRANTED
            ) {

                iniciarMonitoramento()

            } else {

                statusText.text =
                    "❌ Permissão de áudio necessária."

                monitorButton.isEnabled =
                    true
            }
        }
    }
}
