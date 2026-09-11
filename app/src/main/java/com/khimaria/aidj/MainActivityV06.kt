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
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlin.concurrent.thread

class MainActivityV06 : AppCompatActivity() {
    private lateinit var nowPlaying: TextView
    private lateinit var recommendation: TextView
    private lateinit var detailText: TextView
    private lateinit var historyText: TextView
    private lateinit var autoButton: Button
    private lateinit var flowButton: Button

    private var autoMode = true
    private var flowMode = FlowMode.BALANCED
    private var current: Track? = null
    private var currentRecommendation: RankedRecommendation? = null
    private val history = mutableListOf<Track>()
    private val liked = mutableSetOf<String>()
    private val disliked = mutableSetOf<String>()
    private var lastSeedTags: Set<String> = emptySet()
    private var generation = 0

    private val prefs by lazy { getSharedPreferences("aidj_v06", Context.MODE_PRIVATE) }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val title = intent?.getStringExtra(NowPlayingListenerService.EXTRA_TITLE).orEmpty()
            val artist = intent?.getStringExtra(NowPlayingListenerService.EXTRA_ARTIST).orEmpty()
            val source = intent?.getStringExtra(NowPlayingListenerService.EXTRA_PACKAGE).orEmpty()
            if (title.isBlank()) return
            val track = Track(title, artist, source)
            if (track.key == current?.key) return

            current = track
            history.removeAll { it.key == track.key }
            history.add(0, track)
            while (history.size > 150) history.removeLast()
            saveState()

            nowPlaying.text = "Şimdi çalıyor\n${track.display}"
            refreshHistory()
            if (autoMode) produceRecommendation() else setRecommendationMessage("Auto DJ kapalı")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        loadState()

        val scroll = ScrollView(this).apply { setBackgroundColor(Color.rgb(8, 10, 15)) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(34, 44, 34, 56)
        }
        scroll.addView(root)

        fun label(text: String, size: Float, color: Int = Color.WHITE) = TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            gravity = Gravity.CENTER
            setPadding(0, 9, 0, 9)
        }
        fun button(text: String, click: () -> Unit) = Button(this).apply {
            this.text = text
            setOnClickListener { click() }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 7, 0, 7)
            }
        }

        root.addView(label("AI DJ", 38f))
        root.addView(label("v0.6 • DJ seçim motoru", 16f, Color.CYAN))
        root.addView(label(
            "Çalan parçayı, son set akışını, global benzerlik/tür sinyallerini, popülerlik, tekrar cezası ve kişisel beğenilerini birlikte puanlar.",
            14f,
            Color.LTGRAY
        ))

        root.addView(button("BİLDİRİM ERİŞİMİNİ AÇ / KONTROL ET") {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        })

        nowPlaying = label(current?.let { "Şimdi çalıyor\n${it.display}" } ?: "Şimdi çalıyor\nHenüz veri yok", 18f)
        root.addView(nowPlaying)

        autoButton = button(if (autoMode) "AUTO DJ: AÇIK" else "AUTO DJ: KAPALI") {
            autoMode = !autoMode
            autoButton.text = if (autoMode) "AUTO DJ: AÇIK" else "AUTO DJ: KAPALI"
            saveState()
            if (autoMode) produceRecommendation() else setRecommendationMessage("Auto DJ kapalı")
        }
        root.addView(autoButton)

        flowButton = button(flowLabel()) {
            flowMode = when (flowMode) {
                FlowMode.SAFE -> FlowMode.BALANCED
                FlowMode.BALANCED -> FlowMode.DISCOVERY
                FlowMode.DISCOVERY -> FlowMode.SAFE
            }
            flowButton.text = flowLabel()
            saveState()
            if (autoMode) produceRecommendation(true)
        }
        root.addView(flowButton)

        val feedback = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        feedback.addView(Button(this).apply {
            text = "👍 BEĞENDİM"
            setOnClickListener {
                current?.let {
                    liked.add(it.key); disliked.remove(it.key); saveState(); toast("Tercihin kaydedildi")
                    if (autoMode) produceRecommendation(true)
                }
            }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        feedback.addView(Button(this).apply {
            text = "👎 BUNU AZ ÇAL"
            setOnClickListener {
                current?.let {
                    disliked.add(it.key); liked.remove(it.key); saveState(); toast("Bu parça geri plana alındı")
                    if (autoMode) produceRecommendation(true)
                }
            }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(feedback)

        recommendation = label("AI sıradaki seçim\nHazırlanıyor…", 18f, Color.rgb(80, 220, 210))
        root.addView(recommendation)
        detailText = label("", 13f, Color.LTGRAY)
        root.addView(detailText)

        root.addView(button("BAŞKA ADAY SEÇ") {
            if (autoMode) produceRecommendation(true) else toast("Önce Auto DJ'yi aç")
        })
        root.addView(button("SEÇİMİ YOUTUBE MUSIC'TE ARA") {
            currentRecommendation?.track?.let { openYoutubeMusic(it.display) } ?: toast("Henüz seçim yok")
        })

        historyText = label("", 14f, Color.LTGRAY)
        root.addView(historyText)
        refreshHistory()

        root.addView(label(
            "BPM/key yalnızca doğrulanmış bir veri kaynağından geldiğinde kullanılacak; bu sürüm uydurma BPM üretmez. YouTube Music resmi API'si üçüncü taraf uygulamalara canlı kuyruğa otomatik parça ekleme yetkisi vermediği için seçim motoru gerçek, oynatma adımı ise şimdilik YouTube Music aramasını açar.",
            12.3f,
            Color.GRAY
        ))

        setContentView(scroll)
        if (autoMode && current != null) produceRecommendation()
    }

    private fun produceRecommendation(forceDifferent: Boolean = false) {
        val playing = current ?: run {
            setRecommendationMessage("Önce YouTube Music'te bir şarkı çal")
            return
        }
        generation += 1
        val myGeneration = generation
        recommendation.text = "AI sıradaki seçim\nGlobal adaylar analiz ediliyor…"
        detailText.text = ""

        val avoid = history.take(6).map { it.key }.toMutableSet()
        if (forceDifferent) currentRecommendation?.track?.let { avoid.add(it.key) }

        thread {
            val discovery = runCatching { RecommendationClient.discover(playing) }
                .getOrDefault(RecommendationClient.DiscoveryResult(emptyList(), emptySet()))
            val historical = history.map { it.copy(source = "history") }
            val candidates = (discovery.candidates + historical).distinctBy { it.key }
            val ranked = AutoDjEngine.rank(
                current = playing,
                candidates = candidates,
                history = history,
                liked = liked,
                disliked = disliked,
                avoid = avoid,
                seedTags = discovery.seedTags,
                mode = flowMode
            )
            val chosen = ranked.firstOrNull()

            runOnUiThread {
                if (myGeneration != generation) return@runOnUiThread
                lastSeedTags = discovery.seedTags
                currentRecommendation = chosen
                if (chosen == null) {
                    recommendation.text = "AI sıradaki seçim\nUygun aday bulunamadı"
                    detailText.text = "Ağ verisi veya açık müzik veritabanı eşleşmesi yetersiz olabilir."
                } else {
                    recommendation.text = "AI sıradaki seçim\n${chosen.track.display}"
                    val reason = chosen.reasons.joinToString(" • ").ifBlank { "çoklu sinyal puanlaması" }
                    val tagText = if (discovery.seedTags.isEmpty()) "" else "\nAkış etiketleri: ${discovery.seedTags.take(4).joinToString(", ")}"
                    detailText.text = "Uyumluluk skoru: ${chosen.score}\n$reason$tagText\nAday havuzu: ${ranked.size} parça"
                }
            }
        }
    }

    private fun setRecommendationMessage(message: String) {
        currentRecommendation = null
        recommendation.text = "AI sıradaki seçim\n$message"
        detailText.text = ""
    }

    private fun flowLabel(): String = when (flowMode) {
        FlowMode.SAFE -> "AKIŞ MODU: GÜVENLİ"
        FlowMode.BALANCED -> "AKIŞ MODU: DENGELİ"
        FlowMode.DISCOVERY -> "AKIŞ MODU: KEŞİF"
    }

    private fun refreshHistory() {
        historyText.text = "Son set akışı\n" + if (history.isEmpty()) "—" else history.take(10).joinToString("\n") { "• ${it.display}" }
    }

    private fun saveState() {
        val arr = JSONArray()
        history.take(150).forEach { arr.put(JSONObject().put("title", it.title).put("artist", it.artist).put("source", it.source)) }
        prefs.edit()
            .putString("history", arr.toString())
            .putStringSet("liked", liked.toSet())
            .putStringSet("disliked", disliked.toSet())
            .putBoolean("auto", autoMode)
            .putString("flow", flowMode.name)
            .apply()
    }

    private fun loadState() {
        autoMode = prefs.getBoolean("auto", true)
        flowMode = runCatching { FlowMode.valueOf(prefs.getString("flow", FlowMode.BALANCED.name) ?: FlowMode.BALANCED.name) }.getOrDefault(FlowMode.BALANCED)
        liked += prefs.getStringSet("liked", emptySet()).orEmpty()
        disliked += prefs.getStringSet("disliked", emptySet()).orEmpty()
        val raw = prefs.getString("history", "[]") ?: "[]"
        runCatching {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val title = o.optString("title")
                if (title.isBlank()) continue
                history += Track(title, o.optString("artist"), o.optString("source"))
            }
        }
        current = history.firstOrNull()
    }

    private fun openYoutubeMusic(q: String) {
        val encoded = URLEncoder.encode(q, StandardCharsets.UTF_8.toString())
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://music.youtube.com/search?q=$encoded")))
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(NowPlayingListenerService.ACTION_NOW_PLAYING)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED) else registerReceiver(receiver, filter)
    }

    override fun onStop() {
        runCatching { unregisterReceiver(receiver) }
        super.onStop()
    }
}
