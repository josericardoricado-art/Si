package com.si.tradutor

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

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

            val url = socialUrl.text.toString().trim()

            if (url.isEmpty()) {

                Toast.makeText(
                    this,
                    "Cole primeiro o link do vídeo ou live.",
                    Toast.LENGTH_LONG
                ).show()

                socialUrl.requestFocus()

                return@setOnClickListener
            }

            if (!url.startsWith("http://") &&
                !url.startsWith("https://")) {

                Toast.makeText(
                    this,
                    "Digite um link válido começando com https://",
                    Toast.LENGTH_LONG
                ).show()

                return@setOnClickListener
            }

            status.text =
                "🟡 Link recebido!\n\n" +
                "Preparando dublagem..."

            Toast.makeText(
                this,
                "Link recebido pelo SI Tradutor.",
                Toast.LENGTH_LONG
            ).show()

            // Aqui vamos conectar o link ao backend Render
            // na próxima etapa.
        }
    }
}
