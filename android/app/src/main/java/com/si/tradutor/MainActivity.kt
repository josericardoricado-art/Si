package com.si.tradutor

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private val BACKEND_URL = "https://si-u2ul.onrender.com"

    private lateinit var status: TextView
    private lateinit var videoView: VideoView
    private lateinit var videoMessage: TextView
    private lateinit var socialUrl: EditText
    private lateinit var languageSpinner: Spinner

    private var microphoneOn = false

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_main)

        status = findViewById(R.id.status)
        videoView = findViewById(R.id.videoView)
        videoMessage = findViewById(R.id.videoMessage)
        socialUrl = findViewById(R.id.socialUrl)
        languageSpinner = findViewById(R.id.languageSpinner)

        val startButton = findViewById<Button>(R.id.startButton)
        val loadVideoButton = findViewById<Button>(R.id.loadVideoButton)
        val dubButton = findViewById<Button>(R.id.dubButton)
        val microphoneButton = findViewById<Button>(R.id.microphoneButton)
        val translationButton = findViewById<Button>(R.id.translationButton)
        val registerButton = findViewById<Button>(R.id.registerButton)
        val plansButton = findViewById<Button>(R.id.plansButton)

        configurarIdiomas()

        startButton.setOnClickListener {
            status.text =
                "🟢 SI Tradutor iniciado!\n\n" +
                "Cole o link do vídeo ou live abaixo."

            socialUrl.requestFocus()
        }

        loadVideoButton.setOnClickListener {
            carregarVideo()
        }

        dubButton.setOnClickListener {
            enviarLinkParaServidor(dubButton)
        }

        microphoneButton.setOnClickListener {
            alternarMicrofone(microphoneButton)
        }

        translationButton.setOnClickListener {
            iniciarTraducao()
        }

        registerButton.setOnClickListener {
            mostrarCadastro()
        }

        plansButton.setOnClickListener {
            mostrarPlanos()
        }

        verificarServidor()
    }

    private fun configurarIdiomas() {

        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            languages
        )

        adapter.setDropDownViewResource(
            android.R.layout.simple_spinner_dropdown_item
        )

        languageSpinner.adapter = adapter
    }

    private fun obterIdiomaSelecionado(): String {

        val position = languageSpinner.selectedItemPosition

        if (position < 0 || position >= languageCodes.size) {
            return "pt"
        }

        return languageCodes[position]
    }

    private fun obterNomeIdioma(): String {

        val position = languageSpinner.selectedItemPosition

        if (position < 0 || position >= languages.size) {
            return "Português"
        }

        return languages[position]
    }

    private fun carregarVideo() {

        val link = socialUrl.text.toString().trim()

        if (link.isEmpty()) {

            Toast.makeText(
                this,
                "Cole primeiro o link do vídeo.",
                Toast.LENGTH_LONG
            ).show()

            socialUrl.requestFocus()
            return
        }

        if (!link.startsWith("http://") &&
            !link.startsWith("https://")
        ) {

            Toast.makeText(
                this,
                "Digite um link começando com https://",
                Toast.LENGTH_LONG
            ).show()

            return
        }

        videoMessage.visibility = View.VISIBLE

        videoMessage.text =
            "⏳ Tentando carregar o vídeo..."

        status.text =
            "🟡 Carregando vídeo...\n\n" +
            "Verificando o endereço informado."

        /*
         * VideoView funciona com arquivos de vídeo diretos,
         * como MP4.
         *
         * Links de páginas do YouTube, TikTok etc.
         * não são necessariamente arquivos de vídeo.
         */

        try {

            videoView.setVideoURI(Uri.parse(link))

            videoView.setOnPreparedListener {

                videoMessage.visibility = View.GONE

                status.text =
                    "🟢 Vídeo carregado!\n\n" +
                    "Você pode iniciar a reprodução."

                videoView.start()
            }

            videoView.setOnErrorListener { _, _, _ ->

                videoMessage.visibility = View.VISIBLE

                videoMessage.text =
                    "🎬 Este link é uma página de vídeo.\n\n" +
                    "A reprodução direta ainda será integrada."

                status.text =
                    "🟡 Link recebido.\n\n" +
                    "Para YouTube/live/TikTok precisamos usar a captura de áudio do Android."

                false
            }

            videoView.requestFocus()

        } catch (e: Exception) {

            videoMessage.visibility = View.VISIBLE

            videoMessage.text =
                "⚠️ Não foi possível abrir este endereço."

            status.text =
                "🔴 Erro ao abrir vídeo\n\n" +
                "${e.message}"
        }
    }

    private fun enviarLinkParaServidor(button: Button) {

        val link = socialUrl.text.toString().trim()

        if (link.isEmpty()) {

            Toast.makeText(
                this,
                "Cole primeiro o link do vídeo ou live.",
                Toast.LENGTH_LONG
            ).show()

            socialUrl.requestFocus()
            return
        }

        if (!link.startsWith("http://") &&
            !link.startsWith("https://")
        ) {

            Toast.makeText(
                this,
                "Digite um link válido começando com https://",
                Toast.LENGTH_LONG
            ).show()

            return
        }

        val targetLang = obterIdiomaSelecionado()
        val languageName = obterNomeIdioma()

        status.text =
            "🟡 Enviando link...\n\n" +
            "Idioma: $languageName\n" +
            "Conectando ao servidor SI..."

        button.isEnabled = false

        thread {

            var connection: HttpURLConnection? = null

            try {

                val url =
                    URL("$BACKEND_URL/api/dub-url")

                connection =
                    url.openConnection() as HttpURLConnection

                connection.requestMethod = "POST"

                connection.setRequestProperty(
                    "Content-Type",
                    "application/json"
                )

                connection.setRequestProperty(
                    "Accept",
                    "application/json"
                )

                connection.doOutput = true

                connection.connectTimeout = 15000
                connection.readTimeout = 30000

                val json = JSONObject()

                json.put("url", link)
                json.put("targetLang", targetLang)

                connection.outputStream.use { output ->

                    output.write(
                        json.toString()
                            .toByteArray(Charsets.UTF_8)
                    )
                }

                val responseCode =
                    connection.responseCode

                val responseText =

                    if (responseCode in 200..299) {

                        connection.inputStream
                            .bufferedReader()
                            .use { it.readText() }

                    } else {

                        connection.errorStream
                            ?.bufferedReader()
                            ?.use { it.readText() }
                            ?: ""
                    }

                runOnUiThread {

                    button.isEnabled = true

                    if (responseCode in 200..299) {

                        try {

                            val response =
                                JSONObject(responseText)

                            val jobId =
                                response.optString(
                                    "jobId"
                                )

                            status.text =
                                "🟢 Link recebido pelo servidor!\n\n" +
                                "Idioma: $languageName\n" +
                                "Job: $jobId\n\n" +
                                "Preparando a captura de áudio..."

                            Toast.makeText(
                                this,
                                "Dublagem iniciada!",
                                Toast.LENGTH_LONG
                            ).show()

                        } catch (e: Exception) {

                            status.text =
                                "🟢 Link enviado com sucesso!\n\n" +
                                "Preparando a dublagem..."
                        }

                    } else {

                        status.text =
                            "🔴 Erro no servidor\n\n" +
                            "Código: $responseCode\n" +
                            responseText

                        Toast.makeText(
                            this,
                            "O servidor recusou o link.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }

            } catch (e: Exception) {

                runOnUiThread {

                    button.isEnabled = true

                    status.text =
                        "🔴 Erro de conexão\n\n" +
                        "${e.message}"

                    Toast.makeText(
                        this,
                        "Não foi possível conectar ao servidor.",
                        Toast.LENGTH_LONG
                    ).show()
                }

            } finally {

                connection?.disconnect()
            }
        }
    }

    private fun alternarMicrofone(button: Button) {

        if (!microphoneOn) {

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
                    100
                )

                return
            }

            microphoneOn = true

            button.text =
                "🔴  DESLIGAR MICROFONE"

            status.text =
                "🟢 Microfone ligado!\n\n" +
                "O aplicativo está pronto para captura de áudio."

        } else {

            microphoneOn = false

            button.text =
                "🎙️  LIGAR MICROFONE"

            status.text =
                "🟡 Microfone desligado."
        }
    }

    private fun iniciarTraducao() {

        val languageName = obterNomeIdioma()

        status.text =
            "🌎 Tradução selecionada\n\n" +
            "Idioma de saída: $languageName\n\n" +
            "Aguardando captura do áudio do vídeo."

        Toast.makeText(
            this,
            "Idioma selecionado: $languageName",
            Toast.LENGTH_LONG
        ).show()
    }

    private fun mostrarCadastro() {

        val layout = EditText(this)

        layout.hint = "Digite seu e-mail"
        layout.setPadding(30, 20, 30, 20)

        AlertDialog.Builder(this)
            .setTitle("👤 Cadastro / Entrar")
            .setMessage(
                "Crie sua conta para acessar o SI Tradutor."
            )
            .setView(layout)
            .setPositiveButton("CONTINUAR") { _, _ ->

                val email =
                    layout.text.toString().trim()

                if (email.isEmpty()) {

                    Toast.makeText(
                        this,
                        "Digite seu e-mail.",
                        Toast.LENGTH_LONG
                    ).show()

                } else {

                    status.text =
                        "👤 Cadastro iniciado!\n\n" +
                        "E-mail: $email\n\n" +
                        "A autenticação completa será conectada ao servidor."
                }
            }
            .setNegativeButton("CANCELAR", null)
            .show()
    }

    private fun mostrarPlanos() {

        AlertDialog.Builder(this)
            .setTitle("💳 Planos SI Tradutor")
            .setMessage(
                "🆓 GRÁTIS\n" +
                "Teste limitado\n\n" +

                "⭐ PLANO MENSAL\n" +
                "Tradução e dublagem com mais recursos\n\n" +

                "🚀 PLANO PRO\n" +
                "Mais tempo de tradução e recursos avançados\n\n" +

                "O sistema de pagamento será conectado ao checkout na próxima etapa."
            )
            .setPositiveButton("ESCOLHER PLANO") { _, _ ->

                status.text =
                    "💳 Planos selecionados.\n\n" +
                    "O checkout será conectado nesta etapa."

            }
            .setNegativeButton("FECHAR", null)
            .show()
    }

    private fun verificarServidor() {

        thread {

            try {

                val url =
                    URL("$BACKEND_URL/api/health")

                val connection =
                    url.openConnection() as HttpURLConnection

                connection.requestMethod = "GET"

                connection.connectTimeout = 10000
                connection.readTimeout = 10000

                val code =
                    connection.responseCode

                connection.disconnect()

                runOnUiThread {

                    if (code in 200..299) {

                        status.text =
                            "🟢 Servidor SI conectado!\n\n" +
                            "Pronto para receber o vídeo."

                    } else {

                        status.text =
                            "🟡 Servidor respondeu com código $code"
                    }
                }

            } catch (e: Exception) {

                runOnUiThread {

                    status.text =
                        "🔴 Servidor indisponível.\n\n" +
                        "Verifique sua conexão com a internet."
                }
            }
        }
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

        if (requestCode == 100) {

            if (
                grantResults.isNotEmpty() &&
                grantResults[0] ==
                PackageManager.PERMISSION_GRANTED
            ) {

                Toast.makeText(
                    this,
                    "Microfone autorizado!",
                    Toast.LENGTH_LONG
                ).show()

            } else {

                Toast.makeText(
                    this,
                    "Permissão do microfone recusada.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
}
