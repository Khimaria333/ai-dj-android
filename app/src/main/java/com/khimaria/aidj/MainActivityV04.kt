package com.khimaria.aidj

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
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
    private var autoMode = false
    private var current: Track? = null
    private var history = mutableListOf<Track>()
    private val liked = mutableSetOf<String>()
    private val disliked = mutableSetOf<String>()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val title = intent?.getStringExtra(NowPlayingListenerService.EXTRA_TITLE).orEmpty()
            val artist = intent?.getStringExtra(NowPlayingListenerService.EXTRA_ARTIST).orEmpty()
            val source = intent?.getStringExtra(NowPlayingListenerService.EXTRA_PACKAGE).orEmpty()
            if (title.isBlank()) return
            val track = Track(title, artist, source)
            if (track.key == current?.key) return
            current = track
            history.add(0, track)
            history = history.distinctBy { it.key }.take(50).toMutableList()
            nowPlaying.text = "Şimdi çalıyor\n${track.display}"
            refreshRecommendation()
            refreshHistory()
        }
    }

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
        fun button(text: String, onClick: () -> Unit) = Button(this).apply {
            this.text = text
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0,7,0,7) }
        }

        root.addView(label("AI DJ", 38f))
        root.addView(label("v0.4 • Auto DJ çekirdeği", 16f, Color.CYAN))
        root.addView(label("YouTube Music'te çalan parçayı takip eder, dinleme geçmişinden öğrenir ve tekrarları azaltarak bir sonraki parça önerisini seçer.", 14f, Color.LTGRAY))

        root.addView(button("1 • BİLDİRİM ERİŞİMİNİ AÇ") {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
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

        root.addView(label("Önemli: YouTube Music'in herkese açık resmi API'si üçüncü taraf uygulamaların gerçek oynatma kuyruğuna seçtiği parçayı otomatik eklemesine izin vermiyor. Bu sürüm gerçek çalan parçayı takip eder ve seçim motorunu geliştirir; tam otomatik 'seç ve sıraya koy' için yayınlanabilir, resmi queue erişimi sağlayan bir müzik kaynağı gerekir.", 12.5f, Color.GRAY))

        setContentView(scroll)
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(NowPlayingListenerService.ACTION_NOW_PLAYING)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED) else registerReceiver(receiver, filter)
    }

    override fun onStop() {
        runCatching { unregisterReceiver(receiver) }
        super.onStop()
    }

    private fun refreshRecommendation() {
        if (!autoMode) {
            recommendation.text = "AI önerisi\nAuto DJ kapalı"
            return
        }
        val rec = AutoDjEngine.recommend(current, history, liked, disliked)
        recommendation.text = if (rec == null) "AI önerisi\nDaha fazla şarkı dinledikçe öğreniyorum" else "AI sıradaki öneri\n${rec.display}"
    }

    private fun refreshHistory() {
        historyText.text = "Son dinlenenler\n" + history.take(6).joinToString("\n") { "• ${it.display}" }
    }

    private fun openYoutubeMusic(query: String) {
        val encoded = URLEncoder.encode(query, StandardCharsets.UTF_8.toString())
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://music.youtube.com/search?q=$encoded")))
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
}
