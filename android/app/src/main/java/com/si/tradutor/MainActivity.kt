package com.si.tradutor

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    companion object {
        private const val BACKEND_URL = "https://si-u2ul.onrender.com"

        private const val REQUEST_CAPTURE_AUDIO = 100
    }

    private lateinit var statusText: TextView
    private lateinit var monitorButton: Button
    private lateinit var stopButton: Button
    private lateinit var languageSpinner: Spinner

    private var mediaProjectionManager: MediaProjectionManager? = null

    /*
     * Autorização do microfone.
     *
     * IMPORTANTE:
     * O aplicativo não vai usar o microfone
     * para ouvir o vídeo.
     *
     * Essa permissão é necessária pelo Android
     * para utilizar AudioPlaybackCapture.
     */
    private val microphonePermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->

            if (granted) {
                solicitarCapturaDeTela()
            } else {

                statusText.text =
                    "Permissão de áudio não concedida."

                Toast.makeText(
                    this,
                    "Precisamos da permissão de áudio para monitorar a reprodução.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

    /*
     * Resultado da autorização oficial do Android
     * para MediaProjection.
     */
    private val screenCaptureLauncher =
        registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->

            if (
                result.resultCode == Activity.RESULT_OK &&
                result.data != null
            ) {

                iniciarServicoDeCaptura(
                    result.resultCode,
                    result.data!!
                )

            } else {

                statusText.text =
                    "Monitoramento cancelado."

                Toast.makeText(
                    this,
                    "Você cancelou a autorização de monitoramento.",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_main)

        mediaProjectionManager =
            getSystemService(
                MEDIA_PROJECTION_SERVICE
            ) as MediaProjectionManager

        localizarElementos()

        configurarBotoes()

        verificarServidor()
    }

    private fun localizarElementos() {

        statusText =
            findViewById(R.id.statusText)

        monitorButton =
            findViewById(R.id.monitorButton)

        stopButton =
            findViewById(R.id.stopButton)

        languageSpinner =
            findViewById(R.id.languageSpinner)
    }

    private fun configurarBotoes() {

        monitorButton.setOnClickListener {

            iniciarProcessoDeMonitoramento()
        }

        stopButton.setOnClickListener {

            pararMonitoramento()
        }
    }

    private fun iniciarProcessoDeMonitoramento() {

        statusText.text =
            "Preparando monitoramento..."

        /*
         * Android 10 ou superior é necessário
         * para AudioPlaybackCapture.
         */
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {

            statusText.text =
                "Seu Android precisa ser 10 ou superior."

            Toast.makeText(
                this,
                "A captura de áudio da reprodução exige Android 10+.",
                Toast.LENGTH_LONG
            ).show()

            return
        }

        /*
         * Primeiro verificamos RECORD_AUDIO.
         */
        val permission =
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            )

        if (
            permission !=
            PackageManager.PERMISSION_GRANTED
        ) {

            statusText.text =
                "Solicitando permissão de áudio..."

            microphonePermissionLauncher.launch(
                Manifest.permission.RECORD_AUDIO
            )

        } else {

            solicitarCapturaDeTela()
        }
    }

    private fun solicitarCapturaDeTela() {

        statusText.text =
            "Solicitando autorização do Android..."

        val captureIntent =
            mediaProjectionManager
                ?.createScreenCaptureIntent()

        if (captureIntent == null) {

            statusText.text =
                "Não foi possível iniciar o monitoramento."

            return
        }

        /*
         * O Android exibirá a tela oficial
         * perguntando se o usuário permite
         * que o SI Tradutor monitore a tela.
         */
        screenCaptureLauncher.launch(
            captureIntent
        )
    }

    private fun iniciarServicoDeCaptura(
        resultCode: Int,
        data: Intent
    ) {

        val selectedLanguage =
            languageSpinner.selectedItem
                ?.toString()
                ?: "Português"

        statusText.text =
            "Monitoramento ativado • $selectedLanguage"

        monitorButton.isEnabled = false
        stopButton.isEnabled = true

        val serviceIntent =
            Intent(
                this,
                AudioCaptureService::class.java
            ).apply {

                action =
                    AudioCaptureService.ACTION_START

                putExtra(
                    AudioCaptureService.EXTRA_RESULT_CODE,
                    resultCode
                )

                putExtra(
                    AudioCaptureService.EXTRA_RESULT_DATA,
                    data
                )
            }

        /*
         * Android 8+ exige startForegroundService
         * para serviços que continuarão funcionando
         * em segundo plano.
         */
        ContextCompat.startForegroundService(
            this,
            serviceIntent
        )

        Toast.makeText(
            this,
            "Monitoramento iniciado.",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun pararMonitoramento() {

        val serviceIntent =
            Intent(
                this,
                AudioCaptureService::class.java
            ).apply {

                action =
                    AudioCaptureService.ACTION_STOP
            }

        stopService(serviceIntent)

        statusText.text =
            "Monitoramento parado."

        monitorButton.isEnabled = true
        stopButton.isEnabled = false

        Toast.makeText(
            this,
            "Monitoramento encerrado.",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun verificarServidor() {

        Thread {

            try {

                val url =
                    java.net.URL(
                        "$BACKEND_URL/api/health"
                    )

                val connection =
                    url.openConnection()
                        as java.net.HttpURLConnection

                connection.requestMethod =
                    "GET"

                connection.connectTimeout =
                    10000

                connection.readTimeout =
                    10000

                val responseCode =
                    connection.responseCode

                connection.disconnect()

                runOnUiThread {

                    if (responseCode == 200) {

                        statusText.text =
                            "Servidor online • Pronto para monitorar"

                    } else {

                        statusText.text =
                            "Servidor respondeu com erro."
                    }
                }

            } catch (e: Exception) {

                runOnUiThread {

                    statusText.text =
                        "Servidor offline ou sem conexão."
                }
            }

        }.start()
    }

    override fun onDestroy() {

        /*
         * Não encerramos o serviço aqui,
         * porque o monitoramento pode continuar
         * enquanto o usuário utiliza outro aplicativo.
         */

        super.onDestroy()
    }
}
