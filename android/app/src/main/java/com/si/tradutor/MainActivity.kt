package com.si.tradutor

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import org.json.JSONObject

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

    private var jobId: String? = null

    private val clientId: String by lazy {
        val prefs = getSharedPreferences("si_config", MODE_PRIVATE)

        var id = prefs.getString("client_id", null)

        if (id == null) {
            id = UUID.randomUUID().toString()

            prefs.edit()
                .putString("client_id", id)
                .apply()
        }

        id
    }

    private val languageCodes = arrayOf(
        "pt", "en", "es", "fr", "de", "it",
        "ja", "ko", "zh", "ru", "ar", "hi",
        "tr", "nl", "pl", "uk", "th", "id", "vi"
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        monitorButton = findViewById(R.id.monitorButton)
        stopButton = findViewById(R.id.stopButton)
        languageSpinner = findViewById(R.id.languageSpinner)

        languageSpinner.adapter = ArrayAdapter(
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

        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {

            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                REQUEST_RECORD_AUDIO
            )

            return
        }

        statusText.text = "🔄 Criando sessão..."

        val selectedPosition = languageSpinner.selectedItemPosition
        val targetLang = languageCodes[selectedPosition]

        Thread {

            try {

                val url = URL("$BACKEND_URL/api/audio/start")

                val connection =
                    url.openConnection() as HttpURLConnection

                connection.requestMethod = "POST"
                connection.connectTimeout = 15000
                connection.readTimeout = 15000
                connection.doOutput = true

                connection.setRequestProperty(
                    "Content-Type",
                    "application/json"
                )

                connection.setRequestProperty(
                    "X-Client-Id",
                    clientId
                )

                val body = JSONObject()

                body.put("clientId", clientId)
                body.put("targetLang", targetLang)

                connection.outputStream.use { output ->

                    output.write(
                        body.toString().toByteArray(Charsets.UTF_8)
                    )
                }

                val responseCode = connection.responseCode

                if (responseCode !in 200..299) {

                    runOnUiThread {

                        statusText.text =
                            "❌ Erro ao criar sessão: HTTP $responseCode"
                    }

                    connection.disconnect()
                    return@Thread
                }

                val responseText =
                    connection.inputStream
                        .bufferedReader()
                        .use { it.readText() }

                connection.disconnect()

                val response =
                    JSONObject(responseText)

                val ok =
                    response.optBoolean("ok", false)

                if (!ok) {

                    runOnUiThread {

                        statusText.text =
                            "❌ O servidor recusou a sessão."
                    }

                    return@Thread
                }

                jobId =
                    response.optString("jobId", null)

                if (jobId.isNullOrEmpty()) {

                    runOnUiThread {

                        statusText.text =
                            "❌ Render não retornou o jobId."
                    }

                    return@Thread
                }

                runOnUiThread {

                    statusText.text =
                        "🎬 Preparando captura de áudio..."

                    solicitarCapturaDeTela()
                }

            } catch (e: Exception) {

                runOnUiThread {

                    statusText.text =
                        "❌ Erro de conexão com Render: ${e.message}"
                }
            }

        }.start()
    }

    private fun solicitarCapturaDeTela() {

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
    }

    @Deprecated("Deprecated API usada para compatibilidade")
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

        if (requestCode == REQUEST_MEDIA_PROJECTION) {

            if (
                resultCode == Activity.RESULT_OK &&
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

                jobId = null
            }
        }
    }

    private fun iniciarServicoDeAudio() {

        val currentJobId =
            jobId ?: return

        val data =
            mediaProjectionData ?: return

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

        monitorButton.isEnabled = false
        stopButton.isEnabled = true
    }

    private fun pararMonitoramento() {

        val serviceIntent =
            Intent(
                this,
                AudioCaptureService::class.java
            )

        serviceIntent.action =
            AudioCaptureService.ACTION_STOP

        startService(serviceIntent)

        stopService(
            Intent(
                this,
                AudioCaptureService::class.java
            )
        )

        val currentJobId =
            jobId

        if (!currentJobId.isNullOrEmpty()) {

            Thread {

                try {

                    val url =
                        URL("$BACKEND_URL/api/audio/stop")

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

                    val body =
                        JSONObject()

                    body.put(
                        "jobId",
                        currentJobId
                    )

                    connection.outputStream.use { output ->

                        output.write(
                            body.toString()
                                .toByteArray(Charsets.UTF_8)
                        )
                    }

                    connection.responseCode

                    connection.disconnect()

                } catch (_: Exception) {
                    // Não impede o encerramento local.
                }

            }.start()
        }

        jobId = null
        mediaProjectionData = null

        statusText.text =
            "⏹ Monitoramento parado."

        monitorButton.isEnabled = true
        stopButton.isEnabled = false
    }

    private fun verificarServidor() {

        Thread {

            try {

                val url =
                    URL("$BACKEND_URL/api/health")

                val connection =
                    url.openConnection()
                        as HttpURLConnection

                connection.requestMethod =
                    "GET"

                connection.connectTimeout =
                    10000

                connection.readTimeout =
                    10000

                val code =
                    connection.responseCode

                connection.disconnect()

                runOnUiThread {

                    if (code in 200..299) {

                        statusText.text =
                            "🟢 Servidor conectado"
                    } else {

                        statusText.text =
                            "🟡 Servidor respondeu HTTP $code"
                    }
                }

            } catch (e: Exception) {

                runOnUiThread {

                    statusText.text =
                        "🔴 Servidor offline"
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
            requestCode == REQUEST_RECORD_AUDIO
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
            }
        }
    }
}
