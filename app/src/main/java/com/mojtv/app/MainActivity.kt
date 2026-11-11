package com.mojtv.app

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
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
import coil.load
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.concurrent.ConcurrentHashMap
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var player: ExoPlayer
    private lateinit var playerView: PlayerView
    private lateinit var osd: TextView
    private lateinit var osdBox: LinearLayout
    private lateinit var osdLogo: ImageView
    private lateinit var status: TextView
    private lateinit var controls: LinearLayout
    private lateinit var btnPlay: TextView
    private lateinit var btnFav: TextView
    private lateinit var menuPanel: LinearLayout
    private lateinit var list: ListView
    private lateinit var search: EditText
    private lateinit var groupSpinner: Spinner
    private lateinit var plSpinner: Spinner

    private class PlRef(val name: String, val url: String)
    private class Row(val logo: ImageView, val title: TextView, val sub: TextView, val star: TextView)

    private var plists = listOf<PlRef>()
    private var plIdx = 0
    private var loadedIdx = -1
    private val cache = ConcurrentHashMap<Int, Parsed>()
    private var all = listOf<Channel>()
    private var shown = listOf<Channel>()
    private var groups = listOf<String>()
    private var current = -1
    private var triedHls = false
    private var resizeIdx = 0
    private val resizeModes = intArrayOf(0, 3, 4) // fit, fill, zoom
    private val resizeNames = arrayOf("Prilagodi", "Ispuni", "Zumiraj")

    private var favs = HashSet<String>()
    private var pin = ""
    private var locked = listOf<String>()
    private var epgUrl = ""
    private var unlocked = false
    private var blocked = false
    private var errorState = false
    private var mergeMode = true
    private var rulesVer = ""
    private var serverCat = ""
    private var reloading = false
    private var rawLists = listOf<List<Channel>>()
    private var epgFromList = ""
    private var hiddenGroups = setOf<String>()
    private var hiddenCh = setOf<String>()
    private var order = listOf<String>()
    private var alias = mapOf<String, String>()
    private var srcIdx = 0

    private val h = Handler(Looper.getMainLooper())
    private val tf = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val adapter = ChAdapter()
    private val prefs by lazy { getSharedPreferences("p", MODE_PRIVATE) }

    private val hideOsd = Runnable { osdBox.visibility = View.GONE }
    private val hideControls = Runnable { controls.visibility = View.GONE }
    private val retry = Runnable { if (current >= 0) play(current) }
    private val beat = object : Runnable {
        override fun run() {
            Thread {
                try {
                    val r = get(beatUrl())
                    ui { onBeat(r.first, r.second) }
                } catch (e: Exception) {
                }
            }.start()
            h.postDelayed(this, 60000)
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun ui(f: () -> Unit) = runOnUiThread { f() }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        favs = HashSet<String>(prefs.getStringSet("fav", HashSet<String>()) ?: HashSet<String>())
        buildUi()
        player = ExoPlayer.Builder(this).build()
        playerView.player = player
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (blocked) return
                when (state) {
                    Player.STATE_BUFFERING -> setStatus("Učitavanje…")
                    Player.STATE_READY -> setStatus(null)
                }
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                btnPlay.text = if (isPlaying) "⏸" else "▶"
            }
            override fun onVideoSizeChanged(v: VideoSize) {
                if (osdBox.visibility == View.VISIBLE) updateOsd()
            }
            override fun onPlayerError(e: PlaybackException) {
                if (blocked) return
                val ch = all.getOrNull(current) ?: return
                val urls = listOf(ch.url) + ch.alts
                val url = urls[srcIdx.coerceIn(0, urls.size - 1)]
                if (!triedHls) { triedHls = true; startUrl(url, true); return }
                if (srcIdx < urls.size - 1) {
                    srcIdx++; triedHls = false
                    setStatus("Rezervni izvor " + (srcIdx + 1) + "…")
                    startUrl(urls[srcIdx], false)
                    return
                }
                setStatus("Nema signala – ponovni pokušaj…")
                h.removeCallbacks(retry); h.postDelayed(retry, 6000)
            }
        })
        load()
        h.postDelayed(beat, 60000)
    }

    // ---------------- UI ----------------
    private fun focusBg() = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), ColorDrawable(0xFF3366FF.toInt()))
        addState(intArrayOf(), ColorDrawable(0x33FFFFFF))
    }

    private fun btn(t: String, onClick: () -> Unit) = TextView(this).apply {
        text = t; textSize = 20f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
        setPadding(dp(16), dp(10), dp(16), dp(10)); background = focusBg()
        isFocusable = true; isClickable = true
        setOnClickListener { onClick(); keepControls() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { setMargins(dp(5), 0, dp(5), 0) }
    }

    private fun makeRow(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }
        val logo = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        root.addView(logo, LinearLayout.LayoutParams(dp(44), dp(44)))
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val title = TextView(this).apply {
            textSize = 16f; setTextColor(Color.WHITE); setSingleLine(); ellipsize = TextUtils.TruncateAt.END
        }
        val sub = TextView(this).apply {
            textSize = 12f; setTextColor(0xFFAAAAAA.toInt()); setSingleLine(); ellipsize = TextUtils.TruncateAt.END
        }
        col.addView(title)
        col.addView(sub)
        root.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            setMargins(dp(10), 0, dp(6), 0)
        })
        val star = TextView(this).apply { textSize = 18f; setTextColor(0xFFFFC107.toInt()) }
        root.addView(star)
        root.tag = Row(logo, title, sub, star)
        return root
    }

    private inner class ChAdapter : BaseAdapter() {
        override fun getCount() = shown.size
        override fun getItem(p: Int): Any = shown[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getView(p: Int, cv: View?, parent: ViewGroup?): View {
            val v = cv ?: makeRow()
            val r = v.tag as Row
            val c = shown[p]
            r.title.text = (if (isLocked(c)) "🔒 " else "") + c.no + ". " + c.name
            r.sub.text = Epg.now(c.tvgId)?.title ?: c.group
            r.star.text = if (favs.contains(c.url)) "★" else ""
            r.logo.load(if (c.logo.isEmpty()) null else c.logo)
            return v
        }
    }

    private fun buildUi() {
        val mp = ViewGroup.LayoutParams.MATCH_PARENT
        val wc = ViewGroup.LayoutParams.WRAP_CONTENT
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        playerView = PlayerView(this).apply { useController = false }
        root.addView(playerView, FrameLayout.LayoutParams(mp, mp))

        val tap = View(this).apply { setOnClickListener { onTap() } }
        root.addView(tap, FrameLayout.LayoutParams(mp, mp))

        osdBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.END
            setBackgroundColor(0x99000000.toInt()); setPadding(dp(16), dp(10), dp(16), dp(10)); visibility = View.GONE
        }
        osdLogo = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_END; visibility = View.GONE }
        osd = TextView(this).apply { textSize = 22f; setTextColor(Color.WHITE); gravity = Gravity.END }
        osdBox.addView(osdLogo, LinearLayout.LayoutParams(dp(150), dp(70)))
        osdBox.addView(osd, LinearLayout.LayoutParams(wc, wc))
        root.addView(osdBox, FrameLayout.LayoutParams(wc, wc).apply {
            gravity = Gravity.TOP or Gravity.END; setMargins(0, dp(24), dp(24), 0)
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
        btnFav = btn("☆") { all.getOrNull(current)?.let { toggleFav(it) } }
        controls.addView(btn("☰ Izbornik") { showMenu() })
        controls.addView(btn("⏮") { play(current - 1, -1) })
        controls.addView(btnPlay)
        controls.addView(btn("⏭") { play(current + 1, 1) })
        controls.addView(btnFav)
        controls.addView(btn("⛶ Format") { cycleResize() })
        root.addView(controls, FrameLayout.LayoutParams(mp, wc).apply { gravity = Gravity.BOTTOM })

        menuPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(0xF0101018.toInt())
            setPadding(dp(12), dp(12), dp(12), dp(12)); visibility = View.GONE
        }
        plSpinner = Spinner(this).apply { visibility = View.GONE }
        plSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (pos != loadedIdx && pos < plists.size) {
                    plIdx = pos
                    prefs.edit().putInt("pl", pos).apply()
                    loadPlaylist(pos)
                }
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        groupSpinner = Spinner(this)
        groupSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) { applyFilter() }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        search = EditText(this).apply {
            hint = "Traži kanal…"; setSingleLine(); setTextColor(Color.WHITE); setHintTextColor(Color.GRAY)
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { applyFilter() }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        list = ListView(this)
        list.adapter = adapter
        list.setOnItemClickListener { _, _, pos, _ ->
            shown.getOrNull(pos)?.let { play(it.no - 1) }
            hideMenu()
        }
        list.setOnItemLongClickListener { _, _, pos, _ ->
            shown.getOrNull(pos)?.let { toggleFav(it) }
            true
        }
        menuPanel.addView(plSpinner, LinearLayout.LayoutParams(mp, wc))
        menuPanel.addView(groupSpinner, LinearLayout.LayoutParams(mp, wc))
        menuPanel.addView(search, LinearLayout.LayoutParams(mp, wc))
        menuPanel.addView(list, LinearLayout.LayoutParams(mp, mp))
        root.addView(menuPanel, FrameLayout.LayoutParams(dp(460), mp).apply { gravity = Gravity.START })

        setContentView(root)
    }

    private fun setStatus(t: String?) {
        if (t == null) status.visibility = View.GONE else { status.text = t; status.visibility = View.VISIBLE }
    }

    private fun fmt(t: Long) = tf.format(Date(t))

    private fun updateOsd() {
        val c = all.getOrNull(current) ?: return
        val sb = StringBuilder()
        if (c.logo.isNotEmpty()) {
            osdLogo.visibility = View.VISIBLE
            osdLogo.load(c.logo) {
                listener(onError = { _, _ ->
                    osdLogo.visibility = View.GONE
                    osd.text = if (osd.text.isEmpty()) c.name else c.name + "\n" + osd.text
                    osd.visibility = View.VISIBLE
                })
            }
        } else {
            osdLogo.visibility = View.GONE
            sb.append(c.name)
        }
        val now = Epg.now(c.tvgId)
        if (now != null) {
            if (sb.isNotEmpty()) sb.append("\n")
            sb.append(fmt(now.start)).append("  ").append(now.title)
            val nx = Epg.next(c.tvgId)
            if (nx != null) sb.append("\n› ").append(fmt(nx.start)).append("  ").append(nx.title)
        }
        osd.text = sb.toString()
        osd.visibility = if (sb.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun showOsd() {
        updateOsd(); osdBox.visibility = View.VISIBLE
        h.removeCallbacks(hideOsd); h.postDelayed(hideOsd, 5000)
    }

    private fun flash(msg: String) {
        osdLogo.visibility = View.GONE
        osd.text = msg; osd.visibility = View.VISIBLE; osdBox.visibility = View.VISIBLE
        h.removeCallbacks(hideOsd); h.postDelayed(hideOsd, 2500)
    }

    private fun keepControls() { h.removeCallbacks(hideControls); h.postDelayed(hideControls, 6000) }

    private fun toggleControls() {
        if (controls.visibility == View.VISIBLE) controls.visibility = View.GONE
        else { controls.visibility = View.VISIBLE; keepControls(); controls.getChildAt(2).requestFocus() }
    }

    private fun showMenu() {
        controls.visibility = View.GONE; menuPanel.visibility = View.VISIBLE
        adapter.notifyDataSetChanged()
        val pos = shown.indexOfFirst { it.no - 1 == current }
        if (pos >= 0) list.setSelection(pos)
        list.requestFocus()
    }

    private fun hideMenu() { menuPanel.visibility = View.GONE }

    private fun cycleResize() {
        resizeIdx = (resizeIdx + 1) % resizeModes.size
        playerView.resizeMode = resizeModes[resizeIdx]
        flash("Format: " + resizeNames[resizeIdx])
    }

    private fun onTap() {
        if (menuPanel.visibility == View.VISIBLE) hideMenu()
        else if (errorState || all.isEmpty() && !blocked) { errorState = false; load() }
        else if (!blocked) toggleControls()
    }

    // ---------------- Mreža ----------------
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun get(u: String): Pair<Int, String> {
        var url = u
        for (i in 0..5) {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 10000
            c.readTimeout = 30000
            c.instanceFollowRedirects = false
            c.setRequestProperty("User-Agent", "MojTV/2.0")
            val code = c.responseCode
            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                val loc = c.getHeaderField("Location") ?: break
                url = URL(URL(url), loc).toString()
                c.disconnect()
                continue
            }
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
            return Pair(code, body)
        }
        throw IOException("Previše preusmjeravanja")
    }

    private fun http(u: String): String {
        val r = get(u)
        if (r.first !in 200..299) throw IOException("HTTP " + r.first)
        return r.second
    }

    private fun deviceId(): String =
        Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"

    private fun beatUrl(): String = initUrl().replace("a=init", "a=beat")

    private fun initUrl(): String {
        val nm = Build.MANUFACTURER + " " + Build.MODEL
        return Config.SERVER_URL + "api.php?a=init&device=" + enc(deviceId()) + "&name=" + enc(nm)
    }

    // ---------------- Podaci ----------------
    private fun showBlocked(body: String) {
        val st = try { JSONObject(body).optString("status") } catch (e: Exception) { "" }
        val t = if (st == "pending") "Uređaj čeka odobrenje" else "Uređaj je blokiran"
        setStatus(t + "\nID: " + deviceId())
    }

    private fun applySettings(j: JSONObject) {
        pin = j.optString("pin")
        epgUrl = j.optString("epg")
        val arr = j.optJSONArray("locked")
        val l = ArrayList<String>()
        if (arr != null) for (k in 0 until arr.length()) l.add(arr.getString(k).lowercase())
        locked = l
    }

    private fun load() {
        errorState = false
        setStatus("Povezivanje…")
        Thread {
            try {
                val r = get(initUrl())
                if (r.first == 403) {
                    ui { blocked = true; player.stop(); showBlocked(r.second) }
                    return@Thread
                }
                if (r.first != 200) throw IOException("HTTP " + r.first)
                val j = JSONObject(r.second)
                ui { blocked = false; applySettings(j); applyRules(j); setupPlaylists(j) }
            } catch (e: Exception) {
                ui { errorState = true; setStatus("Greška: " + e.message + "\nDodirni za ponovni pokušaj") }
            }
        }.start()
    }

    private fun onBeat(code: Int, body: String) {
        if (code == 403) {
            if (!blocked) {
                blocked = true
                h.removeCallbacks(retry)
                player.stop()
                showBlocked(body)
            }
        } else if (code == 200) {
            if (blocked) { blocked = false; load(); return }
            try {
                val j = JSONObject(body)
                applySettings(j)
                val ver = j.optString("ver")
                if (ver.isNotEmpty() && ver != rulesVer && !reloading) refreshRules()
            } catch (e: Exception) {}
            adapter.notifyDataSetChanged()
        }
    }

    private fun strList(a: JSONArray?): List<String> {
        val l = ArrayList<String>()
        if (a != null) for (k in 0 until a.length()) l.add(a.getString(k))
        return l
    }

    private fun applyRules(j: JSONObject) {
        mergeMode = j.optBoolean("merge", true)
        rulesVer = j.optString("ver")
        serverCat = j.optString("cat")
        val r = j.optJSONObject("rules") ?: return
        hiddenGroups = strList(r.optJSONArray("hide_groups")).toSet()
        hiddenCh = strList(r.optJSONArray("hide_ch")).toSet()
        order = strList(r.optJSONArray("order"))
        val am = HashMap<String, String>()
        val aa = r.optJSONArray("alias")
        if (aa != null) for (k in 0 until aa.length()) {
            val o = aa.getJSONObject(k)
            am[o.getString("f")] = o.getString("t")
        }
        alias = am
    }

    private fun refreshRules() {
        reloading = true
        Thread {
            var j: JSONObject? = null
            try {
                val r = get(initUrl())
                if (r.first == 200) j = JSONObject(r.second)
            } catch (e: Exception) {
            }
            val res = j
            ui {
                reloading = false
                if (res != null) {
                    val oldMerge = mergeMode
                    applySettings(res)
                    applyRules(res)
                    if (oldMerge != mergeMode) load() else compose(false)
                }
            }
        }.start()
    }

    private fun setupPlaylists(j: JSONObject) {
        val arr = j.getJSONArray("playlists")
        val l = ArrayList<PlRef>()
        for (k in 0 until arr.length()) {
            val o = arr.getJSONObject(k)
            l.add(PlRef(o.getString("name"), o.getString("url")))
        }
        plists = l
        cache.clear()
        loadedIdx = -1
        if (l.isEmpty()) { setStatus("Nema playlisti.\nDodaj ih u panelu."); return }
        if (mergeMode) { plSpinner.visibility = View.GONE; loadMerged(); return }
        plIdx = prefs.getInt("pl", 0).coerceIn(0, l.size - 1)
        plSpinner.visibility = if (l.size > 1) View.VISIBLE else View.GONE
        plSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, l.map { it.name })
        plSpinner.setSelection(plIdx)
        loadPlaylist(plIdx)
    }

    private fun loadMerged() {
        loadedIdx = -2
        setStatus("Učitavanje lista…")
        val lists = plists
        Thread {
            val raws = ArrayList<List<Channel>>()
            var epg = ""
            var err = ""
            for (i in lists.indices) {
                try {
                    val p = cache[i] ?: M3U.parse(http(lists[i].url)).also { cache[i] = it }
                    raws.add(p.channels)
                    if (epg.isEmpty()) epg = p.epg
                } catch (e: Exception) {
                    err = e.message ?: "greška"
                }
            }
            ui {
                if (raws.isEmpty()) {
                    loadedIdx = -1; errorState = true
                    setStatus("Greška: " + err + "\nDodirni za ponovni pokušaj")
                } else {
                    rawLists = raws; epgFromList = epg; compose(true)
                }
            }
        }.start()
    }

    private fun loadPlaylist(i: Int) {
        loadedIdx = i
        val cached = cache[i]
        if (cached != null) { rawLists = listOf(cached.channels); epgFromList = cached.epg; compose(true); return }
        setStatus("Učitavanje liste…")
        Thread {
            try {
                val p = M3U.parse(http(plists[i].url))
                ui { cache[i] = p; rawLists = listOf(p.channels); epgFromList = p.epg; compose(true) }
            } catch (e: Exception) {
                ui { loadedIdx = -1; errorState = true; setStatus("Greška: " + e.message + "\nDodirni za ponovni pokušaj") }
            }
        }.start()
    }

    /** Pravila iz panela: sakrij grupe/kanale, preimenuj grupe, spoji duplikate, poredaj grupe. */
    private fun build(): List<Channel> {
        val l = ArrayList<Channel>()
        for (pl in rawLists) for (c in pl) {
            if (!hiddenGroups.contains(c.group) && !hiddenCh.contains(c.name)) l.add(c)
        }
        var res: List<Channel> = if (mergeMode) Merge.dedupe(l) else l
        res = res.map { c ->
            val a = alias[c.group]
            if (a != null && a.isNotEmpty()) c.copy(group = a) else c
        }
        val names = LinkedHashSet<String>()
        for (c in res) names.add(c.group)
        val ord = ArrayList<String>()
        for (g in order) {
            val a = alias[g] ?: g
            if (names.contains(a) && !ord.contains(a)) ord.add(a)
        }
        for (g in names) if (!ord.contains(g)) ord.add(g)
        val idx = HashMap<String, Int>()
        ord.forEachIndexed { i, g -> idx[g] = i }
        res = res.sortedBy { idx[it.group] ?: 0 }
        return res.mapIndexed { i, c -> c.copy(no = i + 1) }
    }

    private fun compose(autoPlay: Boolean) {
        val curUrl = all.getOrNull(current)?.url
        all = build()
        if (all.isEmpty()) {
            shown = listOf(); adapter.notifyDataSetChanged(); current = -1
            setStatus("Nema kanala za prikaz."); return
        }
        groups = all.map { it.group }.distinct()
        val items = ArrayList<String>()
        items.add("Sve grupe")
        items.add("★ Favoriti")
        for (g in groups) items.add(if (groupLocked(g)) "🔒 $g" else g)
        groupSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, items)
        applyFilter()
        loadEpg(epgFromList)
        uploadCatalog()
        if (autoPlay) {
            setStatus(null)
            val last = prefs.getString("last", null)
            val idx = all.indexOfFirst { it.url == last }
            play(if (idx >= 0) idx else 0, 1)
        } else {
            if (!blocked) setStatus(null)
            current = all.indexOfFirst { it.url == curUrl }
            updateFavBtn()
            if (current < 0) play(0, 1)
        }
    }

    private fun uploadCatalog() {
        val src = rawLists.flatten()
        if (src.isEmpty()) return
        val sb = StringBuilder()
        for (c in src) sb.append(c.group).append('\u0001').append(c.name).append('\n')
        val hash = sb.toString().hashCode().toString()
        if (hash == serverCat) return
        Thread {
            try {
                val arr = JSONArray()
                for (c in src) {
                    val e = JSONArray()
                    e.put(c.group)
                    e.put(c.name)
                    arr.put(e)
                }
                val url = Config.SERVER_URL + "api.php?a=catalog&device=" + enc(deviceId()) + "&hash=" + hash
                if (post(url, arr.toString()) == 200) serverCat = hash
            } catch (e: Throwable) {
            }
        }.start()
    }

    private fun post(u: String, body: String): Int {
        val c = URL(u).openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.doOutput = true
        c.connectTimeout = 10000
        c.readTimeout = 30000
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val code = c.responseCode
        c.disconnect()
        return code
    }

    private fun loadEpg(epg: String) {
        val url = if (epgUrl.isNotEmpty()) epgUrl else epg
        if (url.isEmpty()) return
        val ids = HashSet<String>()
        for (c in all) if (c.tvgId.isNotEmpty()) ids.add(c.tvgId)
        if (ids.isEmpty()) return
        Thread {
            try {
                Epg.load(url, ids)
                ui { adapter.notifyDataSetChanged(); if (osdBox.visibility == View.VISIBLE) updateOsd() }
            } catch (e: Throwable) {
            }
        }.start()
    }

    private fun applyFilter() {
        val g = groupSpinner.selectedItemPosition
        val q = search.text.toString().trim().lowercase()
        shown = all.filter { c ->
            val okGroup = when {
                g <= 0 -> true
                g == 1 -> favs.contains(c.url)
                else -> c.group == groups.getOrNull(g - 2)
            }
            okGroup && (q.isEmpty() || c.name.lowercase().contains(q))
        }
        adapter.notifyDataSetChanged()
    }

    // ---------------- Favoriti / zaključavanje ----------------
    private fun toggleFav(c: Channel) {
        if (!favs.remove(c.url)) favs.add(c.url)
        prefs.edit().putStringSet("fav", HashSet<String>(favs)).apply()
        applyFilter()
        updateFavBtn()
        if (osdBox.visibility == View.VISIBLE) updateOsd()
    }

    private fun updateFavBtn() {
        val c = all.getOrNull(current)
        btnFav.text = if (c != null && favs.contains(c.url)) "★" else "☆"
    }

    private fun groupLocked(g: String): Boolean {
        if (pin.isEmpty()) return false
        val lg = g.lowercase()
        return locked.any { lg.contains(it) }
    }

    private fun isLocked(c: Channel) = !unlocked && groupLocked(c.group)

    private fun askPin(onOk: () -> Unit) {
        val et = EditText(this)
        et.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        et.hint = "PIN"
        AlertDialog.Builder(this)
            .setTitle("Zaključani sadržaj")
            .setView(et)
            .setPositiveButton("OK") { _, _ ->
                if (et.text.toString() == pin) { unlocked = true; onOk() } else flash("Pogrešan PIN")
            }
            .setNegativeButton("Odustani", null)
            .show()
    }

    // ---------------- Reprodukcija ----------------
    private fun play(i: Int, step: Int = 0) {
        if (all.isEmpty() || blocked) return
        var idx = i.mod(all.size)
        if (step != 0) {
            var n = 0
            while (isLocked(all[idx]) && n < all.size) { idx = (idx + step).mod(all.size); n++ }
        }
        if (isLocked(all[idx])) { val t = idx; askPin { play(t) }; return }
        h.removeCallbacks(retry)
        current = idx
        triedHls = false
        srcIdx = 0
        val c = all[idx]
        startUrl(c.url, false)
        prefs.edit().putString("last", c.url).apply()
        updateFavBtn()
        showOsd()
    }

    private fun startUrl(url: String, hls: Boolean) {
        val item = if (hls) MediaItem.Builder().setUri(url).setMimeType("application/x-mpegURL").build()
                   else MediaItem.fromUri(url)
        player.setMediaItem(item); player.prepare(); player.playWhenReady = true
    }

    // ---------------- Tipke (daljinski) ----------------
    override fun onKeyDown(k: Int, e: KeyEvent): Boolean {
        if (menuPanel.visibility == View.VISIBLE) {
            if (k == KeyEvent.KEYCODE_BACK || k == KeyEvent.KEYCODE_MENU) { hideMenu(); return true }
            if (k == KeyEvent.KEYCODE_PROG_YELLOW) {
                shown.getOrNull(list.selectedItemPosition)?.let { toggleFav(it) }
                return true
            }
            return super.onKeyDown(k, e)
        }
        when (k) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_MEDIA_NEXT -> { play(current + 1, 1); return true }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_MEDIA_PREVIOUS -> { play(current - 1, -1); return true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { onTap(); return true }
            KeyEvent.KEYCODE_MENU -> { if (!blocked && all.isNotEmpty()) showMenu(); return true }
            KeyEvent.KEYCODE_INFO -> { showOsd(); return true }
            KeyEvent.KEYCODE_PROG_YELLOW -> { all.getOrNull(current)?.let { toggleFav(it) }; return true }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { if (player.isPlaying) player.pause() else player.play(); return true }
            KeyEvent.KEYCODE_BACK -> if (controls.visibility == View.VISIBLE) { controls.visibility = View.GONE; return true }
        }
        return super.onKeyDown(k, e)
    }

    // ---------------- Životni ciklus / full screen ----------------
    @Suppress("DEPRECATION")
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
    }
    override fun onStart() { super.onStart(); if (current >= 0 && !blocked) player.playWhenReady = true }
    override fun onStop() { super.onStop(); player.playWhenReady = false }
    override fun onDestroy() { super.onDestroy(); h.removeCallbacksAndMessages(null); player.release() }
}
