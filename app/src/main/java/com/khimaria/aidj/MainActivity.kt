package com.khimaria.aidj

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class MainActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var deckAText: TextView
    private lateinit var deckBText: TextView
    private lateinit var searchInput: EditText
    private var selectedDeck = "A"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.rgb(8, 10, 15))
            isFillViewport = true
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(42, 58, 42, 58)
        }
        scroll.addView(root)

        fun label(text: String, size: Float, color: Int = Color.WHITE, gravityValue: Int = Gravity.CENTER) = TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            gravity = gravityValue
            setPadding(0, 10, 0, 10)
        }

        fun fullWidthButton(text: String, onClick: () -> Unit) = Button(this).apply {
            this.text = text
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 8, 0, 8) }
        }

        root.addView(label("AI DJ", 40f))
        root.addView(label("Streaming-first prototip", 16f, Color.CYAN))
        root.addView(label("Şarkıyı burada seç, YouTube Music veya Spotify'da aç. Deck düzeni ve geçiş planı AI DJ içinde kalsın.", 15f, Color.LTGRAY))

        status = label("Hazır • Deck A seçili", 14f, Color.rgb(80, 220, 210))
        root.addView(status)

        searchInput = EditText(this).apply {
            hint = "Şarkı veya sanatçı ara"
            setHintTextColor(Color.GRAY)
            setTextColor(Color.WHITE)
            setSingleLine(true)
            setPadding(24, 12, 24, 12)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 18, 0, 8) }
        }
        root.addView(searchInput)

        val deckRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val deckAButton = Button(this).apply {
            text = "DECK A"
            setOnClickListener {
                selectedDeck = "A"
                status.text = "Deck A seçili"
            }
        }
        val deckBButton = Button(this).apply {
            text = "DECK B"
            setOnClickListener {
                selectedDeck = "B"
                status.text = "Deck B seçili"
            }
        }
        deckRow.addView(deckAButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        deckRow.addView(deckBButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(deckRow)

        root.addView(fullWidthButton("SEÇİLİ DECK'E ATA") {
            val query = searchInput.text.toString().trim()
            if (query.isBlank()) {
                toast("Önce bir şarkı veya sanatçı yaz")
                return@fullWidthButton
            }
            assignToDeck(query)
        })

        root.addView(fullWidthButton("YOUTUBE MUSIC'TE ARA") {
            openStreamingSearch("youtube")
        })

        root.addView(fullWidthButton("SPOTIFY'DA ARA") {
            openStreamingSearch("spotify")
        })

        root.addView(label("Deck durumu", 18f))
        deckAText = label("Deck A • boş", 15f, Color.WHITE, Gravity.START)
        deckBText = label("Deck B • boş", 15f, Color.WHITE, Gravity.START)
        root.addView(deckAText)
        root.addView(deckBText)

        root.addView(label("Crossfade", 16f))
        val crossfade = SeekBar(this).apply {
            max = 100
            progress = 50
        }
        root.addView(crossfade)

        root.addView(label("Enerji / geçiş hassasiyeti", 16f))
        val energy = SeekBar(this).apply {
            max = 100
            progress = 70
        }
        root.addView(energy)

        root.addView(fullWidthButton("GEÇİŞ PLANI HAZIRLA") {
            if (deckAText.text.contains("boş") || deckBText.text.contains("boş")) {
                toast("Önce iki deck'e de şarkı ata")
            } else {
                status.text = "Geçiş planı hazır • Crossfade ${crossfade.progress}% • Enerji ${energy.progress}%"
            }
        })

        root.addView(label(
            "v0.2: Yerel müzik tarama kaldırıldı. Streaming servislerinde arama + Deck A/B + geçiş planı eklendi. Gerçek BPM/beat analizi ve ses miksleme, servislerin izin verdiği resmi entegrasyon üzerinden sonraki adımda bağlanacak.",
            13f,
            Color.GRAY
        ))

        setContentView(scroll)
    }

    private fun assignToDeck(query: String) {
        if (selectedDeck == "A") {
            deckAText.text = "Deck A • $query"
        } else {
            deckBText.text = "Deck B • $query"
        }
        status.text = "$query → Deck $selectedDeck"
    }

    private fun openStreamingSearch(service: String) {
        val query = searchInput.text.toString().trim()
        if (query.isBlank()) {
            toast("Önce bir şarkı veya sanatçı yaz")
            return
        }

        assignToDeck(query)
        val encoded = URLEncoder.encode(query, StandardCharsets.UTF_8.toString())
        val url = when (service) {
            "spotify" -> "https://open.spotify.com/search/$encoded"
            else -> "https://music.youtube.com/search?q=$encoded"
        }

        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            status.text = if (service == "spotify") "Spotify açıldı • Deck $selectedDeck hazır" else "YouTube Music açıldı • Deck $selectedDeck hazır"
        } catch (e: Exception) {
            toast("Streaming uygulaması açılamadı")
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
