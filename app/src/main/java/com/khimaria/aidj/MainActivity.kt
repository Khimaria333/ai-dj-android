package com.khimaria.aidj

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {
    private lateinit var status: TextView

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        status.text = if (granted) "Müzik erişimi hazır • AI DJ motoru beklemede" else "Müzik izni verilmedi"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(48, 90, 48, 48)
            setBackgroundColor(Color.rgb(9, 11, 16))
        }

        fun label(text: String, size: Float, color: Int = Color.WHITE) = TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            gravity = Gravity.CENTER
            setPadding(0, 12, 0, 12)
        }

        root.addView(label("AI DJ", 42f))
        root.addView(label("Telefonundaki müzikleri akıllı geçişlerle mikslemek için Android prototipi", 17f, Color.LTGRAY))

        status = label("Hazır", 15f, Color.CYAN)
        root.addView(status)

        root.addView(label("Crossfade", 16f))
        root.addView(SeekBar(this).apply { max = 12; progress = 6 })

        root.addView(label("Enerji / geçiş hassasiyeti", 16f))
        root.addView(SeekBar(this).apply { max = 100; progress = 70 })

        val scan = Button(this).apply {
            text = "MÜZİKLERİ TARA"
            setOnClickListener { requestAudioPermission() }
        }
        root.addView(scan)

        root.addView(label("İlk APK: temel Android kabuğu + medya izni + DJ kontrol arayüzü. Sonraki adımda gerçek ses analizi, BPM/beat eşleme ve otomatik miks motoru eklenecek.", 14f, Color.GRAY))

        setContentView(root)
    }

    private fun requestAudioPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            val permission = Manifest.permission.READ_MEDIA_AUDIO
            if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
                status.text = "Müzik erişimi hazır • AI DJ motoru beklemede"
            } else permissionLauncher.launch(permission)
        } else {
            val permission = Manifest.permission.READ_EXTERNAL_STORAGE
            if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
                status.text = "Müzik erişimi hazır • AI DJ motoru beklemede"
            } else permissionLauncher.launch(permission)
        }
    }
}
