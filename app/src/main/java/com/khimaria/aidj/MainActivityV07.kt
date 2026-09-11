package com.khimaria.aidj

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlin.concurrent.thread

class MainActivityV07 : AppCompatActivity() {
    private val bg = Color.rgb(7, 10, 16)
    private val surface = Color.rgb(18, 23, 34)
    private val surface2 = Color.rgb(24, 31, 45)
    private val textPrimary = Color.rgb(245, 247, 251)
    private val textSecondary = Color.rgb(158, 169, 188)
    private val accent = Color.rgb(57, 225, 203)
    private val accent2 = Color.rgb(90, 132, 255)
    private val danger = Color.rgb(255, 112, 126)

    private lateinit var nowTitle: TextView
    private lateinit var nowArtist: TextView
    private lateinit var nowMeta: TextView
    private lateinit var autoButton: MaterialButton
    private lateinit var flowButton: MaterialButton
    private lateinit var recTitle: TextView
    private lateinit var recArtist: TextView
    private lateinit var recMeta: TextView
    private lateinit var historyText: TextView
    private lateinit var learningText: TextView

    private var autoMode = true
    private var flowMode = FlowMode.BALANCED
    private var current: Track? = null
    private var currentRecommendation: RankedRecommendation? = null
    private var currentPositionMs = 0L
    private var currentDurationMs = 0L
    private var currentPlaying = false
    private var currentSource = ""
    private var generation = 0

    private val history = mutableListOf<Track>()
    private val liked = mutableSetOf<String>()
    private val disliked = mutableSetOf<String>()
    private val skipCounts = mutableMapOf<String, Int>()
    private val listenedCounts = mutableMapOf<String, Int>()
    private val suggestedThisTrack = mutableSetOf<String>()

    private val prefs by lazy { getSharedPreferences("aidj_v07", Context.MODE_PRIVATE) }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != NowPlayingListenerService.ACTION_NOW_PLAYING) return

            val previousTitle = intent.getStringExtra(NowPlayingListenerService.EXTRA_PREVIOUS_TITLE).orEmpty()
            val previousArtist = intent.getStringExtra(NowPlayingListenerService.EXTRA_PREVIOUS_ARTIST).orEmpty()
            val previousPackage = intent.getStringExtra(NowPlayingListenerService.EXTRA_PREVIOUS_PACKAGE).orEmpty()
            val previousListenedMs = intent.getLongExtra(NowPlayingListenerService.EXTRA_PREVIOUS_LISTENED_MS, 0L)
            if (previousTitle.isNotBlank()) finalizePrevious(Track(previousTitle, previousArtist, previousPackage), previousListenedMs)

            val title = intent.getStringExtra(NowPlayingListenerService.EXTRA_TITLE).orEmpty()
            val artist = intent.getStringExtra(NowPlayingListenerService.EXTRA_ARTIST).orEmpty()
            val pkg = intent.getStringExtra(NowPlayingListenerService.EXTRA_PACKAGE).orEmpty()
            if (title.isBlank()) return

            val incoming = Track(title, artist, pkg)
            val changed = incoming.key != current?.key
            current = incoming
            currentPositionMs = intent.getLongExtra(NowPlayingListenerService.EXTRA_POSITION_MS, 0L)
            currentDurationMs = intent.getLongExtra(NowPlayingListenerService.EXTRA_DURATION_MS, 0L)
            currentPlaying = intent.getIntExtra(NowPlayingListenerService.EXTRA_PLAYBACK_STATE, PlaybackState.STATE_NONE) == PlaybackState.STATE_PLAYING
            currentSource = intent.getStringExtra(NowPlayingListenerService.EXTRA_SOURCE).orEmpty()

            if (changed) {
                suggestedThisTrack.clear()
                currentRecommendation = null
                updateNowPlaying()
                saveState()
                if (autoMode) produceRecommendation() else clearRecommendation("Auto DJ kapalı")
            } else {
                updateNowPlaying()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        loadState()
        buildUi()
        registerNowPlayingReceiver()
        syncSnapshot()
        refreshAll()
        if (autoMode && current != null) produceRecommendation()
    }

    override fun onResume() {
        super.onResume()
        syncSnapshot()
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(receiver) }
        super.onDestroy()
    }

    private fun registerNowPlayingReceiver() {
        val filter = IntentFilter(NowPlayingListenerService.ACTION_NOW_PLAYING)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED) else registerReceiver(receiver, filter)
    }

    private fun buildUi() {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(bg)
            isFillViewport = true
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(20), dp(18), dp(42))
        }
        scroll.addView(root)

        val brandRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val logo = TextView(this).apply {
            text = "AI"
            textSize = 22f
            setTextColor(Color.BLACK)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = roundRect(accent, 16f)
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
        }
        val brandText = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
        }
        brandText.addView(text("AI DJ", 27f, textPrimary, true))
        brandText.addView(text("INTELLIGENT MUSIC FLOW  •  v0.7", 11f, accent, true))
        brandRow.addView(logo)
        brandRow.addView(brandText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(brandRow)

        root.addView(space(18))
        root.addView(sectionLabel("NOW PLAYING"))
        val nowCard = card().apply { setContentPadding(dp(18), dp(18), dp(18), dp(18)) }
        val nowBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        nowMeta = text("WAITING FOR MEDIA", 11f, accent, true)
        nowTitle = text("Henüz veri yok", 24f, textPrimary, true)
        nowArtist = text("YouTube Music'te bir şarkı çal", 15f, textSecondary)
        nowBox.addView(nowMeta)
        nowBox.addView(nowTitle)
        nowBox.addView(nowArtist)
        nowCard.addView(nowBox)
        root.addView(nowCard)

        root.addView(space(12))
        val toggles = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        autoButton = pillButton("AUTO DJ • ON", accent) {
            autoMode = !autoMode
            autoButton.text = if (autoMode) "AUTO DJ • ON" else "AUTO DJ • OFF"
            saveState()
            if (autoMode) produceRecommendation() else clearRecommendation("Auto DJ kapalı")
        }
        flowButton = pillButton(flowLabel(), accent2) {
            flowMode = when (flowMode) {
                FlowMode.SAFE -> FlowMode.BALANCED
                FlowMode.BALANCED -> FlowMode.DISCOVERY
                FlowMode.DISCOVERY -> FlowMode.SAFE
            }
            flowButton.text = flowLabel()
            saveState()
            if (autoMode) produceRecommendation(true)
        }
        toggles.addView(autoButton, LinearLayout.LayoutParams(0, dp(52), 1f).apply { marginEnd = dp(6) })
        toggles.addView(flowButton, LinearLayout.LayoutParams(0, dp(52), 1f).apply { marginStart = dp(6) })
        root.addView(toggles)

        root.addView(space(18))
        root.addView(sectionLabel("NEXT SELECTION"))
        val recCard = card(stroke = accent).apply { setContentPadding(dp(18), dp(18), dp(18), dp(18)) }
        val recBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val badge = text("AI PICK", 10f, Color.BLACK, true).apply {
            gravity = Gravity.CENTER
            background = roundRect(accent, 10f)
            setPadding(dp(10), dp(5), dp(10), dp(5))
        }
        recTitle = text("Hazırlanıyor…", 22f, textPrimary, true)
        recArtist = text("", 15f, textSecondary)
        recMeta = text("", 12.5f, textSecondary)
        recBox.addView(badge, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        recBox.addView(space(12))
        recBox.addView(recTitle)
        recBox.addView(recArtist)
        recBox.addView(space(8))
        recBox.addView(recMeta)
        recCard.addView(recBox)
        root.addView(recCard)

        root.addView(space(10))
        val actionRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val another = secondaryButton("BAŞKA ADAY") {
            if (autoMode) produceRecommendation(true) else toast("Önce Auto DJ'yi aç")
        }
        val open = primaryButton("YOUTUBE MUSIC'TE AÇ") {
            currentRecommendation?.track?.let { openYoutubeMusic(it.display) } ?: toast("Henüz seçim yok")
        }
        actionRow.addView(another, LinearLayout.LayoutParams(0, dp(54), 1f).apply { marginEnd = dp(6) })
        actionRow.addView(open, LinearLayout.LayoutParams(0, dp(54), 1f).apply { marginStart = dp(6) })
        root.addView(actionRow)

        root.addView(space(16))
        val feedbackRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        feedbackRow.addView(secondaryButton("♥  BU ŞARKIYI SEVİYORUM") {
            current?.let {
                liked += it.key
                disliked -= it.key
                saveState()
                toast("Beğeni profiline eklendi")
                if (autoMode) produceRecommendation(true)
            }
        }, LinearLayout.LayoutParams(0, dp(52), 1f).apply { marginEnd = dp(6) })
        feedbackRow.addView(secondaryButton("−  BUNU AZ ÇAL") {
            current?.let {
                disliked += it.key
                liked -= it.key
                skipCounts[it.key] = (skipCounts[it.key] ?: 0) + 1
                saveState()
                toast("Bu parça geri plana alındı")
                if (autoMode) produceRecommendation(true)
            }
        }, LinearLayout.LayoutParams(0, dp(52), 1f).apply { marginStart = dp(6) })
        root.addView(feedbackRow)

        root.addView(space(20))
        root.addView(sectionLabel("LEARNING"))
        val learningCard = card().apply { setContentPadding(dp(16), dp(14), dp(16), dp(14)) }
        learningText = text("", 13f, textSecondary)
        learningCard.addView(learningText)
        root.addView(learningCard)

        root.addView(space(20))
        root.addView(sectionLabel("RECENT FLOW"))
        val historyCard = card().apply { setContentPadding(dp(16), dp(14), dp(16), dp(14)) }
        historyText = text("", 14f, textSecondary)
        historyCard.addView(historyText)
        root.addView(historyCard)

        root.addView(space(14))
        root.addView(secondaryButton("BİLDİRİM / MEDYA ERİŞİMİNİ KONTROL ET") {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)))

        root.addView(space(12))
        root.addView(text(
            "AI DJ artık aktif MediaSession verisini öncelikli kullanır; hızlı geçilen parçaları zevk profiline eklemez. BPM / key yalnızca doğrulanmış veri bulunduğunda kullanılacaktır. YouTube Music'in herkese açık resmi API'si üçüncü taraf uygulamaya canlı kuyruğu doğrudan yönetme yetkisi vermediği için oynatma entegrasyonu hâlâ servis sınırları içindedir.",
            11.5f,
            Color.rgb(104, 116, 137)
        ).apply { gravity = Gravity.CENTER })

        setContentView(scroll)
    }

    private fun finalizePrevious(track: Track, listenedMs: Long) {
        if (track.title.isBlank()) return
        if (listenedMs >= MIN_MEANINGFUL_LISTEN_MS) {
            history.removeAll { it.key == track.key }
            history.add(0, track)
            while (history.size > 120) history.removeLast()
            listenedCounts[track.key] = (listenedCounts[track.key] ?: 0) + 1
        } else if (listenedMs in 1 until MIN_MEANINGFUL_LISTEN_MS) {
            skipCounts[track.key] = (skipCounts[track.key] ?: 0) + 1
        }
        saveState()
        refreshHistory()
        refreshLearning()
    }

    private fun produceRecommendation(forceDifferent: Boolean = false) {
        val playing = current ?: run {
            clearRecommendation("Önce bir şarkı çal")
            return
        }
        generation += 1
        val myGeneration = generation
        recTitle.text = "Adaylar analiz ediliyor…"
        recArtist.text = ""
        recMeta.text = "Global katalog ve kişisel sinyaller taranıyor"

        if (!forceDifferent) suggestedThisTrack.clear()
        currentRecommendation?.track?.let { if (forceDifferent) suggestedThisTrack += it.key }
        val avoid = history.take(8).map { it.key }.toMutableSet().apply { addAll(suggestedThisTrack) }

        thread {
            val discovery = runCatching { RecommendationClient.discover(playing) }
                .getOrDefault(RecommendationClient.DiscoveryResult(emptyList(), emptySet(), listOf("network_failed")))
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
                mode = flowMode,
                skipCounts = skipCounts,
                listenedCounts = listenedCounts
            )
            val chosen = ranked.firstOrNull()

            runOnUiThread {
                if (myGeneration != generation) return@runOnUiThread
                currentRecommendation = chosen
                if (chosen == null) {
                    recTitle.text = "Uygun aday bulunamadı"
                    recArtist.text = ""
                    recMeta.text = "Ağ veya açık katalog eşleşmesi yetersiz. Bir sonraki parçada otomatik yeniden denenecek."
                } else {
                    suggestedThisTrack += chosen.track.key
                    recTitle.text = chosen.track.title
                    recArtist.text = chosen.track.artist.ifBlank { "Sanatçı bilgisi yok" }
                    val reason = chosen.reasons.joinToString("  •  ").ifBlank { "çoklu kaynak sıralaması" }
                    val sourceCount = discovery.candidates.size
                    recMeta.text = "AI seçim puanı ${chosen.score}  •  ${ranked.size} uygun / $sourceCount global aday\n$reason\nBu parça için ${suggestedThisTrack.size} aday denendi"
                }
                refreshLearning(discovery.candidates.size)
            }
        }
    }

    private fun clearRecommendation(message: String) {
        currentRecommendation = null
        recTitle.text = message
        recArtist.text = ""
        recMeta.text = ""
    }

    private fun syncSnapshot() {
        val p = getSharedPreferences(NowPlayingListenerService.PREFS, Context.MODE_PRIVATE)
        val title = p.getString("title", "").orEmpty()
        if (title.isBlank()) return
        val artist = p.getString("artist", "").orEmpty()
        val pkg = p.getString("package", "").orEmpty()
        val incoming = Track(title, artist, pkg)
        val changed = incoming.key != current?.key
        current = incoming
        currentPositionMs = p.getLong("position_ms", 0L)
        currentDurationMs = p.getLong("duration_ms", 0L)
        currentPlaying = p.getInt("playback_state", PlaybackState.STATE_NONE) == PlaybackState.STATE_PLAYING
        currentSource = p.getString("source", "").orEmpty()
        if (changed) {
            suggestedThisTrack.clear()
            currentRecommendation = null
        }
        updateNowPlaying()
    }

    private fun updateNowPlaying() {
        val track = current
        if (track == null) {
            nowMeta.text = "WAITING FOR MEDIA"
            nowTitle.text = "Henüz veri yok"
            nowArtist.text = "YouTube Music'te bir şarkı çal"
            return
        }
        val provider = when (track.source) {
            "com.google.android.apps.youtube.music" -> "YOUTUBE MUSIC"
            "com.spotify.music" -> "SPOTIFY"
            else -> "MEDIA"
        }
        val state = if (currentPlaying) "LIVE" else "PAUSED"
        val sourceBadge = if (currentSource == "media_session") "MEDIA SESSION" else "FALLBACK"
        nowMeta.text = "$provider  •  $state  •  $sourceBadge"
        nowTitle.text = track.title
        nowArtist.text = buildString {
            append(track.artist.ifBlank { "Sanatçı bilgisi yok" })
            if (currentDurationMs > 0) append("   ${formatTime(currentPositionMs)} / ${formatTime(currentDurationMs)}")
        }
    }

    private fun refreshAll() {
        autoButton.text = if (autoMode) "AUTO DJ • ON" else "AUTO DJ • OFF"
        flowButton.text = flowLabel()
        updateNowPlaying()
        refreshHistory()
        refreshLearning()
    }

    private fun refreshHistory() {
        historyText.text = if (history.isEmpty()) {
            "Henüz anlamlı dinleme kaydı yok. 12 saniyeden kısa geçilen parçalar burada görünmez."
        } else {
            history.take(8).mapIndexed { index, t -> "${index + 1}.  ${t.display}" }.joinToString("\n")
        }
    }

    private fun refreshLearning(globalCandidates: Int? = null) {
        val totalSkips = skipCounts.values.sum()
        val totalListens = listenedCounts.values.sum()
        val likedCount = liked.size
        val candidateText = globalCandidates?.let { "  •  son global havuz $it" }.orEmpty()
        learningText.text = "Anlamlı dinleme $totalListens  •  hızlı geçiş $totalSkips  •  beğeni $likedCount$candidateText\n12 saniyeden kısa geçişler zevk profiline olumlu dinleme olarak eklenmez."
    }

    private fun flowLabel(): String = when (flowMode) {
        FlowMode.SAFE -> "FLOW • SAFE"
        FlowMode.BALANCED -> "FLOW • BALANCED"
        FlowMode.DISCOVERY -> "FLOW • DISCOVERY"
    }

    private fun saveState() {
        val arr = JSONArray()
        history.take(120).forEach { arr.put(JSONObject().put("title", it.title).put("artist", it.artist).put("source", it.source)) }
        prefs.edit()
            .putString("history", arr.toString())
            .putStringSet("liked", liked.toSet())
            .putStringSet("disliked", disliked.toSet())
            .putString("skip_counts", mapToJson(skipCounts))
            .putString("listen_counts", mapToJson(listenedCounts))
            .putBoolean("auto", autoMode)
            .putString("flow", flowMode.name)
            .apply()
    }

    private fun loadState() {
        autoMode = prefs.getBoolean("auto", true)
        flowMode = runCatching { FlowMode.valueOf(prefs.getString("flow", FlowMode.BALANCED.name) ?: FlowMode.BALANCED.name) }
            .getOrDefault(FlowMode.BALANCED)
        liked += prefs.getStringSet("liked", emptySet()).orEmpty()
        disliked += prefs.getStringSet("disliked", emptySet()).orEmpty()
        skipCounts.putAll(jsonToMap(prefs.getString("skip_counts", "{}").orEmpty()))
        listenedCounts.putAll(jsonToMap(prefs.getString("listen_counts", "{}").orEmpty()))

        runCatching {
            val arr = JSONArray(prefs.getString("history", "[]") ?: "[]")
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val title = o.optString("title")
                if (title.isNotBlank()) history += Track(title, o.optString("artist"), o.optString("source"))
            }
        }
    }

    private fun mapToJson(map: Map<String, Int>): String {
        val o = JSONObject()
        map.forEach { (k, v) -> o.put(k, v) }
        return o.toString()
    }

    private fun jsonToMap(raw: String): Map<String, Int> {
        return runCatching {
            val o = JSONObject(raw.ifBlank { "{}" })
            buildMap {
                val keys = o.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    put(k, o.optInt(k, 0))
                }
            }
        }.getOrDefault(emptyMap())
    }

    private fun openYoutubeMusic(query: String) {
        val encoded = URLEncoder.encode(query, StandardCharsets.UTF_8.toString())
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://music.youtube.com/search?q=$encoded")))
    }

    private fun card(stroke: Int? = null) = MaterialCardView(this).apply {
        radius = dp(20).toFloat()
        cardElevation = 0f
        setCardBackgroundColor(surface)
        strokeWidth = if (stroke == null) dp(1) else dp(2)
        strokeColor = stroke ?: Color.rgb(38, 47, 65)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun text(value: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        includeFontPadding = false
        if (bold) typeface = Typeface.DEFAULT_BOLD
        setLineSpacing(0f, 1.12f)
    }

    private fun sectionLabel(value: String) = text(value, 11f, textSecondary, true).apply {
        letterSpacing = 0.16f
        setPadding(dp(2), 0, 0, dp(8))
    }

    private fun pillButton(label: String, color: Int, click: () -> Unit) = MaterialButton(this).apply {
        text = label
        textSize = 12f
        setTextColor(textPrimary)
        typeface = Typeface.DEFAULT_BOLD
        cornerRadius = dp(18)
        setBackgroundColor(surface2)
        strokeWidth = dp(1)
        strokeColor = android.content.res.ColorStateList.valueOf(color)
        insetTop = 0
        insetBottom = 0
        setOnClickListener { click() }
    }

    private fun primaryButton(label: String, click: () -> Unit) = MaterialButton(this).apply {
        text = label
        textSize = 11.5f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(Color.BLACK)
        cornerRadius = dp(16)
        setBackgroundColor(accent)
        insetTop = 0
        insetBottom = 0
        setOnClickListener { click() }
    }

    private fun secondaryButton(label: String, click: () -> Unit) = MaterialButton(this).apply {
        text = label
        textSize = 11.5f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(textPrimary)
        cornerRadius = dp(16)
        setBackgroundColor(surface2)
        strokeWidth = dp(1)
        strokeColor = android.content.res.ColorStateList.valueOf(Color.rgb(48, 59, 79))
        insetTop = 0
        insetBottom = 0
        setOnClickListener { click() }
    }

    private fun roundRect(color: Int, radiusDp: Float) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp.toInt()).toFloat()
    }

    private fun space(heightDp: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(heightDp)) }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun formatTime(ms: Long): String {
        val sec = (ms / 1000L).coerceAtLeast(0L)
        return "%d:%02d".format(sec / 60L, sec % 60L)
    }
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    companion object {
        private const val MIN_MEANINGFUL_LISTEN_MS = 12_000L
    }
}
