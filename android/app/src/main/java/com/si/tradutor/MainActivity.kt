package com.si.tradutor

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
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

    private lateinit var statusText: TextView
    private lateinit var monitorButton: Button
    private lateinit var stopButton: Button
    private lateinit var languageSpinner: Spinner

    private var currentJobId: String? = null
    private var monitoring = false
    private var starting = false


    // =========================================================
    // ON CREATE
    // =========================================================

    override fun onCreate(savedInstanceState: Bundle?) {

        super.onCreate(savedInstanceState)

        criarTela()

        verificarPermissaoMicrofone()
    }


    // =========================================================
    // CRIAR TELA
    // =========================================================

    private fun criarTela() {

        val root = LinearLayout(this)

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


        // -----------------------------------------------------
        // LOGO
        // -----------------------------------------------------

        val logo = TextView(this)

        logo.text = "SI"

        logo.textSize = 70f

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


        // -----------------------------------------------------
        // TÍTULO
        // -----------------------------------------------------

        val title = TextView(this)

        title.text =
            "SI TRADUTOR LIVE"

        title.textSize = 30f

        title.setTextColor(
            Color.WHITE
        )

        title.setTypeface(
            null,
            Typeface.BOLD
        )

        title.gravity =
            Gravity.CENTER

        root.addView(title)


        // -----------------------------------------------------
        // SUBTÍTULO
        // -----------------------------------------------------

        val subtitle = TextView(this)

        subtitle.text =
            "Tradução e dublagem em tempo real"

        subtitle.textSize = 18f

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

        root.addView(subtitle)


        // -----------------------------------------------------
        // STATUS
        // -----------------------------------------------------

        statusText = TextView(this)

        statusText.text =
            "🔵 Pronto para iniciar"

        statusText.textSize = 17f

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


        adicionarEspaco(
            root,
            25
        )


        // -----------------------------------------------------
        // MONITORAR TELA
        // -----------------------------------------------------

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


        // -----------------------------------------------------
        // PARAR
        // -----------------------------------------------------

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


        // -----------------------------------------------------
        // IDIOMA
        // -----------------------------------------------------

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


        // -----------------------------------------------------
        // SPINNER
        // -----------------------------------------------------

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


        // -----------------------------------------------------
        // DUBLAGEM
        // -----------------------------------------------------

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


        // -----------------------------------------------------
        // TRADUÇÃO
        // -----------------------------------------------------

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


        setContentView(root)
    }


    // =========================================================
    // ESPAÇO
    // =========================================================

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


    // =========================================================
    // PERMISSÃO MICROFONE
    // =========================================================

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


    // =========================================================
    // INICIAR MONITORAMENTO
    // =========================================================

    private fun iniciarMonitoramento() {

        if (monitoring) {

            Toast.makeText(
                this,
                "Monitoramento já está ativo",
                Toast.LENGTH_SHORT
            ).show()

            return
        }


        if (starting) {

            return
        }


        starting = true

        monitorButton.isEnabled =
            false

        stopButton.isEnabled =
            false


        status(
            "🟡 Acordando servidor..."
        )


        // -----------------------------------------------------
        // NÃO FAZEMOS MAIS /api/health PRIMEIRO.
        //
        // O próprio /api/audio/start acorda o Render.
        // -----------------------------------------------------

        criarSessao()
    }


    // =========================================================
    // CRIAR SESSÃO
    // =========================================================

    private fun criarSessao() {

        thread {

            var ultimaMensagem =
                "Erro desconhecido"


            // -------------------------------------------------
            // Até 3 tentativas.
            //
            // Isso permite que o Render acorde se estiver
            // dormindo.
            // -------------------------------------------------

            for (
                tentativa in 1..3
            ) {

                try {

                    runOnUiThread {

                        if (tentativa == 1) {

                            status(
                                "🟡 Acordando servidor..."
                            )

                        } else {

                            status(
                                "🟡 Servidor acordando... tentativa $tentativa/3"
                            )
                        }
                    }


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


                    // -------------------------------------------------
                    // Timeout menor que o antigo.
                    //
                    // Se o Render não responder, fazemos nova tentativa.
                    // -------------------------------------------------

                    connection.connectTimeout =
                        15000

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
                            ).use { reader ->

                                reader.readText()
                            }

                        } else {

                            ""
                        }


                    connection.disconnect()


                    println(
                        "SI: resposta start = $response"
                    )


                    // -------------------------------------------------
                    // ERRO HTTP
                    // -------------------------------------------------

                    if (
                        responseCode !in 200..299
                    ) {

                        ultimaMensagem =
                            "HTTP $responseCode"


                        // -------------------------------------------------
                        // 404 não adianta repetir.
                        // Significa que a rota não existe na versão
                        // do Render que está ativa.
                        // -------------------------------------------------

                        if (
                            responseCode == 404
                        ) {

                            runOnUiThread {

                                status(
                                    "❌ Rota /api/audio/start não encontrada no Render"
                                )

                                monitorButton.isEnabled =
                                    true

                                starting =
                                    false

                                Toast.makeText(
                                    this,
                                    "O Render está usando uma versão sem /api/audio/start.",
                                    Toast.LENGTH_LONG
                                ).show()
                            }

                            return@thread
                        }


                        // -------------------------------------------------
                        // Outros erros: tenta novamente.
                        // -------------------------------------------------

                        if (
                            tentativa < 3
                        ) {

                            Thread.sleep(
                                2000
                            )

                            continue
                        }


                        runOnUiThread {

                            status(
                                "❌ Erro do Render: HTTP $responseCode"
                            )

                            monitorButton.isEnabled =
                                true

                            starting =
                                false
                        }

                        return@thread
                    }


                    // -------------------------------------------------
                    // JSON DA SESSÃO
                    // -------------------------------------------------

                    val result =
                        try {

                            JSONObject(
                                response
                            )

                        } catch (
                            e: Exception
                        ) {

                            null
                        }


                    if (
                        result == null
                    ) {

                        ultimaMensagem =
                            "Resposta inválida do servidor"


                        if (
                            tentativa < 3
                        ) {

                            Thread.sleep(
                                2000
                            )

                            continue
                        }


                        runOnUiThread {

                            status(
                                "❌ Resposta inválida do Render"
                            )

                            monitorButton.isEnabled =
                                true

                            starting =
                                false
                        }

                        return@thread
                    }


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


                    println(
                        "SI: ok = $ok"
                    )

                    println(
                        "SI: jobId = $returnedJobId"
                    )


                    // -------------------------------------------------
                    // SESSÃO CRIADA
                    // -------------------------------------------------

                    if (
                        ok &&
                        returnedJobId.isNotEmpty()
                    ) {

                        currentJobId =
                            returnedJobId


                        println(
                            "SI: sessão criada = $returnedJobId"
                        )


                        runOnUiThread {

                            status(
                                "🟢 Servidor conectado. Autorize a captura..."
                            )


                            monitoring =
                                true

                            starting =
                                false


                            monitorButton.isEnabled =
                                false

                            stopButton.isEnabled =
                                true


                            solicitarCapturaDeTela()
                        }


                        return@thread
                    }


                    // -------------------------------------------------
                    // SERVIDOR RESPONDEU MAS NÃO CRIOU SESSÃO
                    // -------------------------------------------------

                    ultimaMensagem =
                        result.optString(
                            "message",
                            "Render não criou a sessão"
                        )


                    if (
                        tentativa < 3
                    ) {

                        Thread.sleep(
                            2000
                        )

                        continue
                    }


                    runOnUiThread {

                        status(
                            "❌ $ultimaMensagem"
                        )

                        monitorButton.isEnabled =
                            true

                        starting =
                            false
                    }

                    return@thread


                } catch (
                    e: Exception
                ) {

                    ultimaMensagem =
                        e.message
                            ?: "Falha de conexão"


                    println(
                        "SI: erro tentativa $tentativa: $ultimaMensagem"
                    )


                    if (
                        tentativa < 3
                    ) {

                        runOnUiThread {

                            status(
                                "🟡 Servidor ainda acordando... tentativa $tentativa/3"
                            )
                        }


                        try {

                            Thread.sleep(
                                2000
                            )

                        } catch (
                            _: Exception
                        ) {
                        }

                        continue
                    }


                    runOnUiThread {

                        status(
                            "❌ Não foi possível conectar ao servidor"
                        )

                        monitorButton.isEnabled =
                            true

                        starting =
                            false


                        Toast.makeText(
                            this,
                            ultimaMensagem,
                            Toast.LENGTH_LONG
                        ).show()
                    }


                    return@thread
                }
            }


            // -----------------------------------------------------
            // SEGURANÇA
            // -----------------------------------------------------

            runOnUiThread {

                status(
                    "❌ Servidor não respondeu"
                )

                monitorButton.isEnabled =
                    true

                starting =
                    false
            }
        }
    }


    // =========================================================
    // SOLICITAR CAPTURA DE TELA
    // =========================================================

    private fun solicitarCapturaDeTela() {

        try {

            val projectionManager =
                getSystemService(
                    Context.MEDIA_PROJECTION_SERVICE
                ) as MediaProjectionManager


            val intent =
                projectionManager
                    .createScreenCaptureIntent()


            startActivityForResult(
                intent,
                REQUEST_MEDIA_PROJECTION
            )

        } catch (
            e: Exception
        ) {

            status(
                "❌ Erro ao abrir captura de tela"
            )


            monitoring =
                false

            starting =
                false


            monitorButton.isEnabled =
                true

            stopButton.isEnabled =
                false


            Toast.makeText(
                this,
                e.message
                    ?: "Não foi possível iniciar a captura",
                Toast.LENGTH_LONG
            ).show()
        }
    }


    // =========================================================
    // RESULTADO DA CAPTURA DE TELA
    // =========================================================

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
            data != null
        ) {

            iniciarCapturaAudio(
                resultCode,
                data
            )

        } else {

            status(
                "❌ Captura de tela não autorizada"
            )


            monitoring =
                false

            starting =
                false


            monitorButton.isEnabled =
                true

            stopButton.isEnabled =
                false


            // -------------------------------------------------
            // Se o usuário cancelar a autorização, também
            // pedimos para o servidor encerrar a sessão.
            // -------------------------------------------------

            encerrarSessaoNoServidor()
        }
    }


    // =========================================================
    // INICIAR CAPTURA DE ÁUDIO
    // =========================================================

    private fun iniciarCapturaAudio(
        resultCode: Int,
        resultData: Intent
    ) {

        val intent =
            Intent(
                this,
                AudioCaptureService::class.java
            )


        intent.action =
            AudioCaptureService.ACTION_START


        intent.putExtra(
            AudioCaptureService.EXTRA_JOB_ID,
            currentJobId
        )


        intent.putExtra(
            AudioCaptureService.EXTRA_RESULT_CODE,
            resultCode
        )


        intent.putExtra(
            AudioCaptureService.EXTRA_RESULT_DATA,
            resultData
        )


        // -----------------------------------------------------
        // Android 8+:
        // inicia o serviço como foreground.
        // -----------------------------------------------------

        try {

            if (
                Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.O
            ) {

                ContextCompat.startForegroundService(
                    this,
                    intent
                )

            } else {

                startService(
                    intent
                )
            }

        } catch (
            e: Exception
        ) {

            monitoring =
                false

            starting =
                false


            monitorButton.isEnabled =
                true

            stopButton.isEnabled =
                false


            status(
                "❌ Não foi possível iniciar o serviço de áudio"
            )


            Toast.makeText(
                this,
                e.message
                    ?: "Erro no serviço de áudio",
                Toast.LENGTH_LONG
            ).show()


            return
        }


        status(
            "🎤 Capturando áudio em tempo real..."
        )
    }


    // =========================================================
    // PARAR MONITORAMENTO
    // =========================================================

    private fun pararMonitoramento() {

        val intent =
            Intent(
                this,
                AudioCaptureService::class.java
            )


        intent.action =
            AudioCaptureService.ACTION_STOP


        try {

            startService(
                intent
            )

        } catch (
            _: Exception
        ) {
        }


        encerrarSessaoNoServidor()


        status(
            "⏸ Monitoramento parado"
        )


        monitoring =
            false

        starting =
            false


        monitorButton.isEnabled =
            true

        stopButton.isEnabled =
            false


        currentJobId =
            null
    }


    // =========================================================
    // ENCERRAR SESSÃO NO SERVIDOR
    // =========================================================

    private fun encerrarSessaoNoServidor() {

        val jobId =
            currentJobId
                ?: return


        thread {

            try {

                val connection =
                    URL(
                        "$BACKEND_URL/api/audio/stop/$jobId"
                    )
                        .openConnection()
                        as HttpURLConnection


                connection.requestMethod =
                    "POST"


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


                try {

                    connection.responseCode

                } catch (
                    _: Exception
                ) {
                }


                connection.disconnect()

            } catch (
                _: Exception
            ) {
            }
        }
    }


    // =========================================================
    // CLIENT ID
    // =========================================================

    private fun obterClientId():
            String {

        val prefs =
            getSharedPreferences(
                PREFS,
                Context.MODE_PRIVATE
            )


        var clientId =
            prefs.getString(
                CLIENT_ID,
                ""
            ) ?: ""


        if (
            clientId.isEmpty()
        ) {

            clientId =
                UUID
                    .randomUUID()
                    .toString()


            prefs
                .edit()
                .putString(
                    CLIENT_ID,
                    clientId
                )
                .apply()
        }


        return clientId
    }


    // =========================================================
    // IDIOMA
    // =========================================================

    private fun obterIdiomaSelecionado():
            String {

        return when (
            languageSpinner.selectedItemPosition
        ) {

            0 ->
                "pt-BR"

            1 ->
                "en-US"

            2 ->
                "es-ES"

            3 ->
                "fr-FR"

            4 ->
                "de-DE"

            5 ->
                "it-IT"

            6 ->
                "ja-JP"

            7 ->
                "ko-KR"

            8 ->
                "zh-CN"

            9 ->
                "ru-RU"

            10 ->
                "ar-SA"

            11 ->
                "hi-IN"

            12 ->
                "tr-TR"

            13 ->
                "nl-NL"

            14 ->
                "pl-PL"

            15 ->
                "uk-UA"

            16 ->
                "th-TH"

            17 ->
                "id-ID"

            18 ->
                "vi-VN"

            else ->
                "pt-BR"
        }
    }


    // =========================================================
    // STATUS
    // =========================================================

    private fun status(
        message: String
    ) {

        runOnUiThread {

            statusText.text =
                message
        }
    }


    // =========================================================
    // ON DESTROY
    // =========================================================

    override fun onDestroy() {

        super.onDestroy()

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
    }
}
