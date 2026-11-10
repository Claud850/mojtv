package com.mojtv.app

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.Spinner
import android.widget.TextView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class MainActivity : Activity() {
    private lateinit var player: ExoPlayer
    private lateinit var playerView: PlayerView
    private lateinit var osd: TextView
    private lateinit var status: TextView
    private lateinit var controls: LinearLayout
    private lateinit var btnPlay: TextView
    private lateinit var menuPanel: LinearLayout
    private lateinit var list: ListView
    private lateinit var search: EditText
    private lateinit var groupSpinner: Spinner

    private var all = listOf<Channel>()
    private var shown = listOf<Channel>()
    private var groups = listOf<String>()
    private var current = -1
    private var triedHls = false
    private var resizeIdx = 0
    private val resizeModes = intArrayOf(0, 3, 4) // fit, fill, zoom
    private val resizeNames = arrayOf("Prilagodi", "Ispuni", "Zumiraj")
    private val h = Handler(Looper.getMainLooper())

    private val hideOsd = Runnable { osd.visibility = View.GONE }
    private val hideControls = Runnable { controls.visibility = View.GONE }
    private val retry = Runnable { if (current >= 0) play(current) }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun ui(f: () -> Unit) = runOnUiThread { f() }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        buildUi()
        player = ExoPlayer.Builder(this).build()
        playerView.player = player
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_BUFFERING -> setStatus("Učitavanje…")
                    Player.STATE_READY -> setStatus(null)
                }
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                btnPlay.text = if (isPlaying) "⏸" else "▶"
            }
            override fun onVideoSizeChanged(v: VideoSize) { if (osd.visibility == View.VISIBLE) updateOsd() }
            override fun onPlayerError(e: PlaybackException) {
                val url = all.getOrNull(current)?.url ?: return
                if (!triedHls) { triedHls = true; startUrl(url, true); return }
                setStatus("Nema signala – ponovni pokušaj…")
                h.removeCallbacks(retry); h.postDelayed(retry, 6000)
            }
        })
        load()
    }

    // ---------- UI ----------
    private fun focusBg() = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), ColorDrawable(0xFF3366FF.toInt()))
        addState(intArrayOf(), ColorDrawable(0x33FFFFFF))
    }

    private fun btn(t: String, onClick: () -> Unit) = TextView(this).apply {
        text = t; textSize = 20f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
        setPadding(dp(18), dp(10), dp(18), dp(10)); background = focusBg()
        isFocusable = true; isClickable = true
        setOnClickListener { onClick(); keepControls() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { setMargins(dp(6), 0, dp(6), 0) }
    }

    private fun buildUi() {
        val mp = ViewGroup.LayoutParams.MATCH_PARENT
        val wc = ViewGroup.LayoutParams.WRAP_CONTENT
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        playerView = PlayerView(this).apply { useController = false }
        root.addView(playerView, FrameLayout.LayoutParams(mp, mp))

        val tap = View(this).apply { setOnClickListener { onTap() } }
        root.addView(tap, FrameLayout.LayoutParams(mp, mp))

        osd = TextView(this).apply {
            textSize = 22f; setTextColor(Color.WHITE); setBackgroundColor(0x99000000.toInt())
            setPadding(dp(16), dp(10), dp(16), dp(10)); visibility = View.GONE
        }
        root.addView(osd, FrameLayout.LayoutParams(wc, wc).apply {
            gravity = Gravity.TOP or Gravity.START; setMargins(dp(24), dp(24), 0, 0)
        })

        status = TextView(this).apply {
            textSize = 24f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            setBackgroundColor(0xAA000000.toInt()); setPadding(dp(24), dp(16), dp(24), dp(16)); visibility = View.GONE
        }
        root.addView(status, FrameLayout.LayoutParams(wc, wc).apply { gravity = Gravity.CENTER })

        controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            setBackgroundColor(0xAA000000.toInt()); setPadding(dp(8), dp(10), dp(8), dp(10)); visibility = View.GONE
        }
        btnPlay = btn("⏸") { if (player.isPlaying) player.pause() else player.play() }
        controls.addView(btn("☰ Izbornik") { showMenu() })
        controls.addView(btn("⏮") { play(current - 1) })
        controls.addView(btnPlay)
        controls.addView(btn("⏭") { play(current + 1) })
        controls.addView(btn("⛶ Format") { cycleResize() })
        root.addView(controls, FrameLayout.LayoutParams(mp, wc).apply { gravity = Gravity.BOTTOM })

        menuPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(0xF0101018.toInt())
            setPadding(dp(12), dp(12), dp(12), dp(12)); visibility = View.GONE
        }
        groupSpinner = Spinner(this)
        groupSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) { applyFilter() }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        search = EditText(this).apply { hint = "Traži kanal…"; setSingleLine(); setTextColor(Color.WHITE); setHintTextColor(Color.GRAY) }
        search.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { applyFilter() }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        list = ListView(this).apply {
            setOnItemClickListener { _, _, pos, _ -> shown.getOrNull(pos)?.let { play(it.no - 1) }; hideMenu() }
        }
        menuPanel.addView(groupSpinner, LinearLayout.LayoutParams(mp, wc))
        menuPanel.addView(search, LinearLayout.LayoutParams(mp, wc))
        menuPanel.addView(list, LinearLayout.LayoutParams(mp, mp))
        root.addView(menuPanel, FrameLayout.LayoutParams(dp(380), mp).apply { gravity = Gravity.START })

        setContentView(root)
    }

    private fun setStatus(t: String?) {
        if (t == null) status.visibility = View.GONE else { status.text = t; status.visibility = View.VISIBLE }
    }

    private fun updateOsd() {
        val c = all.getOrNull(current) ?: return
        val v = player.videoSize
        val res = if (v.width > 0) " • ${v.width}x${v.height}" else ""
        osd.text = "${c.no}  ${c.name}\n${c.group}$res"
    }

    private fun showOsd() {
        updateOsd(); osd.visibility = View.VISIBLE
        h.removeCallbacks(hideOsd); h.postDelayed(hideOsd, 4000)
    }

    private fun keepControls() { h.removeCallbacks(hideControls); h.postDelayed(hideControls, 6000) }
    private fun toggleControls() {
        if (controls.visibility == View.VISIBLE) { controls.visibility = View.GONE }
        else { controls.visibility = View.VISIBLE; keepControls(); controls.getChildAt(2).requestFocus() }
    }
    private fun showMenu() {
        controls.visibility = View.GONE; menuPanel.visibility = View.VISIBLE
        val pos = shown.indexOfFirst { it.no - 1 == current }
        if (pos >= 0) list.setSelection(pos)
        list.requestFocus()
    }
    private fun hideMenu() { menuPanel.visibility = View.GONE }
    private fun cycleResize() {
        resizeIdx = (resizeIdx + 1) % resizeModes.size
        playerView.resizeMode = resizeModes[resizeIdx]
        osd.text = "Format: ${resizeNames[resizeIdx]}"; osd.visibility = View.VISIBLE
        h.removeCallbacks(hideOsd); h.postDelayed(hideOsd, 2000)
    }
    private fun onTap() {
        if (menuPanel.visibility == View.VISIBLE) hideMenu()
        else if (all.isEmpty()) load()
        else toggleControls()
    }

    // ---------- Podaci ----------
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun http(u: String): String {
        val c = URL(u).openConnection() as HttpURLConnection
        c.connectTimeout = 10000; c.readTimeout = 30000
        c.setRequestProperty("User-Agent", "MojTV/1.0")
        return c.inputStream.bufferedReader().use { it.readText() }
    }

    private fun deviceId(): String =
        Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"

    private fun load() {
        setStatus("Povezivanje…")
        Thread {
            val id = deviceId()
            try {
                val name = "${Build.MANUFACTURER} ${Build.MODEL}"
                val raw = try {
                    http(Config.SERVER_URL + "api.php?a=init&device=${enc(id)}&name=${enc(name)}")
                } catch (e: java.io.IOException) {
                    // 403 = blokiran / čeka odobrenje
                    val c = URL(Config.SERVER_URL + "api.php?a=init&device=${enc(id)}").openConnection() as HttpURLConnection
                    val code = c.responseCode
                    if (code == 403) {
                        ui { setStatus("Uređaj nije odobren / blokiran\nID: $id") }
                        return@Thread
                    }
                    throw e
                }
                val j = JSONObject(raw)
                if (j.optString("status") != "ok") {
                    ui { setStatus("Uređaj nije odobren / blokiran\nID: $id") }; return@Thread
                }
                val arr = j.getJSONArray("playlists")
                val acc = ArrayList<Channel>()
                for (k in 0 until arr.length()) acc.addAll(M3U.parse(http(arr.getJSONObject(k).getString("url"))))
                ui { onChannels(acc) }
            } catch (e: Exception) {
                ui { setStatus("Greška: ${e.message}\nDodirni za ponovni pokušaj") }
            }
        }.start()
    }

    private fun onChannels(list: List<Channel>) {
        all = list.mapIndexed { i, c -> c.copy(no = i + 1) }
        if (all.isEmpty()) { setStatus("Playlista je prazna.\nDodaj kanale u panelu."); return }
        groups = all.map { it.group }.distinct().sorted()
        groupSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, listOf("Sve grupe") + groups)
        applyFilter()
        setStatus(null)
        val last = getSharedPreferences("p", MODE_PRIVATE).getString("last", null)
        val idx = all.indexOfFirst { it.url == last }
        play(if (idx >= 0) idx else 0)
    }

    private fun applyFilter() {
        val g = groupSpinner.selectedItemPosition
        val gName = if (g <= 0) null else groups.getOrNull(g - 1)
        val q = search.text.toString().trim().lowercase()
        shown = all.filter { (gName == null || it.group == gName) && (q.isEmpty() || it.name.lowercase().contains(q)) }
        list.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, shown.map { "${it.no}. ${it.name}" })
    }

    // ---------- Reprodukcija ----------
    private fun play(i: Int) {
        if (all.isEmpty()) return
        h.removeCallbacks(retry)
        current = Math.floorMod(i, all.size)
        triedHls = false
        val c = all[current]
        startUrl(c.url, false)
        getSharedPreferences("p", MODE_PRIVATE).edit().putString("last", c.url).apply()
        showOsd()
    }

    private fun startUrl(url: String, hls: Boolean) {
        val item = if (hls) MediaItem.Builder().setUri(url).setMimeType("application/x-mpegURL").build()
                   else MediaItem.fromUri(url)
        player.setMediaItem(item); player.prepare(); player.playWhenReady = true
    }

    // ---------- Tipke (daljinski) ----------
    override fun onKeyDown(k: Int, e: KeyEvent): Boolean {
        if (menuPanel.visibility == View.VISIBLE) {
            if (k == KeyEvent.KEYCODE_BACK || k == KeyEvent.KEYCODE_MENU) { hideMenu(); return true }
            return super.onKeyDown(k, e)
        }
        when (k) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_MEDIA_NEXT -> { play(current + 1); return true }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_MEDIA_PREVIOUS -> { play(current - 1); return true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { onTap(); return true }
            KeyEvent.KEYCODE_MENU -> { showMenu(); return true }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { if (player.isPlaying) player.pause() else player.play(); return true }
            KeyEvent.KEYCODE_BACK -> if (controls.visibility == View.VISIBLE) { controls.visibility = View.GONE; return true }
        }
        return super.onKeyDown(k, e)
    }

    // ---------- Životni ciklus / full screen ----------
    @Suppress("DEPRECATION")
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
    }
    override fun onStart() { super.onStart(); if (current >= 0) player.playWhenReady = true }
    override fun onStop() { super.onStop(); player.playWhenReady = false }
    override fun onDestroy() { super.onDestroy(); h.removeCallbacksAndMessages(null); player.release() }
}
