package com.si.tradutor

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.widget.TextView
import android.graphics.Color
import android.graphics.Typeface
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    companion object {

        private const val BACKEND_URL =
            "https://si-u2ul.onrender.com"

        private const val REQUEST_RECORD_AUDIO =
            5001

        private const val REQUEST_MEDIA_PROJECTION =
            5002

        private const val PREFS =
            "si_preferences"

        private const val CLIENT_ID =
            "client_id"
    }

    private lateinit var statusText:
            TextView

    private lateinit var monitorButton:
            Button

    private lateinit var stopButton:
            Button

    private lateinit var languageSpinner:
            Spinner

    private var currentJobId:
            String? = null

    private var monitoring =
        false

    private val handler =
        Handler(
            Looper.getMainLooper()
        )


    /*
     * =====================================================
     * ON CREATE
     * =====================================================
     */

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {

        super.onCreate(
            savedInstanceState
        )

        criarTela()

        verificarPermissaoMicrofone()
    }


    /*
     * =====================================================
     * TELA
     * =====================================================
     */

    private fun criarTela() {

        val root =
            LinearLayout(this)

        root.orientation =
            LinearLayout.VERTICAL

        root.setPadding(
            48,
            60,
            48,
            40
        )

        root.gravity =
            Gravity.CENTER_HORIZONTAL

        root.setBackgroundColor(
            Color.rgb(
                5,
                21,
                47
            )
        )


        /*
         * LOGO
         */

        val logo =
            TextView(this)

        logo.text =
            "SI"

        logo.textSize =
            70f

        logo.setTextColor(
            Color.WHITE
        )

        logo.setTypeface(
            null,
            Typeface.BOLD
        )

        logo.gravity =
            Gravity.CENTER

        root.addView(
            logo,
            LinearLayout.LayoutParams(
                -1,
                130
            )
        )


        /*
         * TÍTULO
         */

        val title =
            TextView(this)

        title.text =
            "SI TRADUTOR LIVE"

        title.textSize =
            30f

        title.setTextColor(
            Color.WHITE
        )

        title.setTypeface(
            null,
            Typeface.BOLD
        )

        title.gravity =
            Gravity.CENTER

        root.addView(
            title
        )


        /*
         * SUBTÍTULO
         */

        val subtitle =
            TextView(this)

        subtitle.text =
            "Tradução e dublagem em tempo real"

        subtitle.textSize =
            18f

        subtitle.setTextColor(
            Color.LTGRAY
        )

        subtitle.gravity =
            Gravity.CENTER

        subtitle.setPadding(
            0,
            15,
            0,
            35
        )

        root.addView(
            subtitle
        )


        /*
         * STATUS
         */

        statusText =
            TextView(this)

        statusText.text =
            "🔵 Verificando conexão com o servidor..."

        statusText.textSize =
            17f

        statusText.setTextColor(
            Color.WHITE
        )

        statusText.gravity =
            Gravity.CENTER

        statusText.setPadding(
            20,
            25,
            20,
            25
        )

        statusText.setBackgroundColor(
            Color.rgb(
                20,
                48,
                90
            )
        )

        root.addView(
            statusText,
            LinearLayout.LayoutParams(
                -1,
                100
            )
        )


        /*
         * ESPAÇO
         */

        adicionarEspaco(
            root,
            25
        )


        /*
         * BOTÃO MONITORAR
         */

        monitorButton =
            Button(this)

        monitorButton.text =
            "🎬 MONITORAR TELA"

        monitorButton.textSize =
            18f

        monitorButton.setTextColor(
            Color.WHITE
        )

        monitorButton.setBackgroundColor(
            Color.rgb(
                25,
                140,
                255
            )
        )

        monitorButton.setOnClickListener {

            iniciarMonitoramento()
        }

        root.addView(
            monitorButton,
            LinearLayout.LayoutParams(
                -1,
                90
            )
        )


        adicionarEspaco(
            root,
            18
        )


        /*
         * BOTÃO PARAR
         */

        stopButton =
            Button(this)

        stopButton.text =
            "⏹ PARAR MONITORAMENTO"

        stopButton.textSize =
            17f

        stopButton.setTextColor(
            Color.WHITE
        )

        stopButton.setBackgroundColor(
            Color.rgb(
                40,
                65,
                105
            )
        )

        stopButton.isEnabled =
            false

        stopButton.setOnClickListener {

            pararMonitoramento()
        }

        root.addView(
            stopButton,
            LinearLayout.LayoutParams(
                -1,
                90
            )
        )


        adicionarEspaco(
            root,
            25
        )


        /*
         * IDIOMA
         */

        val languageTitle =
            TextView(this)

        languageTitle.text =
            "🌎 Idioma da tradução"

        languageTitle.textSize =
            20f

        languageTitle.setTextColor(
            Color.WHITE
        )

        languageTitle.setTypeface(
            null,
            Typeface.BOLD
        )

        root.addView(
            languageTitle
        )


        adicionarEspaco(
            root,
            10
        )


        /*
         * SPINNER
         */

        languageSpinner =
            Spinner(this)

        val languages =
            arrayOf(
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

        val adapter =
            ArrayAdapter(
                this,
                android.R.layout.simple_spinner_dropdown_item,
                languages
            )

        languageSpinner.adapter =
            adapter

        root.addView(
            languageSpinner,
            LinearLayout.LayoutParams(
                -1,
                70
            )
        )


        adicionarEspaco(
            root,
            25
        )


        /*
         * DUBLAGEM
         */

        val dubbing =
            Button(this)

        dubbing.text =
            "🔊 DUBLAGEM ATIVADA"

        dubbing.textSize =
            17f

        dubbing.setTextColor(
            Color.BLACK
        )

        dubbing.setBackgroundColor(
            Color.rgb(
                30,
                225,
                120
            )
        )

        root.addView(
            dubbing,
            LinearLayout.LayoutParams(
                -1,
                85
            )
        )


        adicionarEspaco(
            root,
            15
        )


        /*
         * TRADUÇÃO
         */

        val translation =
            Button(this)

        translation.text =
            "🌐 TRADUÇÃO ATIVADA"

        translation.textSize =
            17f

        translation.setTextColor(
            Color.WHITE
        )

        translation.setBackgroundColor(
            Color.rgb(
                25,
                140,
                255
            )
        )

        root.addView(
            translation,
            LinearLayout.LayoutParams(
                -1,
                85
            )
        )


        setContentView(
            root
        )
    }


    private fun adicionarEspaco(
        root: LinearLayout,
        altura: Int
    ) {

        val space =
            View(this)

        root.addView(
            space,
            LinearLayout.LayoutParams(
                1,
                altura
            )
        )
    }


    /*
     * =====================================================
     * PERMISSÃO MICROFONE
     * =====================================================
     */

    private fun verificarPermissaoMicrofone() {

        if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.M
        ) {

            if (
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.RECORD_AUDIO
                ) !=
                PackageManager.PERMISSION_GRANTED
            ) {

                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(
                        Manifest.permission.RECORD_AUDIO
                    ),
                    REQUEST_RECORD_AUDIO
                )
            }
        }
    }


    /*
     * =====================================================
     * INICIAR MONITORAMENTO
     * =====================================================
     */

    private fun iniciarMonitoramento() {

        if (monitoring) {

            Toast.makeText(
                this,
                "Monitoramento já está ativo",
                Toast.LENGTH_SHORT
            ).show()

            return
        }


        status(
            "🟡 Acordando servidor..."
        )


        monitorButton.isEnabled =
            false


        /*
         * Primeiro acordamos o Render
         * através do health.
         */

        thread {

            val servidorOk =
                esperarRender()


            runOnUiThread {

                if (!servidorOk) {

                    status(
                        "❌ Não foi possível conectar ao Render"
                    )

                    monitorButton.isEnabled =
                        true

                    Toast.makeText(
                        this,
                        "O servidor demorou para responder.",
                        Toast.LENGTH_LONG
                    ).show()

                    return@runOnUiThread
                }


                status(
                    "🟢 Render conectado. Criando sessão..."
                )


                criarSessao()
            }
        }
    }


    /*
     * =====================================================
     * ESPERAR RENDER
     * =====================================================
     */

    private fun esperarRender():
            Boolean {

        /*
         * Faz até 5 tentativas.
         *
         * Cada uma pode esperar até 30 segundos.
         */

        for (
            tentativa in 1..5
        ) {

            try {

                println(
                    "SI: testando Render tentativa $tentativa"
                )


                val connection =
                    URL(
                        "$BACKEND_URL/api/health"
                    )
                        .openConnection()
                        as HttpURLConnection


                connection.requestMethod =
                    "GET"


                connection.connectTimeout =
                    30000


                connection.readTimeout =
                    30000


                connection.useCaches =
                    false


                connection.setRequestProperty(
                    "Connection",
                    "close"
                )


                val code =
                    connection.responseCode


                println(
                    "SI: Render health HTTP $code"
                )


                if (
                    code in 200..299
                ) {

                    connection.disconnect()

                    return true
                }


                connection.disconnect()

            } catch (e: Exception) {

                println(
                    "SI: health erro: ${e.message}"
                )
            }


            /*
             * Dá alguns segundos para o Render
             * terminar de acordar.
             */

            try {

                Thread.sleep(
                    3000
                )

            } catch (
                _: Exception
            ) {
            }
        }


        return false
    }


    /*
     * =====================================================
     * CRIAR SESSÃO
     * =====================================================
     */

    private fun criarSessao() {

        thread {

            try {

                val clientId =
                    obterClientId()


                val targetLang =
                    obterIdiomaSelecionado()


                println(
                    "SI: clientId = $clientId"
                )

                println(
                    "SI: targetLang = $targetLang"
                )


                val connection =
                    URL(
                        "$BACKEND_URL/api/audio/start"
                    )
                        .openConnection()
                        as HttpURLConnection


                connection.requestMethod =
                    "POST"


                connection.connectTimeout =
                    30000


                connection.readTimeout =
                    30000


                connection.doOutput =
                    true


                connection.useCaches =
                    false


                connection.setRequestProperty(
                    "Content-Type",
                    "application/json"
                )


                connection.setRequestProperty(
                    "Accept",
                    "application/json"
                )


                connection.setRequestProperty(
                    "Connection",
                    "close"
                )


                val json =
                    JSONObject()


                json.put(
                    "clientId",
                    clientId
                )


                json.put(
                    "targetLang",
                    targetLang
                )


                println(
                    "SI: enviando /api/audio/start"
                )


                OutputStreamWriter(
                    connection.outputStream
                ).use { writer ->

                    writer.write(
                        json.toString()
                    )

                    writer.flush()
                }


                val responseCode =
                    connection.responseCode


                println(
                    "SI: /api/audio/start HTTP $responseCode"
                )


                val stream =
                    if (
                        responseCode in 200..299
                    ) {

                        connection.inputStream

                    } else {

                        connection.errorStream
                    }


                val response =
                    if (stream != null) {

                        BufferedReader(
                            InputStreamReader(
                                stream
                            )
                        ).use {
                            it.readText()
                        }

                    } else {

                        ""
                    }


                connection.disconnect()


                println(
                    "SI: resposta start = $response"
                )


                if (
                    responseCode !in 200..299
                ) {

                    runOnUiThread {

                        status(
                            "❌ Erro do Render: HTTP $responseCode"
                        )

                        monitorButton.isEnabled =
                            true
                    }

                    return@thread
                }


                val result =
                    JSONObject(
                        response
                    )


                val ok =
                    result.optBoolean(
                        "ok",
                        false
                    )


                val returnedJobId =
                    result.optString(
                        "jobId",
                        ""
                    )


                if (
                    !ok ||
                    returnedJobId.isEmpty()
                ) {

                    runOnUiThread {

                        status(
                            "❌ Render não criou a sessão"
                        )

                        monitorButton.isEnabled =
                            true
                    }

                    return@thread
                }


                currentJobId =
                    returnedJobId


                println(
                    "SI: jobId criado = $returnedJobId"
                )


                runOnUiThread {

                    status(
                        "🟢 Servidor conectado. Autorize a captura..."
                    )


                    pedirMediaProjection()
                }

            } catch (e: Exception) {

                println(
                    "SI: erro criando sessão: ${e.message}"
                )

                e.printStackTrace()


                runOnUiThread {

                    status(
                        "❌ Falha ao conectar: ${e.message}"
                    )

                    monitorButton.isEnabled =
                        true
                }
            }
        }
    }


    /*
     * =====================================================
     * MEDIA PROJECTION
     * =====================================================
     */

    private fun pedirMediaProjection() {

        try {

            val manager =
                getSystemService(
                    Context.MEDIA_PROJECTION_SERVICE
                ) as MediaProjectionManager


            val intent =
                manager.createScreenCaptureIntent()


            startActivityForResult(
                intent,
                REQUEST_MEDIA_PROJECTION
            )

        } catch (e: Exception) {

            status(
                "❌ Erro ao solicitar captura"
            )

            monitorButton.isEnabled =
                true
        }
    }


    /*
     * =====================================================
     * RESULTADO DA MEDIA PROJECTION
     * =====================================================
     */

    @Deprecated(
        "Deprecated in Android API"
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
            resultCode !=
            Activity.RESULT_OK ||
            data == null
        ) {

            status(
                "❌ Captura cancelada"
            )

            monitorButton.isEnabled =
                true

            pararSessaoBackend()

            return
        }


        val job =
            currentJobId


        if (
            job.isNullOrEmpty()
        ) {

            status(
                "❌ Sessão inválida"
            )

            monitorButton.isEnabled =
                true

            return
        }


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
                resultCode
            )


            serviceIntent.putExtra(
                AudioCaptureService.EXTRA_RESULT_DATA,
                data
            )


            serviceIntent.putExtra(
                AudioCaptureService.EXTRA_JOB_ID,
                job
            )


            if (
                Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.O
            ) {

                ContextCompat.startForegroundService(
                    this,
                    serviceIntent
                )

            } else {

                startService(
                    serviceIntent
                )
            }


            monitoring =
                true


            monitorButton.isEnabled =
                false


            stopButton.isEnabled =
                true


            status(
                "🟢 MONITORANDO ÁUDIO DO VÍDEO"
            )


            println(
                "SI: AudioCaptureService iniciado"
            )

        } catch (e: Exception) {

            println(
                "SI: erro iniciando serviço: ${e.message}"
            )

            status(
                "❌ Erro iniciando monitoramento"
            )

            monitorButton.isEnabled =
                true

            pararSessaoBackend()
        }
    }


    /*
     * =====================================================
     * ID DO CLIENTE
     * =====================================================
     */

    private fun obterClientId():
            String {

        val prefs:
                SharedPreferences =
            getSharedPreferences(
                PREFS,
                Context.MODE_PRIVATE
            )


        var id =
            prefs.getString(
                CLIENT_ID,
                null
            )


        if (
            id.isNullOrEmpty()
        ) {

            id =
                UUID.randomUUID()
                    .toString()


            prefs.edit()
                .putString(
                    CLIENT_ID,
                    id
                )
                .apply()
        }


        return id
    }


    /*
     * =====================================================
     * IDIOMA
     * =====================================================
     */

    private fun obterIdiomaSelecionado():
            String {

        return when (
            languageSpinner.selectedItemPosition
        ) {

            0 -> "pt"

            1 -> "en"

            2 -> "es"

            3 -> "fr"

            4 -> "de"

            5 -> "it"

            6 -> "ja"

            7 -> "ko"

            8 -> "zh-Hans"

            9 -> "ru"

            10 -> "ar"

            11 -> "hi"

            12 -> "tr"

            13 -> "nl"

            14 -> "pl"

            15 -> "uk"

            16 -> "th"

            17 -> "id"

            18 -> "vi"

            else -> "pt"
        }
    }


    /*
     * =====================================================
     * PARAR MONITORAMENTO
     * =====================================================
     */

    private fun pararMonitoramento() {

        try {

            val intent =
                Intent(
                    this,
                    AudioCaptureService::class.java
                )


            intent.action =
                AudioCaptureService.ACTION_STOP


            startService(
                intent
            )

        } catch (
            _: Exception
        ) {
        }


        pararSessaoBackend()


        monitoring =
            false


        currentJobId =
            null


        stopButton.isEnabled =
            false


        monitorButton.isEnabled =
            true


        status(
            "🔵 Monitoramento parado"
        )
    }


    /*
     * =====================================================
     * PARAR SESSÃO NO BACKEND
     * =====================================================
     */

    private fun pararSessaoBackend() {

        val job =
            currentJobId


        if (
            job.isNullOrEmpty()
        ) {

            return
        }


        thread {

            try {

                val connection =
                    URL(
                        "$BACKEND_URL/api/audio/stop"
                    )
                        .openConnection()
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
                    "application/json"
                )


                connection.setRequestProperty(
                    "Connection",
                    "close"
                )


                val json =
                    JSONObject()


                json.put(
                    "jobId",
                    job
                )


                OutputStreamWriter(
                    connection.outputStream
                ).use { writer ->

                    writer.write(
                        json.toString()
                    )

                    writer.flush()
                }


                println(
                    "SI: sessão parada HTTP ${connection.responseCode}"
                )


                connection.disconnect()

            } catch (e: Exception) {

                println(
                    "SI: erro parando sessão: ${e.message}"
                )
            }
        }
    }


    /*
     * =====================================================
     * STATUS
     * =====================================================
     */

    private fun status(
        texto: String
    ) {

        runOnUiThread {

            if (
                ::statusText.isInitialized
            ) {

                statusText.text =
                    texto
            }
        }
    }


    /*
     * =====================================================
     * DESTROY
     * =====================================================
     */

    override fun onDestroy() {

        if (monitoring) {

            try {

                val intent =
                    Intent(
                        this,
                        AudioCaptureService::class.java
                    )


                intent.action =
                    AudioCaptureService.ACTION_STOP


                startService(
                    intent
                )

            } catch (
                _: Exception
            ) {
            }
        }


        super.onDestroy()
    }
}
