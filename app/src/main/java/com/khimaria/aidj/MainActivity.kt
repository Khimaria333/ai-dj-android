package com.khimaria.aidj

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
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
            setPadding(38, 52, 38, 58)
        }
        scroll.addView(root)

        fun label(text: String, size: Float, color: Int = Color.WHITE, gravityValue: Int = Gravity.CENTER) = TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            gravity = gravityValue
            setPadding(0, 9, 0, 9)
        }

        fun fullWidthButton(text: String, onClick: () -> Unit) = Button(this).apply {
            this.text = text
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 7, 0, 7) }
        }

        root.addView(label("AI DJ", 38f))
        root.addView(label("v0.3 • Streaming kumanda modu", 16f, Color.CYAN))
        root.addView(label(
            "YouTube Music veya Spotify müziği çalar. AI DJ parça/deck planını tutar ve Android medya tuşlarıyla aktif uygulamayı kontrol eder.",
            14f,
            Color.LTGRAY
        ))

        status = label("Hazır • Deck A seçili", 14f, Color.rgb(80, 220, 210))
        root.addView(status)

        searchInput = EditText(this).apply {
            hint = "Şarkı veya sanatçı ara"
            setHintTextColor(Color.GRAY)
            setTextColor(Color.WHITE)
            setSingleLine(true)
            setPadding(22, 12, 22, 12)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 16, 0, 6) }
        }
        root.addView(searchInput)

        val deckRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val deckAButton = Button(this).apply {
            text = "DECK A"
            setOnClickListener { selectedDeck = "A"; status.text = "Deck A seçili" }
        }
        val deckBButton = Button(this).apply {
            text = "DECK B"
            setOnClickListener { selectedDeck = "B"; status.text = "Deck B seçili" }
        }
        deckRow.addView(deckAButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        deckRow.addView(deckBButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(deckRow)

        root.addView(fullWidthButton("SEÇİLİ DECK'E ATA") {
            val query = searchInput.text.toString().trim()
            if (query.isBlank()) toast("Önce bir şarkı veya sanatçı yaz") else assignToDeck(query)
        })

        root.addView(fullWidthButton("YOUTUBE MUSIC'TE AÇ") {
            openStreamingSearch("youtube")
        })
        root.addView(fullWidthButton("SPOTIFY'DA AÇ") {
            openStreamingSearch("spotify")
        })

        root.addView(label("Aktif müzik uygulaması", 18f))
        val controlRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val playPause = Button(this).apply {
            text = "▶ / ⏸"
            setOnClickListener { sendMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) }
        }
        val next = Button(this).apply {
            text = "SONRAKİ ⏭"
            setOnClickListener { sendMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT) }
        }
        controlRow.addView(playPause, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        controlRow.addView(next, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(controlRow)

        root.addView(label("Deck durumu", 18f))
        deckAText = label("Deck A • boş", 14f, Color.WHITE, Gravity.START)
        deckBText = label("Deck B • boş", 14f, Color.WHITE, Gravity.START)
        root.addView(deckAText)
        root.addView(deckBText)

        root.addView(label("Geçiş tercihi", 17f))
        root.addView(label("Crossfade hedefi", 14f))
        val crossfade = SeekBar(this).apply { max = 100; progress = 50 }
        root.addView(crossfade)
        root.addView(label("Enerji / geçiş hassasiyeti", 14f))
        val energy = SeekBar(this).apply { max = 100; progress = 70 }
        root.addView(energy)

        root.addView(fullWidthButton("GEÇİŞ PLANI HAZIRLA") {
            if (deckAText.text.contains("boş") || deckBText.text.contains("boş")) {
                toast("Önce iki deck'e de şarkı ata")
            } else {
                status.text = "Plan hazır • Crossfade hedefi ${crossfade.progress}% • Enerji ${energy.progress}%"
            }
        })

        root.addView(label(
            "v0.3 sınırı: YouTube resmi politikaları YouTube içeriğinin sesini ayırmaya veya değiştirmeye izin vermiyor. Spotify resmi API politikaları da Spotify içeriğinin değiştirilmesini yasaklıyor. Bu nedenle bu sürüm streaming uygulamasını kontrol eder ve deck/sıra planlar; gerçek iki-deck ses miksleme yalnızca mikslemeye izin veren veya kullanıcıya ait/lisanslı ses kaynağıyla yapılabilir.",
            12.5f,
            Color.GRAY
        ))

        setContentView(scroll)
    }

    private fun assignToDeck(track: String) {
        if (selectedDeck == "A") deckAText.text = "Deck A • $track" else deckBText.text = "Deck B • $track"
        status.text = "$track → Deck $selectedDeck"
    }

    private fun sendMediaKey(keyCode: Int) {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
        status.text = if (keyCode == KeyEvent.KEYCODE_MEDIA_NEXT) "Sonraki parça komutu gönderildi" else "Oynat/Duraklat komutu gönderildi"
    }

    private fun openStreamingSearch(service: String) {
        val query = searchInput.text.toString().trim()
        if (query.isBlank()) {
            toast("Önce bir şarkı veya sanatçı yaz")
            return
        }
        assignToDeck(query)
        val encoded = URLEncoder.encode(query, StandardCharsets.UTF_8.toString())
        val url = if (service == "spotify") "https://open.spotify.com/search/$encoded" else "https://music.youtube.com/search?q=$encoded"
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            status.text = if (service == "spotify") "Spotify açıldı • Deck $selectedDeck planlandı" else "YouTube Music açıldı • Deck $selectedDeck planlandı"
        } catch (e: Exception) {
            toast("Streaming uygulaması açılamadı")
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
