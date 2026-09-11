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
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class MainActivityV04 : AppCompatActivity() {
    private lateinit var nowPlaying: TextView
    private lateinit var recommendation: TextView
    private lateinit var historyText: TextView
    private lateinit var autoButton: Button
    private lateinit var trackInput: EditText
    private lateinit var artistInput: EditText
    private var autoMode = false
    private var current: Track? = null
    private var history = mutableListOf<Track>()
    private val liked = mutableSetOf<String>()
    private val disliked = mutableSetOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val scroll = ScrollView(this).apply { setBackgroundColor(Color.rgb(8,10,15)) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(36, 46, 36, 54)
        }
        scroll.addView(root)

        fun label(text: String, size: Float, color: Int = Color.WHITE) = TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            gravity = Gravity.CENTER
            setPadding(0, 10, 0, 10)
        }

        fun input(hintText: String) = EditText(this).apply {
            hint = hintText
            setHintTextColor(Color.GRAY)
            setTextColor(Color.WHITE)
            setSingleLine(true)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        fun button(text: String, onClick: () -> Unit) = Button(this).apply {
            this.text = text
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0,7,0,7) }
        }

        root.addView(label("AI DJ", 38f))
        root.addView(label("v0.4.1 • Güvenli Auto DJ", 16f, Color.CYAN))
        root.addView(label("Bildirim erişimi kaldırıldı. Şarkıyı elle ekleyebilir veya YouTube Music'ten Paylaş → AI DJ ile gönderebilirsin. Auto DJ geçmişinden öğrenmeye devam eder.", 14f, Color.LTGRAY))

        trackInput = input("Şarkı adı")
        artistInput = input("Sanatçı")
        root.addView(trackInput)
        root.addView(artistInput)

        root.addView(button("ŞİMDİ ÇALAN OLARAK EKLE") {
            val title = trackInput.text.toString().trim()
            val artist = artistInput.text.toString().trim()
            if (title.isBlank()) toast("Şarkı adını yaz") else addTrack(Track(title, artist, "manual"))
        })

        nowPlaying = label("Şimdi çalıyor\nHenüz veri yok", 18f)
        root.addView(nowPlaying)

        autoButton = button("AUTO DJ: KAPALI") {
            autoMode = !autoMode
            autoButton.text = if (autoMode) "AUTO DJ: AÇIK" else "AUTO DJ: KAPALI"
            refreshRecommendation()
        }
        root.addView(autoButton)

        val feedback = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val like = Button(this).apply {
            text = "👍 BEĞENDİM"
            setOnClickListener {
                current?.let { liked.add(it.key); disliked.remove(it.key); toast("Tercihine eklendi"); refreshRecommendation() }
            }
        }
        val dislike = Button(this).apply {
            text = "👎 BUNU AZ ÇAL"
            setOnClickListener {
                current?.let { disliked.add(it.key); liked.remove(it.key); toast("Bu parça geri plana alındı"); refreshRecommendation() }
            }
        }
        feedback.addView(like, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        feedback.addView(dislike, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(feedback)

        recommendation = label("AI önerisi\nYeterli dinleme geçmişi yok", 17f, Color.rgb(80,220,210))
        root.addView(recommendation)

        root.addView(button("ÖNERİYİ YOUTUBE MUSIC'TE ARA") {
            val rec = AutoDjEngine.recommend(current, history, liked, disliked)
            if (rec == null) toast("Henüz öneri yok") else openYoutubeMusic(rec.display)
        })

        historyText = label("Dinleme geçmişi\n—", 14f, Color.LTGRAY)
        root.addView(historyText)

        root.addView(label("Bu sürüm hassas bildirim erişimi istemez. YouTube Music'in resmi API'si canlı oynatma kuyruğunu üçüncü taraf uygulamaya açmadığı için tam otomatik sıraya ekleme hâlâ mümkün değil; ancak seçim motoru, geçmiş ve tercih öğrenmesi korunuyor.", 12.5f, Color.GRAY))

        setContentView(scroll)
        handleSharedText(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleSharedText(intent)
    }

    private fun handleSharedText(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND || intent.type != "text/plain") return
        val shared = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty().trim()
        if (shared.isBlank()) return

        val firstLine = shared.lineSequence().firstOrNull().orEmpty().trim()
        val cleaned = firstLine
            .replace("https://music.youtube.com/", "")
            .replace("https://youtu.be/", "")
            .trim()

        val title = when {
            intent.getStringExtra(Intent.EXTRA_SUBJECT).orEmpty().isNotBlank() -> intent.getStringExtra(Intent.EXTRA_SUBJECT).orEmpty().trim()
            cleaned.isNotBlank() && !cleaned.startsWith("http") -> cleaned
            else -> "YouTube Music paylaşımı"
        }
        addTrack(Track(title, "", "youtube_share"))
        toast("Paylaşılan parça AI DJ geçmişine eklendi")
    }

    private fun addTrack(track: Track) {
        if (track.key == current?.key) return
        current = track
        history.add(0, track)
        history = history.distinctBy { it.key }.take(50).toMutableList()
        nowPlaying.text = "Şimdi çalıyor\n${track.display}"
        refreshRecommendation()
        refreshHistory()
    }

    private fun refreshRecommendation() {
        if (!autoMode) {
            recommendation.text = "AI önerisi\nAuto DJ kapalı"
            return
        }
        val rec = AutoDjEngine.recommend(current, history, liked, disliked)
        recommendation.text = if (rec == null) "AI önerisi\nDaha fazla şarkı ekledikçe öğreniyorum" else "AI sıradaki öneri\n${rec.display}"
    }

    private fun refreshHistory() {
        historyText.text = if (history.isEmpty()) "Son dinlenenler\n—" else "Son dinlenenler\n" + history.take(6).joinToString("\n") { "• ${it.display}" }
    }

    private fun openYoutubeMusic(query: String) {
        val encoded = URLEncoder.encode(query, StandardCharsets.UTF_8.toString())
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://music.youtube.com/search?q=$encoded")))
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
}
