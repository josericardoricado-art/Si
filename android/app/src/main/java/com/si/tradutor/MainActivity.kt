package com.si.tradutor

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    companion object {
        private const val BACKEND_URL =
            "https://si-u2ul.onrender.com"
    }

    private lateinit var statusText: TextView
    private lateinit var monitorButton: Button
    private lateinit var stopButton: Button
    private lateinit var languageSpinner: Spinner
    private lateinit var dubbingButton: Button
    private lateinit var translationButton: Button
    private lateinit var registerButton: Button
    private lateinit var plansButton: Button

    private var mediaProjectionManager:
            MediaProjectionManager? = null

    private var dubbingEnabled = true
    private var translationEnabled = true

    /*
     * Idiomas disponíveis.
     */
    private val languages = arrayOf(
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

    /*
     * Permissão necessária para o sistema permitir
     * AudioPlaybackCapture.
     *
     * A captura do áudio do vídeo NÃO será feita
     * pelo microfone.
     */
    private val microphonePermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->

            if (granted) {
                solicitarMonitoramento()
            } else {

                statusText.text =
                    "Permissão de áudio não concedida."

                Toast.makeText(
                    this,
                    "A permissão de áudio é necessária para monitorar a reprodução.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

    /*
     * Autorização oficial do Android para
     * MediaProjection.
     */
    private val screenCaptureLauncher =
        registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->

            if (
                result.resultCode == Activity.RESULT_OK &&
                result.data != null
            ) {

                iniciarCaptura(
                    result.resultCode,
                    result.data!!
                )

            } else {

                statusText.text =
                    "Monitoramento cancelado."

                Toast.makeText(
                    this,
                    "A autorização de monitoramento foi cancelada.",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {
        super.onCreate(savedInstanceState)

        setContentView(
            R.layout.activity_main
        )

        mediaProjectionManager =
            getSystemService(
                MEDIA_PROJECTION_SERVICE
            ) as MediaProjectionManager

        localizarElementos()

        configurarIdiomas()

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

        dubbingButton =
            findViewById(R.id.dubbingButton)

        translationButton =
            findViewById(R.id.translationButton)

        registerButton =
            findViewById(R.id.registerButton)

        plansButton =
            findViewById(R.id.plansButton)
    }

    private fun configurarIdiomas() {

        val adapter =
            ArrayAdapter(
                this,
                android.R.layout.simple_spinner_item,
                languages
            )

        adapter.setDropDownViewResource(
            android.R.layout.simple_spinner_dropdown_item
        )

        languageSpinner.adapter =
            adapter

        /*
         * Português como idioma inicial.
         */
        languageSpinner.setSelection(0)
    }

    private fun configurarBotoes() {

        /*
         * MONITORAR TELA
         */
        monitorButton.setOnClickListener {

            iniciarProcessoMonitoramento()
        }

        /*
         * PARAR
         */
        stopButton.setOnClickListener {

            pararMonitoramento()
        }

        /*
         * DUBLAGEM
         */
        dubbingButton.setOnClickListener {

            dubbingEnabled =
                !dubbingEnabled

            if (dubbingEnabled) {

                dubbingButton.text =
                    "🔊  DUBLAGEM ATIVADA"

                statusText.text =
                    "Dublagem ativada."

            } else {

                dubbingButton.text =
                    "🔇  DUBLAGEM DESATIVADA"

                statusText.text =
                    "Dublagem desativada."
            }
        }

        /*
         * TRADUÇÃO
         */
        translationButton.setOnClickListener {

            translationEnabled =
                !translationEnabled

            if (translationEnabled) {

                translationButton.text =
                    "🌐  TRADUÇÃO ATIVADA"

                statusText.text =
                    "Tradução ativada."

            } else {

                translationButton.text =
                    "🌐  TRADUÇÃO DESATIVADA"

                statusText.text =
                    "Tradução desativada."
            }
        }

        /*
         * CADASTRO
         */
        registerButton.setOnClickListener {

            mostrarCadastro()
        }

        /*
         * PLANOS
         */
        plansButton.setOnClickListener {

            mostrarPlanos()
        }
    }

    private fun iniciarProcessoMonitoramento() {

        if (Build.VERSION.SDK_INT <
            Build.VERSION_CODES.Q
        ) {

            statusText.text =
                "Android 10 ou superior necessário."

            Toast.makeText(
                this,
                "A captura de áudio da reprodução exige Android 10+.",
                Toast.LENGTH_LONG
            ).show()

            return
        }

        statusText.text =
            "Preparando monitoramento..."

        /*
         * Verifica RECORD_AUDIO.
         *
         * Não significa que vamos usar o microfone
         * para capturar o vídeo.
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
                "Solicitando autorização de áudio..."

            microphonePermissionLauncher.launch(
                Manifest.permission.RECORD_AUDIO
            )

        } else {

            solicitarMonitoramento()
        }
    }

    private fun solicitarMonitoramento() {

        statusText.text =
            "Aguardando autorização do Android..."

        val intent =
            mediaProjectionManager
                ?.createScreenCaptureIntent()

        if (intent == null) {

            statusText.text =
                "Não foi possível iniciar o monitoramento."

            return
        }

        /*
         * O Android exibirá a confirmação oficial.
         */
        screenCaptureLauncher.launch(
            intent
        )
    }

    private fun iniciarCaptura(
        resultCode: Int,
        data: Intent
    ) {

        val idioma =
            languageSpinner.selectedItem
                ?.toString()
                ?: "Português"

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

        try {

            ContextCompat.startForegroundService(
                this,
                serviceIntent
            )

            monitorButton.isEnabled = false
            stopButton.isEnabled = true

            statusText.text =
                "🎬 Monitorando • $idioma"

            Toast.makeText(
                this,
                "Monitoramento iniciado.",
                Toast.LENGTH_SHORT
            ).show()

        } catch (e: Exception) {

            monitorButton.isEnabled = true
            stopButton.isEnabled = false

            statusText.text =
                "Erro ao iniciar monitoramento."

            Toast.makeText(
                this,
                "Erro: ${e.message}",
                Toast.LENGTH_LONG
            ).show()
        }
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

        try {
            startService(serviceIntent)
        } catch (_: Exception) {
        }

        try {
            stopService(serviceIntent)
        } catch (_: Exception) {
        }

        monitorButton.isEnabled = true
        stopButton.isEnabled = false

        statusText.text =
            "Monitoramento parado."

        Toast.makeText(
            this,
            "Monitoramento encerrado.",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun mostrarCadastro() {

        val mensagem =
            """
            👤 CADASTRO

            O sistema de cadastro será conectado
            ao servidor nas próximas etapas.

            Aqui vamos colocar:

            • Criar conta
            • Entrar
            • E-mail
            • Senha
            • Área do usuário
            """.trimIndent()

        android.app.AlertDialog.Builder(this)
            .setTitle("SI Tradutor Live")
            .setMessage(mensagem)
            .setPositiveButton(
                "OK",
                null
            )
            .show()
    }

    private fun mostrarPlanos() {

        val mensagem =
            """
            💳 PLANOS SI TRADUTOR LIVE

            GRATUITO
            • Teste do monitoramento

            PRO
            • Tradução em tempo real
            • Dublagem
            • Mais recursos

            PREMIUM
            • Tradução e dublagem avançadas
            • Mais tempo de uso
            • Recursos completos

            O pagamento será conectado
            ao Mercado Pago na próxima etapa.
            """.trimIndent()

        android.app.AlertDialog.Builder(this)
            .setTitle("Planos")
            .setMessage(mensagem)
            .setPositiveButton(
                "OK",
                null
            )
            .show()
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
                            "🟢 Servidor online • Pronto para monitorar"

                    } else {

                        statusText.text =
                            "🟠 Servidor respondeu com erro."
                    }
                }

            } catch (_: Exception) {

                runOnUiThread {

                    statusText.text =
                        "🔴 Sem conexão com o servidor."
                }
            }

        }.start()
    }

    override fun onDestroy() {

        /*
         * O serviço pode continuar funcionando
         * mesmo quando esta tela é fechada.
         */
        super.onDestroy()
    }
}
