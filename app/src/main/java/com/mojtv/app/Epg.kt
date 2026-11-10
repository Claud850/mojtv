package com.mojtv.app

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.BufferedInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.zip.GZIPInputStream

object Epg {
    class Prog(val start: Long, val stop: Long, val title: String)

    @Volatile
    var data: Map<String, List<Prog>> = emptyMap()

    fun now(id: String): Prog? {
        if (id.isEmpty()) return null
        val t = System.currentTimeMillis()
        val l = data[id] ?: return null
        return l.firstOrNull { t >= it.start && t < it.stop }
    }

    fun next(id: String): Prog? {
        if (id.isEmpty()) return null
        val t = System.currentTimeMillis()
        val l = data[id] ?: return null
        return l.firstOrNull { it.start >= t }
    }

    /** Blokirajuće; zvati iz pozadinske niti. Čuva samo kanale iz [ids] i samo sljedećih 24 h. */
    fun load(url: String, ids: Set<String>) {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 90000
        c.setRequestProperty("User-Agent", "MojTV/2.0")
        val buf = BufferedInputStream(c.inputStream)
        buf.mark(2)
        val b1 = buf.read()
        val b2 = buf.read()
        buf.reset()
        val stream: InputStream = if (b1 == 0x1f && b2 == 0x8b) GZIPInputStream(buf) else buf

        val fz = SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US)
        val fu = SimpleDateFormat("yyyyMMddHHmmss", Locale.US)
        fu.timeZone = TimeZone.getTimeZone("UTC")
        fun ts(s: String?): Long {
            if (s == null || s.length < 14) return 0L
            return try {
                val d = if (s.length > 14) fz.parse(s.trim()) else fu.parse(s.trim())
                d?.time ?: 0L
            } catch (e: Exception) { 0L }
        }

        val map = HashMap<String, ArrayList<Prog>>()
        val from = System.currentTimeMillis() - 3600000L
        val limit = System.currentTimeMillis() + 24 * 3600000L
        val p = Xml.newPullParser()
        p.setInput(stream, null)
        var ev = p.eventType
        var ch = ""
        var st = ""
        var sp = ""
        var title = ""
        var inProg = false
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG) {
                if (p.name == "programme") {
                    ch = p.getAttributeValue(null, "channel") ?: ""
                    inProg = ids.contains(ch)
                    st = p.getAttributeValue(null, "start") ?: ""
                    sp = p.getAttributeValue(null, "stop") ?: ""
                    title = ""
                } else if (p.name == "title" && inProg && title.isEmpty()) {
                    title = p.nextText().trim()
                }
            } else if (ev == XmlPullParser.END_TAG && p.name == "programme") {
                if (inProg && title.isNotEmpty()) {
                    val a = ts(st)
                    val b = ts(sp)
                    if (b > from && a < limit) map.getOrPut(ch) { ArrayList() }.add(Prog(a, b, title))
                }
                inProg = false
            }
            ev = p.next()
        }
        for (l in map.values) l.sortBy { it.start }
        data = map
    }
}
