package com.si.tradutor

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private val BACKEND_URL = "https://si-u2ul.onrender.com"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_main)

        val status = findViewById<TextView>(R.id.status)
        val startButton = findViewById<Button>(R.id.startButton)
        val dubButton = findViewById<Button>(R.id.dubButton)
        val socialUrl = findViewById<EditText>(R.id.socialUrl)

        startButton.setOnClickListener {

            status.text =
                "🟢 SI Tradutor iniciado!\n\n" +
                "Cole o link do vídeo ou live abaixo."

            socialUrl.requestFocus()
        }

        dubButton.setOnClickListener {

            val link = socialUrl.text.toString().trim()

            if (link.isEmpty()) {

                Toast.makeText(
                    this,
                    "Cole primeiro o link do vídeo ou live.",
                    Toast.LENGTH_LONG
                ).show()

                socialUrl.requestFocus()

                return@setOnClickListener
            }

            if (!link.startsWith("http://") &&
                !link.startsWith("https://")) {

                Toast.makeText(
                    this,
                    "Digite um link válido começando com https://",
                    Toast.LENGTH_LONG
                ).show()

                return@setOnClickListener
            }

            status.text =
                "🟡 Enviando link...\n\n" +
                "Conectando ao servidor SI..."

            dubButton.isEnabled = false

            thread {

                try {

                    val url = URL("$BACKEND_URL/api/dub-url")

                    val connection =
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
                    json.put("targetLang", "pt")

                    connection.outputStream.use { output ->

                        output.write(
                            json.toString().toByteArray(Charsets.UTF_8)
                        )
                    }

                    val responseCode = connection.responseCode

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

                    connection.disconnect()

                    runOnUiThread {

                        dubButton.isEnabled = true

                        if (responseCode in 200..299) {

                            status.text =
                                "🟢 Link recebido pelo servidor!\n\n" +
                                "Preparando a dublagem..."

                            Toast.makeText(
                                this,
                                "Dublagem iniciada!",
                                Toast.LENGTH_LONG
                            ).show()

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

                        dubButton.isEnabled = true

                        status.text =
                            "🔴 Erro de conexão\n\n" +
                            "${e.message}"

                        Toast.makeText(
                            this,
                            "Não foi possível conectar ao servidor.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }
    }
}
