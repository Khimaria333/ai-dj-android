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
import kotlin.concurrent.thread

class MainActivityV05 : AppCompatActivity() {
    private lateinit var nowPlaying: TextView
    private lateinit var recommendation: TextView
    private lateinit var historyText: TextView
    private lateinit var autoButton: Button
    private var autoMode = false
    private var current: Track? = null
    private var currentRecommendation: Track? = null
    private val history = mutableListOf<Track>()
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
            val unique = history.distinctBy { it.key }.take(100)
            history.clear(); history.addAll(unique)
            nowPlaying.text = "Şimdi çalıyor\n${track.display}"
            refreshHistory()
            if (autoMode) produceRecommendation() else recommendation.text = "AI önerisi\nAuto DJ kapalı"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scroll = ScrollView(this).apply { setBackgroundColor(Color.rgb(8,10,15)) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(36,46,36,54)
        }
        scroll.addView(root)

        fun label(text: String, size: Float, color: Int = Color.WHITE) = TextView(this).apply {
            this.text = text; textSize = size; setTextColor(color); gravity = Gravity.CENTER; setPadding(0,10,0,10)
        }
        fun button(text: String, click: () -> Unit) = Button(this).apply {
            this.text = text; setOnClickListener { click() }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0,7,0,7) }
        }

        root.addView(label("AI DJ", 38f))
        root.addView(label("v0.5 • Akıllı keşif modu", 16f, Color.CYAN))
        root.addView(label("Çalan parçayı algılar; benzer sanatçı ve kayıtları çevrimiçi keşfeder; geçmiş, beğeni ve tekrar cezasıyla sıradaki şarkıyı seçer.", 14f, Color.LTGRAY))

        root.addView(button("BİLDİRİM ERİŞİMİNİ AÇ / KONTROL ET") { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) })
        nowPlaying = label("Şimdi çalıyor\nHenüz veri yok", 18f); root.addView(nowPlaying)

        autoButton = button("AUTO DJ: KAPALI") {
            autoMode = !autoMode
            autoButton.text = if (autoMode) "AUTO DJ: AÇIK" else "AUTO DJ: KAPALI"
            if (autoMode) produceRecommendation() else recommendation.text = "AI önerisi\nAuto DJ kapalı"
        }
        root.addView(autoButton)

        val feedback = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        feedback.addView(Button(this).apply { text = "👍 BEĞENDİM"; setOnClickListener { current?.let { liked.add(it.key); disliked.remove(it.key); if (autoMode) produceRecommendation() } } }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        feedback.addView(Button(this).apply { text = "👎 BUNU AZ ÇAL"; setOnClickListener { current?.let { disliked.add(it.key); liked.remove(it.key); if (autoMode) produceRecommendation() } } }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(feedback)

        recommendation = label("AI önerisi\nAuto DJ kapalı", 17f, Color.rgb(80,220,210)); root.addView(recommendation)
        root.addView(button("YENİ ÖNERİ ÜRET") { if (autoMode) produceRecommendation(true) else toast("Önce Auto DJ'yi aç") })
        root.addView(button("ÖNERİYİ YOUTUBE MUSIC'TE ARA") {
            currentRecommendation?.let { openYoutubeMusic(it.display) } ?: toast("Henüz öneri yok")
        })

        historyText = label("Son dinlenenler\n—", 14f, Color.LTGRAY); root.addView(historyText)
        root.addView(label("Not: YouTube Music resmi API'si seçtiğimiz parçayı canlı kuyruğa otomatik ekleme yetkisi vermiyor. Bu sürüm gerçek öneri motorunu kuruyor; oynatma tarafı şu an YouTube Music aramasını açıyor.", 12.5f, Color.GRAY))
        setContentView(scroll)
    }

    private fun produceRecommendation(forceDifferent: Boolean = false) {
        val playing = current ?: run { recommendation.text = "AI önerisi\nÖnce YouTube Music'te bir şarkı çal"; return }
        recommendation.text = "AI önerisi\nAdaylar analiz ediliyor…"
        val avoid = history.take(5).map { it.key }.toMutableSet()
        if (forceDifferent) currentRecommendation?.let { avoid.add(it.key) }
        thread {
            val discovered = runCatching { RecommendationClient.discover(playing) }.getOrDefault(emptyList())
            val all = (discovered + history).distinctBy { it.key }
            val rec = AutoDjEngine.recommendFromCandidates(playing, all, history, liked, disliked, avoid)
            runOnUiThread {
                currentRecommendation = rec
                recommendation.text = if (rec == null) "AI önerisi\nUygun aday bulunamadı" else "AI sıradaki öneri\n${rec.display}"
            }
        }
    }

    private fun refreshHistory() { historyText.text = "Son dinlenenler\n" + if (history.isEmpty()) "—" else history.take(8).joinToString("\n") { "• ${it.display}" } }
    private fun openYoutubeMusic(q: String) { val e = URLEncoder.encode(q, StandardCharsets.UTF_8.toString()); startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://music.youtube.com/search?q=$e"))) }
    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(NowPlayingListenerService.ACTION_NOW_PLAYING)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED) else registerReceiver(receiver, filter)
    }
    override fun onStop() { runCatching { unregisterReceiver(receiver) }; super.onStop() }
}
