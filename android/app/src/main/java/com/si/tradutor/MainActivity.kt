package com.si.tradutor

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_main)

        val status = findViewById<TextView>(R.id.status)
        val startButton = findViewById<Button>(R.id.startButton)

        startButton.setOnClickListener {

            status.text =
                "🟢 SI Tradutor iniciado!\n\n" +
                "Preparando captura de áudio..."
        }
    }
}
