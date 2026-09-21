package com.si.tradutor

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var audioPlayer: GeminiAudioPlayer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Exemplo de inicialização (substitua com seu backendUrl e jobId reais)
        val backendUrl = "https://seu-backend.onrender.com"
        audioPlayer = GeminiAudioPlayer(this, backendUrl)
    }

    fun iniciarAudio(jobId: String) {
        audioPlayer.start(jobId)
    }

    fun pararAudio() {
        audioPlayer.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        audioPlayer.stop()
    }
}
