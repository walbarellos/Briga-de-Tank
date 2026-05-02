package com.tankbriga.app

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity

class HomeActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)

        findViewById<Button>(R.id.btnSolo).setOnClickListener {
            val intent = Intent(this, MainActivity::class.java)
            intent.putExtra("MODE", "SOLO")
            startActivity(intent)
        }

        findViewById<Button>(R.id.btnLobby).setOnClickListener {
            startActivity(Intent(this, LobbyActivity::class.java))
        }
    }
}
